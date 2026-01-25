package eclipsectlmcp.mcp.services;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.ui.IMarkerResolution;
import org.eclipse.ui.IMarkerResolution2;
import org.eclipse.ui.ide.IDE;

import eclipsectlmcp.tools.ResourceUtilities;
import jakarta.inject.Inject;

/**
 * Service for retrieving and applying Eclipse Quick Fixes (marker resolutions).
 * Provides access to Quick Fixes that can resolve compilation errors, warnings, and other issues.
 */
@Creatable
public class QuickFixService {

    @Inject
    private ILog logger;

    private static final String[] PROBLEM_MARKER_TYPES = {
        IMarker.PROBLEM,
        "org.eclipse.jdt.core.problem",
        "org.eclipse.jdt.core.buildpath_problem",
        "org.eclipse.m2e.core.maven2Problem"
    };

    /**
     * Retrieves available Quick Fixes for problems in a file.
     *
     * @param projectName The name of the project (optional if filePath is unique or starts with /projectName/)
     * @param filePath The path to the file relative to the project root or absolute path
     * @param lineNumber Optional line number to filter problems (1-based)
     * @param problemId Optional problem ID to filter specific problem
     * @return Formatted string containing available Quick Fixes
     */
    public String getQuickFixes(String projectName, String filePath, Integer lineNumber, String problemId) {
        try {
            // Find the file
            IFile file = ResourceUtilities.findFile(projectName, filePath);
            if (file == null || !file.exists()) {
                return "Error: File not found: " + filePath;
            }

            // Find markers
            List<IMarker> markers = findMarkers(file, lineNumber);
            if (markers.isEmpty()) {
                if (lineNumber != null) {
                    return "Problems not found at line " + lineNumber + " in file: " + filePath;
                }
                return "Problems not found in file: " + filePath;
            }

            // Collect Quick Fixes for all markers
            List<QuickFixInfo> quickFixes = new ArrayList<>();
            int fixIndex = 1;

            for (IMarker marker : markers) {
                // Filter by problemId if specified
                if (problemId != null && !problemId.isEmpty()) {
                    String markerId = String.valueOf(marker.getId());
                    if (!markerId.equals(problemId)) {
                        continue;
                    }
                }

                IMarkerResolution[] resolutions = IDE.getMarkerHelpRegistry().getResolutions(marker);
                if (resolutions != null && resolutions.length > 0) {
                    for (IMarkerResolution resolution : resolutions) {
                        QuickFixInfo info = new QuickFixInfo();
                        info.index = fixIndex++;
                        info.label = resolution.getLabel();
                        info.marker = marker;
                        info.resolution = resolution;

                        // Get description if available (IMarkerResolution2)
                        if (resolution instanceof IMarkerResolution2 resolution2) {
                            info.description = resolution2.getDescription();
                        }

                        // Get problem details
                        info.problemMessage = marker.getAttribute(IMarker.MESSAGE, "");
                        info.lineNumber = marker.getAttribute(IMarker.LINE_NUMBER, -1);
                        info.markerType = getMarkerTypeDescription(marker.getType());

                        quickFixes.add(info);
                    }
                }
            }

            if (quickFixes.isEmpty()) {
                return "Quick Fixes not found for problems in file: " + filePath;
            }

            return formatQuickFixList(quickFixes, filePath);

        } catch (Exception e) {
            if (logger != null) {
                logger.error("Error retrieving Quick Fixes", e);
            }
            return "Error retrieving Quick Fixes: " + e.getMessage();
        }
    }

    /**
     * Applies a specific Quick Fix to resolve a problem.
     *
     * @param projectName The name of the project (optional if filePath is unique or starts with /projectName/)
     * @param filePath The path to the file relative to the project root or absolute path
     * @param lineNumber The line number where the problem is located (1-based)
     * @param fixIndex The index of the Quick Fix to apply (1-based, from getQuickFixes output)
     * @return Result message indicating success or failure
     */
    public String applyQuickFix(String projectName, String filePath, int lineNumber, int fixIndex) {
        try {
            // Validate input
            if (fixIndex < 1) {
                return "Error: Fix index must be >= 1";
            }

            // Find the file
            IFile file = ResourceUtilities.findFile(projectName, filePath);
            if (file == null || !file.exists()) {
                return "Error: File not found: " + filePath;
            }

            // Find markers at the specified line
            List<IMarker> markers = findMarkers(file, lineNumber);
            if (markers.isEmpty()) {
                return "Error: Quick Fix not found - no problems at line " + lineNumber + " in file: " + filePath;
            }

            // Collect all resolutions and find the one at the specified index
            int currentIndex = 1;
            IMarkerResolution targetResolution = null;
            IMarker targetMarker = null;
            String fixLabel = null;

            for (IMarker marker : markers) {
                IMarkerResolution[] resolutions = IDE.getMarkerHelpRegistry().getResolutions(marker);
                if (resolutions != null && resolutions.length > 0) {
                    for (IMarkerResolution resolution : resolutions) {
                        if (currentIndex == fixIndex) {
                            targetResolution = resolution;
                            targetMarker = marker;
                            fixLabel = resolution.getLabel();
                            break;
                        }
                        currentIndex++;
                    }
                }

                if (targetResolution != null) {
                    break;
                }
            }

            if (targetResolution == null) {
                if (currentIndex == 1) {
                    return "Error: Quick Fix index " + fixIndex + " not found - no Quick Fixes available at line " + lineNumber + " in file: " + filePath;
                }
                return "Error: Quick Fix index " + fixIndex + " not found. Available indices: 1-" + (currentIndex - 1);
            }

            // Apply the Quick Fix
            String problemMessage = targetMarker.getAttribute(IMarker.MESSAGE, "");
            targetResolution.run(targetMarker);

            // Format success message
            StringBuilder result = new StringBuilder();
            result.append("Quick Fix Applied: ").append(fixLabel).append("\n");
            result.append("Status: Success\n");
            result.append("File: ").append(filePath).append(":").append(lineNumber).append("\n");
            result.append("Problem resolved: ").append(problemMessage);

            return result.toString();

        } catch (Exception e) {
            if (logger != null) {
                logger.error("Error applying Quick Fix", e);
            }
            return "Error: Quick Fix not found - " + e.getMessage();
        }
    }

    /**
     * Finds markers in a file, optionally filtering by line number.
     */
    private List<IMarker> findMarkers(IFile file, Integer lineNumber) throws CoreException {
        List<IMarker> result = new ArrayList<>();

        for (String markerType : PROBLEM_MARKER_TYPES) {
            try {
                IMarker[] markers = file.findMarkers(markerType, true, IResource.DEPTH_ZERO);

                for (IMarker marker : markers) {
                    // Filter by line number if specified
                    if (lineNumber != null) {
                        int markerLine = marker.getAttribute(IMarker.LINE_NUMBER, -1);
                        if (markerLine != lineNumber) {
                            continue;
                        }
                    }

                    result.add(marker);
                }
            } catch (CoreException e) {
                if (logger != null) {
                    logger.warn("Failed to find markers of type " + markerType + " in " + file.getName(), e);
                }
            }
        }

        return result;
    }

    /**
     * Formats the Quick Fix list as a readable string.
     */
    private String formatQuickFixList(List<QuickFixInfo> quickFixes, String filePath) {
        StringBuilder result = new StringBuilder();

        result.append("Quick Fixes Available: ").append(quickFixes.size()).append("\n\n");

        for (QuickFixInfo fix : quickFixes) {
            result.append("[").append(fix.index).append("] ").append(fix.label).append("\n");

            if (fix.description != null && !fix.description.isEmpty()) {
                result.append("    Description: ").append(fix.description).append("\n");
            }

            result.append("    Type: ").append(fix.markerType).append("\n");
            result.append("    Location: ").append(filePath).append(":").append(fix.lineNumber).append("\n");
            result.append("    Problem: ").append(fix.problemMessage).append("\n");
            result.append("\n");
        }

        return result.toString();
    }

    /**
     * Gets a human-readable description for marker types.
     */
    private String getMarkerTypeDescription(String markerType) {
        return switch (markerType) {
            case "org.eclipse.jdt.core.problem" -> "Java Compilation Problem";
            case "org.eclipse.jdt.core.buildpath_problem" -> "Java Build Path Problem";
            case "org.eclipse.m2e.core.maven2Problem" -> "Maven Problem";
            default -> markerType;
        };
    }

    /**
     * Internal class to represent Quick Fix information.
     */
    private static class QuickFixInfo {
        int index;
        String label;
        String description;
        IMarker marker;
        IMarkerResolution resolution;
        String problemMessage;
        int lineNumber;
        String markerType;
    }
}
