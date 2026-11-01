package eclipsectlmcp.mcp.services;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.eclemma.core.CoverageTools;
import org.eclipse.eclemma.core.ICoverageSession;
import org.eclipse.eclemma.core.ISessionListener;
import org.eclipse.eclemma.core.ISessionManager;
import org.jacoco.core.data.ExecutionDataWriter;

/** Loaded only after checking that the optional EclEmma bundle is available. */
final class CoverageSessionExport implements CoverageService.CoverageExport {
	private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS");
	private final ISessionManager manager;
	private final Path directory;
	private final Duration timeout;
	private final List<ICoverageSession> added = new ArrayList<>();
	private final ISessionListener listener = new ISessionListener() {
		@Override public void sessionAdded(ICoverageSession session) {
			synchronized (added) {
				added.add(session);
				added.notifyAll();
			}
		}
		@Override public void sessionRemoved(ICoverageSession session) {}
		@Override public void sessionActivated(ICoverageSession session) {}
	};

	static CoverageSessionExport capture() {
		Path workspace = ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath();
		return new CoverageSessionExport(CoverageTools.getSessionManager(),
				workspace.resolve(".logs/eclipsectl"), Duration.ofSeconds(15));
	}

	CoverageSessionExport(ISessionManager manager, Path directory, Duration timeout) {
		this.manager = manager;
		this.directory = directory;
		this.timeout = timeout;
		// Register before launching: short tests can publish their session before launch() returns.
		manager.addSessionListener(listener);
	}

	@Override public String export(ILaunch launch, String configName) throws Exception {
		ICoverageSession session = awaitSession(launch);
		return write(session, directory, configName, LocalDateTime.now()).toString();
	}

	ICoverageSession awaitSession(ILaunch launch) throws Exception {
		long deadline = System.nanoTime() + timeout.toNanos();
		synchronized (added) {
			while (true) {
				List<ICoverageSession> matching = added.stream()
						.filter(session -> Objects.equals(launch.getLaunchConfiguration(), session.getLaunchConfiguration()))
						.toList();
				if (matching.size() > 1) {
					throw new IOException("Multiple new coverage sessions match this launch configuration; cannot choose an export safely.");
				}
				if (matching.size() == 1) {
					return matching.getFirst();
				}
				long remaining = deadline - System.nanoTime();
				if (remaining <= 0) {
					throw new IOException("No new coverage session for this launch after " + timeout.toSeconds() + "s.");
				}
				try {
					java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(added, remaining);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw e;
				}
			}
		}
	}

	static Path write(ICoverageSession session, Path directory, String configName, LocalDateTime time) throws Exception {
		Files.createDirectories(directory);
		String safeName = configName.replaceAll("[^a-zA-Z0-9._-]", "_");
		if (safeName.isEmpty()) safeName = "coverage";
		safeName = safeName.substring(0, Math.min(32, safeName.length()));
		String timestamp = time.format(TIMESTAMP);
		for (int attempt = 0; ; attempt++) {
			String suffix = attempt == 0 ? "" : "-" + attempt;
			Path file = directory.resolve(timestamp + suffix + "_" + safeName + ".exec").toAbsolutePath();
			OutputStream output;
			try {
				output = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
			} catch (FileAlreadyExistsException e) {
				continue;
			}
			try {
				try (OutputStream buffered = new BufferedOutputStream(output)) {
					ExecutionDataWriter writer = new ExecutionDataWriter(buffered);
					session.accept(writer, writer);
				}
				return file;
			} catch (Exception e) {
				try { Files.deleteIfExists(file); }
				catch (IOException cleanup) { e.addSuppressed(cleanup); }
				throw e;
			}
		}
	}

	@Override public void close() {
		manager.removeSessionListener(listener);
	}
}
