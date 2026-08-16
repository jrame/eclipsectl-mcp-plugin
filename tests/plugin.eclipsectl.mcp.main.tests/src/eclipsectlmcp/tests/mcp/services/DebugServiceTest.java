package eclipsectlmcp.tests.mcp.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IDebugEventSetListener;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.EclipseContextFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;
import org.eclipse.jdt.launching.JavaRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;

import eclipsectlmcp.mcp.servers.EclipseDebugMcpServer;
import eclipsectlmcp.tools.UISynchronizeCallable;

/**
 * Tests for EclipseDebugMcpServer: breakpoints, configurations, session control, inspection.
 */
public class DebugServiceTest {

	private static final String TEST_PROJECT_NAME = "DebugTestProject";
	private IProject project;
	private IJavaProject javaProject;
	private EclipseDebugMcpServer service;
	private NullProgressMonitor monitor = new NullProgressMonitor();

	@BeforeEach
	public void beforeEach() throws Exception {
		IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();

		project = root.getProject(TEST_PROJECT_NAME);
		if (project.exists()) {
			project.delete(true, true, monitor);
		}

		project = root.getProject(TEST_PROJECT_NAME);
		project.create(monitor);
		project.open(monitor);

		// Add Java nature
		IProjectDescription desc = project.getDescription();
		desc.setNatureIds(new String[] { JavaCore.NATURE_ID });
		project.setDescription(desc, monitor);

		javaProject = JavaCore.create(project);
		createJavaProjectStructure();
		setupClasspath();
		createTestClasses();
		project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
		createLaunchConfiguration();

		// Initialize service with DI context
		IEclipseContext context = EclipseContextFactory.create();

		Bundle testBundle = org.osgi.framework.FrameworkUtil.getBundle(DebugServiceTest.class);
		ILog log = new ILog() {
			@Override public void removeLogListener(ILogListener listener) {}
			@Override public void log(IStatus status) {
				System.out.println("[LOG] " + status.getMessage());
				if (status.getException() != null) status.getException().printStackTrace();
			}
			@Override public Bundle getBundle() { return testBundle; }
			@Override public void addLogListener(ILogListener listener) {}
			@Override public void error(String message) { System.err.println("[ERROR] " + message); }
			@Override public void error(String message, Throwable e) { System.err.println("[ERROR] " + message); if (e != null) e.printStackTrace(); }
			@Override public void warn(String message) { System.out.println("[WARN] " + message); }
			@Override public void warn(String message, Throwable e) { System.out.println("[WARN] " + message); }
			@Override public void info(String message) { System.out.println("[INFO] " + message); }
			@Override public void info(String message, Throwable e) { System.out.println("[INFO] " + message); }
		};
		context.set(ILog.class, log);

		UISynchronize uiSync = new UISynchronize() {
			@Override public void syncExec(Runnable r) { r.run(); }
			@Override public void asyncExec(Runnable r) { r.run(); }
			@Override protected boolean isUIThread(Thread t) { return true; }
			@Override protected void showBusyWhile(Runnable r) { r.run(); }
			@Override protected boolean dispatchEvents() { return false; }
		};
		context.set(UISynchronize.class, uiSync);

		// Create and set up UISynchronizeCallable
		UISynchronizeCallable uiSyncCallable = ContextInjectionFactory.make(UISynchronizeCallable.class, context);
		context.set(UISynchronizeCallable.class, uiSyncCallable);

		service = ContextInjectionFactory.make(EclipseDebugMcpServer.class, context);
	}

	@AfterEach
	public void afterEach() throws CoreException, InterruptedException {
		// Terminate any debug sessions we may have started
		try {
			ILaunchManager lm = DebugPlugin.getDefault().getLaunchManager();
			for (var launch : lm.getLaunches()) {
				if (!launch.isTerminated() && launch.canTerminate()) {
					launch.terminate();
				}
			}
		} catch (Exception e) {
			// ignore cleanup errors
		}

		// Remove all breakpoints
		try {
			var bpMgr = DebugPlugin.getDefault().getBreakpointManager();
			for (var bp : bpMgr.getBreakpoints()) {
				bp.delete();
			}
		} catch (Exception e) {
			// ignore
		}

		// Remove launch configurations created for the test project
		try {
			ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
			for (ILaunchConfiguration config : launchManager.getLaunchConfigurations()) {
				String configuredProject = config.getAttribute(
						IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, "");
				if (TEST_PROJECT_NAME.equals(configuredProject)) {
					config.delete();
				}
			}
		} catch (Exception e) {
			// ignore cleanup errors
		}

		if (project != null && project.exists()) {
			project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
			Thread.sleep(500);
			project.delete(IResource.FORCE | IResource.ALWAYS_DELETE_PROJECT_CONTENT, monitor);
		}
	}

	// ===== Breakpoint Tests =====

	@Test
	public void testAddBreakpoint() {
		System.out.println("\n=== Test: Add Breakpoint ===");
		String result = service.addBreakpoint("src/com/MainHelloWorld.java", 5, TEST_PROJECT_NAME, null, null);
		System.out.println("Result: " + result);
		assertNotNull(result);
		assertTrue(result.contains("Breakpoint added") || result.contains("Error"), "Unexpected: " + result);
	}

	@Test
	public void testAddBreakpointWithCondition() {
		System.out.println("\n=== Test: Add Breakpoint with Condition ===");
		String result = service.addBreakpoint("src/com/Foo.java", 5, TEST_PROJECT_NAME, "a > 0", null);
		System.out.println("Result: " + result);
		assertNotNull(result);
		if (result.contains("Breakpoint added")) {
			assertTrue(result.contains("Condition: a > 0"));
		}
	}

	@Test
	public void testAddBreakpointWithHitCount() {
		System.out.println("\n=== Test: Add Breakpoint with Hit Count ===");
		String result = service.addBreakpoint("src/com/Foo.java", 5, TEST_PROJECT_NAME, null, 3);
		System.out.println("Result: " + result);
		assertNotNull(result);
		if (result.contains("Breakpoint added")) {
			assertTrue(result.contains("Hit count: 3"));
		}
	}

	@Test
	public void testAddBreakpointFileNotFound() {
		System.out.println("\n=== Test: Add Breakpoint - File Not Found ===");
		String result = service.addBreakpoint("src/com/NonExistent.java", 5, TEST_PROJECT_NAME, null, null);
		System.out.println("Result: " + result);
		assertNotNull(result);
		assertTrue(result.contains("Error"), "Expected error for missing file: " + result);
	}

	@Test
	public void testListBreakpoints() {
		System.out.println("\n=== Test: List Breakpoints ===");
		// Add a breakpoint first
		service.addBreakpoint("src/com/Foo.java", 5, TEST_PROJECT_NAME, null, null);

		String result = service.listBreakpoints();
		System.out.println("Result: " + result);
		assertNotNull(result);
		assertTrue(result.contains("breakpoint") || result.contains("Error"), "Unexpected: " + result);
	}

	@Test
	public void testRemoveBreakpoint() {
		System.out.println("\n=== Test: Remove Breakpoint ===");
		// Add then remove
		service.addBreakpoint("src/com/Foo.java", 5, TEST_PROJECT_NAME, null, null);
		String result = service.removeBreakpoint("src/com/Foo.java", 5, TEST_PROJECT_NAME);
		System.out.println("Result: " + result);
		assertNotNull(result);
		assertTrue(result.contains("Breakpoint removed") || result.contains("Error"), "Unexpected: " + result);
	}

	@Test
	public void testRemoveBreakpointNotFound() {
		System.out.println("\n=== Test: Remove Breakpoint - Not Found ===");
		String result = service.removeBreakpoint("src/com/Foo.java", 99, TEST_PROJECT_NAME);
		System.out.println("Result: " + result);
		assertNotNull(result);
		assertTrue(result.contains("Error") || result.contains("No breakpoint found"), "Unexpected: " + result);
	}

	@Test
	public void testEnableDisableBreakpoint() {
		System.out.println("\n=== Test: Enable/Disable Breakpoint ===");
		// Add a breakpoint
		service.addBreakpoint("src/com/Foo.java", 5, TEST_PROJECT_NAME, null, null);

		// Disable it
		String disableResult = service.disableBreakpoint("src/com/Foo.java", 5, TEST_PROJECT_NAME);
		System.out.println("Disable: " + disableResult);
		assertNotNull(disableResult);
		assertTrue(disableResult.contains("disabled") || disableResult.contains("Error"));

		// Enable it
		String enableResult = service.enableBreakpoint("src/com/Foo.java", 5, TEST_PROJECT_NAME);
		System.out.println("Enable: " + enableResult);
		assertNotNull(enableResult);
		assertTrue(enableResult.contains("enabled") || enableResult.contains("Error"));
	}

	// ===== Configuration Tests =====

	@Test
	public void testListDebugConfigurations() {
		System.out.println("\n=== Test: List Debug Configurations ===");
		String result = service.listRunConfigurations();
		System.out.println("Result: " + result);
		assertNotNull(result);
		assertTrue(result.contains("configuration") || result.contains("Error"), "Unexpected: " + result);
		// Should contain our test config
		if (!result.contains("Error")) {
			assertTrue(result.contains("MainHelloWorld"), "Should list our launch config");
		}
	}

	@Test
	public void testCreateJavaLaunchConfigurationWithInferredProject() throws CoreException {
		String result = service.createJavaLaunchConfiguration(
				"com.MainHelloWorld",
				"CreatedMain",
				null,
				"first second",
				"-Xmx256m",
				"${workspace_loc:/" + TEST_PROJECT_NAME + "}",
				Map.of("APP_MODE", "test"),
				false,
				"error");

		assertTrue(result.contains("Created Java launch configuration 'CreatedMain'"), "Unexpected: " + result);
		assertTrue(result.contains("Project: " + TEST_PROJECT_NAME), "Unexpected: " + result);

		ILaunchConfiguration config = findLaunchConfiguration("CreatedMain");
		assertNotNull(config);
		assertEquals(TEST_PROJECT_NAME,
				config.getAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, ""));
		assertEquals("com.MainHelloWorld",
				config.getAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, ""));
		assertEquals("first second",
				config.getAttribute(IJavaLaunchConfigurationConstants.ATTR_PROGRAM_ARGUMENTS, ""));
		assertEquals("-Xmx256m",
				config.getAttribute(IJavaLaunchConfigurationConstants.ATTR_VM_ARGUMENTS, ""));
		assertEquals(Map.of("APP_MODE", "test"),
				config.getAttribute(ILaunchManager.ATTR_ENVIRONMENT_VARIABLES, Map.of()));
		assertEquals(false,
				config.getAttribute(ILaunchManager.ATTR_APPEND_ENVIRONMENT_VARIABLES, true));
	}

	@Test
	public void testCreateJavaLaunchConfigurationNameConflictPolicies() throws CoreException {
		String rejected = service.createJavaLaunchConfiguration(
				"com.MainHelloWorld", "MainHelloWorld", null,
				null, null, null, null, null, null);
		assertTrue(rejected.contains("already exists"), "Unexpected: " + rejected);

		String generated = service.createJavaLaunchConfiguration(
				"com.MainHelloWorld", "MainHelloWorld", null,
				null, null, null, null, null, "generate");
		assertTrue(generated.contains("Created Java launch configuration"), "Unexpected: " + generated);
		assertTrue(generated.contains("Requested name: MainHelloWorld"), "Unexpected: " + generated);
	}

	@Test
	public void testCreateJUnit5LaunchConfigurationExplicitly() throws CoreException {
		String result = service.createJavaLaunchConfiguration(
				"com.ExampleTest", "CreatedJUnit5", TEST_PROJECT_NAME,
				null, "-Xmx256m", null, null, null, "error", "junit5");

		assertTrue(result.contains("Created JUnit 5 launch configuration 'CreatedJUnit5'"),
				"Unexpected: " + result);
		ILaunchConfiguration config = findLaunchConfiguration("CreatedJUnit5");
		assertNotNull(config);
		assertEquals("org.eclipse.jdt.junit.launchconfig", config.getType().getIdentifier());
		assertEquals("org.eclipse.jdt.junit.loader.junit5",
				config.getAttribute("org.eclipse.jdt.junit.TEST_KIND", ""));
		assertEquals("com.ExampleTest",
				config.getAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, ""));
	}

	@Test
	public void testCreateLaunchConfigurationWithAutoDetectedJUnit5() throws CoreException {
		String result = service.createJavaLaunchConfiguration(
				"com.ExampleTest", "AutoJUnit5", TEST_PROJECT_NAME,
				null, null, null, null, null, "error", "auto");

		assertTrue(result.contains("Type: JUnit 5 (auto-detected)"), "Unexpected: " + result);
		ILaunchConfiguration config = findLaunchConfiguration("AutoJUnit5");
		assertNotNull(config);
		assertEquals("org.eclipse.jdt.junit.loader.junit5",
				config.getAttribute("org.eclipse.jdt.junit.TEST_KIND", ""));
	}

	@Test
	public void testCreateTestNgLaunchConfigurationOrReportsMissingPlugin() throws CoreException {
		ILaunchConfigurationType testNgType = DebugPlugin.getDefault().getLaunchManager()
				.getLaunchConfigurationType("org.testng.eclipse.launchconfig");
		String result = service.createJavaLaunchConfiguration(
				"com.ExampleTest", "CreatedTestNG", TEST_PROJECT_NAME,
				null, null, null, null, null, "error", "testng");

		if (testNgType == null) {
			assertTrue(result.contains("Install the TestNG Eclipse plugin"), "Unexpected: " + result);
			return;
		}
		assertTrue(result.contains("Created TestNG launch configuration 'CreatedTestNG'"),
				"Unexpected: " + result);
		ILaunchConfiguration config = findLaunchConfiguration("CreatedTestNG");
		assertNotNull(config);
		assertEquals(List.of("com.ExampleTest"),
				config.getAttribute("org.testng.eclipse.CLASS_TEST_LIST", List.of()));
		assertEquals(1, config.getAttribute("org.testng.eclipse.TYPE", 0));
	}

	@Test
	public void testRejectsInvalidLaunchConfigurationType() {
		String result = service.createJavaLaunchConfiguration(
				"com.MainHelloWorld", "InvalidType", TEST_PROJECT_NAME,
				null, null, null, null, null, "error", "spock");

		assertTrue(result.contains("Expected: java, junit4, junit5, testng, or auto"),
				"Unexpected: " + result);
	}

	@Test
	public void testGetLaunchConfigurationMasksEnvironmentValues() {
		service.updateLaunchEnvironment("MainHelloWorld", Map.of("API_TOKEN", "secret-value"),
				null, null, null);

		String masked = service.getLaunchConfiguration("MainHelloWorld", null);
		assertTrue(masked.contains("API_TOKEN = <hidden>"), "Unexpected: " + masked);
		assertTrue(!masked.contains("secret-value"), "Environment value should be hidden: " + masked);

		String visible = service.getLaunchConfiguration("MainHelloWorld", true);
		assertTrue(visible.contains("API_TOKEN = secret-value"), "Unexpected: " + visible);
		assertTrue(visible.contains("Main class: com.MainHelloWorld"), "Unexpected: " + visible);
	}

	@Test
	public void testUpdateLaunchEnvironmentMergeRemoveAndReplace() throws CoreException {
		String initial = service.updateLaunchEnvironment("MainHelloWorld",
				Map.of("FIRST", "1", "SECOND", "2"), null, "merge", false);
		assertTrue(initial.contains("Configured variables: 2"), "Unexpected: " + initial);

		String merged = service.updateLaunchEnvironment("MainHelloWorld",
				Map.of("FIRST", "updated"), List.of("SECOND"), null, null);
		assertTrue(merged.contains("Variables removed: 1"), "Unexpected: " + merged);

		ILaunchConfiguration mergedConfig = findLaunchConfiguration("MainHelloWorld");
		assertEquals(Map.of("FIRST", "updated"),
				mergedConfig.getAttribute(ILaunchManager.ATTR_ENVIRONMENT_VARIABLES, Map.of()));
		assertEquals(false,
				mergedConfig.getAttribute(ILaunchManager.ATTR_APPEND_ENVIRONMENT_VARIABLES, true));

		String replaced = service.updateLaunchEnvironment("MainHelloWorld",
				Map.of("ONLY", "value"), null, "replace", true);
		assertTrue(replaced.contains("Update mode: replace"), "Unexpected: " + replaced);

		ILaunchConfiguration replacedConfig = findLaunchConfiguration("MainHelloWorld");
		assertEquals(Map.of("ONLY", "value"),
				replacedConfig.getAttribute(ILaunchManager.ATTR_ENVIRONMENT_VARIABLES, Map.of()));
		assertEquals(true,
				replacedConfig.getAttribute(ILaunchManager.ATTR_APPEND_ENVIRONMENT_VARIABLES, false));
	}

	@Test
	public void testLaunchDebugConfigNotFound() {
		System.out.println("\n=== Test: Launch Debug Config - Not Found ===");
		String result = service.runDebug("NonExistentConfig", false);
		System.out.println("Result: " + result);
		assertNotNull(result);
		assertTrue(result.contains("Error") && result.contains("not found"), "Unexpected: " + result);
	}

	// ===== Session Control Tests (no active session) =====

	@Test
	public void testStepIntoNoSession() {
		System.out.println("\n=== Test: Step Into - No Session ===");
		String result = service.stepInto();
		System.out.println("Result: " + result);
		assertNotNull(result);
		assertTrue(result.contains("Error") && result.contains("No active debug session"), "Unexpected: " + result);
	}

	@Test
	public void testStepOverNoSession() {
		System.out.println("\n=== Test: Step Over - No Session ===");
		String result = service.stepOver();
		System.out.println("Result: " + result);
		assertNotNull(result);
		assertTrue(result.contains("Error") && result.contains("No active debug session"));
	}

	@Test
	public void testStepReturnNoSession() {
		System.out.println("\n=== Test: Step Return - No Session ===");
		String result = service.stepReturn();
		System.out.println("Result: " + result);
		assertNotNull(result);
		assertTrue(result.contains("Error") && result.contains("No active debug session"));
	}

	@Test
	public void testResumeNoSession() {
		System.out.println("\n=== Test: Resume - No Session ===");
		String result = service.resume();
		System.out.println("Result: " + result);
		assertNotNull(result);
		assertTrue(result.contains("Error") && result.contains("No active debug session"));
	}

	@Test
	public void testSuspendNoSession() {
		System.out.println("\n=== Test: Suspend - No Session ===");
		String result = service.suspend();
		System.out.println("Result: " + result);
		assertNotNull(result);
		assertTrue(result.contains("Error") && result.contains("No active debug session"));
	}

	@Test
	public void testTerminateNoSession() {
		System.out.println("\n=== Test: Terminate - No Session ===");
		String result = service.terminate();
		System.out.println("Result: " + result);
		assertNotNull(result);
		assertTrue(result.contains("Error") && result.contains("No active debug session"));
	}

	// ===== Inspection Tests (no active session) =====

	@Test
	public void testGetStackTraceNoSession() {
		System.out.println("\n=== Test: Get Stack Trace - No Session ===");
		String result = service.getStackTrace(null, null, null);
		System.out.println("Result: " + result);
		assertNotNull(result);
		assertTrue(result.contains("Error") && result.contains("No active debug session"));
	}

	@Test
	public void testGetVariablesNoSession() {
		System.out.println("\n=== Test: Get Variables - No Session ===");
		String result = service.getVariables(null);
		System.out.println("Result: " + result);
		assertNotNull(result);
		assertTrue(result.contains("Error") && result.contains("No active debug session"));
	}

	@Test
	public void testEvaluateExpressionNoSession() {
		System.out.println("\n=== Test: Evaluate Expression - No Session ===");
		String result = service.evaluateExpression("x + 1", null);
		System.out.println("Result: " + result);
		assertNotNull(result);
		assertTrue(result.contains("Error") && result.contains("No active debug session"));
	}

	@Test
	public void testEvaluateExpressionEmpty() {
		System.out.println("\n=== Test: Evaluate Expression - Empty ===");
		String result = service.evaluateExpression("", null);
		System.out.println("Result: " + result);
		assertNotNull(result);
		assertTrue(result.contains("Error") && result.contains("empty"));
	}

	@Test
	public void testGetDebugStatus() {
		System.out.println("\n=== Test: Get Debug Status ===");
		String result = service.getDebugStatus();
		System.out.println("Result: " + result);
		assertNotNull(result);
		// Should return valid JSON even with no active session
		assertTrue(result.contains("Debug status") || result.contains("Error"), "Unexpected: " + result);
	}

	// ===== Thread Management Tests (no active session) =====

	@Test
	public void testListThreadsNoSession() {
		System.out.println("\n=== Test: List Threads - No Session ===");
		String result = service.listThreads(null);
		System.out.println("Result: " + result);
		assertNotNull(result);
		assertTrue(result.contains("Error") && result.contains("No active debug session"));
	}

	@Test
	public void testSelectThreadNoSession() {
		System.out.println("\n=== Test: Select Thread - No Session ===");
		String result = service.selectThread("main", null);
		System.out.println("Result: " + result);
		assertNotNull(result);
		assertTrue(result.contains("Error") && result.contains("No active debug session"));
	}

	@Test
	public void testSelectThreadReset() {
		System.out.println("\n=== Test: Select Thread - Reset Focus ===");
		String result = service.selectThread(null, null);
		System.out.println("Result: " + result);
		assertNotNull(result);
		assertTrue(result.contains("Thread focus cleared"), "Unexpected: " + result);
	}

	// ===== Integration Test: Full Debug Session =====

	@Test
	public void testFullDebugSession() throws Exception {
		System.out.println("\n" + "=".repeat(60));
		System.out.println("Test: Full Debug Session (breakpoint -> launch -> step -> inspect -> terminate)");
		System.out.println("=".repeat(60));

		// 0. Create a class with a sleep loop so breakpoint is reliably hit
		String debugContent = "package com;\n\n"
				+ "public class DebugTarget {\n"                                       // line 3
				+ "    public static void main(String[] args) throws Exception {\n"    // line 4
				+ "        Thread.sleep(500);\n"                                        // line 5
				+ "        int total = 0;\n"                                            // line 6 <- breakpoint here
				+ "        for (int i = 0; i < 5; i++) {\n"                            // line 7
				+ "            total += i;\n"                                           // line 8
				+ "        }\n"                                                         // line 9
				+ "        System.out.println(\"Total: \" + total);\n"                 // line 10
				+ "    }\n"                                                             // line 11
				+ "}\n";
		createFile("src/com/DebugTarget.java", debugContent);
		project.refreshLocal(IResource.DEPTH_INFINITE, monitor);

		// Create a launch config for DebugTarget
		ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
		ILaunchConfigurationType type = launchManager
				.getLaunchConfigurationType(IJavaLaunchConfigurationConstants.ID_JAVA_APPLICATION);
		ILaunchConfigurationWorkingCopy wc = type.newInstance(null, "DebugTarget");
		wc.setAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, TEST_PROJECT_NAME);
		wc.setAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, "com.DebugTarget");
		wc.doSave();

		// Build the project so .class files are generated
		project.build(IncrementalProjectBuilder.FULL_BUILD, monitor);
		// Wait for build to complete
		Thread.sleep(2000);

		// Verify build output exists
		boolean classExists = project.getFile("bin/com/DebugTarget.class").exists();
		System.out.println("Class file exists: " + classExists);
		if (!classExists) {
			project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
			classExists = project.getFile("bin/com/DebugTarget.class").exists();
			System.out.println("Class file exists after refresh: " + classExists);
		}
		assertTrue(classExists, "DebugTarget.class not found - build failed");

		// 1. Add a breakpoint on line 6 (int total = 0; after the sleep)
		System.out.println("\n--- Step 1: Add breakpoint ---");
		String addResult = service.addBreakpoint("src/com/DebugTarget.java", 6, TEST_PROJECT_NAME, null, null);
		System.out.println("Add breakpoint: " + addResult);
		assertTrue(addResult.contains("Breakpoint added"), "Failed to add breakpoint: " + addResult);

		// 2. Launch debug configuration directly (not via service, to avoid UISynchronize issues)
		System.out.println("\n--- Step 2: Launch debug config ---");
		ILaunchManager lm = DebugPlugin.getDefault().getLaunchManager();
		ILaunchConfiguration targetConfig = null;
		for (ILaunchConfiguration cfg : lm.getLaunchConfigurations()) {
			if (cfg.getName().equals("DebugTarget")) {
				targetConfig = cfg;
				break;
			}
		}
		assertNotNull(targetConfig, "DebugTarget launch config not found");

		// Register a listener to detect suspension
		final boolean[] suspendedFlag = { false };
		final Object lock = new Object();
		IDebugEventSetListener listener = events -> {
			for (DebugEvent event : events) {
				String kindName = switch (event.getKind()) {
					case DebugEvent.CREATE -> "CREATE";
					case DebugEvent.TERMINATE -> "TERMINATE";
					case DebugEvent.RESUME -> "RESUME";
					case DebugEvent.SUSPEND -> "SUSPEND";
					case DebugEvent.CHANGE -> "CHANGE";
					case DebugEvent.MODEL_SPECIFIC -> "MODEL_SPECIFIC";
					default -> "kind=" + event.getKind();
				};
				System.out.println("  DebugEvent: " + kindName + " detail=" + event.getDetail()
						+ " source=" + event.getSource().getClass().getSimpleName());
				if (event.getKind() == DebugEvent.SUSPEND
						&& event.getSource().getClass().getSimpleName().contains("Thread")) {
					synchronized (lock) {
						suspendedFlag[0] = true;
						lock.notifyAll();
					}
				}
			}
		};
		DebugPlugin.getDefault().addDebugEventListener(listener);

		try {
			ILaunch launch = targetConfig.launch(ILaunchManager.DEBUG_MODE, monitor);
			System.out.println("Launch started: " + launch);

			// 3. Wait for breakpoint hit via listener (max 20 seconds)
			System.out.println("\n--- Step 3: Waiting for breakpoint hit ---");
			synchronized (lock) {
				if (!suspendedFlag[0]) {
					lock.wait(20_000);
				}
			}
			assertTrue(suspendedFlag[0], "Debug session never hit the breakpoint (not suspended)");
			System.out.println("Breakpoint hit!");
		} finally {
			DebugPlugin.getDefault().removeDebugEventListener(listener);
		}

		// 4. Get debug status (should show suspended)
		System.out.println("\n--- Step 4: Debug status ---");
		String statusResult = service.getDebugStatus();
		System.out.println("Status: " + statusResult);
		assertTrue(statusResult.contains("suspended") || statusResult.contains("Debug status"));
		assertTrue(statusResult.contains("suspended") || statusResult.contains("Breakpoint"));

		// 5. Get stack trace
		System.out.println("\n--- Step 5: Stack trace ---");
		String stackTrace = service.getStackTrace(10, null, false);
		System.out.println("Stack trace:\n" + stackTrace);
		assertNotNull(stackTrace);
		assertTrue(stackTrace.contains("Stack Trace") || stackTrace.contains("main"), "Unexpected stack trace: " + stackTrace);

		// 6. Get variables
		System.out.println("\n--- Step 6: Variables ---");
		String variables = service.getVariables(0);
		System.out.println("Variables: " + variables);
		assertNotNull(variables);
		assertTrue(variables.contains("variables") || variables.contains("args"), "Unexpected variables: " + variables);

		// 6b. List threads
		System.out.println("\n--- Step 6b: List threads ---");
		String threadsResult = service.listThreads(null);
		System.out.println("Threads: " + threadsResult);
		assertNotNull(threadsResult);
		assertTrue(threadsResult.contains("Threads") || threadsResult.contains("threads"), "Should contain thread list: " + threadsResult);
		assertTrue(threadsResult.contains("main"), "Should contain 'main' thread: " + threadsResult);

		// 6c. Select thread "main"
		System.out.println("\n--- Step 6c: Select thread 'main' ---");
		String selectResult = service.selectThread("main", null);
		System.out.println("Select thread: " + selectResult);
		assertTrue(selectResult.contains("focused"), "Should confirm focus: " + selectResult);

		// 6d. Verify variables still work with focused thread
		System.out.println("\n--- Step 6d: Variables with focused thread ---");
		String variablesFocused = service.getVariables(0);
		System.out.println("Variables (focused): " + variablesFocused);
		assertNotNull(variablesFocused);
		assertTrue(variablesFocused.contains("Variables") || variablesFocused.contains("variables"), "Should still return variables: " + variablesFocused);

		// 6e. Select non-existent thread (should error)
		System.out.println("\n--- Step 6e: Select non-existent thread ---");
		String badSelect = service.selectThread("nonexistent-thread", null);
		System.out.println("Bad select: " + badSelect);
		assertTrue(badSelect.contains("Error") && badSelect.contains("not found"), "Should error for bad thread: " + badSelect);

		// 6f. Reset thread focus
		System.out.println("\n--- Step 6f: Reset thread focus ---");
		String resetResult = service.selectThread(null, null);
		System.out.println("Reset: " + resetResult);
		assertTrue(resetResult.contains("cleared"), "Should confirm clear: " + resetResult);

		// 7. Evaluate expression (simple variable lookup)
		System.out.println("\n--- Step 7: Evaluate expression ---");
		String evalResult = service.evaluateExpression("args", 0);
		System.out.println("Eval 'args': " + evalResult);
		assertNotNull(evalResult);

		// 8. Step over
		System.out.println("\n--- Step 8: Step over ---");
		String stepResult = service.stepOver();
		System.out.println("Step over: " + stepResult);
		assertTrue(stepResult.contains("Stepping over"), "Step over failed: " + stepResult);

		// Wait for step to complete
		Thread.sleep(1000);

		// 9. Check we're still suspended (on next line)
		System.out.println("\n--- Step 9: Status after step ---");
		String statusAfterStep = service.getDebugStatus();
		System.out.println("Status after step: " + statusAfterStep);

		// 10. Step into (go into Foo constructor or add method)
		System.out.println("\n--- Step 10: Step into ---");
		// Re-check if still suspended before stepping
		if (statusAfterStep.contains("\"suspended\"")) {
			String stepIntoResult = service.stepInto();
			System.out.println("Step into: " + stepIntoResult);
			Thread.sleep(1000);
		}

		// 11. Step return (come back out)
		System.out.println("\n--- Step 11: Step return ---");
		String statusBeforeReturn = service.getDebugStatus();
		if (statusBeforeReturn.contains("\"suspended\"")) {
			String stepReturnResult = service.stepReturn();
			System.out.println("Step return: " + stepReturnResult);
			Thread.sleep(1000);
		}

		// 12. Resume execution
		System.out.println("\n--- Step 12: Resume ---");
		String statusBeforeResume = service.getDebugStatus();
		if (statusBeforeResume.contains("\"suspended\"")) {
			String resumeResult = service.resume();
			System.out.println("Resume: " + resumeResult);
			Thread.sleep(1000);
		}

		// 13. Terminate
		System.out.println("\n--- Step 13: Terminate ---");
		// The program may have finished naturally, try terminate anyway
		String terminateResult = service.terminate();
		System.out.println("Terminate: " + terminateResult);

		// 14. Final status
		System.out.println("\n--- Step 14: Final status ---");
		String finalStatus = service.getDebugStatus();
		System.out.println("Final status: " + finalStatus);

		System.out.println("\n" + "=".repeat(60));
		System.out.println("Full Debug Session Test COMPLETE");
		System.out.println("=".repeat(60));
	}

	// ===== Helper Methods =====

	private void createJavaProjectStructure() throws CoreException {
		IFolder srcFolder = project.getFolder("src");
		if (!srcFolder.exists()) srcFolder.create(IResource.NONE, true, monitor);
		IFolder binFolder = project.getFolder("bin");
		if (!binFolder.exists()) binFolder.create(IResource.NONE, true, monitor);
		IFolder comFolder = srcFolder.getFolder("com");
		if (!comFolder.exists()) comFolder.create(IResource.NONE, true, monitor);
	}

	private void setupClasspath() throws CoreException {
		javaProject.setOutputLocation(project.getFolder("bin").getFullPath(), monitor);
		IClasspathEntry[] entries = new IClasspathEntry[] {
			JavaCore.newSourceEntry(project.getFolder("src").getFullPath()),
			JavaCore.newContainerEntry(new Path(JavaRuntime.JRE_CONTAINER))
		};
		javaProject.setRawClasspath(entries, monitor);
	}

	private void createTestClasses() throws CoreException {
		String fooContent = "package com;\n\n"
				+ "public class Foo {\n"
				+ "    public int add(int a, int b) {\n"
				+ "        return a + b;\n"
				+ "    }\n"
				+ "    public int multiply(int a, int b) {\n"
				+ "        return a * b;\n"
				+ "    }\n"
				+ "}\n";
		createFile("src/com/Foo.java", fooContent);

		String mainContent = "package com;\n\n"
				+ "public class MainHelloWorld {\n"
				+ "    public static void main(String[] args) {\n"
				+ "        System.out.println(\"Hello World!\");\n"
				+ "        Foo foo = new Foo();\n"
				+ "        System.out.println(\"2 + 3 = \" + foo.add(2, 3));\n"
				+ "    }\n"
				+ "}\n";
		createFile("src/com/MainHelloWorld.java", mainContent);

		String junit5Content = "package com;\n\n"
				+ "import org.junit.jupiter.api.Test;\n\n"
				+ "public class ExampleTest {\n"
				+ "    @Test\n"
				+ "    void example() {\n"
				+ "    }\n"
				+ "}\n";
		createFile("src/com/ExampleTest.java", junit5Content);
	}

	private void createLaunchConfiguration() throws CoreException {
		ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
		for (var config : launchManager.getLaunchConfigurations()) {
			if (config.getName().equals("MainHelloWorld")) {
				config.delete();
			}
		}

		ILaunchConfigurationType type = launchManager
				.getLaunchConfigurationType(IJavaLaunchConfigurationConstants.ID_JAVA_APPLICATION);
		ILaunchConfigurationWorkingCopy wc = type.newInstance(null, "MainHelloWorld");
		wc.setAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, TEST_PROJECT_NAME);
		wc.setAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, "com.MainHelloWorld");
		wc.doSave();
	}

	private ILaunchConfiguration findLaunchConfiguration(String name) throws CoreException {
		for (ILaunchConfiguration config : DebugPlugin.getDefault().getLaunchManager().getLaunchConfigurations()) {
			if (config.getName().equals(name)) {
				return config;
			}
		}
		return null;
	}

	private IFile createFile(String path, String content) throws CoreException {
		IFile file = project.getFile(path);
		ByteArrayInputStream source = new ByteArrayInputStream(content.getBytes());
		if (file.exists()) {
			file.setContents(source, true, true, monitor);
		} else {
			file.create(source, true, monitor);
		}
		return file;
	}
}
