package eclipsectlmcp.mcp.services;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.jdt.internal.junit.JUnitCorePlugin;
import org.eclipse.jdt.internal.junit.model.TestRunSession;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import eclipsectlmcp.tools.UISynchronizeCallable;
import eclipsectlmcp.mcp.ToolExecution;

@SuppressWarnings("restriction")
@Timeout(10)
public class CoverageLaunchWaitTest {

	@Test
	public void completionWaitLeavesTheUiAvailableForTestNgReceiver() {
		ILaunch launch = launch();
		CoverageService service = service(launch);
		service.launchLogService = new LaunchLogService() {
			@Override public String startLogging(ILaunch actual, String name, String mode) {
				assertSame(launch, actual);
				assertEquals("FooNgTest", name);
				assertEquals("coverage", mode);
				assertNull(Display.getCurrent(), "Logging must leave the UI thread available");
				return "test.log";
			}
			@Override public TerminationResult waitForTermination(ILaunch actual, long timeout) {
				assertSame(launch, actual);
				assertEquals(120_000L, timeout);
				assertNull(Display.getCurrent(), "Waiting must not block TestNG's UI connection");
				AtomicBoolean receiverConnected = new AtomicBoolean();
				PlatformUI.getWorkbench().getDisplay().syncExec(() -> receiverConnected.set(true));
				assertTrue(receiverConnected.get(), "UI callbacks must run before the wait completes");
				return new TerminationResult(7, Duration.ofMillis(1500));
			}
		};

		String result = service.runCoverage("FooNgTest", true);
		assertTrue(result.contains("completed (coverage mode)"));
		assertTrue(result.contains("Log: test.log"));
		assertTrue(result.contains("Exit code: 7"));
		assertTrue(result.contains("Duration: 1.5s"));
		assertTrue(result.contains("Coverage session: .logs/eclipsectl/test.exec"));
	}

	@Test
	public void timeoutReportsStillRunningWithoutTerminatingTheLaunch() {
		ILaunch launch = launch();
		CoverageService service = service(launch);
		service.launchLogService = new LaunchLogService() {
			@Override public String startLogging(ILaunch actual, String name, String mode) { return "test.log"; }
			@Override public TerminationResult waitForTermination(ILaunch actual, long timeout) {
				assertSame(launch, actual);
				assertEquals(120_000L, timeout);
				assertNull(Display.getCurrent());
				return null;
			}
		};

		String result = service.runCoverage("FooNgTest", true);
		assertTrue(result.contains("timed out after 120s (coverage mode)"));
		assertTrue(result.contains("Status: STILL RUNNING"));
	}

	@Test
	public void asynchronousLaunchDoesNotWait() {
		CoverageService service = service(launch());
		service.launchLogService = new LaunchLogService() {
			@Override public String startLogging(ILaunch actual, String name, String mode) { return "test.log"; }
			@Override public TerminationResult waitForTermination(ILaunch actual, long timeout) {
				fail("An asynchronous launch must not wait for termination");
				return null;
			}
		};

		String result = service.runCoverage("FooNgTest", false);
		assertTrue(result.contains("started (coverage mode)"));
		assertTrue(result.contains("Status: RUNNING"));
	}

	@Test
	public void missingConfigurationReturnsBeforeLoggingOrWaiting() {
		CoverageService service = service(null);
		// No log service: a missing configuration must return before using it.
		assertTrue(service.runCoverage("FooNgTest", true).contains("not found"));
	}

	@Test
	public void missingEclEmmaReturnsBeforeLaunching() {
		CoverageService service = new CoverageService() {
			@Override boolean isEclEmmaAvailable() { return false; }
		};
		assertTrue(service.runCoverage("FooNgTest", true).contains("EclEmma plugin is not installed"));
	}

	@Test
	public void failedExportPreservesCompletionAndMarksToolError() throws Exception {
		AtomicBoolean exported = new AtomicBoolean();
		AtomicBoolean closed = new AtomicBoolean();
		CoverageService service = service(launch(), true, exported, closed);
		service.launchLogService = new LaunchLogService() {
			@Override public String startLogging(ILaunch actual, String name, String mode) { return "test.log"; }
			@Override public TerminationResult waitForTermination(ILaunch actual, long timeout) {
				return new TerminationResult(0, Duration.ofSeconds(1));
			}
		};
		ToolExecution.Result result = ToolExecution.capture(() -> service.runCoverage("FooNgTest", true));
		assertTrue(result.isError());
		String text = (String) result.value();
		assertTrue(text.contains("completed (coverage mode)"));
		assertTrue(text.contains("Log: test.log"));
		assertTrue(text.contains("Coverage export failed: disk full"));
		assertTrue(exported.get());
		assertTrue(closed.get());
	}

	@Test
	public void timedOutLaunchClosesCaptureWithoutExport() {
		AtomicBoolean exported = new AtomicBoolean();
		AtomicBoolean closed = new AtomicBoolean();
		CoverageService service = service(launch(), false, exported, closed);
		service.launchLogService = new LaunchLogService() {
			@Override public String startLogging(ILaunch actual, String name, String mode) { return "test.log"; }
			@Override public TerminationResult waitForTermination(ILaunch actual, long timeout) { return null; }
		};
		assertTrue(service.runCoverage("FooNgTest", true).contains("STILL RUNNING"));
		assertFalse(exported.get());
		assertTrue(closed.get());
	}

	@Test
	public void coverageReportsNativeJUnitCountsForCompletionTimeoutAsyncAndExportErrors() throws Exception {
		for (String state : new String[] { "completed", "timeout", "async", "export-error" }) {
			boolean terminated = state.equals("completed") || state.equals("export-error");
			ILaunch launch = typedLaunch("org.eclipse.jdt.junit.launchconfig", terminated);
			TestRunSession session = new TestRunSession("coverage-summary", null) {
				@Override public ILaunch getLaunch() { return launch; }
				@Override public int getStartedCount() { return 12; }
				@Override public int getFailureCount() { return 1; }
				@Override public int getErrorCount() { return 2; }
				@Override public int getIgnoredCount() { return 1; }
				@Override public int getAssumptionFailureCount() { return 1; }
				@Override public boolean isRunning() { return !terminated; }
			};
			var model = JUnitCorePlugin.getModel();
			model.addTestRunSession(session);
			try {
				CoverageService service = service(launch, state.equals("export-error"), new AtomicBoolean(), new AtomicBoolean());
				service.launchLogService = completedLog(state.equals("timeout"));
				ToolExecution.Result result = ToolExecution.capture(() -> service.runCoverage("FooNgTest", !state.equals("async")));
				String text = (String) result.value();
				assertEquals(state.equals("export-error"), result.isError(), text);
				assertTrue(text.contains("Tests (JUnit): run: 12, failed: 3, skipped: 2"), text);
				assertEquals(!terminated, text.contains("(in progress)"), text);
				if (state.equals("completed")) assertTrue(text.contains("Coverage session: .logs/eclipsectl/test.exec"), text);
				if (state.equals("export-error")) assertTrue(text.contains("Coverage export failed: disk full"), text);
			} finally {
				model.removeTestRunSession(session);
			}
		}
	}

	@Test
	public void testNgCoverageReportsUnavailableOrPendingCountsHonestly() {
		for (boolean terminated : new boolean[] { true, false }) {
			CoverageService service = service(typedLaunch("org.testng.eclipse.launchconfig", terminated));
			service.launchLogService = completedLog(false);
			String text = service.runCoverage("FooNgTest", terminated);
			assertTrue(text.contains("Tests (TestNG): " + (terminated ? "counts unavailable" : "results pending")), text);
			assertFalse(text.contains("run: 0"), text);
		}
	}

	private static LaunchLogService completedLog(boolean timeout) {
		return new LaunchLogService() {
			@Override public String startLogging(ILaunch actual, String name, String mode) { return "test.log"; }
			@Override public TerminationResult waitForTermination(ILaunch actual, long budget) {
				return timeout ? null : new TerminationResult(0, Duration.ofSeconds(1));
			}
		};
	}

	private static ILaunch typedLaunch(String type, boolean terminated) {
		ILaunchConfigurationType configType = stub(ILaunchConfigurationType.class, Map.of("getIdentifier", type));
		ILaunchConfiguration config = stub(ILaunchConfiguration.class, Map.of("getType", configType));
		return stub(ILaunch.class, Map.of("getLaunchConfiguration", config, "isTerminated", terminated));
	}

	private static <T> T stub(Class<T> type, Map<String, Object> values) {
		return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },
				(proxy, method, args) -> switch (method.getName()) {
					case "equals" -> proxy == args[0];
					case "hashCode" -> System.identityHashCode(proxy);
					case "toString" -> type.getSimpleName();
					case "getProcesses" -> throw new AssertionError("Must use native test counters, not the console");
					default -> values.get(method.getName());
				}));
	}

	private static CoverageService service(ILaunch launch) {
		return service(launch, false, new AtomicBoolean(), new AtomicBoolean());
	}

	private static CoverageService service(ILaunch launch, boolean failExport, AtomicBoolean exported, AtomicBoolean closed) {
		CoverageService service = new CoverageService() {
			@Override boolean isEclEmmaAvailable() { return true; }
			@Override CoverageExport captureCoverageExport() {
				assertNull(Display.getCurrent());
				return new CoverageExport() {
					@Override public String export(ILaunch actual, String name) throws Exception {
						exported.set(true);
						if (failExport) throw new java.io.IOException("disk full");
						assertSame(launch, actual);
						assertEquals("FooNgTest", name);
						assertNull(Display.getCurrent(), "Session finalization must leave the UI available");
						return ".logs/eclipsectl/test.exec";
					}
					@Override public void close() { closed.set(true); }
				};
			}
			@Override ILaunch launchCoverageConfiguration(String name) {
				assertNotNull(Display.getCurrent(), "Launching stays on the UI thread");
				assertEquals("FooNgTest", name);
				return launch;
			}
		};
		service.uiSync = new UISynchronizeCallable() {
			@Override public <T> T syncCall(Callable<T> callable) {
				AtomicReference<T> result = new AtomicReference<>();
				PlatformUI.getWorkbench().getDisplay().syncExec(() -> {
					try { result.set(callable.call()); }
					catch (Exception e) { throw new RuntimeException(e); }
				});
				return result.get();
			}
		};
		return service;
	}

	private static ILaunch launch() {
		return (ILaunch) Proxy.newProxyInstance(ILaunch.class.getClassLoader(),
				new Class<?>[] { ILaunch.class }, (proxy, method, args) -> {
					if (method.getName().equals("getLaunchConfiguration")) return null;
					throw new AssertionError("Unexpected launch operation: " + method.getName());
				});
	}
}
