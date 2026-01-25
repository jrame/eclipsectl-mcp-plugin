package eclipsectlmcp.mcp.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jdt.debug.core.IJavaStackFrame;

/**
 * Service for managing debug context (threads, sessions, processes).
 * Handles thread selection, process listing, and debug status.
 */
@Creatable
public class DebugContextService extends DebugServiceBase {

	/**
	 * Get the current debug session status.
	 *
	 * @return Formatted debug status
	 */
	public String getDebugStatus() {
		try {
			return uiSync.syncCall(() -> {
				ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
				ILaunch[] launches = launchManager.getLaunches();

				boolean hasActiveSession = false;
				String sessionName = null;
				String state = "none";
				String suspendedAt = null;
				int threadCount = 0;
				int breakpointCount = DebugPlugin.getDefault().getBreakpointManager().getBreakpoints().length;

				// Find active debug session
				for (ILaunch launch : launches) {
					if (!launch.isTerminated()) {
						IDebugTarget[] targets = launch.getDebugTargets();
						if (targets.length > 0 && !targets[0].isTerminated()) {
							hasActiveSession = true;
							IDebugTarget target = targets[0];
							sessionName = launch.getLaunchConfiguration() != null ?
									launch.getLaunchConfiguration().getName() : "Unknown";

							// Determine state: check target first, then individual threads
							IThread suspendedThread = getSuspendedThread(target);
							if (target.isSuspended() || suspendedThread != null) {
								state = "suspended";
								// Get current location if suspended
								if (suspendedThread == null) {
									suspendedThread = getSuspendedThread(target);
								}
								if (suspendedThread != null) {
									IStackFrame[] frames = suspendedThread.getStackFrames();
									if (frames.length > 0 && frames[0] instanceof IJavaStackFrame) {
										IJavaStackFrame frame = (IJavaStackFrame) frames[0];
										String fileName = frame.getSourceName();
										int lineNumber = frame.getLineNumber();
										suspendedAt = fileName + ":" + lineNumber;
									}
								}
							} else if (target.isTerminated()) {
								state = "terminated";
							} else {
								state = "running";
							}

							// Count threads
							threadCount = target.getThreads().length;
							break; // Use first active session
						}
					}
				}

				StringBuilder sb = new StringBuilder();
				sb.append("Debug status: ").append(state).append("\n");
				if (hasActiveSession) {
					sb.append("Session: ").append(sessionName).append("\n");
					sb.append("Threads: ").append(threadCount).append("\n");
					if (suspendedAt != null) {
						sb.append("Suspended at: ").append(suspendedAt).append("\n");
					}
				}
				sb.append("Breakpoints: ").append(breakpointCount);
				return sb.toString();
			});
		} catch (Exception e) {
			logger.error("Error getting debug status", e);
			return "Error: " + e.getMessage();
		}
	}

	/**
	 * List all threads for a debug session.
	 *
	 * @param sessionName Optional session name (uses focused or first active session if null)
	 * @return Formatted thread list
	 */
	public String listThreads(String sessionName) {
		try {
			return uiSync.syncCall(() -> {
				IDebugTarget debugTarget = getActiveDebugTarget(sessionName);
				if (debugTarget == null) {
					return "Error: No active debug session. Please launch a debug configuration first.";
				}

				IThread[] threads = debugTarget.getThreads();
				// Include session name in header
				ILaunch launch = debugTarget.getLaunch();
				String launchName = (launch != null && launch.getLaunchConfiguration() != null)
						? launch.getLaunchConfiguration().getName() : "Unknown";

				StringBuilder sb = new StringBuilder();
				sb.append("Threads for session '").append(launchName).append("' (")
				  .append(threads.length).append("):");
				if (focusedThreadName != null) {
					sb.append("  [focused: ").append(focusedThreadName).append("]");
				}
				sb.append("\n\n");

				for (IThread thread : threads) {
					String name = thread.getName();
					boolean isFocused = name != null && name.equals(focusedThreadName);

					sb.append(isFocused ? "> " : "  ");
					sb.append(name);

					if (thread.isSuspended()) {
						sb.append(" [suspended]");
						IStackFrame[] frames = thread.getStackFrames();
						if (frames.length > 0 && frames[0] instanceof IJavaStackFrame) {
							IJavaStackFrame jFrame = (IJavaStackFrame) frames[0];
							String fileName = jFrame.getSourceName();
							int lineNumber = jFrame.getLineNumber();
							if (fileName != null) {
								sb.append(" at ").append(fileName).append(":").append(lineNumber);
							}
						}
					} else if (thread.isStepping()) {
						sb.append(" [stepping]");
					} else {
						sb.append(" [running]");
					}

					sb.append("\n");
				}

				return sb.toString();
			});
		} catch (Exception e) {
			logger.error("Error listing threads", e);
			return "Error: " + e.getMessage();
		}
	}

	/**
	 * Select (focus) a thread for subsequent debug commands.
	 *
	 * @param threadName Thread name to focus (null/empty to reset)
	 * @param sessionName Optional session name
	 * @return Success or error message
	 */
	public String selectThread(String threadName, String sessionName) {
		try {
			return uiSync.syncCall(() -> {
				// Reset focus if threadName is null/empty
				if (threadName == null || threadName.trim().isEmpty()) {
					focusedThreadName = null;
					focusedSessionName = null;
					return "Thread focus cleared. Commands will auto-select the first suspended thread.";
				}

				IDebugTarget debugTarget = getActiveDebugTarget(sessionName);
				if (debugTarget == null) {
					return "Error: No active debug session. Please launch a debug configuration first.";
				}

				// Validate the thread exists
				IThread[] threads = debugTarget.getThreads();
				boolean found = false;
				String threadState = null;
				for (IThread thread : threads) {
					if (thread.getName() != null && thread.getName().equals(threadName)) {
						found = true;
						threadState = thread.isSuspended() ? "suspended" : "running";
						break;
					}
				}

				if (!found) {
					StringBuilder available = new StringBuilder();
					for (IThread thread : threads) {
						if (available.length() > 0) available.append(", ");
						available.append(thread.getName());
					}
					return "Error: Thread '" + threadName + "' not found. Available threads: " + available;
				}

				focusedThreadName = threadName;
				if (sessionName != null && !sessionName.trim().isEmpty()) {
					focusedSessionName = sessionName;
				}

				return "Thread '" + threadName + "' focused (state: " + threadState + "). " +
						"Subsequent debug commands will target this thread.";
			});
		} catch (Exception e) {
			logger.error("Error selecting thread", e);
			return "Error: " + e.getMessage();
		}
	}

	/**
	 * List all active processes (run, debug, coverage modes).
	 *
	 * @return Formatted list of active processes
	 */
	public String listProcesses() {
		try {
			return uiSync.syncCall(() -> {
				ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
				ILaunch[] launches = launchManager.getLaunches();

				List<ProcessInfo> processes = new ArrayList<>();

				// Collect all active processes from all launches
				for (ILaunch launch : launches) {
					if (!launch.isTerminated()) {
						IProcess[] launchProcesses = launch.getProcesses();
						String configName = launch.getLaunchConfiguration() != null
								? launch.getLaunchConfiguration().getName()
								: "Unknown";
						String mode = launch.getLaunchMode();

						for (IProcess process : launchProcesses) {
							String label = process.getLabel();
							boolean isTerminated = process.isTerminated();
							int exitCode = -1;
							if (isTerminated) {
								try {
									exitCode = process.getExitValue();
								} catch (Exception e) {
									// Ignore
								}
							}

							processes.add(new ProcessInfo(configName, mode, label, isTerminated, exitCode));
						}
					}
				}

				if (processes.isEmpty()) {
					return "No active processes found.";
				}

				StringBuilder sb = new StringBuilder();
				sb.append("Active processes (").append(processes.size()).append("):\n\n");

				// Group by mode
				Map<String, List<ProcessInfo>> byMode = new java.util.LinkedHashMap<>();
				for (ProcessInfo p : processes) {
					byMode.computeIfAbsent(p.mode(), k -> new ArrayList<>()).add(p);
				}

				for (Map.Entry<String, List<ProcessInfo>> entry : byMode.entrySet()) {
					String mode = entry.getKey();
					List<ProcessInfo> modeProcesses = entry.getValue();

					sb.append("=== ").append(mode.toUpperCase()).append(" mode (")
					  .append(modeProcesses.size()).append(") ===\n");

					for (ProcessInfo p : modeProcesses) {
						sb.append("  - ").append(p.configName());
						if (!p.label().equals(p.configName())) {
							sb.append(" [").append(p.label()).append("]");
						}
						if (p.isTerminated()) {
							sb.append(" [terminated, exit: ").append(p.exitCode()).append("]");
						} else {
							sb.append(" [running]");
						}
						sb.append("\n");
					}
					sb.append("\n");
				}

				return sb.toString();
			});
		} catch (Exception e) {
			logger.error("Error listing processes", e);
			return "Error: " + e.getMessage();
		}
	}
}
