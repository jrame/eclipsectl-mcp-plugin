package eclipsectlmcp.mcp.services;

import org.eclipse.e4.core.di.annotations.Creatable;

import jakarta.inject.Inject;

/**
 * Main service facade for project-related operations.
 * Delegates to specialized services for specific functionality.
 */
@Creatable
public class ProjectService {

    @Inject
    private ProjectListingService listingService;

    @Inject
    private ProjectLayoutService layoutService;

    @Inject
    private ProjectPropertiesService propertiesService;

    @Inject
    private ProjectImportService importService;

    /**
     * Lists all available projects in the workspace with their detected natures.
     *
     * @return A formatted string containing project information
     */
    public String listProjects() {
        return listingService.listProjects();
    }

    /**
     * Gets workspace information including location, project counts, Java version, and Eclipse product info.
     *
     * @return A formatted string containing workspace information
     */
    public String getWorkspaceInfo() {
        return listingService.getWorkspaceInfo();
    }

    /**
     * Gets the file and folder structure of a specified project.
     *
     * @param projectName The name of the project to analyze
     * @return A hierarchical representation of the project structure
     */
    public String getProjectLayout(String projectName) {
        return layoutService.getProjectLayout(projectName);
    }

    /**
     * Retrieves the properties and configuration of a specified project.
     *
     * @param projectName The name of the project to analyze
     * @return A formatted string containing project properties
     */
    public String getProjectProperties(String projectName) {
        return propertiesService.getProjectProperties(projectName);
    }

    /**
     * Imports a project from a directory into the Eclipse workspace.
     * Automatically detects project type (Eclipse project, Maven project, or generic).
     *
     * @param projectPath The absolute path to the project directory
     * @param projectName Optional custom name for the project (if null, uses directory name)
     * @return A status message indicating success or failure
     */
    public String importProject(String projectPath, String projectName) {
        return importService.importProject(projectPath, projectName);
    }
}
