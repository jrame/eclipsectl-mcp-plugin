package eclipsectlmcp.mcp.services;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ILog;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.debug.core.model.IStreamMonitor;
import org.eclipse.debug.core.model.IStreamsProxy;
import org.eclipse.e4.core.di.annotations.Creatable;

import jakarta.inject.Inject;

/**
 * Service that logs launch output (stdout/stderr) to files in a .logs/eclipse/ directory
 * at the workspace root. Supports both async and sync (wait-for-completion) modes.
 */
@Creatable
public class LaunchLogService {

	private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss");
	private static final long POLL_INTERVAL_MS = 500;

	@Inject
	private ILog logger;

	private final Map<ILaunch, LogEntry> activeLaunches = new ConcurrentHashMap<>();

	/**
	 * Start logging output from a launch to a file.
	 *
	 * @param launch     The ILaunch to monitor
	 * @param configName Name of the launch configuration
	 * @param mode       Launch mode (run, debug, coverage)
	 * @return Absolute path to the log file
	 */
	public String startLogging(ILaunch launch, String configName, String mode) {
		File logDir = getLogDirectory();
		String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
		String safeConfigName = configName.replaceAll("[^a-zA-Z0-9._-]", "_");
		File logFile = new File(logDir, safeConfigName + "_" + timestamp + ".log");

		try {
			PrintWriter writer = new PrintWriter(new FileWriter(logFile, true), true);
			Instant startTime = Instant.now();

			// Write header
			writer.println("=== Launch: " + configName + " | Mode: " + mode
					+ " | Started: " + LocalDateTime.now() + " ===");
			writer.println();
			writer.flush();

			LogEntry entry = new LogEntry(writer, logFile, startTime, launch);
			activeLaunches.put(launch, entry);

			// Attach stream listeners to all processes
			attachStreamListeners(launch, writer);

			// Start daemon thread to detect termination
			startTerminationWatcher(launch, entry, configName, mode);

			return logFile.getAbsolutePath();

		} catch (IOException e) {
			logger.error("Failed to create log file: " + logFile.getAbsolutePath(), e);
			return null;
		}
	}

	/**
	 * Wait for a launch to terminate, up to the given timeout.
	 *
	 * @param launch    The launch to wait for
	 * @param timeoutMs Maximum wait time in milliseconds
	 * @return A TerminationResult with exit code and duration, or null if timed out
	 */
	public TerminationResult waitForTermination(ILaunch launch, long timeoutMs) {
		long deadline = System.currentTimeMillis() + timeoutMs;

		while (System.currentTimeMillis() < deadline) {
			if (isTerminated(launch)) {
				LogEntry entry = activeLaunches.get(launch);
				Duration duration = entry != null
						? Duration.between(entry.startTime, Instant.now())
						: Duration.ZERO;
				int exitCode = getExitCode(launch);
				return new TerminationResult(exitCode, duration);
			}
			try {
				Thread.sleep(POLL_INTERVAL_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}

		// Timed out
		return null;
	}

	private void attachStreamListeners(ILaunch launch, PrintWriter writer) {
		// Wait briefly for the process to appear, then attach once
		Thread attachThread = new Thread(() -> {
			try {
				// Poll until processes are available (max 5s)
				for (int i = 0; i < 10; i++) {
					if (launch.getProcesses().length > 0) {
						break;
					}
					Thread.sleep(500);
				}
				for (IProcess process : launch.getProcesses()) {
					attachToProcess(process, writer);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}, "LaunchLog-Attach");
		attachThread.setDaemon(true);
		attachThread.start();
	}

	private void attachToProcess(IProcess process, PrintWriter writer) {
		IStreamsProxy proxy = process.getStreamsProxy();
		if (proxy == null) {
			return;
		}

		IStreamMonitor outMonitor = proxy.getOutputStreamMonitor();
		IStreamMonitor errMonitor = proxy.getErrorStreamMonitor();

		// Write any buffered content first
		String bufferedOut = outMonitor.getContents();
		if (bufferedOut != null && !bufferedOut.isEmpty()) {
			synchronized (writer) {
				writer.print(bufferedOut);
				writer.flush();
			}
		}
		String bufferedErr = errMonitor.getContents();
		if (bufferedErr != null && !bufferedErr.isEmpty()) {
			synchronized (writer) {
				writer.print("[STDERR] " + bufferedErr);
				writer.flush();
			}
		}

		// Attach listeners for new output
		outMonitor.addListener((text, monitor) -> {
			synchronized (writer) {
				writer.print(text);
				writer.flush();
			}
		});

		errMonitor.addListener((text, monitor) -> {
			synchronized (writer) {
				writer.print("[STDERR] " + text);
				writer.flush();
			}
		});
	}

	private void startTerminationWatcher(ILaunch launch, LogEntry entry, String configName, String mode) {
		Thread watcher = new Thread(() -> {
			while (!isTerminated(launch)) {
				try {
					Thread.sleep(POLL_INTERVAL_MS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			}

			// Write footer
			Duration duration = Duration.between(entry.startTime, Instant.now());
			int exitCode = getExitCode(launch);
			String durationStr = formatDuration(duration);

			synchronized (entry.writer) {
				entry.writer.println();
				entry.writer.println("=== Terminated | Exit code: " + exitCode
						+ " | Duration: " + durationStr + " ===");
				entry.writer.flush();
				entry.writer.close();
			}

			activeLaunches.remove(launch);
		}, "LaunchLog-Watcher-" + configName);
		watcher.setDaemon(true);
		watcher.start();
	}

	private boolean isTerminated(ILaunch launch) {
		IProcess[] processes = launch.getProcesses();
		if (processes.length == 0) {
			return false;
		}
		for (IProcess process : processes) {
			if (!process.isTerminated()) {
				return false;
			}
		}
		return true;
	}

	private int getExitCode(ILaunch launch) {
		IProcess[] processes = launch.getProcesses();
		if (processes.length > 0 && processes[0].isTerminated()) {
			try {
				return processes[0].getExitValue();
			} catch (Exception e) {
				return -1;
			}
		}
		return -1;
	}

	private File getLogDirectory() {
		File workspaceRoot = ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile();
		File logDir = new File(workspaceRoot, ".logs/eclipse");
		if (!logDir.exists()) {
			logDir.mkdirs();
		}
		return logDir;
	}

	static String formatDuration(Duration duration) {
		long totalSeconds = duration.getSeconds();
		long millis = duration.toMillis() % 1000;

		if (totalSeconds < 60) {
			return String.format("%d.%ds", totalSeconds, millis / 100);
		} else if (totalSeconds < 3600) {
			return String.format("%dm %ds", totalSeconds / 60, totalSeconds % 60);
		} else {
			return String.format("%dh %dm %ds", totalSeconds / 3600,
					(totalSeconds % 3600) / 60, totalSeconds % 60);
		}
	}

	/**
	 * Tracks an active launch being logged.
	 */
	private static class LogEntry {
		final PrintWriter writer;
		final File logFile;
		final Instant startTime;
		final ILaunch launch;

		LogEntry(PrintWriter writer, File logFile, Instant startTime, ILaunch launch) {
			this.writer = writer;
			this.logFile = logFile;
			this.startTime = startTime;
			this.launch = launch;
		}
	}

	/**
	 * Result of waiting for a launch to terminate.
	 */
	public static class TerminationResult {
		private final int exitCode;
		private final Duration duration;

		public TerminationResult(int exitCode, Duration duration) {
			this.exitCode = exitCode;
			this.duration = duration;
		}

		public int getExitCode() {
			return exitCode;
		}

		public Duration getDuration() {
			return duration;
		}

		public String getDurationFormatted() {
			return LaunchLogService.formatDuration(duration);
		}
	}
}
