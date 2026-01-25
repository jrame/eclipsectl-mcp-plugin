package eclipsectlmcp.mcp.services;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.e4.core.di.annotations.Creatable;

import jakarta.inject.Inject;

/**
 * Service for retrieving Eclipse problems (errors, warnings, info) from the Problems view.
 * Provides access to workspace markers that represent compilation errors, warnings, and other issues.
 */
@Creatable
public class EclipseProblemService {
    
    @Inject
    private ILog logger;
    
    private static final String[] PROBLEM_MARKER_TYPES = {
        IMarker.PROBLEM,
        "org.eclipse.jdt.core.problem",
        "org.eclipse.jdt.core.buildpath_problem", 
        "org.eclipse.jdt.core.task",
        "org.eclipse.m2e.core.maven2Problem",
        "org.eclipse.ui.texteditor.spelling"
    };

    /**
     * Retrieves Eclipse problems (errors, warnings, info) from the workspace.
     *
     * @param severity Filter by severity level: "error", "warning", "info", or "all" (default: "all")
     * @param maxResults Maximum number of results to return (default: 10, max: 500)
     * @param skipUnknownLocation If true, skip problems without a valid file location or line number
     * @return Formatted string containing problem details
     */
    public String getProblems(String severity, int maxResults, boolean skipUnknownLocation) {
        try {
            // Validate and set defaults
            if (severity == null || severity.trim().isEmpty()) {
                severity = "error";
            }
            severity = severity.toLowerCase().trim();
            
            if (maxResults <= 0) {
                maxResults = 10;
            } else if (maxResults > 500) {
                maxResults = 500; // Prevent excessive results
            }
            
            // Get all workspace projects
            IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
            List<ProblemInfo> problems = new ArrayList<>();
            
            Counter counter = new Counter();
            
            for (IProject project : projects) {
                if (!project.isOpen()) {
                    continue;
                }
                
                try {
                    collectProblemsFromResource(project, problems, severity, counter, skipUnknownLocation);
                } catch (CoreException e) {
                    logger.error("Error collecting problems from project: " + project.getName(), e);
                }
            }
            
            // Sort by severity (errors first)
            problems.sort((p1, p2) -> {
                int severityCompare = Integer.compare(p2.severity, p1.severity); // Descending
                if (severityCompare != 0) return severityCompare;
                return p1.resource.compareTo(p2.resource); // Then by resource name
            });
            
            //limit results
            List<ProblemInfo> problemsDetails = problems; 
            if (problems.size() > maxResults) {
            	problemsDetails = problems.subList(0, maxResults);
            }
            
            return formatProblemsOutput(problemsDetails, severity, maxResults, counter);
            
        } catch (Exception e) {
            logger.error("Error retrieving Eclipse problems", e);
            return "Error retrieving problems: " + e.getMessage();
        }
    }
    
    /**
     * Recursively collects problems from a resource and its children.
     */
    private void collectProblemsFromResource(IResource resource, List<ProblemInfo> problems, String severityFilter, Counter counter, boolean skipUnknownLocation)
            throws CoreException {
        
        // Collect markers from this resource
        for (String markerType : PROBLEM_MARKER_TYPES) {
            try {
                IMarker[] markers = resource.findMarkers(markerType, true, IResource.DEPTH_INFINITE);
                
                for (IMarker marker : markers) {
                    int markerSeverity = marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
                    
                    switch (markerSeverity) {
	                    case IMarker.SEVERITY_ERROR -> counter.errorCount+=1 ;
	                    case IMarker.SEVERITY_WARNING ->  counter.warningCount+=1  ;
	                    case IMarker.SEVERITY_INFO ->  counter.infoCount+=1   ;
                    }
                    
                    // Filter by severity if specified
                    if (!shouldIncludeProblem(severityFilter, markerSeverity)) {
                        continue;
                    }
                    
                    ProblemInfo problem = createProblemInfo(marker);
                    if (problem != null) {
                        // Skip problems without valid location if requested
                        if (skipUnknownLocation && problem.lineNumber < 0) {
                            continue;
                        }
                        problems.add(problem);
                    }
                }
            } catch (CoreException e) {
                // Continue with other marker types if one fails
                logger.warn("Failed to collect markers of type " + markerType + " from " + resource.getName(), e);
            }
        }
    }
    
    /**
     * Gets all markers (errors, warnings, infos, tasks) for a specific file.
     *
     * @param projectName Optional project name (used to locate the file)
     * @param filePath Path to the file relative to the project root
     * @return Formatted string with all markers for the file
     */
    public String getMarkers(String projectName, String filePath) {
        try {
            IResource file = findFile(projectName, filePath);
            if (file == null) {
                return "File not found: " + filePath;
            }

            IMarker[] markers = file.findMarkers(null, true, IResource.DEPTH_ZERO);
            if (markers.length == 0) {
                return "No markers found for: " + filePath;
            }

            StringBuilder result = new StringBuilder();
            result.append("Markers for: ").append(file.getFullPath()).append("\n");
            result.append("Total: ").append(markers.length).append("\n\n");
            result.append("severity|line|message|type\n");

            for (IMarker marker : markers) {
                int severity = marker.getAttribute(IMarker.SEVERITY, -1);
                String severityText = severity >= 0 ? getSeverityText(severity) : "N/A";
                int lineNumber = marker.getAttribute(IMarker.LINE_NUMBER, -1);
                String message = marker.getAttribute(IMarker.MESSAGE, "");
                String type = getMarkerTypeDescription(marker.getType());

                result.append(severityText).append("|");
                result.append(lineNumber >= 0 ? lineNumber : "N/A").append("|");
                result.append(message).append("|");
                result.append(type).append("\n");
            }

            return result.toString();
        } catch (Exception e) {
            logger.error("Error getting markers", e);
            return "Error getting markers: " + e.getMessage();
        }
    }

    private IResource findFile(String projectName, String filePath) {
        IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
        for (IProject project : projects) {
            if (!project.isOpen()) continue;
            if (projectName != null && !projectName.isEmpty() && !project.getName().equals(projectName)) continue;

            IResource file = project.findMember(filePath);
            if (file != null && file.exists()) {
                return file;
            }
        }
        return null;
    }

    static class Counter{
    	int errorCount=0;
    	int warningCount=0;
    	int infoCount=0;
    }
    
    
    /**
     * Determines if a problem should be included based on severity filter.
     */
    private boolean shouldIncludeProblem(String severityFilter, int markerSeverity) {
        if ("all".equals(severityFilter)) {
            return true;
        }
        
        return switch (severityFilter) {
            case "error" -> markerSeverity == IMarker.SEVERITY_ERROR;
            case "warning" -> markerSeverity == IMarker.SEVERITY_WARNING;
            case "info" -> markerSeverity == IMarker.SEVERITY_INFO;
            default -> true;
        };
    }
    
    /**
     * Creates a ProblemInfo object from an IMarker.
     */
    private ProblemInfo createProblemInfo(IMarker marker) {
        try {
            ProblemInfo problem = new ProblemInfo();
            problem.severity = marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
            problem.message = marker.getAttribute(IMarker.MESSAGE, "");
            problem.resource = marker.getResource().getFullPath().toString();
            IPath location = marker.getResource().getLocation();
            if (location != null) {
            	problem.location = location.toString();
            }
            problem.lineNumber = marker.getAttribute(IMarker.LINE_NUMBER, -1);
            problem.charStart = marker.getAttribute(IMarker.CHAR_START, -1);
            problem.charEnd = marker.getAttribute(IMarker.CHAR_END, -1);
            problem.markerType = marker.getType();
            
            return problem;
        } catch (CoreException e) {
            logger.warn("Failed to create problem info from marker", e);
            return null;
        }
    }
    
    /**
     * Formats the problems output as a readable string.
     * @param counter 
     */
    private String formatProblemsOutput(List<ProblemInfo> problems, String severityFilter, int maxResults, Counter counter) {
        StringBuilder result = new StringBuilder();
                
        result.append(counter.errorCount+" errors, "+counter.warningCount+" warnings, "+counter.infoCount+" others\n");
                
        if(!problems.isEmpty()) {
            result.append("severity|description|location|type\n");//path,
        
	        for (ProblemInfo problem : problems) {
	            String severityText = getSeverityText(problem.severity);
	            String type = getMarkerTypeDescription(problem.markerType);
	            
	            result.append(severityText+"|"+problem.message+"| "+problem.resource);
	            result.append(":"+problem.lineNumber);
	            result.append(" |"+type+"\n");
	            
	        }
        }
        
        return result.toString();
    }
        
    /**
     * Gets the text description for problem severity.
     */
    private String getSeverityText(int severity) {
        return switch (severity) {
            case IMarker.SEVERITY_ERROR -> "ERROR";
            case IMarker.SEVERITY_WARNING -> "WARNING";
            case IMarker.SEVERITY_INFO -> "INFO";
            default -> "UNKNOWN";
        };
    }
    
    /**
     * Gets a human-readable description for marker types.
     */
    private String getMarkerTypeDescription(String markerType) {
        return switch (markerType) {
            case "org.eclipse.jdt.core.problem" -> "Java Compilation Problem";
            case "org.eclipse.jdt.core.buildpath_problem" -> "Java Build Path Problem";
            case "org.eclipse.jdt.core.task" -> "Java Task";
            case "org.eclipse.m2e.core.maven2Problem" -> "Maven Problem";
            case "org.eclipse.ui.texteditor.spelling" -> "Spelling Problem";
            default -> markerType;
        };
    }
    
    /**
     * Internal class to represent problem information.
     */
    private static class ProblemInfo {
		int severity;
        String message;
        String resource;
        String location;
        int lineNumber;
        int charStart;
        int charEnd;
        String markerType;
    }
}
