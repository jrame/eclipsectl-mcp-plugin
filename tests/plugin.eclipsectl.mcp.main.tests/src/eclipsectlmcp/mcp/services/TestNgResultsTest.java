package eclipsectlmcp.mcp.services;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.List;

import org.eclipse.debug.core.ILaunch;
import org.junit.jupiter.api.Test;

public class TestNgResultsTest {

	@Test
	public void nativeCountersWorkWithNoConsoleAndIgnoreOtherLaunches() {
		ILaunch target = launch();
		ILaunch other = launch();
		var current = new NativeRun(other, 99, 9, 5, true);
		var previous = new NativeRun(target, 12, 1, 2, true);
		var counts = TestNgResults.findInViews(target, List.of(new View(current, List.of(previous)))).orElseThrow();
		assertEquals(new TestNgResults.Counts(12, 1, 2, true), counts);
	}

	@Test
	public void runningAndEmptyCompletedRunsKeepTheirNativeState() {
		ILaunch target = launch();
		var partial = TestNgResults.findInViews(target,
				List.of(new View(new NativeRun(target, 3, 1, 1, false), List.of()))).orElseThrow();
		assertFalse(partial.complete());
		assertEquals(3, partial.run());
		assertEquals(new TestNgResults.Counts(0, 0, 0, true), TestNgResults.findInViews(target,
				List.of(new View(new NativeRun(target, 0, 0, 0, true), List.of()))).orElseThrow());
	}

	@Test
	public void absentOrIncompatibleModelsDoNotInventZeroCounts() {
		ILaunch target = launch();
		assertTrue(TestNgResults.findInViews(target, List.of()).isEmpty());
		assertTrue(TestNgResults.findInViews(target, List.of(new Object())).isEmpty());
		assertTrue(TestNgResults.findInViews(target,
				List.of(new View(new NativeRun(launch(), 20, 1, 1, true), List.of()))).isEmpty());
		assertTrue(TestNgResults.findInViews(target,
				List.of(new View(new NativeRun(target, -1, 0, 0, true), List.of()))).isEmpty());
	}

	@Test
	public void searchesOtherWindowsAfterAnIncompatibleView() {
		ILaunch target = launch();
		assertEquals(4, TestNgResults.findInViews(target, List.of(new Object(),
				new View(new NativeRun(target, 4, 0, 0, true), List.of()))).orElseThrow().run());
	}

	// These fixtures expose SuiteRunInfo's native getters and the view/history
	// ownership structure. Any attempt to read a process or console fails.
	public record NativeRun(ILaunch getLaunch, int getMethodCount, int getFailedCount,
			int getSkippedCount, boolean hasRun) { }

	private static final class View {
		private final Object currentSuiteRunInfo;
		private final History m_runHistoryAction;
		View(Object current, List<?> runs) {
			currentSuiteRunInfo = current;
			m_runHistoryAction = new History(runs);
		}
	}

	private static final class History {
		private final List<?> runs;
		History(List<?> runs) { this.runs = runs; }
	}

	static ILaunch launch() {
		return (ILaunch) Proxy.newProxyInstance(ILaunch.class.getClassLoader(), new Class<?>[] { ILaunch.class },
				(proxy, method, args) -> {
					throw new AssertionError("Native model lookup must only use launch identity");
				});
	}
}
