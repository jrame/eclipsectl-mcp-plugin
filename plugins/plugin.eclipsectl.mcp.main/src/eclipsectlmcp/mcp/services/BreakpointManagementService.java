package eclipsectlmcp.mcp.services;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.debug.core.IJavaLineBreakpoint;
import org.eclipse.jdt.debug.core.JDIDebugModel;

import eclipsectlmcp.tools.ResourceUtilities;

/**
 * Service for managing breakpoints in Eclipse debug sessions.
 * Handles adding, removing, enabling, disabling, and listing breakpoints.
 */
@Creatable
public class BreakpointManagementService extends DebugServiceBase {

	/**
	 * Add a line breakpoint to a Java file.
	 *
	 * @param filePath Path to the file (relative or absolute)
	 * @param lineNumber Line number (1-based)
	 * @param projectName Optional project name for path resolution
	 * @param condition Optional breakpoint condition expression
	 * @param hitCount Optional hit count (breakpoint triggers after N hits)
	 * @return Success or error message
	 */
	public String addBreakpoint(String filePath, int lineNumber, String projectName, String condition, Integer hitCount) {
		try {
			return uiSync.syncCall(() -> {
				// Resolve file
				IFile file = ResourceUtilities.findFile(projectName, filePath);
				if (file == null || !file.exists()) {
					return "Error: File not found: " + filePath;
				}

				IResource resource = file;

				// Resolve fully qualified type name from the Java file
				String typeName = null;
				ICompilationUnit cu = JavaCore.createCompilationUnitFrom(file);
				if (cu != null) {
					IType primaryType = cu.findPrimaryType();
					if (primaryType != null) {
						typeName = primaryType.getFullyQualifiedName();
					}
				}
				if (typeName == null) {
					// Fallback: derive from file path (e.g. src/com/Foo.java -> com.Foo)
					String path = file.getProjectRelativePath().toString();
					// Remove source folder prefix and .java extension
					typeName = path.replaceFirst("^.*/src/", "")
							.replace("/", ".")
							.replaceFirst("\\.java$", "");
				}

				// Create line breakpoint
				IJavaLineBreakpoint breakpoint = JDIDebugModel.createLineBreakpoint(
						resource,
						typeName,
						lineNumber,
						-1, -1, // char start/end (unknown)
						0,      // hit count
						true,   // register
						null    // attributes
				);

				// Set condition if provided
				if (condition != null && !condition.trim().isEmpty()) {
					breakpoint.setCondition(condition);
					breakpoint.setConditionEnabled(true);
				}

				// Set hit count if provided
				if (hitCount != null && hitCount > 0) {
					breakpoint.setHitCount(hitCount);
				}

				StringBuilder result = new StringBuilder();
				result.append("Breakpoint added at ").append(file.getName()).append(":").append(lineNumber);
				if (condition != null && !condition.trim().isEmpty()) {
					result.append("\n  Condition: ").append(condition);
				}
				if (hitCount != null && hitCount > 0) {
					result.append("\n  Hit count: ").append(hitCount);
				}

				return result.toString();
			});
		} catch (Exception e) {
			logger.error("Error adding breakpoint", e);
			return "Error: " + e.getMessage();
		}
	}

	/**
	 * Remove a breakpoint from a Java file.
	 *
	 * @param filePath Path to the file
	 * @param lineNumber Line number
	 * @param projectName Optional project name
	 * @return Success or error message
	 */
	public String removeBreakpoint(String filePath, int lineNumber, String projectName) {
		try {
			return uiSync.syncCall(() -> {
				// Resolve file
				IFile file = ResourceUtilities.findFile(projectName, filePath);
				if (file == null || !file.exists()) {
					return "Error: File not found: " + filePath;
				}

				IResource resource = file;

				// Find breakpoint at this location
				IBreakpoint[] breakpoints = DebugPlugin.getDefault().getBreakpointManager()
						.getBreakpoints(JDIDebugModel.getPluginIdentifier());

				IJavaLineBreakpoint targetBreakpoint = null;
				for (IBreakpoint bp : breakpoints) {
					if (bp instanceof IJavaLineBreakpoint) {
						IJavaLineBreakpoint jbp = (IJavaLineBreakpoint) bp;
						IMarker marker = jbp.getMarker();
						if (marker != null && marker.getResource().equals(resource)) {
							int bpLine = marker.getAttribute(IMarker.LINE_NUMBER, -1);
							if (bpLine == lineNumber) {
								targetBreakpoint = jbp;
								break;
							}
						}
					}
				}

				if (targetBreakpoint == null) {
					return "Error: No breakpoint found at " + file.getName() + ":" + lineNumber;
				}

				// Delete breakpoint
				targetBreakpoint.delete();

				return "Breakpoint removed from " + file.getName() + ":" + lineNumber;
			});
		} catch (Exception e) {
			logger.error("Error removing breakpoint", e);
			return "Error: " + e.getMessage();
		}
	}

	/**
	 * List all breakpoints in the workspace.
	 *
	 * @return Formatted list of breakpoints
	 */
	public String listBreakpoints() {
		try {
			return uiSync.syncCall(() -> {
				IBreakpoint[] breakpoints = DebugPlugin.getDefault().getBreakpointManager()
						.getBreakpoints(JDIDebugModel.getPluginIdentifier());

				StringBuilder sb = new StringBuilder();
				int count = 0;

				for (IBreakpoint bp : breakpoints) {
					if (bp instanceof IJavaLineBreakpoint) {
						IJavaLineBreakpoint jbp = (IJavaLineBreakpoint) bp;

						IMarker marker = jbp.getMarker();
						if (marker != null) {
							IResource resource = marker.getResource();
							int lineNumber = marker.getAttribute(IMarker.LINE_NUMBER, -1);
							String filePath = resource.getFullPath().toString();

							sb.append(filePath).append(":").append(lineNumber);
							sb.append(jbp.isEnabled() ? " [enabled]" : " [disabled]");

							// Get condition if set
							String condition = jbp.getCondition();
							if (condition != null && !condition.trim().isEmpty()) {
								sb.append("  condition: ").append(condition);
								if (!jbp.isConditionEnabled()) {
									sb.append(" (disabled)");
								}
							}

							// Get hit count if set
							int hitCount = jbp.getHitCount();
							if (hitCount > 0) {
								sb.append("  hit count: ").append(hitCount);
							}

							sb.append("\n");
							count++;
						}
					}
				}

				if (count == 0) {
					return "No breakpoints set.";
				}
				return count + " breakpoint(s):\n\n" + sb.toString();
			});
		} catch (Exception e) {
			logger.error("Error listing breakpoints", e);
			return "Error: " + e.getMessage();
		}
	}

	/**
	 * Enable a breakpoint.
	 *
	 * @param filePath Path to the file
	 * @param lineNumber Line number
	 * @param projectName Optional project name
	 * @return Success or error message
	 */
	public String enableBreakpoint(String filePath, int lineNumber, String projectName) {
		try {
			return uiSync.syncCall(() -> {
				// Resolve file
				IFile file = ResourceUtilities.findFile(projectName, filePath);
				if (file == null || !file.exists()) {
					return "Error: File not found: " + filePath;
				}

				IResource resource = file;

				// Find breakpoint at this location
				IBreakpoint[] breakpoints = DebugPlugin.getDefault().getBreakpointManager()
						.getBreakpoints(JDIDebugModel.getPluginIdentifier());

				IJavaLineBreakpoint targetBreakpoint = null;
				for (IBreakpoint bp : breakpoints) {
					if (bp instanceof IJavaLineBreakpoint) {
						IJavaLineBreakpoint jbp = (IJavaLineBreakpoint) bp;
						IMarker marker = jbp.getMarker();
						if (marker != null && marker.getResource().equals(resource)) {
							int bpLine = marker.getAttribute(IMarker.LINE_NUMBER, -1);
							if (bpLine == lineNumber) {
								targetBreakpoint = jbp;
								break;
							}
						}
					}
				}

				if (targetBreakpoint == null) {
					return "Error: No breakpoint found at " + file.getName() + ":" + lineNumber;
				}

				// Enable breakpoint
				targetBreakpoint.setEnabled(true);

				return "Breakpoint enabled at " + file.getName() + ":" + lineNumber;
			});
		} catch (Exception e) {
			logger.error("Error enabling breakpoint", e);
			return "Error: " + e.getMessage();
		}
	}

	/**
	 * Disable a breakpoint.
	 *
	 * @param filePath Path to the file
	 * @param lineNumber Line number
	 * @param projectName Optional project name
	 * @return Success or error message
	 */
	public String disableBreakpoint(String filePath, int lineNumber, String projectName) {
		try {
			return uiSync.syncCall(() -> {
				// Resolve file
				IFile file = ResourceUtilities.findFile(projectName, filePath);
				if (file == null || !file.exists()) {
					return "Error: File not found: " + filePath;
				}

				IResource resource = file;

				// Find breakpoint at this location
				IBreakpoint[] breakpoints = DebugPlugin.getDefault().getBreakpointManager()
						.getBreakpoints(JDIDebugModel.getPluginIdentifier());

				IJavaLineBreakpoint targetBreakpoint = null;
				for (IBreakpoint bp : breakpoints) {
					if (bp instanceof IJavaLineBreakpoint) {
						IJavaLineBreakpoint jbp = (IJavaLineBreakpoint) bp;
						IMarker marker = jbp.getMarker();
						if (marker != null && marker.getResource().equals(resource)) {
							int bpLine = marker.getAttribute(IMarker.LINE_NUMBER, -1);
							if (bpLine == lineNumber) {
								targetBreakpoint = jbp;
								break;
							}
						}
					}
				}

				if (targetBreakpoint == null) {
					return "Error: No breakpoint found at " + file.getName() + ":" + lineNumber;
				}

				// Disable breakpoint
				targetBreakpoint.setEnabled(false);

				return "Breakpoint disabled at " + file.getName() + ":" + lineNumber;
			});
		} catch (Exception e) {
			logger.error("Error disabling breakpoint", e);
			return "Error: " + e.getMessage();
		}
	}
}
