package eclipsectlmcp.mcp.services;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.jdt.internal.junit.JUnitCorePlugin;
import org.eclipse.jdt.internal.junit.model.TestRunSession;

/** Test counters belonging to one launch, including launches still in progress. */
@SuppressWarnings("restriction")
public final class LaunchTestSummary {

	private LaunchTestSummary() { }

	/** Empty for application launches; unknown counts are never reported as zero. */
	public static String describe(ILaunch launch) {
		try {
			if (launch.getLaunchConfiguration() == null) {
				return "";
			}
			String type = launch.getLaunchConfiguration().getType().getIdentifier();
			if ("org.eclipse.jdt.junit.launchconfig".equals(type)) {
				for (TestRunSession session : JUnitCorePlugin.getModel().getTestRunSessions()) {
					// Names are insufficient: concurrent/repeated launches can share a configuration.
					if (session.getLaunch() == launch) {
						return counters("JUnit", session.getStartedCount(),
								session.getFailureCount() + session.getErrorCount(),
								session.getIgnoredCount() + session.getAssumptionFailureCount())
								+ (!launch.isTerminated() || session.isRunning() ? " (in progress)" : "");
					}
				}
				return unavailable("JUnit", launch.isTerminated());
			}
			if ("org.testng.eclipse.launchconfig".equals(type)) {
				return TestNgResults.find(launch)
						.map(counts -> counters("TestNG", counts.run(), counts.failed(), counts.skipped())
								+ (launch.isTerminated() && counts.complete() ? "" : " (in progress)"))
						.orElseGet(() -> unavailable("TestNG", launch.isTerminated()));
			}
		} catch (CoreException | RuntimeException e) {
			// A summary must not turn a successfully launched process into an MCP error.
			return "\nTests: counts unavailable";
		}
		return "";
	}

	private static String counters(String framework, long run, long failed, long skipped) {
		return "\nTests (" + framework + "): run: " + run + ", failed: " + failed + ", skipped: " + skipped;
	}

	private static String unavailable(String framework, boolean terminated) {
		return "\nTests (" + framework + "): " + (terminated ? "counts unavailable" : "results pending");
	}
}
