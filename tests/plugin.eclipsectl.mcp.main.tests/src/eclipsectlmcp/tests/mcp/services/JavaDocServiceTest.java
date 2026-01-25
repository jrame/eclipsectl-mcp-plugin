
package eclipsectlmcp.tests.mcp.services;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
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
import eclipsectlmcp.mcp.services.JavaDocService;

public class JavaDocServiceTest {

    private static final String TEST_PROJECT_NAME = "JavaDocTestProject";
    private IProject project;
    private IJavaProject javaProject;
    private JavaDocService service;
    private NullProgressMonitor monitor = new NullProgressMonitor();

    @BeforeEach
    public void beforeEach() throws CoreException, IOException, InterruptedException {
        BundleContext bundleContext = FrameworkUtil.getBundle(JavaDocServiceTest.class).getBundleContext();
        ServiceTracker<IWorkspace, IWorkspace> workspaceTracker = new ServiceTracker<>(bundleContext, IWorkspace.class, null);

        workspaceTracker.open();
        IWorkspace workspace = workspaceTracker.getService();
        IWorkspaceRoot root = workspace.getRoot();

        // Delete the project if it exists
        project = root.getProject(TEST_PROJECT_NAME);
        if (project.exists()) {
            project.delete(true, true, monitor);
        }

        // Create a test project
        project = root.getProject(TEST_PROJECT_NAME);
        IProjectDescription desc = project.getWorkspace().newProjectDescription(project.getName());
        desc.setNatureIds(new String[] { JavaCore.NATURE_ID });
        project.create(desc, monitor);
        project.open(monitor);

        // Set up Java project
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

        // Create test classes
        createTestClasses();

        // Build
        project.build(IncrementalProjectBuilder.FULL_BUILD, monitor);
        Thread.sleep(1000);

        // Initialize service with DI context
        IEclipseContext context = EclipseContextFactory.create();
        context.set(ILog.class, Activator.getDefault().getLog());
        service = ContextInjectionFactory.make(JavaDocService.class, context);
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
    public void testGetJavaDoc_classWithJavadoc() {
        String result = service.getJavaDoc("com.example.DocumentedClass");
        System.out.println("=== getJavaDoc output ===");
        System.out.println(result);
        System.out.println("=== end ===");

        assertTrue(result.contains("This is the class-level javadoc"),
                "Should contain class javadoc. Got: " + result);
        assertTrue(result.contains("DocumentedClass"),
                "Should contain class name. Got: " + result);
    }

    @Test
    public void testGetJavaDoc_methodJavadoc() {
        String result = service.getJavaDoc("com.example.DocumentedClass");
        System.out.println("=== getJavaDoc method output ===");
        System.out.println(result);
        System.out.println("=== end ===");

        assertTrue(result.contains("This method does something"),
                "Should contain method javadoc. Got: " + result);
        assertTrue(result.contains("@param value"),
                "Should contain @param tag. Got: " + result);
    }

    @Test
    public void testGetJavaDoc_classWithoutJavadoc() {
        String result = service.getJavaDoc("com.example.UndocumentedClass");
        System.out.println("=== getJavaDoc undocumented output ===");
        System.out.println(result);
        System.out.println("=== end ===");

        // Should still return something (member.toString()), not "not available"
        assertFalse(result.contains("not available"),
                "Should find the class even without javadoc. Got: " + result);
    }

    @Test
    public void testGetJavaDoc_unknownClass() {
        String result = service.getJavaDoc("com.example.NonExistentClass");
        assertTrue(result.contains("not available"),
                "Should indicate javadoc not available. Got: " + result);
    }

    @Test
    public void testGetJavaDoc_nullInput() {
        String result = service.getJavaDoc(null);
        assertTrue(result.contains("Error"),
                "Should return error for null input. Got: " + result);
    }

    @Test
    public void testGetJavaDoc_emptyInput() {
        String result = service.getJavaDoc("");
        assertTrue(result.contains("Error"),
                "Should return error for empty input. Got: " + result);
    }

    @Test
    public void testGetSource() {
        String result = service.getSource("com.example.DocumentedClass");
        System.out.println("=== getSource output ===");
        System.out.println(result);
        System.out.println("=== end ===");

        assertTrue(result.contains("package com.example"),
                "Should contain package declaration. Got: " + result);
        assertTrue(result.contains("public class DocumentedClass"),
                "Should contain class declaration. Got: " + result);
        assertTrue(result.contains("doSomething"),
                "Should contain method name. Got: " + result);
    }

    @Test
    public void testGetSource_unknownClass() {
        String result = service.getSource("com.example.NonExistentClass");
        assertTrue(result.contains("not available"),
                "Should indicate source not available. Got: " + result);
    }

    // ===== Helper methods =====

    private void createTestClasses() throws CoreException {
        // Create package structure
        IFolder comFolder = project.getFolder("src/com");
        if (!comFolder.exists()) {
            comFolder.create(IResource.NONE, true, monitor);
        }
        IFolder exampleFolder = project.getFolder("src/com/example");
        if (!exampleFolder.exists()) {
            exampleFolder.create(IResource.NONE, true, monitor);
        }

        // Class with full javadoc
        String documentedSource =
                "package com.example;\n\n" +
                "/**\n" +
                " * This is the class-level javadoc for DocumentedClass.\n" +
                " * It provides functionality for testing.\n" +
                " * @author TestAuthor\n" +
                " */\n" +
                "public class DocumentedClass {\n\n" +
                "    /** A documented field. */\n" +
                "    private String name;\n\n" +
                "    /**\n" +
                "     * This method does something useful.\n" +
                "     * @param value the input value\n" +
                "     * @return the processed result\n" +
                "     */\n" +
                "    public String doSomething(String value) {\n" +
                "        return value;\n" +
                "    }\n" +
                "}\n";

        createFile("src/com/example/DocumentedClass.java", documentedSource);

        // Class without javadoc
        String undocumentedSource =
                "package com.example;\n\n" +
                "public class UndocumentedClass {\n" +
                "    public void noDocMethod() {\n" +
                "        System.out.println(\"no doc\");\n" +
                "    }\n" +
                "}\n";

        createFile("src/com/example/UndocumentedClass.java", undocumentedSource);
    }

    private IFile createFile(String path, String content) throws CoreException {
        IFile file = project.getFile(new Path(path));
        ByteArrayInputStream source = new ByteArrayInputStream(content.getBytes());

        if (file.exists()) {
            file.setContents(source, true, true, monitor);
        } else {
            file.create(source, true, monitor);
        }

        return file;
    }
}
