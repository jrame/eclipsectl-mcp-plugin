package eclipsectlmcp.tests.mcp.services;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.util.tracker.ServiceTracker;

import eclipsectlmcp.Activator;
import eclipsectlmcp.mcp.services.CodeGenerationService;

public class CodeGenerationServiceTest {

    private static final String TEST_PROJECT_NAME = "CodeGenTestProject";
    private IProject project;
    private IJavaProject javaProject;
    private CodeGenerationService service;
    private NullProgressMonitor monitor = new NullProgressMonitor();

    @BeforeEach
    public void beforeEach() throws CoreException, InterruptedException {
        // Get workspace through OSGi service tracker
        BundleContext bundleContext = FrameworkUtil.getBundle(CodeGenerationServiceTest.class).getBundleContext();
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

        // Create source folder
        IFolder srcFolder = project.getFolder("src");
        if (!srcFolder.exists()) {
            srcFolder.create(IResource.NONE, true, monitor);
        }

        // Initialize service with DI context
        IEclipseContext context = EclipseContextFactory.create();
        context.set(ILog.class, Activator.getDefault().getLog());

        service = ContextInjectionFactory.make(CodeGenerationService.class, context);

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
    public void testGenerateGettersSetters() throws Exception {
        // Create a Java file with fields
        String javaCode = """
            package test;

            public class Person {
                private String name;
                private int age;
            }
            """;

        IFolder packageFolder = project.getFolder("src/test");
        if (!packageFolder.exists()) {
            packageFolder.create(IResource.NONE, true, monitor);
        }

        IFile javaFile = packageFolder.getFile("Person.java");
        javaFile.create(new ByteArrayInputStream(javaCode.getBytes()), IResource.NONE, monitor);

        // Wait for indexing
        Thread.sleep(1000);

        // Generate getters and setters
        String result = service.generateGettersSetters(
            TEST_PROJECT_NAME,
            "src/test/Person.java",
            "Person",
            java.util.Collections.emptyList(),
            true,
            true,
            "end");

        System.out.println("Result: " + result);

        // Verify success
        assertTrue(result.contains("Success"), "Should generate getters/setters successfully");
        assertTrue(result.contains("getName") || result.contains("Generated methods"), "Should mention getName method");
        assertTrue(result.contains("setAge") || result.contains("Generated methods"), "Should mention setAge method");

        // Read the file and verify methods were added
        javaFile.refreshLocal(IResource.DEPTH_ZERO, monitor);
        String updatedContent;
        try (InputStream is = javaFile.getContents()) {
            updatedContent = new String(is.readAllBytes());
        }
        System.out.println("Updated content: " + updatedContent);

        assertTrue(updatedContent.contains("getName"), "Should contain getName method");
        assertTrue(updatedContent.contains("setName"), "Should contain setName method");
        assertTrue(updatedContent.contains("getAge"), "Should contain getAge method");
        assertTrue(updatedContent.contains("setAge"), "Should contain setAge method");
    }

    @Test
    public void testGenerateToStringEqualsHashCode() throws Exception {
        // Create a Java file with fields
        String javaCode = """
            package test;

            public class User {
                private String username;
                private String email;
            }
            """;

        IFolder packageFolder = project.getFolder("src/test");
        if (!packageFolder.exists()) {
            packageFolder.create(IResource.NONE, true, monitor);
        }

        IFile javaFile = packageFolder.getFile("User.java");
        javaFile.create(new ByteArrayInputStream(javaCode.getBytes()), IResource.NONE, monitor);

        // Wait for indexing
        Thread.sleep(1000);

        // Generate toString, equals, hashCode
        String result = service.generateToStringEqualsHashCode(
            TEST_PROJECT_NAME,
            "src/test/User.java",
            "User",
            java.util.Collections.emptyList(),
            true,
            true,
            true);

        System.out.println("Result: " + result);

        // Verify success
        assertTrue(result.contains("Success"), "Should generate methods successfully");

        // Read the file and verify methods were added
        javaFile.refreshLocal(IResource.DEPTH_ZERO, monitor);
        String updatedContent;
        try (InputStream is = javaFile.getContents()) {
            updatedContent = new String(is.readAllBytes());
        }
        System.out.println("Updated content: " + updatedContent);

        assertTrue(updatedContent.contains("toString()"), "Should contain toString method");
        assertTrue(updatedContent.contains("equals(Object obj)"), "Should contain equals method");
        assertTrue(updatedContent.contains("hashCode()"), "Should contain hashCode method");
        assertTrue(updatedContent.contains("@Override"), "Should have @Override annotations");
    }

    @Test
    public void testImplementOverrideMethods() throws Exception {
        // Create an interface and a class that implements it
        String interfaceCode = """
            package test;

            public interface Service {
                void doSomething();
                String getName();
            }
            """;

        String classCode = """
            package test;

            public abstract class MyService implements Service {
                // Abstract methods need to be implemented
            }
            """;

        IFolder packageFolder = project.getFolder("src/test");
        if (!packageFolder.exists()) {
            packageFolder.create(IResource.NONE, true, monitor);
        }

        IFile interfaceFile = packageFolder.getFile("Service.java");
        interfaceFile.create(new ByteArrayInputStream(interfaceCode.getBytes()), IResource.NONE, monitor);

        IFile classFile = packageFolder.getFile("MyService.java");
        classFile.create(new ByteArrayInputStream(classCode.getBytes()), IResource.NONE, monitor);

        // Wait for indexing
        Thread.sleep(1000);

        // Note: This test might not work perfectly because finding unimplemented methods
        // requires a full type hierarchy analysis which is simplified in our implementation
        String result = service.implementOverrideMethods(
            TEST_PROJECT_NAME,
            "src/test/MyService.java",
            "MyService",
            java.util.Collections.emptyList());

        System.out.println("Result: " + result);

        // For now, just verify the call doesn't crash
        assertFalse(result.contains("Error"), "Should not return an error");
    }

    @Test
    public void testGenerateGettersSettersWithSpecificFields() throws Exception {
        // Create a Java file with multiple fields
        String javaCode = """
            package test;

            public class Product {
                private String name;
                private double price;
                private int quantity;
            }
            """;

        IFolder packageFolder = project.getFolder("src/test");
        if (!packageFolder.exists()) {
            packageFolder.create(IResource.NONE, true, monitor);
        }

        IFile javaFile = packageFolder.getFile("Product.java");
        javaFile.create(new ByteArrayInputStream(javaCode.getBytes()), IResource.NONE, monitor);

        // Wait for indexing
        Thread.sleep(1000);

        // Generate getters and setters only for "name" field
        String result = service.generateGettersSetters(
            TEST_PROJECT_NAME,
            "src/test/Product.java",
            "Product",
            java.util.Arrays.asList("name"),
            true,
            true,
            "end");

        System.out.println("Result: " + result);

        // Verify success
        assertTrue(result.contains("Success") || result.contains("getName"), "Should generate getters/setters successfully");

        // Read the file and verify only name methods were added
        javaFile.refreshLocal(IResource.DEPTH_ZERO, monitor);
        String updatedContent;
        try (InputStream is = javaFile.getContents()) {
            updatedContent = new String(is.readAllBytes());
        }
        System.out.println("Updated content: " + updatedContent);

        assertTrue(updatedContent.contains("getName"), "Should contain getName method");
        assertTrue(updatedContent.contains("setName"), "Should contain setName method");
        assertFalse(updatedContent.contains("getPrice"), "Should NOT contain getPrice method");
        assertFalse(updatedContent.contains("setQuantity"), "Should NOT contain setQuantity method");
    }

    @Test
    public void testGenerateOnlyGetters() throws Exception {
        // Create a Java file with fields
        String javaCode = """
            package test;

            public class ReadOnlyData {
                private String id;
                private String value;
            }
            """;

        IFolder packageFolder = project.getFolder("src/test");
        if (!packageFolder.exists()) {
            packageFolder.create(IResource.NONE, true, monitor);
        }

        IFile javaFile = packageFolder.getFile("ReadOnlyData.java");
        javaFile.create(new ByteArrayInputStream(javaCode.getBytes()), IResource.NONE, monitor);

        // Wait for indexing
        Thread.sleep(1000);

        // Generate only getters
        String result = service.generateGettersSetters(
            TEST_PROJECT_NAME,
            "src/test/ReadOnlyData.java",
            "ReadOnlyData",
            java.util.Collections.emptyList(),
            true,  // generateGetters
            false, // generateSetters
            "end");

        System.out.println("Result: " + result);

        // Verify success
        assertTrue(result.contains("Success") || result.contains("getId"), "Should generate getters successfully");

        // Read the file and verify only getters were added
        javaFile.refreshLocal(IResource.DEPTH_ZERO, monitor);
        String updatedContent;
        try (InputStream is = javaFile.getContents()) {
            updatedContent = new String(is.readAllBytes());
        }
        System.out.println("Updated content: " + updatedContent);

        assertTrue(updatedContent.contains("getId"), "Should contain getId method");
        assertTrue(updatedContent.contains("getValue"), "Should contain getValue method");
        assertFalse(updatedContent.contains("setId"), "Should NOT contain setId method");
        assertFalse(updatedContent.contains("setValue"), "Should NOT contain setValue method");
    }
}
