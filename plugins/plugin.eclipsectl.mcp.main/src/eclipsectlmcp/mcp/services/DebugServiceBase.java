package eclipsectlmcp.mcp.services;

import jakarta.inject.Inject;

import org.eclipse.core.runtime.ILog;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.e4.core.di.annotations.Creatable;

import eclipsectlmcp.tools.UISynchronizeCallable;

/**
 * Abstract base class for debug services.
 * Contains shared state and helper methods used by all debug-related services.
 */
@Creatable
public abstract class DebugServiceBase {

	@Inject
	protected ILog logger;

	@Inject
	protected UISynchronizeCallable uiSync;

	@Inject
	protected LaunchLogService launchLogService;

	/** Focused thread name (null = auto-select first suspended thread). */
	protected String focusedThreadName;

	/** Focused session name (null = auto-select first active session). */
	protected String focusedSessionName;

	/**
	 * Helper record to hold process information.
	 */
	protected record ProcessInfo(String configName, String mode, String label, boolean isTerminated, int exitCode) {}

	/**
	 * Get the active debug target, optionally filtered by session name.
	 * Priority: explicit sessionName param > focusedSessionName > first active session.
	 *
	 * @param sessionName Optional session name filter (null to use default logic)
	 * @return Active debug target or null
	 */
	protected IDebugTarget getActiveDebugTarget(String sessionName) {
		try {
			return uiSync.syncCall(() -> {
				ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
				ILaunch[] launches = launchManager.getLaunches();

				// Determine the effective session name to match
				String effectiveName = (sessionName != null && !sessionName.trim().isEmpty())
						? sessionName : focusedSessionName;

				// If we have a session name, search for it
				if (effectiveName != null) {
					for (ILaunch launch : launches) {
						if (!launch.isTerminated() && launch.getLaunchConfiguration() != null
								&& launch.getLaunchConfiguration().getName().equalsIgnoreCase(effectiveName)) {
							IDebugTarget[] targets = launch.getDebugTargets();
							if (targets.length > 0 && !targets[0].isTerminated()) {
								return targets[0];
							}
						}
					}
				}

				// Fallback: first active debug session
				for (ILaunch launch : launches) {
					if (!launch.isTerminated()) {
						IDebugTarget[] targets = launch.getDebugTargets();
						if (targets.length > 0 && !targets[0].isTerminated()) {
							return targets[0];
						}
					}
				}
				return null;
			});
		} catch (Exception e) {
			logger.error("Error getting active debug target", e);
			return null;
		}
	}

	/**
	 * Get the active debug target using the default session resolution (no explicit sessionName).
	 */
	protected IDebugTarget getActiveDebugTarget() {
		return getActiveDebugTarget(null);
	}

	/**
	 * Get a suspended thread from the debug target.
	 * Priority: explicit threadName > focusedThreadName > first suspended thread.
	 *
	 * @param debugTarget Debug target
	 * @return Suspended thread or null
	 */
	protected IThread getSuspendedThread(IDebugTarget debugTarget) {
		try {
			IThread[] threads = debugTarget.getThreads();

			// If we have a focused thread, try to find it first
			if (focusedThreadName != null) {
				for (IThread thread : threads) {
					if (thread.isSuspended() && thread.getName() != null
							&& thread.getName().equals(focusedThreadName)) {
						return thread;
					}
				}
			}

			// Fallback: first suspended thread
			for (IThread thread : threads) {
				if (thread.isSuspended()) {
					return thread;
				}
			}
			return null;
		} catch (Exception e) {
			logger.error("Error getting suspended thread", e);
			return null;
		}
	}

	/**
	 * Check if a package should be filtered out.
	 *
	 * @param className Fully qualified class name
	 * @param packageFilter Package filter (e.g., "com.example")
	 * @param skipFramework Whether to skip framework packages
	 * @return true if should be filtered
	 */
	protected boolean shouldFilterFrame(String className, String packageFilter, boolean skipFramework) {
		if (className == null) {
			return false;
		}

		// Apply package filter if specified
		if (packageFilter != null && !packageFilter.isEmpty()) {
			if (!className.startsWith(packageFilter)) {
				return true;
			}
		}

		// Skip framework packages if requested
		if (skipFramework) {
			if (className.startsWith("java.") ||
				className.startsWith("javax.") ||
				className.startsWith("org.eclipse.") ||
				className.startsWith("sun.") ||
				className.startsWith("com.sun.") ||
				className.startsWith("jdk.")) {
				return true;
			}
		}

		return false;
	}
}
