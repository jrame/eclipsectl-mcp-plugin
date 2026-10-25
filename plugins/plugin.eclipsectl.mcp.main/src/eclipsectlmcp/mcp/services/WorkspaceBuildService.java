package eclipsectlmcp.mcp.services;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IBuildConfiguration;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.swt.widgets.Display;

import eclipsectlmcp.mcp.ToolExecution;

/** Runs Eclipse builders and captures markers under the same workspace scheduling rule. */
@Creatable
public class WorkspaceBuildService {
    private record BuildResult(String text, boolean failed) {}

    public String buildProjects(String projectNames, String buildKind, Boolean includeDependents,
            Integer timeoutSeconds, Integer maxProblems) {
        if (Display.getCurrent() != null)
            throw new IllegalStateException("Build waiting must run outside the Eclipse UI thread.");
        int timeout = timeoutSeconds == null ? 120 : timeoutSeconds;
        int limit = maxProblems == null ? 100 : maxProblems;
        if (timeout < 1 || timeout > 3600)
            throw new IllegalArgumentException("timeoutSeconds must be between 1 and 3600.");
        if (limit < 1 || limit > 500)
            throw new IllegalArgumentException("maxProblems must be between 1 and 500.");
        String kind = buildKind == null ? "incremental" : buildKind.trim().toLowerCase(java.util.Locale.ROOT);
        int mode = switch (kind) {
            case "incremental" -> IncrementalProjectBuilder.INCREMENTAL_BUILD;
            case "full" -> IncrementalProjectBuilder.FULL_BUILD;
            default -> throw new IllegalArgumentException("buildKind must be incremental or full.");
        };
        var workspace = ResourcesPlugin.getWorkspace();
        var root = workspace.getRoot();
        // Validate the explicitly requested names before scheduling any work.
        Set<IProject> requested = requestedProjects(projectNames);
        if (requested.isEmpty()) return "No open projects to build.";
        AtomicReference<BuildResult> result = new AtomicReference<>();
        Job job = new Job("EclipseCtl: " + kind + " build") {
            @Override public boolean belongsTo(Object family) {
                return family == ResourcesPlugin.FAMILY_MANUAL_BUILD;
            }

            @Override protected IStatus run(IProgressMonitor monitor) {
                try {
                    List<IProject> projects = buildScope(requested, !Boolean.FALSE.equals(includeDependents));
                    for (IProject project : projects) {
                        if (!project.isAccessible())
                            throw new IllegalArgumentException("Project is no longer open: " + project.getName());
                        project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
                    }
                    IBuildConfiguration[] configs = new IBuildConfiguration[projects.size()];
                    for (int i = 0; i < projects.size(); i++) configs[i] = projects.get(i).getActiveBuildConfig();
                    workspace.build(configs, mode, true, monitor);
                    if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                    result.set(summarize(projects, kind, limit));
                    return Status.OK_STATUS;
                } catch (OperationCanceledException e) {
                    return Status.CANCEL_STATUS;
                } catch (Exception e) {
                    return Status.error("Eclipse build failed: " + e.getMessage(), e);
                }
            }
        };
        // A preceding auto/manual build finishes before this job can run. The explicit
        // build and marker snapshot then run together, without blocking the UI thread.
        job.setRule(root);
        job.schedule();
        try {
            if (!job.join(timeout * 1000L, new NullProgressMonitor())) {
                job.cancel();
                return ToolExecution.error("Eclipse build timed out after " + timeout
                        + " seconds (including scheduling wait). Cancellation requested; compilation result is incomplete.");
            }
            IStatus status = job.getResult();
            if (status == null || !status.isOK())
                return ToolExecution.error(status == null ? "Eclipse build ended without a result."
                        : status.getSeverity() == IStatus.CANCEL ? "Eclipse build cancelled; compilation result is incomplete."
                        : status.getMessage());
            BuildResult completed = result.get();
            if (completed == null) return ToolExecution.error("Eclipse build ended without a problem report.");
            return completed.failed() ? ToolExecution.error(completed.text()) : completed.text();
        } catch (InterruptedException e) {
            job.cancel();
            Thread.currentThread().interrupt();
            return ToolExecution.error("Eclipse build wait interrupted. Cancellation requested; compilation result is incomplete.");
        }
    }

    private static Set<IProject> requestedProjects(String names) {
        var root = ResourcesPlugin.getWorkspace().getRoot();
        Set<IProject> projects = new LinkedHashSet<>();
        if (names == null || names.isBlank()) {
            for (IProject project : root.getProjects()) if (project.isOpen()) projects.add(project);
        } else {
            for (String name : names.split(",", -1)) {
                name = name.trim();
                if (name.isEmpty() || !org.eclipse.core.runtime.Path.ROOT.isValidSegment(name))
                    throw new IllegalArgumentException("projectNames must contain comma-separated project names.");
                IProject project = root.getProject(name);
                if (!project.isAccessible()) throw new IllegalArgumentException("Project not found or closed: " + name);
                projects.add(project);
            }
        }
        return projects;
    }

    public static List<IProject> buildScope(Set<IProject> requested, boolean includeDependents) throws CoreException {
        Set<IProject> affected = new LinkedHashSet<>(requested);
        if (includeDependents) {
            // Compute dependents before dependencies so unrelated users of a common
            // library are not pulled into a targeted verification.
            List<IProject> queue = new ArrayList<>(affected);
            for (int i = 0; i < queue.size(); i++) {
                for (IProject project : queue.get(i).getReferencingProjects())
                    if (project.isOpen() && affected.add(project)) queue.add(project);
            }
        }
        List<IProject> queue = new ArrayList<>(affected);
        for (int i = 0; i < queue.size(); i++) {
            for (IProject project : queue.get(i).getReferencedProjects())
                if (project.isOpen() && affected.add(project)) queue.add(project);
        }
        return Arrays.asList(ResourcesPlugin.getWorkspace()
                .computeProjectOrder(affected.toArray(IProject[]::new)).projects);
    }

    private static BuildResult summarize(List<IProject> projects, String kind, int limit) throws CoreException {
        int errors = 0;
        int warnings = 0;
        List<IMarker> problems = new ArrayList<>();
        StringBuilder counts = new StringBuilder();
        for (IProject project : projects) {
            int projectErrors = 0;
            int projectWarnings = 0;
            for (IMarker marker : project.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE)) {
                int severity = marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
                if (severity == IMarker.SEVERITY_ERROR) projectErrors++;
                if (severity == IMarker.SEVERITY_WARNING) projectWarnings++;
                if (severity >= IMarker.SEVERITY_WARNING) problems.add(marker);
            }
            counts.append("  ").append(project.getName()).append(": ").append(projectErrors)
                    .append(" errors, ").append(projectWarnings).append(" warnings\n");
            errors += projectErrors;
            warnings += projectWarnings;
        }
        problems.sort(Comparator.<IMarker>comparingInt(m -> -m.getAttribute(IMarker.SEVERITY, 0))
                .thenComparing(m -> m.getResource().getFullPath().toPortableString())
                .thenComparingInt(m -> m.getAttribute(IMarker.LINE_NUMBER, 0)));
        StringBuilder text = new StringBuilder("Eclipse ").append(kind).append(" build completed: ")
                .append(errors == 0 ? "SUCCESS" : "FAILED").append("\nBuilt projects: ").append(projects.size())
                .append("\n").append(counts).append("Total: ").append(errors).append(" errors, ")
                .append(warnings).append(" warnings\n");
        if (problems.isEmpty()) text.append("No compilation problems found.\n");
        else {
            text.append("\nProblems (showing ").append(Math.min(limit, problems.size()))
                    .append(" of ").append(problems.size()).append("):\n");
            for (IMarker marker : problems.subList(0, Math.min(limit, problems.size()))) {
                int line = marker.getAttribute(IMarker.LINE_NUMBER, -1);
                text.append(marker.getResource().getFullPath().toPortableString())
                        .append(line > 0 ? ":" + line : "")
                        .append(marker.getAttribute(IMarker.SEVERITY, 0) == IMarker.SEVERITY_ERROR ? " - ERROR: " : " - WARNING: ")
                        .append(marker.getAttribute(IMarker.MESSAGE, "Unknown problem")).append("\n");
            }
        }
        text.append("Verified saved resources with Eclipse builders; unsaved editor changes are not compiled.");
        return new BuildResult(text.toString(), errors > 0);
    }
}
