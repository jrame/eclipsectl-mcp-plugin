package eclipsectlmcp.mcp.services;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jdt.core.JavaCore;

import jakarta.inject.Inject;

/**
 * Service for importing projects into the Eclipse workspace.
 */
@Creatable
public class ProjectImportService {

    @Inject
    ILog logger;

    /**
     * Imports a project from a directory into the Eclipse workspace.
     * Automatically detects project type (Eclipse project, Maven project, or generic).
     *
     * @param projectPath The absolute path to the project directory
     * @param projectName Optional custom name for the project (if null, uses directory name)
     * @return A status message indicating success or failure
     */
    public String importProject(String projectPath, String projectName) {
        if (projectPath == null || projectPath.trim().isEmpty()) {
            return "Error: Project path cannot be null or empty.";
        }

        File projectDir = new File(projectPath);
        if (!projectDir.exists()) {
            return "Error: Directory '" + projectPath + "' does not exist.";
        }

        if (!projectDir.isDirectory()) {
            return "Error: '" + projectPath + "' is not a directory.";
        }

        try {
            IProgressMonitor monitor = new NullProgressMonitor();

            // Determine project name
            String finalProjectName = projectName;
            if (finalProjectName == null || finalProjectName.trim().isEmpty()) {
                finalProjectName = projectDir.getName();
            }

            // Check if project already exists
            IProject existingProject = ResourcesPlugin.getWorkspace().getRoot().getProject(finalProjectName);
            if (existingProject.exists()) {
                return "Error: Project '" + finalProjectName + "' already exists in workspace. " +
                       "Please choose a different name or remove the existing project first.";
            }

            // Check for .project file (Eclipse project)
            File dotProjectFile = new File(projectDir, ".project");

            if (dotProjectFile.exists()) {
                return importExistingEclipseProject(projectPath, finalProjectName, monitor);
            } else {
                return importGenericProject(projectDir, finalProjectName, monitor);
            }

        } catch (Exception e) {
            logger.error("Error importing project: " + e.getMessage(), e);
            return "Error importing project: " + e.getMessage();
        }
    }

    /**
     * Imports an existing Eclipse project (with .project file).
     *
     * @param projectPath The absolute path to the project directory
     * @param projectName The name for the project
     * @param monitor Progress monitor
     * @return Status message
     * @throws CoreException if an error occurs
     */
    private String importExistingEclipseProject(String projectPath, String projectName, IProgressMonitor monitor)
            throws CoreException {
        IPath location = new Path(projectPath);

        // Load the .project description
        IProjectDescription description = ResourcesPlugin.getWorkspace()
                .loadProjectDescription(location.append(".project"));

        // Override the project name if specified
        if (projectName != null && !projectName.equals(description.getName())) {
            description.setName(projectName);
        }

        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(description.getName());

        // Create and open the project
        project.create(description, monitor);
        project.open(monitor);

        // Refresh the project
        project.refreshLocal(IResource.DEPTH_INFINITE, monitor);

        StringBuilder result = new StringBuilder();
        result.append("Successfully imported Eclipse project '").append(project.getName()).append("'.\n");
        result.append("Location: ").append(projectPath).append("\n");

        // Detect project type
        List<String> detectedTypes = detectProjectTypes(project);
        if (!detectedTypes.isEmpty()) {
            result.append("Detected project type(s): ").append(String.join(", ", detectedTypes));
        }

        return result.toString();
    }

    /**
     * Imports a generic project (without .project file).
     * Creates a basic project structure.
     *
     * @param projectDir The project directory
     * @param projectName The name for the project
     * @param monitor Progress monitor
     * @return Status message
     * @throws CoreException if an error occurs
     */
    private String importGenericProject(File projectDir, String projectName, IProgressMonitor monitor)
            throws CoreException {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        IProjectDescription description = ResourcesPlugin.getWorkspace().newProjectDescription(projectName);

        // Set the location to the existing directory
        description.setLocation(new Path(projectDir.getAbsolutePath()));

        // Detect project type and configure natures
        List<String> natures = new ArrayList<>();

        // Check for Maven project
        File pomFile = new File(projectDir, "pom.xml");
        if (pomFile.exists()) {
            natures.add("org.eclipse.m2e.core.maven2Nature");
            // Check if it's a Java Maven project
            if (hasJavaFiles(projectDir)) {
                natures.add(JavaCore.NATURE_ID);
            }
        } else if (hasJavaFiles(projectDir)) {
            // Java project without Maven
            natures.add(JavaCore.NATURE_ID);
        }

        description.setNatureIds(natures.toArray(new String[0]));

        // Create and open the project
        project.create(description, monitor);
        project.open(monitor);

        // Refresh the project
        project.refreshLocal(IResource.DEPTH_INFINITE, monitor);

        StringBuilder result = new StringBuilder();
        result.append("Successfully imported project '").append(projectName).append("' as a generic project.\n");
        result.append("Location: ").append(projectDir.getAbsolutePath()).append("\n");

        if (!natures.isEmpty()) {
            List<String> natureNames = new ArrayList<>();
            if (natures.contains("org.eclipse.m2e.core.maven2Nature")) {
                natureNames.add("Maven");
            }
            if (natures.contains(JavaCore.NATURE_ID)) {
                natureNames.add("Java");
            }
            result.append("Detected project type(s): ").append(String.join(", ", natureNames)).append("\n");
        }

        result.append("\nNote: You may need to configure the project further (source folders, build path, etc.).");

        return result.toString();
    }

    /**
     * Detects the types/natures of a project.
     *
     * @param project The project to analyze
     * @return List of detected project type names
     */
    private List<String> detectProjectTypes(IProject project) {
        List<String> types = new ArrayList<>();

        try {
            String[] natures = project.getDescription().getNatureIds();

            for (String nature : natures) {
                switch (nature) {
                    case JavaCore.NATURE_ID:
                        types.add("Java");
                        break;
                    case "org.eclipse.m2e.core.maven2Nature":
                        types.add("Maven");
                        break;
                    case "org.eclipse.buildship.core.gradleprojectnature":
                        types.add("Gradle");
                        break;
                    case "org.python.pydev.pythonNature":
                        types.add("Python");
                        break;
                    case "org.eclipse.cdt.core.cnature":
                        types.add("C");
                        break;
                    case "org.eclipse.cdt.core.ccnature":
                        types.add("C++");
                        break;
                    case "org.eclipse.wst.jsdt.core.jsNature":
                        types.add("JavaScript");
                        break;
                    case "org.eclipse.php.core.PHPNature":
                        types.add("PHP");
                        break;
                }
            }
        } catch (CoreException e) {
            logger.error("Error detecting project types: " + e.getMessage(), e);
        }

        return types;
    }

    /**
     * Checks if a directory contains Java source files.
     *
     * @param dir The directory to check
     * @return true if Java files are found
     */
    private boolean hasJavaFiles(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return false;
        }

        File[] files = dir.listFiles();
        if (files == null) {
            return false;
        }

        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".java")) {
                return true;
            }
            if (file.isDirectory() && hasJavaFiles(file)) {
                return true;
            }
        }

        return false;
    }
}
