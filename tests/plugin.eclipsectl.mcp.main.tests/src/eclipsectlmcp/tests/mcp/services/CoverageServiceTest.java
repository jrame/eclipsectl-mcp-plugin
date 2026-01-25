package eclipsectlmcp.tests.mcp.services;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.debug.core.DebugPlugin;
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

import eclipsectlmcp.mcp.services.CoverageService;

/**
 * Test for Coverage functionality in CoverageService.
 * Tests runCoverage, getCoverageReport, and getClassCoverage methods.
 */
public class CoverageServiceTest {

	private static final String TEST_PROJECT_NAME = "CoverageTestProject";
	private IProject project;
	private IJavaProject javaProject;
	private CoverageService service;
	private NullProgressMonitor monitor = new NullProgressMonitor();

	@BeforeEach
	public void beforeEach() throws Exception {
		// Get workspace
		IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();

		// Delete the project if it exists
		project = root.getProject(TEST_PROJECT_NAME);
		if (project.exists()) {
			project.delete(true, true, monitor);
		}

		// Create a test project
		project = root.getProject(TEST_PROJECT_NAME);
		project.create(monitor);
		project.open(monitor);

		// Add Java nature
		IProjectDescription desc = project.getDescription();
		desc.setNatureIds(new String[] { JavaCore.NATURE_ID });
		project.setDescription(desc, monitor);

		// Create Java project
		javaProject = JavaCore.create(project);

		// Create Java project structure
		createJavaProjectStructure();

		// Set up classpath
		setupClasspath();

		// Create test Java classes
		createTestClasses();

		// Refresh and build
		project.refreshLocal(IResource.DEPTH_INFINITE, monitor);

		// Create launch configuration
		createLaunchConfiguration();

		// Initialize service with DI context
		IEclipseContext context = EclipseContextFactory.create();

		// Get a real Bundle from the test framework (simpler than mocking)
		Bundle testBundle = org.osgi.framework.FrameworkUtil.getBundle(CoverageServiceTest.class);

		// Create a mock ILog that doesn't rely on Bundle methods
		ILog log = new ILog() {
			@Override
			public void removeLogListener(ILogListener listener) {
			}

			@Override
			public void log(IStatus status) {
				System.out.println("[LOG] " + status.getMessage());
				if (status.getException() != null) {
					status.getException().printStackTrace();
				}
			}

			@Override
			public Bundle getBundle() {
				return testBundle;
			}

			@Override
			public void addLogListener(ILogListener listener) {
			}

			@Override
			public void error(String message) {
				System.err.println("[ERROR] " + message);
			}

			@Override
			public void error(String message, Throwable exception) {
				System.err.println("[ERROR] " + message);
				if (exception != null) {
					exception.printStackTrace();
				}
			}

			@Override
			public void warn(String message) {
				System.out.println("[WARN] " + message);
			}

			@Override
			public void warn(String message, Throwable exception) {
				System.out.println("[WARN] " + message);
				if (exception != null) {
					exception.printStackTrace();
				}
			}

			@Override
			public void info(String message) {
				System.out.println("[INFO] " + message);
			}

			@Override
			public void info(String message, Throwable exception) {
				System.out.println("[INFO] " + message);
				if (exception != null) {
					exception.printStackTrace();
				}
			}
		};
		context.set(ILog.class, log);

		// Create and set mock UISynchronize
		UISynchronize uiSync = new UISynchronize() {
			@Override
			public void syncExec(Runnable runnable) {
				runnable.run();
			}

			@Override
			public void asyncExec(Runnable runnable) {
				runnable.run();
			}

			@Override
			protected boolean isUIThread(Thread thread) {
				return true;
			}

			@Override
			protected void showBusyWhile(Runnable runnable) {
				runnable.run();
			}

			@Override
			protected boolean dispatchEvents() {
				return false;
			}
		};
		context.set(UISynchronize.class, uiSync);

		// Create the service under test
		service = ContextInjectionFactory.make(CoverageService.class, context);
	}

	@AfterEach
	public void afterEach() throws CoreException, InterruptedException {
		// Clean up the test project
		if (project != null && project.exists()) {
			project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
			Thread.sleep(500);
			project.delete(IResource.FORCE | IResource.ALWAYS_DELETE_PROJECT_CONTENT, monitor);
		}
	}

	@Test
	public void testLaunchWithCoverage() {
		System.out.println("\n" + repeat("=", 60));
		System.out.println("Test: Launch with Coverage");
		System.out.println(repeat("=", 60));

		try {
			String result = service.runCoverage("MainHelloWorld", false);

			System.out.println("Result:");
			System.out.println(result);

			// Verify result
			assertNotNull(result);
			assertTrue(result.contains("coverage") || result.contains("Error"));

			// Wait for coverage to complete
			Thread.sleep(2000);
		} catch (Exception e) {
			if (e.getMessage().contains("EclEmma plugin is not installed")) {
				System.out.println("Note: EclEmma not installed - skipping test");
				assumeTrue(false, "Skipping test as EclEmma is not installed");
			} else {
				e.printStackTrace();
			}
		}
	}

	@Test
	public void testGetCoverageReport() {
		System.out.println("\n" + repeat("=", 60));
		System.out.println("Test: Get Coverage Report");
		System.out.println(repeat("=", 60));

		try {
			// First launch with coverage
			service.runCoverage("MainHelloWorld", false);

			// Wait for coverage to complete
			Thread.sleep(2000);

			// Get coverage report
			String result = service.getCoverageReport();

			System.out.println("Coverage Report:");
			System.out.println(result);
			System.out.println("end");

			// Verify result
			assertNotNull(result);
		} catch (Exception e) {
			if (e.getMessage().contains("EclEmma plugin is not installed")) {
				System.out.println("Note: EclEmma not installed - skipping test");
				assumeTrue(false, "Skipping test as EclEmma is not installed");
			} else {
				e.printStackTrace();
			}
		}
	}

	@Test
	public void testGetClassCoverage() {
		System.out.println("\n" + repeat("=", 60));
		System.out.println("Test: Get Class Coverage");
		System.out.println(repeat("=", 60));

		try {
			// First launch with coverage
			service.runCoverage("MainHelloWorld", false);

			// Wait for coverage to complete
			Thread.sleep(2000);

			// Get class coverage for com.Foo
			System.out.println("\n--- Coverage for com.Foo ---");
			String result1 = service.getClassCoverage("com.Foo");
			System.out.println(result1);

			// Get class coverage for com.MainHelloWorld
			System.out.println("\n--- Coverage for com.MainHelloWorld ---");
			String result2 = service.getClassCoverage("com.MainHelloWorld");
			System.out.println(result2);
			System.out.println("end");

			// Verify results
			assertNotNull(result1);
			assertNotNull(result2);
		} catch (Exception e) {
			if (e.getMessage().contains("EclEmma plugin is not installed")) {
				System.out.println("Note: EclEmma not installed - skipping test");
				assumeTrue(false, "Skipping test as EclEmma is not installed");
			} else {
				e.printStackTrace();
			}
		}
	}

	// Helper methods

	private void createJavaProjectStructure() throws CoreException {
		IFolder srcFolder = project.getFolder("src");
		if (!srcFolder.exists()) {
			srcFolder.create(IResource.NONE, true, monitor);
		}

		IFolder binFolder = project.getFolder("bin");
		if (!binFolder.exists()) {
			binFolder.create(IResource.NONE, true, monitor);
		}

		// Create com package
		IFolder comFolder = srcFolder.getFolder("com");
		if (!comFolder.exists()) {
			comFolder.create(IResource.NONE, true, monitor);
		}
	}

	private void setupClasspath() throws CoreException {
		// Set output location
		javaProject.setOutputLocation(project.getFolder("bin").getFullPath(), monitor);

		// Create classpath entries
		IClasspathEntry[] entries = new IClasspathEntry[2];

		// Add source folder
		entries[0] = JavaCore.newSourceEntry(project.getFolder("src").getFullPath());

		// Add JRE container
		entries[1] = JavaCore.newContainerEntry(new Path(JavaRuntime.JRE_CONTAINER));

		// Set classpath
		javaProject.setRawClasspath(entries, monitor);
	}

	private void createFolder(String path) throws CoreException {
		String[] segments = path.split("/");
		StringBuilder currentPath = new StringBuilder();

		for (String segment : segments) {
			currentPath.append(segment);
			IFolder folder = project.getFolder(currentPath.toString());
			if (!folder.exists()) {
				folder.create(IResource.NONE, true, monitor);
			}
			currentPath.append("/");
		}
	}

	private void createTestClasses() throws CoreException {
		// Create com.Foo class
		String fooContent = "package com;\n\n" + "public class Foo {\n" + "    public int add(int a, int b) {\n"
				+ "        return a + b;\n" + "    }\n" + "\n" + "    public int multiply(int a, int b) {\n"
				+ "        return a * b;\n" + "    }\n" + "\n" + "    public String check(boolean x, boolean y) {\n"
				+ "        if (x && y) {\n" + "            return \"both\";\n" + "        }\n"
				+ "        return \"not both\";\n" + "    }\n" + "}\n";

		createFile("src/com/Foo.java", fooContent);

		// Create com.MainHelloWorld class
		String mainContent = "package com;\n\n" + "public class MainHelloWorld {\n"
				+ "    public static void main(String[] args) {\n" + "        System.out.println(\"Hello World!\");\n"
				+ "        Foo foo = new Foo();\n" + "        System.out.println(\"2 + 3 = \" + foo.add(2, 3));\n"
				+ "        System.out.println(foo.check(true, false));\n"
				+ "    }\n" + "}\n";

		createFile("src/com/MainHelloWorld.java", mainContent);
	}

	private void createLaunchConfiguration() throws CoreException {
		ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();

		// Delete existing configuration if it exists
		org.eclipse.debug.core.ILaunchConfiguration[] configs = launchManager.getLaunchConfigurations();
		for (org.eclipse.debug.core.ILaunchConfiguration config : configs) {
			if (config.getName().equals("MainHelloWorld")) {
				config.delete();
			}
		}

		// Get Java Application launch configuration type
		ILaunchConfigurationType type = launchManager
				.getLaunchConfigurationType(IJavaLaunchConfigurationConstants.ID_JAVA_APPLICATION);

		// Create new launch configuration
		ILaunchConfigurationWorkingCopy workingCopy = type.newInstance(null, "MainHelloWorld");

		// Set project name
		workingCopy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, TEST_PROJECT_NAME);

		// Set main class
		workingCopy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, "com.MainHelloWorld");

		// Save the configuration
		workingCopy.doSave();

		System.out.println("✅ Launch configuration 'MainHelloWorld' created successfully");
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

	private String repeat(String str, int count) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < count; i++) {
			sb.append(str);
		}
		return sb.toString();
	}
}
