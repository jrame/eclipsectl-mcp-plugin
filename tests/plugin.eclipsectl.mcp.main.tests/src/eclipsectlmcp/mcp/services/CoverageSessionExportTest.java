package eclipsectlmcp.mcp.services;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.eclemma.core.ICoverageSession;
import org.eclipse.eclemma.core.ISessionListener;
import org.eclipse.eclemma.core.ISessionManager;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.IExecutionDataVisitor;
import org.jacoco.core.data.ISessionInfoVisitor;
import org.jacoco.core.data.SessionInfo;
import org.jacoco.core.tools.ExecFileLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;

@Timeout(10)
public class CoverageSessionExportTest {
	private Path temp;
	@BeforeEach public void createDirectory() throws Exception { temp = Files.createTempDirectory("coverage-export-"); }
	@AfterEach public void removeDirectory() throws Exception {
		if (temp != null) {
			try (var paths = Files.walk(temp)) {
				for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
			}
		}
	}
	private final AtomicReference<ISessionListener> listener = new AtomicReference<>();
	private final AtomicReference<ILaunchConfiguration> configIdentity = new AtomicReference<>();
	private final ILaunchConfiguration config = proxy(ILaunchConfiguration.class, (method, args) -> {
		if (method.equals("equals")) return args[0] == configIdentity.get();
		return null;
	});
	private ISessionManager manager() {
		configIdentity.set(config);
		return proxy(ISessionManager.class, (method, args) -> {
			if (method.equals("addSessionListener")) listener.set((ISessionListener) args[0]);
			else if (method.equals("removeSessionListener")) assertSame(listener.getAndSet(null), args[0]);
			else fail("Must not read the active or pre-existing sessions: " + method);
			return null;
		});
	}

	private ILaunch launch() {
		return proxy(ILaunch.class, (method, args) -> {
			assertEquals("getLaunchConfiguration", method);
			return config;
		});
	}

	private static ICoverageSession session(ILaunchConfiguration configuration, boolean failWrite) {
		return proxy(ICoverageSession.class, (method, args) -> {
			if (method.equals("getLaunchConfiguration")) return configuration;
			if (method.equals("accept")) {
				((ISessionInfoVisitor) args[1]).visitSessionInfo(new SessionInfo("test-session", 100, 200));
				if (failWrite) throw new CoreException(Status.error("broken session"));
				((IExecutionDataVisitor) args[0]).visitClassExecution(new ExecutionData(42, "example/Foo", new boolean[] { true, false, true }));
				return null;
			}
			throw new AssertionError("Unexpected session operation: " + method);
		});
	}

	@Test public void exportsBinaryDataWithSafeTruncatedNameAndNoOverwrite() throws Exception {
		ICoverageSession session = session(config, false);
		LocalDateTime time = LocalDateTime.of(2026, 10, 26, 21, 15, 30);
		String name = "FooNgTest:/\\*?\"<>|" + "x".repeat(50);
		Path directory = temp.resolve(".logs/eclipsectl");
		Path first = CoverageSessionExport.write(session, directory, name, time);
		byte[] original = Files.readAllBytes(first);
		assertEquals("2026-10-26_21-15-30-000_" + name.replaceAll("[^a-zA-Z0-9._-]", "_").substring(0, 32) + ".exec", first.getFileName().toString());
		assertEquals(directory.toAbsolutePath(), first.getParent());
		assertTrue(first.isAbsolute());
		ExecFileLoader reader = new ExecFileLoader();
		reader.load(first.toFile());
		assertEquals("test-session", reader.getSessionInfoStore().getInfos().getFirst().getId());
		assertArrayEquals(new boolean[] { true, false, true }, reader.getExecutionDataStore().get(42).getProbes());
		Path second = CoverageSessionExport.write(session, directory, name, time);
		assertNotEquals(first, second);
		assertArrayEquals(original, Files.readAllBytes(first));
	}

	@Test public void failedWriteRemovesPartialFile() throws Exception {
		assertThrows(CoreException.class, () -> CoverageSessionExport.write(session(config, true), temp, "Foo", LocalDateTime.now()));
		try (var paths = Files.list(temp)) { assertEquals(0, paths.count()); }
	}

	@Test public void waitsForNewMatchingSessionAndReleasesListener() throws Exception {
		try (var export = new CoverageSessionExport(manager(), temp, Duration.ofSeconds(2))) {
			listener.get().sessionAdded(session(null, false)); // unrelated/imported session
			var waiting = CompletableFuture.supplyAsync(() -> {
				try { return export.awaitSession(launch()); }
				catch (Exception e) { throw new RuntimeException(e); }
			});
			ICoverageSession expected = session(config, false);
			listener.get().sessionAdded(expected);
			assertSame(expected, waiting.get(3, java.util.concurrent.TimeUnit.SECONDS));
		}
		assertNull(listener.get());
	}

	@Test public void missingSessionTimesOutWithoutUsingAnOldActiveSession() throws Exception {
		try (var export = new CoverageSessionExport(manager(), temp, Duration.ZERO)) {
			assertThrows(IOException.class, () -> export.awaitSession(launch()));
		}
		assertNull(listener.get());
	}

	@Test public void ambiguousNewSessionsAreRejected() throws Exception {
		try (var export = new CoverageSessionExport(manager(), temp, Duration.ZERO)) {
			listener.get().sessionAdded(session(config, false));
			listener.get().sessionAdded(session(config, false));
			assertThrows(IOException.class, () -> export.awaitSession(launch()));
		}
	}

	@FunctionalInterface interface Invocation { Object invoke(String method, Object[] args) throws Throwable; }
	private static <T> T proxy(Class<T> type, Invocation invocation) {
		return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },
				(instance, method, args) -> invocation.invoke(method.getName(), args)));
	}
}
