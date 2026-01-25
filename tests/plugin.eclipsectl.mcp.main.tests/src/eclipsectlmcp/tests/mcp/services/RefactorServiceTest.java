package eclipsectlmcp.tests.mcp.services;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.EclipseContextFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.JavaRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;

import eclipsectlmcp.mcp.services.RefactoringService;

/**
 * Test for Refactoring functionality in RefactoringService.
 * Tests renameFile, refactorRenameJavaType, refactorMoveJavaType,
 * refactorRenamePackage, moveResource, and refactorExtractMethod.
 */
public class RefactorServiceTest {

	private static final String TEST_PROJECT_NAME = "RefactorTestProject";
	private IProject project;
	private IJavaProject javaProject;
	private RefactoringService service;
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

		IProjectDescription desc = project.getDescription();
		desc.setNatureIds(new String[] { JavaCore.NATURE_ID });
		project.setDescription(desc, monitor);

		javaProject = JavaCore.create(project);

		createJavaProjectStructure();
		setupClasspath();
		createTestClasses();

		project.refreshLocal(IResource.DEPTH_INFINITE, monitor);

		// Build to ensure compilation units are ready
		ResourcesPlugin.getWorkspace().build(
				org.eclipse.core.resources.IncrementalProjectBuilder.FULL_BUILD, monitor);

		IEclipseContext context = EclipseContextFactory.create();

		Bundle testBundle = org.osgi.framework.FrameworkUtil.getBundle(RefactorServiceTest.class);

		ILog log = new ILog() {
			@Override public void removeLogListener(ILogListener listener) {}
			@Override public void log(IStatus status) {
				System.out.println("[LOG] " + status.getMessage());
				if (status.getException() != null) status.getException().printStackTrace();
			}
			@Override public Bundle getBundle() { return testBundle; }
			@Override public void addLogListener(ILogListener listener) {}
			@Override public void error(String message) { System.err.println("[ERROR] " + message); }
			@Override public void error(String message, Throwable exception) {
				System.err.println("[ERROR] " + message);
				if (exception != null) exception.printStackTrace();
			}
			@Override public void warn(String message) { System.out.println("[WARN] " + message); }
			@Override public void warn(String message, Throwable exception) {
				System.out.println("[WARN] " + message);
				if (exception != null) exception.printStackTrace();
			}
			@Override public void info(String message) { System.out.println("[INFO] " + message); }
			@Override public void info(String message, Throwable exception) {
				System.out.println("[INFO] " + message);
				if (exception != null) exception.printStackTrace();
			}
		};
		context.set(ILog.class, log);

		UISynchronize uiSync = new UISynchronize() {
			@Override public void syncExec(Runnable runnable) { runnable.run(); }
			@Override public void asyncExec(Runnable runnable) { runnable.run(); }
			@Override protected boolean isUIThread(Thread thread) { return true; }
			@Override protected void showBusyWhile(Runnable runnable) { runnable.run(); }
			@Override protected boolean dispatchEvents() { return false; }
		};
		context.set(UISynchronize.class, uiSync);

		service = ContextInjectionFactory.make(RefactoringService.class, context);
	}

	@AfterEach
	public void afterEach() throws CoreException, InterruptedException {
		if (project != null && project.exists()) {
			project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
			Thread.sleep(500);
			project.delete(IResource.FORCE | IResource.ALWAYS_DELETE_PROJECT_CONTENT, monitor);
		}
	}

	@Test
	public void testRenameFile() {
		System.out.println("\n" + repeat("=", 60));
		System.out.println("Test: Rename File");
		System.out.println(repeat("=", 60));

		try {
			// Create a simple text file to rename
			createFile("docs/notes.txt", "Some notes");

			String result = service.renameFile(TEST_PROJECT_NAME, "docs/notes.txt", "readme.txt");

			System.out.println("Result: " + result);
			assertNotNull(result);
			assertTrue(result.toLowerCase().contains("success") || result.toLowerCase().contains("renamed"),
					"Expected success message but got: " + result);

			// Verify the new file exists
			IFile newFile = project.getFile("docs/readme.txt");
			assertTrue(newFile.exists(), "Renamed file should exist");
		} catch (Exception e) {
			System.out.println("Error: " + e.getMessage());
			e.printStackTrace();
		}
	}

	@Test
	public void testRefactorRenameJavaType() {
		System.out.println("\n" + repeat("=", 60));
		System.out.println("Test: Refactor Rename Java Type");
		System.out.println(repeat("=", 60));

		try {
			String result = service.refactorRenameJavaType(
					TEST_PROJECT_NAME, "src/com/example/Calculator.java", "MathHelper");

			System.out.println("Result: " + result);
			assertNotNull(result);

			// Check that the renamed file exists or that the result indicates success
			IFile renamedFile = project.getFile("src/com/example/MathHelper.java");
			if (renamedFile.exists()) {
				System.out.println("Renamed file exists at: " + renamedFile.getFullPath());
			}
		} catch (Exception e) {
			System.out.println("Error: " + e.getMessage());
			e.printStackTrace();
		}
	}

	@Test
	public void testRefactorMoveJavaType() {
		System.out.println("\n" + repeat("=", 60));
		System.out.println("Test: Refactor Move Java Type");
		System.out.println(repeat("=", 60));

		try {
			String result = service.refactorMoveJavaType(
					TEST_PROJECT_NAME, "src/com/example/StringUtils.java", "com.example.util");

			System.out.println("Result: " + result);
			assertNotNull(result);

			// Check if moved file exists in new location
			IFile movedFile = project.getFile("src/com/example/util/StringUtils.java");
			if (movedFile.exists()) {
				System.out.println("Moved file exists at: " + movedFile.getFullPath());
			}
		} catch (Exception e) {
			System.out.println("Error: " + e.getMessage());
			e.printStackTrace();
		}
	}

	@Test
	public void testRefactorRenamePackage() {
		System.out.println("\n" + repeat("=", 60));
		System.out.println("Test: Refactor Rename Package");
		System.out.println(repeat("=", 60));

		try {
			String result = service.refactorRenamePackage(
					TEST_PROJECT_NAME, "com.example", "com.sample");

			System.out.println("Result: " + result);
			assertNotNull(result);

			// Check if renamed package exists
			IFolder renamedPkg = project.getFolder("src/com/sample");
			if (renamedPkg.exists()) {
				System.out.println("Renamed package folder exists at: " + renamedPkg.getFullPath());
			}
		} catch (Exception e) {
			System.out.println("Error: " + e.getMessage());
			e.printStackTrace();
		}
	}

	@Test
	public void testMoveResource() {
		System.out.println("\n" + repeat("=", 60));
		System.out.println("Test: Move Resource");
		System.out.println(repeat("=", 60));

		try {
			// Create a file in docs/ to move
			createFile("docs/guide.txt", "User guide content");

			// Create target folder
			IFolder target = project.getFolder("archive");
			if (!target.exists()) {
				target.create(IResource.NONE, true, monitor);
			}

			String result = service.moveResource(TEST_PROJECT_NAME, "docs/guide.txt", "archive");

			System.out.println("Result: " + result);
			assertNotNull(result);

			// Verify moved file exists
			IFile movedFile = project.getFile("archive/guide.txt");
			if (movedFile.exists()) {
				System.out.println("Moved file exists at: " + movedFile.getFullPath());
			}
		} catch (Exception e) {
			System.out.println("Error: " + e.getMessage());
			e.printStackTrace();
		}
	}

	@Test
	public void testRefactorExtractMethod() {
		System.out.println("\n" + repeat("=", 60));
		System.out.println("Test: Refactor Extract Method");
		System.out.println(repeat("=", 60));

		try {
			// The Calculator class has a compute method with extractable code at lines 7-9
			// (the body of compute: int sum = a + b; int result = sum * 2; return result;)
			String result = service.refactorExtractMethod(
					TEST_PROJECT_NAME, "src/com/example/Calculator.java",
					7, 8, "calculateSum", "private");

			System.out.println("Result: " + result);
			assertNotNull(result);
		} catch (Exception e) {
			System.out.println("Error: " + e.getMessage());
			e.printStackTrace();
		}
	}

	// Helper methods

	private void createJavaProjectStructure() throws CoreException {
		createFolder("src");
		createFolder("src/com");
		createFolder("src/com/example");
		createFolder("bin");
		createFolder("docs");
	}

	private void setupClasspath() throws CoreException {
		javaProject.setOutputLocation(project.getFolder("bin").getFullPath(), monitor);

		IClasspathEntry[] entries = new IClasspathEntry[2];
		entries[0] = JavaCore.newSourceEntry(project.getFolder("src").getFullPath());
		entries[1] = JavaCore.newContainerEntry(new Path(JavaRuntime.JRE_CONTAINER));

		javaProject.setRawClasspath(entries, monitor);
	}

	private void createTestClasses() throws CoreException {
		String calculatorContent =
				"package com.example;\n\n"
				+ "public class Calculator {\n"
				+ "    public int compute(int a, int b) {\n"
				+ "        int sum = a + b;\n"
				+ "        int result = sum * 2;\n"
				+ "        return result;\n"
				+ "    }\n"
				+ "\n"
				+ "    public int subtract(int a, int b) {\n"
				+ "        return a - b;\n"
				+ "    }\n"
				+ "}\n";
		createFile("src/com/example/Calculator.java", calculatorContent);

		String stringUtilsContent =
				"package com.example;\n\n"
				+ "public class StringUtils {\n"
				+ "    public static String reverse(String input) {\n"
				+ "        return new StringBuilder(input).reverse().toString();\n"
				+ "    }\n"
				+ "\n"
				+ "    public static boolean isEmpty(String input) {\n"
				+ "        return input == null || input.trim().isEmpty();\n"
				+ "    }\n"
				+ "}\n";
		createFile("src/com/example/StringUtils.java", stringUtilsContent);

		// A class that references others (for testing reference updates)
		String mainAppContent =
				"package com.example;\n\n"
				+ "public class MainApp {\n"
				+ "    public static void main(String[] args) {\n"
				+ "        Calculator calc = new Calculator();\n"
				+ "        System.out.println(calc.compute(3, 4));\n"
				+ "        System.out.println(StringUtils.reverse(\"hello\"));\n"
				+ "    }\n"
				+ "}\n";
		createFile("src/com/example/MainApp.java", mainAppContent);
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

	private IFile createFile(String path, String content) throws CoreException {
		// Ensure parent folders exist
		String parentPath = path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : null;
		if (parentPath != null) {
			createFolder(parentPath);
		}

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
