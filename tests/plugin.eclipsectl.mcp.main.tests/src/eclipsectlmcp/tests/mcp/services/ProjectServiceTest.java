
package eclipsectlmcp.tests.mcp.services;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.EclipseContextFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;

import eclipsectlmcp.Activator;
import eclipsectlmcp.mcp.services.ProjectService;

public class ProjectServiceTest {

    private static final String TEST_PROJECT_NAME = "ImportTestProject";
    private ProjectService service;
    private NullProgressMonitor monitor = new NullProgressMonitor();
    private Path tempProjectDir;

    @BeforeEach
    public void beforeEach() throws IOException {
        // Initialize the service with dependency injection
        BundleContext bundleContext = FrameworkUtil.getBundle(Activator.class).getBundleContext();
        IEclipseContext serviceContext = EclipseContextFactory.getServiceContext(bundleContext);
        IEclipseContext context = serviceContext.createChild();
        context.set(ILog.class, Activator.getDefault().getLog());

        service = ContextInjectionFactory.make(ProjectService.class, context);
        assertNotNull(service);

        // Create a temporary directory for test projects
        tempProjectDir = Files.createTempDirectory("eclipse-import-test");
    }

    @AfterEach
    public void afterEach() throws CoreException, IOException {
        // Clean up the test project if it exists
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IWorkspaceRoot root = workspace.getRoot();
        IProject project = root.getProject(TEST_PROJECT_NAME);

        if (project.exists()) {
            project.delete(true, true, monitor);
        }

        // Clean up temp directory
        if (tempProjectDir != null && Files.exists(tempProjectDir)) {
            deleteDirectory(tempProjectDir.toFile());
        }
    }

    @Test
    public void testListProjects() {
        String result = service.listProjects();
        assertNotNull(result);
        assertTrue(result.contains("Available Projects") || result.contains("No projects") || result.contains("Error"));
    }

    @Test
    public void testImportProject_EmptyPath() {
        String result = service.importProject("", null);
        assertTrue(result.contains("Error"));
        assertTrue(result.contains("cannot be null or empty"));
    }

    @Test
    public void testImportProject_NonExistentPath() {
        String result = service.importProject("/nonexistent/path/to/project", null);
        assertTrue(result.contains("Error"));
        assertTrue(result.contains("does not exist"));
    }

    @Test
    public void testImportProject_NotADirectory() throws IOException {
        // Create a file instead of directory
        File tempFile = File.createTempFile("test", ".txt", tempProjectDir.toFile());

        String result = service.importProject(tempFile.getAbsolutePath(), null);
        assertTrue(result.contains("Error"));
        assertTrue(result.contains("is not a directory"));
    }

    @Test
    public void testImportProject_Generic() throws IOException {
        // Create a simple directory with a text file
        File projectDir = new File(tempProjectDir.toFile(), TEST_PROJECT_NAME);
        projectDir.mkdir();

        File readmeFile = new File(projectDir, "README.md");
        Files.writeString(readmeFile.toPath(), "# Test Project");

        String result = service.importProject(projectDir.getAbsolutePath(), null);

        // Verify success
        assertFalse(result.contains("Error"));
        assertTrue(result.contains("Successfully imported"));
        assertTrue(result.contains(TEST_PROJECT_NAME));

        // Verify project exists in workspace
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IWorkspaceRoot root = workspace.getRoot();
        IProject project = root.getProject(TEST_PROJECT_NAME);
        assertTrue(project.exists());
        assertTrue(project.isOpen());
    }

    @Test
    public void testImportProject_WithCustomName() throws IOException {
        // Create a simple directory
        File projectDir = new File(tempProjectDir.toFile(), "original-name");
        projectDir.mkdir();

        String result = service.importProject(projectDir.getAbsolutePath(), TEST_PROJECT_NAME);

        // Verify success with custom name
        assertFalse(result.contains("Error"));
        assertTrue(result.contains("Successfully imported"));

        // Verify project exists with custom name
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IWorkspaceRoot root = workspace.getRoot();
        IProject project = root.getProject(TEST_PROJECT_NAME);
        assertTrue(project.exists());
        assertTrue(project.isOpen());
    }

    @Test
    public void testImportProject_WithEclipseProject() throws IOException {
        // Create a directory with .project file
        File projectDir = new File(tempProjectDir.toFile(), TEST_PROJECT_NAME);
        projectDir.mkdir();

        // Create a simple .project file
        File dotProjectFile = new File(projectDir, ".project");
        String projectContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <projectDescription>
                    <name>%s</name>
                    <comment></comment>
                    <projects></projects>
                    <buildSpec></buildSpec>
                    <natures></natures>
                </projectDescription>
                """.formatted(TEST_PROJECT_NAME);
        Files.writeString(dotProjectFile.toPath(), projectContent);

        String result = service.importProject(projectDir.getAbsolutePath(), null);

        // Verify success
        assertFalse(result.contains("Error"));
        assertTrue(result.contains("Successfully imported Eclipse project"));
        assertTrue(result.contains(TEST_PROJECT_NAME));

        // Verify project exists
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IWorkspaceRoot root = workspace.getRoot();
        IProject project = root.getProject(TEST_PROJECT_NAME);
        assertTrue(project.exists());
        assertTrue(project.isOpen());
    }

    @Test
    public void testImportProject_WithMavenProject() throws IOException {
        // Create a directory with pom.xml
        File projectDir = new File(tempProjectDir.toFile(), TEST_PROJECT_NAME);
        projectDir.mkdir();

        // Create a minimal pom.xml
        File pomFile = new File(projectDir, "pom.xml");
        String pomContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                         http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>test-project</artifactId>
                    <version>1.0.0</version>
                </project>
                """;
        Files.writeString(pomFile.toPath(), pomContent);

        String result = service.importProject(projectDir.getAbsolutePath(), null);

        // Verify success
        assertFalse(result.contains("Error"));
        assertTrue(result.contains("Successfully imported"));
        assertTrue(result.contains(TEST_PROJECT_NAME));

        // Check for Maven nature mention
        if (result.contains("Detected project type")) {
            assertTrue(result.contains("Maven"));
        }

        // Verify project exists
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IWorkspaceRoot root = workspace.getRoot();
        IProject project = root.getProject(TEST_PROJECT_NAME);
        assertTrue(project.exists());
        assertTrue(project.isOpen());
    }

    @Test
    public void testImportProject_DuplicateName() throws IOException {
        // Create a test project
        File projectDir = new File(tempProjectDir.toFile(), TEST_PROJECT_NAME);
        projectDir.mkdir();

        // Import first time
        String result1 = service.importProject(projectDir.getAbsolutePath(), null);
        assertFalse(result1.contains("Error"));

        // Try to import again with same name
        String result2 = service.importProject(projectDir.getAbsolutePath(), null);
        assertTrue(result2.contains("Error"));
        assertTrue(result2.contains("already exists"));
    }

    /**
     * Recursively deletes a directory and all its contents.
     *
     * @param directory The directory to delete
     */
    private void deleteDirectory(File directory) {
        if (directory == null || !directory.exists()) {
            return;
        }

        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }

        directory.delete();
    }
}
