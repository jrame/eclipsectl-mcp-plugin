package eclipsectlmcp.mcp.services;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.core.di.annotations.Creatable;

/** Read-only job snapshots; never blocks the UI thread or starts a build. */
@Creatable
public class WorkspaceStateService {
    public String getWorkspaceState() {
        int workspaceJobs = 0;
        int buildJobs = 0;
        for (Job job : Job.getJobManager().find(null)) {
            int state = job.getState();
            boolean build = job.belongsTo(ResourcesPlugin.FAMILY_AUTO_BUILD)
                    || job.belongsTo(ResourcesPlugin.FAMILY_MANUAL_BUILD);
            // Delayed auto-builds are pending; other sleeping jobs can be periodic
            // maintenance scheduled for the entire IDE session.
            boolean pending = state == Job.RUNNING || state == Job.WAITING
                    || (build && state == Job.SLEEPING);
            if (pending) {
                workspaceJobs++;
                if (build) {
                    buildJobs++;
                }
            }
        }
        return "Workspace status: " + (workspaceJobs == 0 ? "idle" : "busy")
                + "\nBuild status: " + (buildJobs == 0 ? "idle" : "busy")
                + "\nPending workspace jobs: " + workspaceJobs
                + "\nPending build jobs: " + buildJobs;
    }
}
