package eclipsectlmcp.mcp.services;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.debug.core.ILaunch;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.Test;

import eclipsectlmcp.tools.UISynchronizeCallable;

public class DebugLaunchWaitTest {
	@Test
	public void completionWaitLeavesTheUiAvailableForTestNgReceiver() {
		ILaunch launch = (ILaunch) Proxy.newProxyInstance(ILaunch.class.getClassLoader(),
				new Class<?>[] { ILaunch.class }, (proxy, method, args) -> null);
		var service = new DebugSessionControlService() {
			@Override ILaunch launchDebugConfiguration(String name) {
				assertNotNull(Display.getCurrent(), "Launching stays on the UI thread");
				return launch;
			}
		};
		service.uiSync = new UISynchronizeCallable() {
			@Override public <T> T syncCall(Callable<T> callable) {
				var result = new AtomicReference<T>();
				PlatformUI.getWorkbench().getDisplay().syncExec(() -> {
					try { result.set(callable.call()); }
					catch (Exception e) { throw new RuntimeException(e); }
				});
				return result.get();
			}
		};
		service.launchLogService = new LaunchLogService() {
			@Override public String startLogging(ILaunch actual, String name, String mode) { return "test.log"; }
			@Override public TerminationResult waitForTermination(ILaunch actual, long timeout) {
				assertSame(launch, actual);
				assertNull(Display.getCurrent(), "Waiting must not block TestNG's async UI connection");
				var receiverConnected = new AtomicReference<>(false);
				PlatformUI.getWorkbench().getDisplay().syncExec(() -> receiverConnected.set(true));
				assertTrue(receiverConnected.get());
				return new TerminationResult(0, Duration.ZERO);
			}
		};
		assertTrue(service.runDebug("TestNG test", true).contains("completed (debug mode)"));
	}
}
