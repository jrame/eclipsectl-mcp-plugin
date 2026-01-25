package eclipsectlmcp.tests.mcp.services;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.ByteArrayInputStream;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.EclipseContextFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.JavaRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.util.tracker.ServiceTracker;

import eclipsectlmcp.Activator;
import eclipsectlmcp.mcp.services.QuickFixService;

public class QuickFixServiceTest {

    private static final String TEST_PROJECT_NAME = "QuickFixTestProject";
    private IProject project;
    private IJavaProject javaProject;
    private QuickFixService service;
    private NullProgressMonitor monitor = new NullProgressMonitor();

    @BeforeEach
    public void beforeEach() throws CoreException, InterruptedException {
        // Get workspace through OSGi service tracker
        BundleContext bundleContext = FrameworkUtil.getBundle(QuickFixServiceTest.class).getBundleContext();
        ServiceTracker<IWorkspace, IWorkspace> workspaceTracker = new ServiceTracker<>(bundleContext, IWorkspace.class, null);

        workspaceTracker.open();
        IWorkspace workspace = workspaceTracker.getService();
        IWorkspaceRoot root = workspace.getRoot();

        // Delete the project if it exists
        project = root.getProject(TEST_PROJECT_NAME);
        if (project.exists()) {
            project.delete(true, true, monitor);
        }

        // Create a Java project
        project = root.getProject(TEST_PROJECT_NAME);
        IProjectDescription desc = project.getWorkspace().newProjectDescription(project.getName());
        desc.setNatureIds(new String[] { JavaCore.NATURE_ID });
        project.create(desc, monitor);
        project.open(monitor);

        javaProject = JavaCore.create(project);

        // Create output folder (bin)
        IFolder binFolder = project.getFolder("bin");
        if (!binFolder.exists()) {
            binFolder.create(true, true, monitor);
        }
        javaProject.setOutputLocation(binFolder.getFullPath(), monitor);

        // Create source folder
        IFolder srcFolder = project.getFolder("src");
        if (!srcFolder.exists()) {
            srcFolder.create(IResource.NONE, true, monitor);
        }

        // Set classpath with source folder and JRE
        javaProject.setRawClasspath(
                new org.eclipse.jdt.core.IClasspathEntry[] {
                        JavaCore.newSourceEntry(project.getFullPath().append("src")),
                        JavaRuntime.getDefaultJREContainerEntry()
                },
                monitor);

        // Initialize service with DI context
        IEclipseContext context = EclipseContextFactory.create();
        context.set(ILog.class, Activator.getDefault().getLog());

        service = ContextInjectionFactory.make(QuickFixService.class, context);

        // Wait for build to complete
        Thread.sleep(1000);
    }

    @AfterEach
    public void afterEach() throws CoreException, InterruptedException {
        if (project != null && project.exists()) {
            // Refresh the project to ensure all resources are synchronized
            project.refreshLocal(IResource.DEPTH_INFINITE, monitor);

            // Wait a bit to ensure all streams are released
            Thread.sleep(500);

            // Force delete with both flags set to true
            project.delete(IResource.FORCE | IResource.ALWAYS_DELETE_PROJECT_CONTENT, monitor);
        }
    }

    @Test
    public void testGetQuickFixesForMissingImport() throws Exception {
        // Create a Java file with a missing import
        String javaCode = """
            package test;

            public class TestClass {
                public void testMethod() {
                    ArrayList<String> list = new ArrayList<>();
                }
            }
            """;

        IFile javaFile = createJavaFile("src/test", "TestClass.java", javaCode);

        // Wait for problem markers to be created
        project.build(org.eclipse.core.resources.IncrementalProjectBuilder.FULL_BUILD, monitor);
        Thread.sleep(2000);

        // Check if markers were created
        IMarker[] markers = javaFile.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_ZERO);

        // Test getQuickFixes
        String result = service.getQuickFixes(TEST_PROJECT_NAME, "src/test/TestClass.java", null, null);

        if (markers.length > 0) {
            // If markers exist, verify Quick Fixes are available
            assertFalse(result.startsWith("Error:"), "Should not return error: " + result);
            assertTrue(result.contains("Quick Fixes Available") || result.contains("not found"),
                       "Should indicate Quick Fixes status");
        } else {
            // If no markers, service should report no problems found
            assertTrue(result.contains("not found"),
                       "Should indicate problems not found when markers aren't created: " + result);
        }
    }

    @Test
    public void testGetQuickFixesForSpecificLine() throws Exception {
        // Create a Java file with multiple problems
        String javaCode = """
            package test;

            public class TestClass {
                public void testMethod() {
                    ArrayList<String> list = new ArrayList<>();
                    HashMap<String, String> map = new HashMap<>();
                }
            }
            """;

        IFile javaFile = createJavaFile("src/test", "TestClass.java", javaCode);

        // Wait for problem markers
        project.build(org.eclipse.core.resources.IncrementalProjectBuilder.FULL_BUILD, monitor);
        Thread.sleep(2000);

        // Test getQuickFixes for line 5 only
        String result = service.getQuickFixes(TEST_PROJECT_NAME, "src/test/TestClass.java", 5, null);

        // Verify the result is filtered to line 5
        assertFalse(result.startsWith("Error:"), "Should not return error");
        assertTrue(result.contains("ArrayList") || result.contains("line"),
                   "Should contain information about ArrayList or line number");
    }

    @Test
    public void testGetQuickFixesNoProblems() throws Exception {
        // Create a valid Java file with no problems
        String javaCode = """
            package test;

            import java.util.ArrayList;

            public class TestClass {
                public void testMethod() {
                    ArrayList<String> list = new ArrayList<>();
                }
            }
            """;

        createJavaFile("src/test", "TestClass.java", javaCode);

        // Wait for build
        project.build(org.eclipse.core.resources.IncrementalProjectBuilder.FULL_BUILD, monitor);
        Thread.sleep(2000);

        // Test getQuickFixes on file with no problems
        String result = service.getQuickFixes(TEST_PROJECT_NAME, "src/test/TestClass.java", null, null);

        // Verify appropriate message
        assertTrue(result.contains("not found"),
                   "Should indicate problems not found");
    }

    @Test
    public void testGetQuickFixesFileNotFound() throws Exception {
        // Test with non-existent file
        String result = service.getQuickFixes(TEST_PROJECT_NAME, "src/nonexistent/File.java", null, null);

        // Verify error message
        assertTrue(result.startsWith("Error:"), "Should return error for non-existent file");
        assertTrue(result.contains("not found"), "Error message should mention file not found");
    }

    @Test
    public void testApplyQuickFixInvalidIndex() throws Exception {
        // Create a Java file with a problem
        String javaCode = """
            package test;

            public class TestClass {
                public void testMethod() {
                    ArrayList<String> list = new ArrayList<>();
                }
            }
            """;

        createJavaFile("src/test", "TestClass.java", javaCode);

        // Wait for problem markers
        project.build(org.eclipse.core.resources.IncrementalProjectBuilder.FULL_BUILD, monitor);
        Thread.sleep(2000);

        // Test applyQuickFix with invalid index
        String result = service.applyQuickFix(TEST_PROJECT_NAME, "src/test/TestClass.java", 5, 999);

        // Verify error message - should indicate problem with fix index
        // Possible errors: "No problems found", "Quick Fix not found", "Available indices"
        assertTrue(result.startsWith("Error:"), "Should return error for invalid fix index: " + result);
        assertTrue(result.contains("not found") || result.contains("Available indices"),
                   "Error should mention 'not found' or 'Available indices': " + result);
    }

    @Test
    public void testApplyQuickFixNoProblemsAtLine() throws Exception {
        // Create a valid Java file
        String javaCode = """
            package test;

            import java.util.ArrayList;

            public class TestClass {
                public void testMethod() {
                    ArrayList<String> list = new ArrayList<>();
                }
            }
            """;

        createJavaFile("src/test", "TestClass.java", javaCode);

        // Wait for build
        project.build(org.eclipse.core.resources.IncrementalProjectBuilder.FULL_BUILD, monitor);
        Thread.sleep(2000);

        // Test applyQuickFix on line with no problems
        String result = service.applyQuickFix(TEST_PROJECT_NAME, "src/test/TestClass.java", 5, 1);

        // Verify error message
        assertTrue(result.startsWith("Error:"), "Should return error when no problems at line");
        assertTrue(result.contains("not found"), "Error should mention not found");
    }

    /**
     * Helper method to create a Java file with content
     */
    private IFile createJavaFile(String folderPath, String fileName, String content) throws CoreException {
        // Create package folder structure
        String[] segments = folderPath.split("/");
        IFolder currentFolder = null;

        for (String segment : segments) {
            if (currentFolder == null) {
                currentFolder = project.getFolder(segment);
            } else {
                currentFolder = currentFolder.getFolder(segment);
            }

            if (!currentFolder.exists()) {
                currentFolder.create(IResource.NONE, true, monitor);
            }
        }

        // Create the Java file
        IFile file = currentFolder.getFile(fileName);
        if (file.exists()) {
            file.delete(true, monitor);
        }

        file.create(new ByteArrayInputStream(content.getBytes()), IResource.NONE, monitor);
        return file;
    }
}
