package eclipsectlmcp.mcp.services;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.debug.core.ILaunch;
import org.eclipse.ui.PlatformUI;

/** Optional adapter for the native TestNG Eclipse result model; no console parsing. */
final class TestNgResults {

	record Counts(long run, long failed, long skipped, boolean complete) { }

	private TestNgResults() { }

	static Optional<Counts> find(ILaunch launch) {
		if (!PlatformUI.isWorkbenchRunning()) {
			return Optional.empty();
		}
		var display = PlatformUI.getWorkbench().getDisplay();
		if (display.isDisposed()) {
			return Optional.empty();
		}
		var result = new AtomicReference<Optional<Counts>>(Optional.empty());
		// View references and history belong to the UI. Never open a view as a side effect.
		display.syncExec(() -> {
			List<Object> views = new ArrayList<>();
			for (var window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
				for (var page : window.getPages()) {
					var view = page.findView("org.testng.eclipse.ResultView");
					if (view != null) {
						views.add(view);
					}
				}
			}
			result.set(findInViews(launch, views));
		});
		return result.get();
	}

	static Optional<Counts> findInViews(ILaunch launch, Iterable<?> views) {
		for (Object view : views) {
			try {
				List<Object> runs = new ArrayList<>();
				Object current = field(view, "currentSuiteRunInfo");
				if (current != null) {
					runs.add(current);
				}
				Object history = field(view, "m_runHistoryAction");
				if (history != null && field(history, "runs") instanceof Iterable<?> previous) {
					previous.forEach(runs::add);
				}
				for (Object run : runs) {
					// History selection and configuration names do not identify an execution.
					if (invoke(run, "getLaunch") == launch) {
						return Optional.of(new Counts(count(run, "getMethodCount"),
								count(run, "getFailedCount"), count(run, "getSkippedCount"),
								(Boolean) invoke(run, "hasRun")));
					}
				}
			} catch (ReflectiveOperationException | RuntimeException e) {
				// TestNG is optional. An absent/incompatible model must never invent counts
				// or break Java/JUnit launches. Another workbench window may hold the run.
			}
		}
		return Optional.empty();
	}

	private static long count(Object run, String method) throws ReflectiveOperationException {
		long value = ((Number) invoke(run, method)).longValue();
		if (value < 0) {
			throw new IllegalStateException("Invalid TestNG counter");
		}
		return value;
	}

	private static Object invoke(Object target, String method) throws ReflectiveOperationException {
		return target.getClass().getMethod(method).invoke(target);
	}

	private static Object field(Object target, String name) throws ReflectiveOperationException {
		// SuiteRunInfo has public counters/getLaunch, but TestNG keeps the current
		// model and history private (TestRunnerViewPart / RunHistoryAction).
		Field field = target.getClass().getDeclaredField(name);
		if (!field.trySetAccessible()) {
			throw new IllegalAccessException("TestNG model is not accessible");
		}
		return field.get(target);
	}
}
