
package eclipsectlmcp.tests.mcp.services;

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
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.EclipseContextFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.JavaRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.util.tracker.ServiceTracker;

import eclipsectlmcp.Activator;
import eclipsectlmcp.mcp.services.EclipseRunService;

public class EclipseRunServiceTest {

	private static final String TEST_PROJECT_NAME = "EclipseTestProject";
	private IProject project;
	private IJavaProject javaProject;
	private NullProgressMonitor monitor = new NullProgressMonitor();
	private EclipseRunService service;

    @BeforeEach
    public void beforeEach() throws CoreException, IOException, InterruptedException {
        // Get workspace through OSGi service tracker
        BundleContext bundleContext = FrameworkUtil.getBundle(EclipseRunService.class).getBundleContext();
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
        desc.setNatureIds(new String[] {JavaCore.NATURE_ID}); // set Java nature
        project.create(desc, monitor);
        project.open(monitor);
        
        // Set up Java project
        javaProject = JavaCore.create(project);
        
        // Create output folder (bin)
        IFolder binFolder = project.getFolder("bin");
        if (!binFolder.exists()) {
            binFolder.create(true, true, monitor);
        }
        
        // Set output location
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
        
        // Create package structure
        createPackageStructure();
        
        // Create test classes
        createTestClasses();
        
        // Force a full build of the project
        project.build(IncrementalProjectBuilder.FULL_BUILD, monitor);
        
        // Wait a moment for the build to complete and for Eclipse to process markers
        Thread.sleep(1000);
               
        
        // Initialize service with DI context
        IEclipseContext context = EclipseContextFactory.create();
        context.set(ILog.class, Activator.getDefault().getLog());
        
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
        
        service = ContextInjectionFactory.make(EclipseRunService.class, context);
    }
    
    @AfterEach
    public void afterEach() throws CoreException, InterruptedException {
        // Clean up the test project
        if (project != null && project.exists()) {
            // Refresh the project to ensure all resources are synchronized
            project.refreshLocal(IResource.DEPTH_INFINITE, monitor);

            // Wait a bit to ensure all streams are released
            Thread.sleep(500);

            // Force delete with both flags set to true
            project.delete(IResource.FORCE | IResource.ALWAYS_DELETE_PROJECT_CONTENT, monitor);
        }
    }

//	@BeforeEach
//	public void setUp() throws Exception {
//		try {
//			// Créer un projet de test
//			IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
//			IProject project = root.getProject(TEST_PROJECT_NAME);
//
//			if (!project.exists()) {
//				project.create(null);
//			}
//			project.open(null);
//
//			// Configurer comme projet Java
//			IProjectDescription desc = project.getDescription();
//			desc.setNatureIds(new String[] { JavaCore.NATURE_ID });
//			project.setDescription(desc, null);
//
//			IJavaProject javaProject = JavaCore.create(project);
//
//			// Créer structure source
//			IFolder srcFolder = project.getFolder("src");
//			if (!srcFolder.exists()) {
//				srcFolder.create(true, true, null);
//			}
//
//			// Configurer classpath
//			javaProject.setRawClasspath(new IClasspathEntry[] { JavaCore.newSourceEntry(srcFolder.getFullPath()) },
//					null);
//		} catch (Exception e) {
//			e.printStackTrace();
//		}
//	}

//	@Test
//	public void testGetMethodCallHierarchy() throws Exception {
//		// Créer une classe de test
////		createTestClass("com.example", "TestClass");
//
//		// Test getting call hierarchy for Caller class
//		String result = EclipseSearchService.openType("Cal*");
//
//		// Verify the result contains expected information
//		assertTrue(result.contains("# Call Hierarchy for Method: callerMethod"));
//	}

	@Test
	public void testRun() throws Exception {
		// When run() is called with null, it should list all configurations
		String result = service.run(null, false);

		// Verify the result contains expected information about listing configurations
		assertTrue(result.contains("Launch Configurations") || result.contains("No launch configurations found"));
	}

    
	private void createTestClass(String packageName, String className) throws Exception {
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject("TestProject");
		IJavaProject javaProject = JavaCore.create(project);

		IPackageFragmentRoot srcRoot = javaProject.getPackageFragmentRoot("src");
		IPackageFragment pkg = srcRoot.createPackageFragment(packageName, false, null);

		String source = "package " + packageName + ";\n" + "public class " + className + " {\n" + "}";

		pkg.createCompilationUnit(className + ".java", source, false, null);
	}

	private void createPackageStructure() throws CoreException {
		// Create package folders
		IFolder comFolder = project.getFolder("src/com");
		if (!comFolder.exists()) {
			comFolder.create(IResource.NONE, true, monitor);
		}

		IFolder exampleFolder = project.getFolder("src/com/example");
		if (!exampleFolder.exists()) {
			exampleFolder.create(IResource.NONE, true, monitor);
		}
	}

	private void createTestClasses() throws CoreException {
		// Create a class that calls another class's method
		String callerSource = "package com.example;\n\n" + "public class Caller {\n"
				+ "    public void callerMethod() {\n" + "        Callee callee = new Callee();\n"
				+ "        callee.calleeMethod();\n" + "    }\n" + "}\n";

		createFile("src/com/example/Caller.java", callerSource);

		// Create the class being called
		String calleeSource = "package com.example;\n\n" + "public class Callee {\n"
				+ "    public void calleeMethod() {\n" + "        System.out.println(\"Called method\");\n" + "    }\n"
				+ "}\n";

		createFile("src/com/example/Callee.java", calleeSource);
	}

	private void createClassWithErrors() throws CoreException {
		// Create a class with compilation errors (undefined variable)
		String errorSource = "package com.example;\n\n" + "public class ErrorClass {\n"
				+ "    public void methodWithError() {\n" + "        // This will cause a compilation error\n"
				+ "        System.out.println(undefinedVariable);\n" + "    }\n" + "}\n";

		createFile("src/com/example/ErrorClass.java", errorSource);
	}

	private void createClassWithWarnings() throws CoreException {
		// Create a class with warnings (unused variable)
		String warningSource = "package com.example;\n\n" + "public class WarningClass {\n"
				+ "    public void methodWithWarning() {\n" + "        // This will cause a warning (unused variable)\n"
				+ "        int unusedVariable = 10;\n" + "        // Just to avoid optimization\n"
				+ "        System.out.println(\"Warning test\");\n" + "    }\n" + "}\n";

		createFile("src/com/example/WarningClass.java", warningSource);
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
