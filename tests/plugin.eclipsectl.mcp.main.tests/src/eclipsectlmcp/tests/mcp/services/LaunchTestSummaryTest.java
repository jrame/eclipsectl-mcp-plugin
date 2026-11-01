package eclipsectlmcp.tests.mcp.services;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.util.Map;

import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;

import org.eclipse.jdt.internal.junit.JUnitCorePlugin;
import org.eclipse.jdt.internal.junit.model.TestRunSession;
import org.junit.jupiter.api.Test;

import eclipsectlmcp.mcp.services.LaunchTestSummary;

@SuppressWarnings("restriction")
public class LaunchTestSummaryTest {

	@Test
	public void junitCountsOnlyTheRequestedLaunchAndIncludesErrorsAndAssumptions() {
		ILaunch launch = launch("org.eclipse.jdt.junit.launchconfig", true);
		ILaunch other = launch("org.eclipse.jdt.junit.launchconfig", true);
		TestRunSession unrelated = session(other, 99, 20, 3, 5, 7, false);
		TestRunSession target = session(launch, 12, 1, 2, 1, 1, false);
		var model = JUnitCorePlugin.getModel();
		model.addTestRunSession(unrelated);
		model.addTestRunSession(target);
		try {
			assertEquals("\nTests (JUnit): run: 12, failed: 3, skipped: 2", LaunchTestSummary.describe(launch));
		} finally {
			model.removeTestRunSession(target);
			model.removeTestRunSession(unrelated);
		}
	}

	@Test
	public void unavailableResultsAreNeverInventedAndApplicationOutputIsUnchanged() {
		assertEquals("\nTests (TestNG): results pending",
				LaunchTestSummary.describe(launch("org.testng.eclipse.launchconfig", false)));
		assertEquals("\nTests (TestNG): counts unavailable",
				LaunchTestSummary.describe(launch("org.testng.eclipse.launchconfig", true)));
		assertEquals("", LaunchTestSummary.describe(launch("org.eclipse.jdt.launching.localJavaApplication", true)));
	}

	private static TestRunSession session(ILaunch launch, int run, int failed, int errors,
			int ignored, int assumptions, boolean running) {
		return new TestRunSession("summary-test", null) {
			@Override public ILaunch getLaunch() { return launch; }
			@Override public int getStartedCount() { return run; }
			@Override public int getFailureCount() { return failed; }
			@Override public int getErrorCount() { return errors; }
			@Override public int getIgnoredCount() { return ignored; }
			@Override public int getAssumptionFailureCount() { return assumptions; }
			@Override public boolean isRunning() { return running; }
		};
	}

	private static ILaunch launch(String type, boolean terminated) {
		ILaunchConfigurationType configType = stub(ILaunchConfigurationType.class, Map.of("getIdentifier", type));
		ILaunchConfiguration config = stub(ILaunchConfiguration.class, Map.of("getType", configType));
		return stub(ILaunch.class, Map.of("getLaunchConfiguration", config,
				"isTerminated", terminated));
	}

	private static <T> T stub(Class<T> type, Map<String, Object> values) {
		return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
				(proxy, method, args) -> switch (method.getName()) {
					case "equals" -> proxy == args[0];
					case "hashCode" -> System.identityHashCode(proxy);
					case "toString" -> type.getSimpleName();
					case "getProcesses" -> throw new AssertionError("Test summaries must not read console streams");
					default -> values.get(method.getName());
				}));
	}
}
