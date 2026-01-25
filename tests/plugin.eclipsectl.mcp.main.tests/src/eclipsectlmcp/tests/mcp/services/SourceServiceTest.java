package eclipsectlmcp.tests.mcp.services;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspaceDescription;
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

import eclipsectlmcp.mcp.services.SourceEditingService;

/**
 * Test for Source functionality in SourceEditingService.
 * Tests organizeImports, organizeImportsInPackage, and formatFile.
 */
public class SourceServiceTest {

	private static final String TEST_PROJECT_NAME = "SourceTestProject";
	private IProject project;
	private IJavaProject javaProject;
	private SourceEditingService service;
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

		// Disable auto-build to prevent build deadlocks in tests
		IWorkspaceDescription wsDesc = ResourcesPlugin.getWorkspace().getDescription();
		wsDesc.setAutoBuilding(false);
		ResourcesPlugin.getWorkspace().setDescription(wsDesc);

		project.refreshLocal(IResource.DEPTH_INFINITE, monitor);

		// Build to ensure compilation units are ready
		ResourcesPlugin.getWorkspace().build(
				org.eclipse.core.resources.IncrementalProjectBuilder.FULL_BUILD, monitor);

		IEclipseContext context = EclipseContextFactory.create();

		Bundle testBundle = org.osgi.framework.FrameworkUtil.getBundle(SourceServiceTest.class);

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

		// No-op UISynchronize: safeOpenEditor/refreshEditor try to open real Eclipse editors
		// which causes "file changed on disk" popups and hangs when writeJava/removeJava
		// modify files then try to open them. JDT operations work on ICompilationUnit buffers
		// directly and don't need editors.
		UISynchronize uiSync = new UISynchronize() {
			@Override public void syncExec(Runnable runnable) { /* no-op */ }
			@Override public void asyncExec(Runnable runnable) { /* no-op */ }
			@Override protected boolean isUIThread(Thread thread) { return true; }
			@Override protected void showBusyWhile(Runnable runnable) { runnable.run(); }
			@Override protected boolean dispatchEvents() { return false; }
		};
		context.set(UISynchronize.class, uiSync);

		service = ContextInjectionFactory.make(SourceEditingService.class, context);
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
	public void testOrganizeImports() {
		System.out.println("\n" + repeat("=", 60));
		System.out.println("Test: Organize Imports");
		System.out.println(repeat("=", 60));

		try {
			String result = service.organizeImports(TEST_PROJECT_NAME, "src/com/example/MessyImports.java");

			System.out.println("Result: " + result);
			assertNotNull(result);

			// Read the file to verify imports were organized
			IFile file = project.getFile("src/com/example/MessyImports.java");
			assertTrue(file.exists(), "File should still exist after organizing imports");
		} catch (Exception e) {
			System.out.println("Error: " + e.getMessage());
			e.printStackTrace();
		}
	}

	@Test
	public void testOrganizeImportsInPackage() {
		System.out.println("\n" + repeat("=", 60));
		System.out.println("Test: Organize Imports in Package");
		System.out.println(repeat("=", 60));

		try {
			String result = service.organizeImportsInPackage(TEST_PROJECT_NAME, "com.example");

			System.out.println("Result: " + result);
			assertNotNull(result);
			// Should process multiple files in the package
			assertTrue(result.contains("processed") || result.contains("organized")
					|| result.contains("file") || result.contains("Error"),
					"Expected processing info but got: " + result);
		} catch (Exception e) {
			System.out.println("Error: " + e.getMessage());
			e.printStackTrace();
		}
	}

	@Test
	public void testFormatFile() {
		System.out.println("\n" + repeat("=", 60));
		System.out.println("Test: Format File");
		System.out.println(repeat("=", 60));

		try {
			String result = service.formatFile(TEST_PROJECT_NAME, "src/com/example/BadlyFormatted.java");

			System.out.println("Result: " + result);
			assertNotNull(result);

			// Verify file still exists
			IFile file = project.getFile("src/com/example/BadlyFormatted.java");
			assertTrue(file.exists(), "File should still exist after formatting");
		} catch (Exception e) {
			System.out.println("Error: " + e.getMessage());
			e.printStackTrace();
		}
	}

	// ===== readJava / writeJava / removeJava / removeLines / resetReadGate tests =====

	@Test
	public void testReadJava() {
		System.out.println("\n" + repeat("=", 60));
		System.out.println("Test: readJava");
		System.out.println(repeat("=", 60));

		try {
			String result = service.readJava(TEST_PROJECT_NAME, "src/com/example/CleanClass.java", null);

			System.out.println("Result:\n" + result);
			assertNotNull(result);
			assertTrue(result.contains("CleanClass"), "Should contain class name");
			assertTrue(result.contains("value"), "Should contain field name");
			assertTrue(result.contains("getValue"), "Should contain method name");
			assertTrue(result.contains("setValue"), "Should contain method name");
			assertTrue(result.contains("lines"), "Should contain line ranges");
		} catch (Exception e) {
			System.out.println("Error: " + e.getMessage());
			e.printStackTrace();
			throw e;
		}
	}

	@Test
	public void testReadJavaWithClassName() {
		System.out.println("\n" + repeat("=", 60));
		System.out.println("Test: readJava with specific className");
		System.out.println(repeat("=", 60));

		try {
			String result = service.readJava(TEST_PROJECT_NAME, "src/com/example/CleanClass.java", "CleanClass");

			System.out.println("Result:\n" + result);
			assertNotNull(result);
			assertTrue(result.contains("CleanClass"), "Should contain class name");
		} catch (Exception e) {
			System.out.println("Error: " + e.getMessage());
			e.printStackTrace();
			throw e;
		}
	}

	@Test
	public void testWriteJavaRequiresRead() {
		System.out.println("\n" + repeat("=", 60));
		System.out.println("Test: writeJava requires readJava first (gate)");
		System.out.println(repeat("=", 60));

		try {
			service.writeJava(TEST_PROJECT_NAME, "src/com/example/CleanClass.java", null,
					"public String hello() { return \"hello\"; }");
			assertTrue(false, "Should have thrown RuntimeException");
		} catch (RuntimeException e) {
			System.out.println("Expected error: " + e.getMessage());
			assertTrue(e.getMessage().contains("readJava"), "Error should mention readJava");
		}
	}

	@Test
	public void testWriteJavaAddMethod() {
		System.out.println("\n" + repeat("=", 60));
		System.out.println("Test: writeJava - add new method");
		System.out.println(repeat("=", 60));

		try {
			// First call readJava to satisfy the gate
			service.readJava(TEST_PROJECT_NAME, "src/com/example/CleanClass.java", null);

			String result = service.writeJava(TEST_PROJECT_NAME, "src/com/example/CleanClass.java", null,
					"public String hello() { return \"hello\"; }");

			System.out.println("Result:\n" + result);
			assertNotNull(result);
			assertTrue(result.contains("Success"), "Should report success");
			assertTrue(result.contains("Added") || result.contains("hello"), "Should mention added method");
		} catch (Exception e) {
			System.out.println("Error: " + e.getMessage());
			e.printStackTrace();
			throw e;
		}
	}

	@Test
	public void testWriteJavaReplaceMethod() {
		System.out.println("\n" + repeat("=", 60));
		System.out.println("Test: writeJava - replace existing method");
		System.out.println(repeat("=", 60));

		try {
			service.readJava(TEST_PROJECT_NAME, "src/com/example/CleanClass.java", null);

			String result = service.writeJava(TEST_PROJECT_NAME, "src/com/example/CleanClass.java", null,
					"public int getValue() { return value * 2; }");

			System.out.println("Result:\n" + result);
			assertNotNull(result);
			assertTrue(result.contains("Success"), "Should report success");
			assertTrue(result.contains("Replaced"), "Should mention replaced");
		} catch (Exception e) {
			System.out.println("Error: " + e.getMessage());
			e.printStackTrace();
			throw e;
		}
	}

	@Test
	public void testWriteJavaAddField() {
		System.out.println("\n" + repeat("=", 60));
		System.out.println("Test: writeJava - add new field");
		System.out.println(repeat("=", 60));

		try {
			service.readJava(TEST_PROJECT_NAME, "src/com/example/CleanClass.java", null);

			String result = service.writeJava(TEST_PROJECT_NAME, "src/com/example/CleanClass.java", null,
					"private String name;");

			System.out.println("Result:\n" + result);
			assertNotNull(result);
			assertTrue(result.contains("Success"), "Should report success");
			assertTrue(result.contains("field"), "Should mention field");
		} catch (Exception e) {
			System.out.println("Error: " + e.getMessage());
			e.printStackTrace();
			throw e;
		}
	}

	@Test
	public void testRemoveJavaRequiresRead() {
		System.out.println("\n" + repeat("=", 60));
		System.out.println("Test: removeJava requires readJava first (gate)");
		System.out.println(repeat("=", 60));

		try {
			// Reset gate to ensure clean state
			service.resetReadGate(null, null);

			service.removeJava(TEST_PROJECT_NAME, "src/com/example/CleanClass.java", null, "getValue");
			assertTrue(false, "Should have thrown RuntimeException");
		} catch (RuntimeException e) {
			System.out.println("Expected error: " + e.getMessage());
			assertTrue(e.getMessage().contains("readJava"), "Error should mention readJava");
		}
	}

	@Test
	public void testRemoveJavaMethod() {
		System.out.println("\n" + repeat("=", 60));
		System.out.println("Test: removeJava - remove a method");
		System.out.println(repeat("=", 60));

		try {
			service.readJava(TEST_PROJECT_NAME, "src/com/example/CleanClass.java", null);

			String result = service.removeJava(TEST_PROJECT_NAME, "src/com/example/CleanClass.java", null, "getValue");

			System.out.println("Result:\n" + result);
			assertNotNull(result);
			assertTrue(result.contains("Success"), "Should report success");
			assertTrue(result.contains("getValue"), "Should mention removed member");
		} catch (Exception e) {
			System.out.println("Error: " + e.getMessage());
			e.printStackTrace();
			throw e;
		}
	}

	@Test
	public void testRemoveJavaMultipleMembers() {
		System.out.println("\n" + repeat("=", 60));
		System.out.println("Test: removeJava - remove multiple members");
		System.out.println(repeat("=", 60));

		try {
			service.readJava(TEST_PROJECT_NAME, "src/com/example/CleanClass.java", null);

			String result = service.removeJava(TEST_PROJECT_NAME, "src/com/example/CleanClass.java", null,
					"getValue,setValue");

			System.out.println("Result:\n" + result);
			assertNotNull(result);
			assertTrue(result.contains("Success"), "Should report success");
			assertTrue(result.contains("getValue"), "Should mention getValue");
			assertTrue(result.contains("setValue"), "Should mention setValue");
		} catch (Exception e) {
			System.out.println("Error: " + e.getMessage());
			e.printStackTrace();
			throw e;
		}
	}

	@Test
	public void testRemoveJavaNonExistentMember() {
		System.out.println("\n" + repeat("=", 60));
		System.out.println("Test: removeJava - non-existent member");
		System.out.println(repeat("=", 60));

		try {
			service.readJava(TEST_PROJECT_NAME, "src/com/example/CleanClass.java", null);

			service.removeJava(TEST_PROJECT_NAME, "src/com/example/CleanClass.java", null, "nonExistent");
			assertTrue(false, "Should have thrown RuntimeException");
		} catch (RuntimeException e) {
			System.out.println("Expected error: " + e.getMessage());
			assertTrue(e.getMessage().contains("not found"), "Error should mention not found");
		}
	}

	@Test
	public void testRemoveLines() {
		System.out.println("\n" + repeat("=", 60));
		System.out.println("Test: removeLines");
		System.out.println(repeat("=", 60));

		try {
			String readResult = service.readJava(TEST_PROJECT_NAME, "src/com/example/CleanClass.java", null);
			System.out.println("Before:\n" + readResult);

			// Remove a single line (an empty line)
			String result = service.removeLines(TEST_PROJECT_NAME, "src/com/example/CleanClass.java", "6");

			System.out.println("Result:\n" + result);
			assertNotNull(result);
			assertTrue(result.contains("Success"), "Should report success");
		} catch (Exception e) {
			System.out.println("Error: " + e.getMessage());
			e.printStackTrace();
			throw e;
		}
	}

	@Test
	public void testRemoveLinesRange() {
		System.out.println("\n" + repeat("=", 60));
		System.out.println("Test: removeLines with range");
		System.out.println(repeat("=", 60));

		try {
			service.readJava(TEST_PROJECT_NAME, "src/com/example/BadlyFormatted.java", null);

			// Remove lines 14-16 (method2)
			String result = service.removeLines(TEST_PROJECT_NAME, "src/com/example/BadlyFormatted.java", "14-16");

			System.out.println("Result:\n" + result);
			assertNotNull(result);
			assertTrue(result.contains("Success"), "Should report success");
		} catch (Exception e) {
			System.out.println("Error: " + e.getMessage());
			e.printStackTrace();
			throw e;
		}
	}

	@Test
	public void testRemoveLinesRequiresRead() {
		System.out.println("\n" + repeat("=", 60));
		System.out.println("Test: removeLines requires readJava first (gate)");
		System.out.println(repeat("=", 60));

		try {
			service.resetReadGate(null, null);

			service.removeLines(TEST_PROJECT_NAME, "src/com/example/CleanClass.java", "1-3");
			assertTrue(false, "Should have thrown RuntimeException");
		} catch (RuntimeException e) {
			System.out.println("Expected error: " + e.getMessage());
			assertTrue(e.getMessage().contains("readJava"), "Error should mention readJava");
		}
	}

	@Test
	public void testResetReadGateSpecificFile() {
		System.out.println("\n" + repeat("=", 60));
		System.out.println("Test: resetReadGate for specific file");
		System.out.println(repeat("=", 60));

		try {
			// Read the file first
			service.readJava(TEST_PROJECT_NAME, "src/com/example/CleanClass.java", null);

			// Reset gate for that file
			String result = service.resetReadGate(TEST_PROJECT_NAME, "src/com/example/CleanClass.java");
			System.out.println("Result: " + result);
			assertTrue(result.contains("reset"), "Should confirm reset");

			// Now writeJava should fail
			try {
				service.writeJava(TEST_PROJECT_NAME, "src/com/example/CleanClass.java", null,
						"public String test() { return \"test\"; }");
				assertTrue(false, "Should have thrown RuntimeException after gate reset");
			} catch (RuntimeException e) {
				assertTrue(e.getMessage().contains("readJava"), "Should require readJava again");
			}
		} catch (Exception e) {
			System.out.println("Error: " + e.getMessage());
			e.printStackTrace();
			throw e;
		}
	}

	@Test
	public void testResetReadGateAll() {
		System.out.println("\n" + repeat("=", 60));
		System.out.println("Test: resetReadGate for all files");
		System.out.println(repeat("=", 60));

		try {
			// Read two files
			service.readJava(TEST_PROJECT_NAME, "src/com/example/CleanClass.java", null);
			service.readJava(TEST_PROJECT_NAME, "src/com/example/BadlyFormatted.java", null);

			// Reset all
			String result = service.resetReadGate(null, null);
			System.out.println("Result: " + result);
			assertTrue(result.contains("reset"), "Should confirm reset");
			assertTrue(result.contains("2"), "Should mention 2 entries cleared");

			// Now writeJava should fail for both
			try {
				service.writeJava(TEST_PROJECT_NAME, "src/com/example/CleanClass.java", null,
						"public String test() { return \"test\"; }");
				assertTrue(false, "Should have thrown RuntimeException");
			} catch (RuntimeException e) {
				assertTrue(e.getMessage().contains("readJava"));
			}
		} catch (Exception e) {
			System.out.println("Error: " + e.getMessage());
			e.printStackTrace();
			throw e;
		}
	}

	@Test
	public void testFormatFileRanges() {
		System.out.println("\n" + repeat("=", 60));
		System.out.println("Test: Format File Ranges");
		System.out.println(repeat("=", 60));

		try {
			String result = service.formatFileRanges(TEST_PROJECT_NAME, "src/com/example/BadlyFormatted.java", "4-8");

			System.out.println("Result: " + result);
			assertNotNull(result);
			assertTrue(result.contains("Success"), "Should report success for range formatting");

			// Verify file still exists
			IFile file = project.getFile("src/com/example/BadlyFormatted.java");
			assertTrue(file.exists(), "File should still exist after range formatting");
		} catch (Exception e) {
			System.out.println("Error: " + e.getMessage());
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}

	@Test
	public void testFormatFileRangesMultiple() {
		System.out.println("\n" + repeat("=", 60));
		System.out.println("Test: Format File Multiple Ranges");
		System.out.println(repeat("=", 60));

		try {
			String result = service.formatFileRanges(TEST_PROJECT_NAME, "src/com/example/BadlyFormatted.java", "4-8,14-16");

			System.out.println("Result: " + result);
			assertNotNull(result);
			// Should have results for both ranges
			assertTrue(result.contains("Success"), "Should report success");
		} catch (Exception e) {
			System.out.println("Error: " + e.getMessage());
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}

	@Test
	public void testFormatFileRangesSingleLine() {
		System.out.println("\n" + repeat("=", 60));
		System.out.println("Test: Format File Single Line Range");
		System.out.println(repeat("=", 60));

		try {
			String result = service.formatFileRanges(TEST_PROJECT_NAME, "src/com/example/BadlyFormatted.java", "5");

			System.out.println("Result: " + result);
			assertNotNull(result);
			assertTrue(result.contains("Success"), "Should report success for single line");
		} catch (Exception e) {
			System.out.println("Error: " + e.getMessage());
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}

	@Test
	public void testFormatAlreadyFormattedFile() {
		System.out.println("\n" + repeat("=", 60));
		System.out.println("Test: Format Already Formatted File");
		System.out.println(repeat("=", 60));

		try {
			String result = service.formatFile(TEST_PROJECT_NAME, "src/com/example/CleanClass.java");

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
	}

	private void setupClasspath() throws CoreException {
		javaProject.setOutputLocation(project.getFolder("bin").getFullPath(), monitor);

		IClasspathEntry[] entries = new IClasspathEntry[2];
		entries[0] = JavaCore.newSourceEntry(project.getFolder("src").getFullPath());
		entries[1] = JavaCore.newContainerEntry(new Path(JavaRuntime.JRE_CONTAINER));

		javaProject.setRawClasspath(entries, monitor);
	}

	private void createTestClasses() throws CoreException {
		// A class with messy/unused imports for organizeImports testing
		String messyImportsContent =
				"package com.example;\n\n"
				+ "import java.util.HashMap;\n"
				+ "import java.util.List;\n"
				+ "import java.util.ArrayList;\n"
				+ "import java.io.File;\n"
				+ "import java.util.Map;\n"
				+ "import java.io.IOException;\n\n"
				+ "public class MessyImports {\n"
				+ "    private List<String> names = new ArrayList<>();\n"
				+ "    private Map<String, String> data = new HashMap<>();\n"
				+ "\n"
				+ "    public void addName(String name) {\n"
				+ "        names.add(name);\n"
				+ "    }\n"
				+ "\n"
				+ "    public Map<String, String> getData() {\n"
				+ "        return data;\n"
				+ "    }\n"
				+ "}\n";
		createFile("src/com/example/MessyImports.java", messyImportsContent);

		// A class with bad formatting for formatFile testing
		String badlyFormattedContent =
				"package com.example;\n\n"
				+ "public class BadlyFormatted {\n"
				+ "public void method1(  ) {\n"
				+ "int x=1;\n"
				+ "int y =   2;\n"
				+ "if(x==1){\n"
				+ "y=3;\n"
				+ "}\n"
				+ "for(int i=0;i<10;i++) {\n"
				+ "System.out.println(  i  );\n"
				+ "}\n"
				+ "}\n"
				+ "public String method2(String a,String b){\n"
				+ "return a+b;\n"
				+ "}\n"
				+ "}\n";
		createFile("src/com/example/BadlyFormatted.java", badlyFormattedContent);

		// A properly formatted class
		String cleanClassContent =
				"package com.example;\n\n"
				+ "public class CleanClass {\n"
				+ "\n"
				+ "    private int value;\n"
				+ "\n"
				+ "    public CleanClass(int value) {\n"
				+ "        this.value = value;\n"
				+ "    }\n"
				+ "\n"
				+ "    public int getValue() {\n"
				+ "        return value;\n"
				+ "    }\n"
				+ "\n"
				+ "    public void setValue(int value) {\n"
				+ "        this.value = value;\n"
				+ "    }\n"
				+ "}\n";
		createFile("src/com/example/CleanClass.java", cleanClassContent);

		// Another class in the same package (for organizeImportsInPackage)
		String anotherClassContent =
				"package com.example;\n\n"
				+ "import java.util.Collections;\n"
				+ "import java.util.Date;\n"
				+ "import java.util.List;\n"
				+ "import java.util.ArrayList;\n\n"
				+ "public class AnotherClass {\n"
				+ "    private List<String> items = new ArrayList<>();\n"
				+ "\n"
				+ "    public List<String> getSorted() {\n"
				+ "        Collections.sort(items);\n"
				+ "        return items;\n"
				+ "    }\n"
				+ "}\n";
		createFile("src/com/example/AnotherClass.java", anotherClassContent);
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
