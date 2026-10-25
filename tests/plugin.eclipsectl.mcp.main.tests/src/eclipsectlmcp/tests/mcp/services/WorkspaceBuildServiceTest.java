package eclipsectlmcp.tests.mcp.services;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.*;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.*;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import eclipsectlmcp.mcp.ToolExecution;
import eclipsectlmcp.mcp.services.WorkspaceBuildService;

/** Real Eclipse builds, dependency traversal, and bounded waiting in an isolated PDE workspace. */
public class WorkspaceBuildServiceTest {
    private final List<IProject> projects = new ArrayList<>();
    private final NullProgressMonitor monitor = new NullProgressMonitor();
    private final WorkspaceBuildService service = new WorkspaceBuildService();

    private ICompilationUnit source(String code) throws Exception {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject("Build_" + UUID.randomUUID().toString().replace("-", ""));
        project.create(monitor);
        projects.add(project);
        project.open(monitor);
        var desc = project.getDescription();
        desc.setNatureIds(new String[] {JavaCore.NATURE_ID});
        project.setDescription(desc, monitor);
        var javaProject = JavaCore.create(project);
        var options = new HashMap<String, String>();
        JavaCore.setComplianceOptions(JavaCore.VERSION_17, options);
        javaProject.setOptions(options);
        var src = project.getFolder("src");
        src.create(true, true, monitor);
        javaProject.setRawClasspath(new IClasspathEntry[] {
            JavaCore.newSourceEntry(src.getFullPath()),
            JavaCore.newContainerEntry(new Path(JavaRuntime.JRE_CONTAINER))
        }, project.getFullPath().append("bin"), monitor);
        return javaProject.getPackageFragmentRoot(src).createPackageFragment("sample", true, monitor)
                .createCompilationUnit("Example.java", code, true, monitor);
    }

    @AfterEach void cleanup() throws Exception {
        for (IProject project : projects.reversed()) if (project.exists()) project.delete(true, true, monitor);
    }

    private static void references(IProject from, IProject to) throws Exception {
        var desc = from.getDescription();
        desc.setReferencedProjects(new IProject[] {to});
        from.setDescription(desc, new NullProgressMonitor());
    }

    @Test void buildsSavedJavaAndReportsCompilerErrorsAsToolFailures() throws Exception {
        var unit = source("package sample; public class Example { public int value() { return 1; } }");
        String name = unit.getJavaProject().getElementName();
        var ok = ToolExecution.capture(() -> service.buildProjects(name, "full", false, 60, 10));
        assertFalse(ok.isError());
        assertTrue(ok.value().toString().contains("completed: SUCCESS"), ok.value().toString());
        unit.getBuffer().setContents("package sample; public class Example { MissingType a; AnotherMissingType b; }");
        unit.save(monitor, true);
        var failed = ToolExecution.capture(() -> service.buildProjects(name, null, false, 60, 1));
        assertTrue(failed.isError());
        String text = failed.value().toString();
        assertTrue(text.contains("completed: FAILED"), text);
        assertTrue(text.contains("2 errors"), text);
        assertTrue(text.contains("Problems (showing 1 of 2)"), text);
        assertTrue(text.contains("/src/sample/Example.java:1 - ERROR:"), text);
    }

    @Test void includesTransitiveDependentsAndDependenciesOnlyWhenAppropriate() throws Exception {
        var library = source("package sample; public class Example {}").getJavaProject().getProject();
        var target = source("package sample; public class Example {}").getJavaProject().getProject();
        var client = source("package sample; public class Example {}").getJavaProject().getProject();
        var leaf = source("package sample; public class Example {}").getJavaProject().getProject();
        var unrelated = source("package sample; public class Example {}").getJavaProject().getProject();
        references(target, library);
        references(client, target);
        references(leaf, client);
        references(unrelated, library);
        assertEquals(Set.of(library, target), Set.copyOf(WorkspaceBuildService.buildScope(Set.of(target), false)));
        assertEquals(Set.of(library, target, client, leaf), Set.copyOf(WorkspaceBuildService.buildScope(Set.of(target), true)));
        String text = service.buildProjects(target.getName() + ", " + client.getName(), "full", true, 60, 20);
        assertTrue(text.contains("Built projects: 4"), text);
        assertFalse(text.contains(unrelated.getName()), text);
    }

    @Test void timeoutIncludesSchedulingAndDoesNotCancelOtherBuilds() throws Exception {
        IProject project = source("package sample; public class Example {}").getJavaProject().getProject();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Job blocker = new Job("Independent build holding the workspace rule") {
            @Override protected IStatus run(IProgressMonitor progress) {
                started.countDown();
                try {
                    return release.await(20, TimeUnit.SECONDS) ? Status.OK_STATUS : Status.CANCEL_STATUS;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Status.CANCEL_STATUS;
                }
            }
        };
        blocker.setRule(ResourcesPlugin.getWorkspace().getRoot());
        blocker.schedule();
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS));
            long start = System.nanoTime();
            var result = ToolExecution.capture(() -> service.buildProjects(project.getName(), null, false, 1, 10));
            assertTrue(result.isError());
            assertTrue(result.value().toString().contains("timed out"), result.value().toString());
            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 10);
            assertEquals(Job.RUNNING, blocker.getState());
        } finally {
            release.countDown();
            blocker.join();
        }
    }

    @Test void rejectsUnknownClosedAndInvalidRequestsAndUIThreadWaiting() throws Exception {
        var project = source("package sample; public class Example {}").getJavaProject().getProject();
        assertThrows(IllegalArgumentException.class, () -> service.buildProjects("missing_" + UUID.randomUUID(), null, null, 1, 1));
        project.close(monitor);
        assertThrows(IllegalArgumentException.class, () -> service.buildProjects(project.getName(), null, null, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> service.buildProjects(null, "clean", null, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> service.buildProjects(null, null, null, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> service.buildProjects(null, null, null, 1, 0));
        AtomicReference<Throwable> error = new AtomicReference<>();
        PlatformUI.getWorkbench().getDisplay().syncExec(() -> {
            try { service.buildProjects(null, null, null, 1, 1); }
            catch (Throwable e) { error.set(e); }
        });
        assertInstanceOf(IllegalStateException.class, error.get());
    }
}
