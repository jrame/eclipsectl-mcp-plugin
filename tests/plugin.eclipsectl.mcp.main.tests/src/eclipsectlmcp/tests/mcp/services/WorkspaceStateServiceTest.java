package eclipsectlmcp.tests.mcp.services;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.junit.jupiter.api.Test;

import eclipsectlmcp.mcp.services.WorkspaceStateService;

public class WorkspaceStateServiceTest {
    @Test
    public void delayedAutoAndManualBuildsRemainPending() {
        for (Object family : new Object[] {
                ResourcesPlugin.FAMILY_AUTO_BUILD, ResourcesPlugin.FAMILY_MANUAL_BUILD }) {
            Job build = new Job("workspace-state-test") {
                @Override
                public boolean belongsTo(Object candidate) {
                    return candidate == family;
                }

                @Override
                protected IStatus run(IProgressMonitor monitor) {
                    return Status.OK_STATUS;
                }
            };
            try {
                build.schedule(60_000);
                String state = new WorkspaceStateService().getWorkspaceState();
                assertTrue(state.startsWith("Workspace status: busy\n"), state);
                assertTrue(state.contains("\nBuild status: busy\n"), state);
            } finally {
                build.cancel();
            }
        }
    }
}
