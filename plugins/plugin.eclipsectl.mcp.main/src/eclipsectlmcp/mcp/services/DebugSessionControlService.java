package eclipsectlmcp.mcp.services;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.e4.core.di.annotations.Creatable;

/**
 * Service for controlling debug sessions.
 * Handles launching, stepping, resuming, suspending, and terminating debug sessions.
 */
@Creatable
public class DebugSessionControlService extends DebugServiceBase {

	/**
	 * Launch a debug configuration by name.
	 *
	 * @param configName Name of the debug configuration
	 * @param waitForCompletion If true, waits for the process to terminate (max 120s)
	 * @return Success or error message with log path
	 */
	public String runDebug(String configName, boolean waitForCompletion) {
		try {
			return uiSync.syncCall(() -> {
				ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();

				ILaunchConfiguration[] configs = launchManager.getLaunchConfigurations();

				ILaunchConfiguration targetConfig = null;
				for (ILaunchConfiguration config : configs) {
					if (config.getName().equalsIgnoreCase(configName)) {
						targetConfig = config;
						break;
					}
				}

				if (targetConfig == null) {
					return "Error: Debug configuration '" + configName + "' not found. " +
							"Use listRunConfigurations to see available configurations.";
				}

				ILaunch launch = targetConfig.launch(ILaunchManager.DEBUG_MODE, null);

				String logPath = launchLogService.startLogging(launch, configName, "debug");

				if (waitForCompletion) {
					LaunchLogService.TerminationResult result = launchLogService.waitForTermination(launch, 120_000);
					if (result != null) {
						return "Launch '" + configName + "' completed (debug mode).\n" +
								"Log: " + logPath + "\n" +
								"Exit code: " + result.getExitCode() + "\n" +
								"Duration: " + result.getDurationFormatted();
					} else {
						return "Launch '" + configName + "' timed out after 120s (debug mode).\n" +
								"Log: " + logPath + "\n" +
								"Status: STILL RUNNING";
					}
				} else {
					return "Launch '" + configName + "' started (debug mode).\n" +
							"Log: " + logPath + "\n" +
							"Status: RUNNING\n" +
							"Use getDebugStatus to check session status.";
				}
			});
		} catch (Exception e) {
			logger.error("Error launching debug configuration", e);
			return "Error: " + e.getMessage();
		}
	}

	/**
	 * List all available run/launch configurations.
	 *
	 * @return Formatted list of run configurations
	 */
	public String listRunConfigurations() {
		try {
			return uiSync.syncCall(() -> {
				ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
				ILaunchConfiguration[] configs = launchManager.getLaunchConfigurations();

				if (configs.length == 0) {
					return "No run configurations found.";
				}

				StringBuilder sb = new StringBuilder();
				sb.append(configs.length).append(" run configuration(s):\n\n");

				for (ILaunchConfiguration config : configs) {
					sb.append("- ").append(config.getName());
					sb.append(" (").append(config.getType().getName()).append(")");

					// Show supported modes
					List<String> modes = new ArrayList<>();
					if (config.supportsMode(ILaunchManager.RUN_MODE)) {
						modes.add("run");
					}
					if (config.supportsMode(ILaunchManager.DEBUG_MODE)) {
						modes.add("debug");
					}
					if (config.supportsMode("coverage")) {
						modes.add("coverage");
					}
					if (!modes.isEmpty()) {
						sb.append(" [").append(String.join(", ", modes)).append("]");
					}
					sb.append("\n");
				}

				return sb.toString();
			});
		} catch (Exception e) {
			logger.error("Error listing run configurations", e);
			return "Error: " + e.getMessage();
		}
	}

	/**
	 * Step into the next method call (F5).
	 *
	 * @return Success or error message
	 */
	public String stepInto() {
		try {
			return uiSync.syncCall(() -> {
				IDebugTarget debugTarget = getActiveDebugTarget();
				if (debugTarget == null) {
					return "Error: No active debug session. Please launch a debug configuration first.";
				}

				IThread suspendedThread = getSuspendedThread(debugTarget);
				if (suspendedThread == null) {
					return "Error: Debug session is not suspended. Set a breakpoint or suspend execution first.";
				}

				if (!suspendedThread.canStepInto()) {
					return "Error: Cannot step into at current location.";
				}

				suspendedThread.stepInto();
				return "Stepping into method...";
			});
		} catch (Exception e) {
			logger.error("Error stepping into", e);
			return "Error: " + e.getMessage();
		}
	}

	/**
	 * Step over the current line (F6).
	 *
	 * @return Success or error message
	 */
	public String stepOver() {
		try {
			return uiSync.syncCall(() -> {
				IDebugTarget debugTarget = getActiveDebugTarget();
				if (debugTarget == null) {
					return "Error: No active debug session. Please launch a debug configuration first.";
				}

				IThread suspendedThread = getSuspendedThread(debugTarget);
				if (suspendedThread == null) {
					return "Error: Debug session is not suspended. Set a breakpoint or suspend execution first.";
				}

				if (!suspendedThread.canStepOver()) {
					return "Error: Cannot step over at current location.";
				}

				suspendedThread.stepOver();
				return "Stepping over line...";
			});
		} catch (Exception e) {
			logger.error("Error stepping over", e);
			return "Error: " + e.getMessage();
		}
	}

	/**
	 * Step out of the current method (F7).
	 *
	 * @return Success or error message
	 */
	public String stepReturn() {
		try {
			return uiSync.syncCall(() -> {
				IDebugTarget debugTarget = getActiveDebugTarget();
				if (debugTarget == null) {
					return "Error: No active debug session. Please launch a debug configuration first.";
				}

				IThread suspendedThread = getSuspendedThread(debugTarget);
				if (suspendedThread == null) {
					return "Error: Debug session is not suspended. Set a breakpoint or suspend execution first.";
				}

				if (!suspendedThread.canStepReturn()) {
					return "Error: Cannot step return at current location.";
				}

				suspendedThread.stepReturn();
				return "Stepping out of method...";
			});
		} catch (Exception e) {
			logger.error("Error stepping return", e);
			return "Error: " + e.getMessage();
		}
	}

	/**
	 * Resume execution (F8).
	 *
	 * @return Success or error message
	 */
	public String resume() {
		try {
			return uiSync.syncCall(() -> {
				IDebugTarget debugTarget = getActiveDebugTarget();
				if (debugTarget == null) {
					return "Error: No active debug session. Please launch a debug configuration first.";
				}

				if (!debugTarget.canResume()) {
					return "Error: Debug session cannot be resumed (may already be running).";
				}

				debugTarget.resume();
				return "Execution resumed.";
			});
		} catch (Exception e) {
			logger.error("Error resuming", e);
			return "Error: " + e.getMessage();
		}
	}

	/**
	 * Suspend the current debug session.
	 *
	 * @return Success or error message
	 */
	public String suspend() {
		try {
			return uiSync.syncCall(() -> {
				IDebugTarget debugTarget = getActiveDebugTarget();
				if (debugTarget == null) {
					return "Error: No active debug session. Please launch a debug configuration first.";
				}

				if (!debugTarget.canSuspend()) {
					return "Error: Debug session cannot be suspended (may already be suspended).";
				}

				debugTarget.suspend();
				return "Execution suspended.";
			});
		} catch (Exception e) {
			logger.error("Error suspending", e);
			return "Error: " + e.getMessage();
		}
	}

	/**
	 * Terminate the current debug session.
	 *
	 * @return Success or error message
	 */
	public String terminate() {
		try {
			return uiSync.syncCall(() -> {
				IDebugTarget debugTarget = getActiveDebugTarget();
				if (debugTarget == null) {
					return "Error: No active debug session to terminate.";
				}

				ILaunch activeLaunch = debugTarget.getLaunch();
				if (activeLaunch == null || !activeLaunch.canTerminate()) {
					return "Error: Debug session cannot be terminated.";
				}

				String sessionName = activeLaunch.getLaunchConfiguration() != null ?
						activeLaunch.getLaunchConfiguration().getName() : "Unknown";

				activeLaunch.terminate();
				return "Debug session '" + sessionName + "' terminated.";
			});
		} catch (Exception e) {
			logger.error("Error terminating", e);
			return "Error: " + e.getMessage();
		}
	}
}
