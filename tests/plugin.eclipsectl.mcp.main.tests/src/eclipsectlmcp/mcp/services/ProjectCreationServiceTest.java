package eclipsectlmcp.mcp.services;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.debug.core.*;
import org.eclipse.jdt.core.*;
import org.eclipse.jdt.junit.JUnitCore;
import org.eclipse.jdt.junit.TestRunListener;
import org.eclipse.jdt.junit.model.ITestElement.Result;
import org.eclipse.jdt.junit.model.ITestRunSession;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;
import org.eclipse.jdt.launching.IVMInstall2;
import org.eclipse.jdt.launching.JavaRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.BeforeEach;

import eclipsectlmcp.mcp.ToolExecution;

/** Real JDT projects, builds and JUnit launch in Tycho's disposable Eclipse workbench. */
@Timeout(120)
public class ProjectCreationServiceTest {
    private Path temp;
    private final List<IProject> projects = new ArrayList<>();
    private final ProjectCreationService service = new ProjectCreationService();

    private String name() { return "Create_" + UUID.randomUUID().toString().replace("-", ""); }
    private IProject track(String name) {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
        projects.add(project);
        return project;
    }
    private ToolExecution.Result create(Path path, String name, Integer version, String junit) throws Exception {
        return ToolExecution.capture(() -> service.createJavaProject(path.toString(), name, version, junit));
    }
    private static void success(ToolExecution.Result result) {
        assertFalse(result.isError(), result.value().toString());
    }
    private static void failure(ToolExecution.Result result, String message) {
        assertTrue(result.isError(), result.value().toString());
        assertTrue(result.value().toString().contains(message), result.value().toString());
    }
    @BeforeEach void setup() throws Exception { temp = Files.createTempDirectory("eclipsectl-create-"); }

    @AfterEach void cleanup() throws Exception {
        for (IProject project : projects.reversed()) if (project.exists()) project.delete(true, true, null);
        if (temp != null) try (var paths = Files.walk(temp)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    @Test void defaultProjectHasNatureBuilderUtf8AndCompilesWithoutJUnit() throws Exception {
        String name = name();
        IProject project = track(name);
        Path path = temp.resolve(name);
        var result = create(path, null, null, null);
        success(result);
        assertTrue(project.isOpen());
        assertEquals(path.toRealPath(), project.getLocation().toFile().toPath().toRealPath());
        assertTrue(project.hasNature(JavaCore.NATURE_ID));
        assertTrue(List.of(project.getDescription().getBuildSpec()).stream().anyMatch(c -> c.getBuilderName().equals(JavaCore.BUILDER_ID)));
        assertEquals("UTF-8", project.getDefaultCharset(false));
        IJavaProject java = JavaCore.create(project);
        assertEquals(project.getFullPath().append("bin"), java.getOutputLocation());
        assertEquals(2, java.getRawClasspath().length);
        assertFalse(project.getFolder("test").exists());
        assertFalse(project.getFolder("bin-test").exists());
        assertEquals(JavaRuntime.getDefaultVMInstall(), JavaRuntime.getVMInstall(java));
        Files.writeString(path.resolve("src/Example.java"), "public class Example { public static int value() { return \"été\".length(); } }", StandardCharsets.UTF_8);
        project.refreshLocal(IResource.DEPTH_INFINITE, null);
        build(project);
        assertTrue(project.getFile("bin/Example.class").exists());
        assertTrue(new ProjectPropertiesService().getProjectProperties(name).contains(name));
        assertTrue(new ProjectLayoutService().getProjectLayout(name).contains("src"));
    }

    @Test void junit5UsesSeparateTestOutputCompilesAndRunsOneRealTest() throws Exception {
        String name = name();
        IProject project = track(name);
        String version = ((IVMInstall2) JavaRuntime.getDefaultVMInstall()).getJavaVersion();
        int major = Integer.parseInt(version.split("[.\\-+]")[0]);
        success(create(temp.resolve("custom-directory"), name, major, "5"));
        var java = JavaCore.create(project);
        assertEquals(Integer.toString(major), java.getOption(JavaCore.COMPILER_COMPLIANCE, true));
        var test = List.of(java.getRawClasspath()).stream().filter(IClasspathEntry::isTest)
                .filter(e -> e.getEntryKind() == IClasspathEntry.CPE_SOURCE).findFirst().orElseThrow();
        assertEquals(project.getFullPath().append("bin-test"), test.getOutputLocation());
        Path root = project.getLocation().toFile().toPath();
        Files.writeString(root.resolve("src/Example.java"), "public class Example { public static int value() { return 42; } }");
        Files.writeString(root.resolve("test/ExampleTest.java"), "import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*; public class ExampleTest { @Test void works() { assertEquals(42, Example.value()); } }");
        project.refreshLocal(IResource.DEPTH_INFINITE, null);
        build(project);
        assertTrue(project.getFile("bin/Example.class").exists());
        assertTrue(project.getFile("bin-test/ExampleTest.class").exists());
        assertFalse(project.getFile("bin/ExampleTest.class").exists());
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<ITestRunSession> completed = new AtomicReference<>();
        TestRunListener listener = new TestRunListener() {
            @Override public void sessionFinished(ITestRunSession session) {
                if (java.equals(session.getLaunchedProject())) { completed.set(session); done.countDown(); }
            }
        };
        JUnitCore.addTestRunListener(listener);
        ILaunch launch = null;
        ILaunchConfiguration configuration = null;
        try {
            var manager = DebugPlugin.getDefault().getLaunchManager();
            var working = manager.getLaunchConfigurationType("org.eclipse.jdt.junit.launchconfig").newInstance(null, name);
            working.setAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, name);
            working.setAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, "ExampleTest");
            working.setAttribute("org.eclipse.jdt.junit.TEST_KIND", "org.eclipse.jdt.junit.loader.junit5");
            configuration = working.doSave();
            launch = configuration.launch(ILaunchManager.RUN_MODE, null);
            assertTrue(done.await(60, TimeUnit.SECONDS), "JUnit session must finish in the real Eclipse runner");
            assertEquals(Result.OK, completed.get().getTestResult(true));
            assertEquals(1, completed.get().getChildren().length);
        } finally {
            JUnitCore.removeTestRunListener(listener);
            if (launch != null && !launch.isTerminated()) launch.terminate();
            if (configuration != null) configuration.delete();
        }
    }

    @Test void occupiedTargetsAndWorkspaceConflictsNeverOverwrite() throws Exception {
        String name = name();
        IProject project = track(name);
        Path path = temp.resolve("occupied");
        Files.createDirectory(path);
        Files.writeString(path.resolve("keep.txt"), "keep me");
        failure(create(path, name, null, null), "already exists");
        assertEquals("keep me", Files.readString(path.resolve("keep.txt")));
        assertFalse(Files.exists(path.resolve(".project")));
        Files.createDirectory(temp.resolve("empty"));
        failure(create(temp.resolve("empty"), name, null, null), "already exists");
        Path file = Files.writeString(temp.resolve("file"), "unchanged");
        failure(create(file, name, null, null), "already exists");
        assertEquals("unchanged", Files.readString(file));
        Path created = temp.resolve("created");
        success(create(created, name, null, null));
        failure(create(temp.resolve("other"), name, null, null), "already exists");
        assertFalse(Files.exists(temp.resolve("other")));
        failure(create(created.resolve("nested"), name(), null, null), "overlaps");
        assertFalse(Files.exists(created.resolve("nested")));
        assertTrue(project.isOpen());
    }

    @Test void invalidArgumentsAndUnavailableRuntimeLeaveNoFiles() throws Exception {
        Path target = temp.resolve("never-created");
        failure(create(target, "bad/name", null, null), "Invalid project name");
        failure(create(target, name(), 999, null), "Unsupported execution environment");
        failure(create(target, name(), 7, null), "at least 8");
        failure(create(target, name(), null, "4"), "Only JUnit 5");
        failure(ToolExecution.capture(() -> service.createJavaProject("relative", name(), null, null)), "absolute");
        failure(ToolExecution.capture(() -> service.createJavaProject("\\\\wsl.localhost\\Ubuntu\\home\\app", name(), null, null)), "unsupported");
        failure(create(temp.resolve("missing-parent/app"), name(), null, null), "Parent directory must already exist");
        assertFalse(Files.exists(target));
        assertFalse(Files.exists(temp.resolve("missing-parent")));
    }

    @Test void failureAfterReservationReportsPartialStateWithoutDeletingFiles() throws Exception {
        String name = name();
        track(name);
        Path target = temp.resolve("partial");
        ProjectCreationService broken = new ProjectCreationService() {
            @Override void configureJava(IProject project, RuntimeConfiguration runtime, boolean junit, IProgressMonitor monitor) throws Exception {
                Files.writeString(target.resolve("inspection.txt"), "retained");
                throw new IllegalStateException("injected configuration failure");
            }
        };
        var result = ToolExecution.capture(() -> broken.createJavaProject(target.toString(), name, null, null));
        failure(result, "Partial creation");
        assertTrue(result.value().toString().contains("injected configuration failure"));
        assertEquals("retained", Files.readString(target.resolve("inspection.txt")));
        failure(create(target, name(), null, null), "overlaps");
    }

    @Test void unavailableInstalledExecutionEnvironmentFailsBeforeReservation() throws Exception {
        var unavailable = java.util.Arrays.stream(JavaRuntime.getExecutionEnvironmentsManager().getExecutionEnvironments())
                .filter(e -> e.getId().matches("JavaSE-[0-9]+"))
                .filter(e -> Integer.parseInt(e.getId().substring(7)) >= 8)
                .filter(e -> e.getCompatibleVMs().length == 0)
                .findFirst().orElseThrow(() -> new AssertionError("Isolated JDK 21 target must have an unavailable newer EE"));
        Path target = temp.resolve("never-created");
        failure(create(target, name(), Integer.valueOf(unavailable.getId().substring(7)), null), "No compatible JRE");
        assertFalse(Files.exists(target));
    }
    @Test void createsAtDefaultWorkspaceLocation() throws Exception {
        createsAtDefaultWorkspaceLocation(false);
    }

    @Test void createsAtDefaultWorkspaceLocationWithExplicitName() throws Exception {
        createsAtDefaultWorkspaceLocation(true);
    }

    private void createsAtDefaultWorkspaceLocation(boolean explicitName) throws Exception {
        String name = name();
        IProject project = track(name);
        Path target = ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath().resolve(name);
        success(create(target, explicitName ? name : null, null, null));
        assertTrue(project.isOpen());
        assertEquals(target.toRealPath(), project.getLocation().toFile().toPath().toRealPath());
        assertNull(project.getDescription().getLocationURI());
        assertTrue(project.hasNature(JavaCore.NATURE_ID));
    }

    @Test void occupiedDefaultWorkspaceLocationIsNeverReused() throws Exception {
        String name = name();
        IProject project = track(name);
        Path target = ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath().resolve(name);
        Files.createDirectory(target);
        try {
            failure(create(target, null, null, null), "already exists");
            assertFalse(project.exists());
            try (var children = Files.list(target)) {
                assertEquals(0, children.count(), "No project files should be written to an occupied target");
            }
        } finally {
            Files.delete(target);
        }
    }

    @Test void concurrentReservationsCreateOnlyOneProject() throws Exception {
        String first = name(), second = name();
        track(first);
        track(second);
        Path target = temp.resolve("race");
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> create(target, first, null, null));
            var b = executor.submit(() -> create(target, second, null, null));
            var ar = a.get(30, TimeUnit.SECONDS);
            var br = b.get(30, TimeUnit.SECONDS);
            assertNotEquals(ar.isError(), br.isError());
            assertTrue(Files.exists(target.resolve(".project")));
            assertEquals(1, projects.stream().filter(IProject::exists).count());
        }
    }
    private static void build(IProject project) throws Exception {
        project.build(IncrementalProjectBuilder.FULL_BUILD, null);
        for (IMarker marker : project.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE))
            assertNotEquals(IMarker.SEVERITY_ERROR, marker.getAttribute(IMarker.SEVERITY, 0), marker.getAttribute(IMarker.MESSAGE, ""));
    }
}
