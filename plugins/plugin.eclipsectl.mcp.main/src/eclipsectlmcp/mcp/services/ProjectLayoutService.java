package eclipsectlmcp.mcp.services;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;

import jakarta.inject.Inject;

/**
 * Service for retrieving project layout and structure.
 */
@Creatable
public class ProjectLayoutService {

    @Inject
    ILog logger;

    /**
     * Gets the file and folder structure of a specified project.
     *
     * @param projectName The name of the project to analyze
     * @return A hierarchical representation of the project structure
     */
    public String getProjectLayout(String projectName)
    {
        if (projectName == null || projectName.isEmpty()) {
            return "Error: Project name cannot be null or empty.";
        }
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return "Project '" + projectName + "' not found.";
        }

        StringBuilder result = new StringBuilder();
        try
        {
            result.append("# Project Structure: ").append(projectName).append("\n\n");
            collectResourcesForLLM(project, 0, result); // Start with the project root
        }
        catch (CoreException e)
        {
            logger.error(e.getMessage(), e);
            return "Error retrieving project layout: " + e.getMessage();
        }

        return result.toString();
    }

    /**
     * Collects resources in a hierarchical structure for display.
     *
     * @param resource The starting resource
     * @param depth The current depth in the hierarchy
     * @param result The StringBuilder to append results to
     * @throws CoreException if an error occurs
     */
    private void collectResourcesForLLM(IResource resource, int depth, StringBuilder result) throws CoreException {
        String indent = "  ".repeat(depth);
        String prefix = depth > 0 ? indent + "- " : "- ";
        result.append(prefix).append(resource.getName());

        if (resource instanceof IContainer container)
        {
            IResource[] members = container.members();
            // Only tag empty directories so the LLM knows they have no children
            if (members.length == 0)
            {
                result.append("/");
            }
            result.append("\n");
            for (IResource member : members)
            {
                collectResourcesForLLM(member, depth + 1, result);
            }
        }
        else
        {
            result.append("\n");
        }
    }
}
