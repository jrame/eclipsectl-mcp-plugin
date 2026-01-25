package eclipsectlmcp.mcp.services;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Platform;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;

import jakarta.inject.Inject;

/**
 * Service for listing projects and workspace information.
 */
@Creatable
public class ProjectListingService {

    @Inject
    ILog logger;

    /**
     * Lists all available projects in the workspace with their detected natures.
     *
     * @return A formatted string containing project information
     */
    public String listProjects() {
        StringBuilder result = new StringBuilder();
        result.append("# Available Projects in Workspace\n\n");

        try {
            // Get all projects in the workspace
            IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();

            if (projects.length == 0)
            {
                return "No projects found in the workspace.";
            }

            // Define common nature IDs
            final String JAVA_NATURE = JavaCore.NATURE_ID; // "org.eclipse.jdt.core.javanature"
            final String CPP_NATURE = "org.eclipse.cdt.core.cnature";
            final String CPP_CC_NATURE = "org.eclipse.cdt.core.ccnature";
            final String PYTHON_NATURE = "org.python.pydev.pythonNature";
            final String JS_NATURE = "org.eclipse.wst.jsdt.core.jsNature";
            final String PHP_NATURE = "org.eclipse.php.core.PHPNature";
            final String WEB_NATURE = "org.eclipse.wst.common.project.facet.core.nature";
            final String MAVEN_NATURE = "org.eclipse.m2e.core.maven2Nature";
            final String GRADLE_NATURE = "org.eclipse.buildship.core.gradleprojectnature";

            // List all projects with their status and natures
            for (IProject project : projects) {
                result.append("- **").append(project.getName()).append("**");

                // Add project status (open/closed)
                result.append(" (").append(project.isOpen() ? "Open" : "Closed").append(")");

                // Only attempt to determine natures if the project is open
                if (project.isOpen()) {
                    try {
                        List<String> detectedNatures = new ArrayList<>();

                        // Check for Java nature
                        if (project.hasNature(JAVA_NATURE)) {
                            IJavaProject javaProject = JavaCore.create(project);
                            String javaVersion = javaProject.getOption(JavaCore.COMPILER_COMPLIANCE, true);
                            detectedNatures.add("Java " + javaVersion);
                        }

                        // Check for C/C++ nature
                        if (project.hasNature(CPP_NATURE)) {
                            detectedNatures.add("C");
                        }
                        if (project.hasNature(CPP_CC_NATURE)) {
                            detectedNatures.add("C++");
                        }

                        // Check for Python nature
                        if (project.hasNature(PYTHON_NATURE)) {
                            detectedNatures.add("Python");
                        }

                        // Check for JavaScript nature
                        if (project.hasNature(JS_NATURE)) {
                            detectedNatures.add("JavaScript");
                        }

                        // Check for PHP nature
                        if (project.hasNature(PHP_NATURE)) {
                            detectedNatures.add("PHP");
                        }

                        // Check for Web nature
                        if (project.hasNature(WEB_NATURE)) {
                            detectedNatures.add("Web");
                        }

                        // Check for build system natures
                        if (project.hasNature(MAVEN_NATURE)) {
                            detectedNatures.add("Maven");
                        }
                        if (project.hasNature(GRADLE_NATURE)) {
                            detectedNatures.add("Gradle");
                        }

                        // If we detected natures, add them to the output
                        if (!detectedNatures.isEmpty()) {
                            result.append(" - Project Type: ").append(String.join(", ", detectedNatures));
                        } else {
                            // Get all natures for projects we couldn't categorize
                            String[] natures = project.getDescription().getNatureIds();
                            if (natures.length > 0) {
                                result.append(" - Other Nature IDs: ").append(String.join(", ", natures));
                            } else {
                                result.append(" - Generic Project (no specific natures)");
                            }
                        }
                    } catch (CoreException e) {
                        result.append(" - Error determining project nature");
                        logger.error(e.getMessage(), e);
                    }
                }

                result.append("\n");
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return "Error retrieving projects: " + e.getMessage();
        }

        return result.toString();
    }

    /**
     * Gets workspace information including location, project counts, Java version, and Eclipse product info.
     *
     * @return A formatted string containing workspace information
     */
    public String getWorkspaceInfo() {
        StringBuilder result = new StringBuilder();
        result.append("# Workspace Information\n\n");

        try {
            // Workspace location
            IPath workspaceLocation = ResourcesPlugin.getWorkspace().getRoot().getLocation();
            result.append("## Location\n");
            result.append("- ").append(workspaceLocation.toOSString()).append("\n\n");

            // Project counts
            IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
            int openCount = 0;
            int closedCount = 0;
            for (IProject project : projects) {
                if (project.isOpen()) {
                    openCount++;
                } else {
                    closedCount++;
                }
            }
            result.append("## Projects\n");
            result.append("- Total: ").append(projects.length).append("\n");
            result.append("- Open: ").append(openCount).append("\n");
            result.append("- Closed: ").append(closedCount).append("\n\n");

            // Java version
            result.append("## Java\n");
            result.append("- Version: ").append(System.getProperty("java.version")).append("\n");
            result.append("- Vendor: ").append(System.getProperty("java.vendor")).append("\n\n");

            // Eclipse product info
            result.append("## Eclipse\n");
            var product = Platform.getProduct();
            if (product != null) {
                result.append("- Product: ").append(product.getName()).append("\n");
                var bundle = product.getDefiningBundle();
                if (bundle != null) {
                    result.append("- Version: ").append(bundle.getVersion()).append("\n");
                }
            }
            result.append("- OS: ").append(Platform.getOS()).append(" / ").append(Platform.getOSArch()).append("\n");

        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return "Error retrieving workspace info: " + e.getMessage();
        }

        return result.toString();
    }
}
