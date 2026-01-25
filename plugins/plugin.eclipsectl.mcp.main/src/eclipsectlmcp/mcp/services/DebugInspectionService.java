package eclipsectlmcp.mcp.services;

import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.debug.core.model.IVariable;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jdt.debug.core.IJavaStackFrame;

/**
 * Service for runtime inspection in debug sessions.
 * Handles stack traces, variable inspection, and expression evaluation.
 */
@Creatable
public class DebugInspectionService extends DebugServiceBase {

	/**
	 * Get the current stack trace with filtering options.
	 *
	 * @param maxDepth Maximum number of frames to return (default 10)
	 * @param packageFilter Only include frames from matching packages (e.g., "com.example")
	 * @param skipFramework Skip framework packages (java.*, javax.*, org.eclipse.*)
	 * @return Markdown formatted stack trace
	 */
	public String getStackTrace(Integer maxDepth, String packageFilter, Boolean skipFramework) {
		try {
			return uiSync.syncCall(() -> {
				IDebugTarget debugTarget = getActiveDebugTarget();
				if (debugTarget == null) {
					return "Error: No active debug session. Please launch a debug configuration first.";
				}

				IThread suspendedThread = getSuspendedThread(debugTarget);
				if (suspendedThread == null) {
					return "Error: Debug session is not suspended. Set a breakpoint or suspend execution first.";
				}

				// Apply defaults
				int depth = (maxDepth != null && maxDepth > 0) ? maxDepth : 10;
				boolean skip = (skipFramework != null) ? skipFramework : true;

				IStackFrame[] frames = suspendedThread.getStackFrames();
				StringBuilder trace = new StringBuilder();
				trace.append("## Stack Trace\n\n");

				int displayedFrames = 0;
				int skippedFrames = 0;

				for (int i = 0; i < frames.length && displayedFrames < depth; i++) {
					IStackFrame frame = frames[i];

					// Get class name for filtering
					String className = null;
					if (frame instanceof IJavaStackFrame) {
						IJavaStackFrame jFrame = (IJavaStackFrame) frame;
						className = jFrame.getDeclaringTypeName();
					}

					// Apply filters
					if (shouldFilterFrame(className, packageFilter, skip)) {
						skippedFrames++;
						continue;
					}

					// Format frame
					String frameName = frame.getName();
					int lineNumber = frame.getLineNumber();
					String sourceFile = null;

					if (frame instanceof IJavaStackFrame) {
						IJavaStackFrame jFrame = (IJavaStackFrame) frame;
						sourceFile = jFrame.getSourceName();
					}

					trace.append(String.format("[%d] %s", displayedFrames, frameName));
					if (sourceFile != null && lineNumber >= 0) {
						trace.append(String.format(" (%s:%d)", sourceFile, lineNumber));
					}
					trace.append("\n");

					displayedFrames++;
				}

				if (displayedFrames == 0) {
					trace.append("*No frames match the filter criteria*\n");
				}

				if (skippedFrames > 0) {
					trace.append(String.format("\n*(%d frames filtered out)*\n", skippedFrames));
				}

				if (displayedFrames >= depth && frames.length > depth) {
					trace.append(String.format("\n*(%d more frames not shown - increase maxDepth to see more)*\n",
							frames.length - displayedFrames - skippedFrames));
				}

				return trace.toString();
			});
		} catch (Exception e) {
			logger.error("Error getting stack trace", e);
			return "Error: " + e.getMessage();
		}
	}

	/**
	 * Get variables from a stack frame.
	 *
	 * @param stackFrameIndex Index of the stack frame (0 = top)
	 * @return Formatted list of variables
	 */
	public String getVariables(Integer stackFrameIndex) {
		try {
			return uiSync.syncCall(() -> {
				IDebugTarget debugTarget = getActiveDebugTarget();
				if (debugTarget == null) {
					return "Error: No active debug session. Please launch a debug configuration first.";
				}

				IThread suspendedThread = getSuspendedThread(debugTarget);
				if (suspendedThread == null) {
					return "Error: Debug session is not suspended. Set a breakpoint or suspend execution first.";
				}

				int frameIndex = (stackFrameIndex != null && stackFrameIndex >= 0) ? stackFrameIndex : 0;

				IStackFrame[] frames = suspendedThread.getStackFrames();
				if (frameIndex >= frames.length) {
					return "Error: Stack frame index " + frameIndex + " out of bounds (max: " + (frames.length - 1) + ")";
				}

				IStackFrame frame = frames[frameIndex];
				IVariable[] variables = frame.getVariables();

				StringBuilder sb = new StringBuilder();
				sb.append("Variables at frame [").append(frameIndex).append("] ")
				  .append(frame.getName()).append(" (").append(variables.length).append("):\n\n");

				for (IVariable var : variables) {
					String valueString = var.getValue().getValueString();
					String referenceType = var.getValue().getReferenceTypeName();
					boolean hasNested = var.getValue().hasVariables();

					sb.append("  ").append(var.getName()).append(" = ").append(valueString);
					sb.append(" (").append(referenceType).append(")");
					if (hasNested) {
						sb.append(" [+]");
					}
					sb.append("\n");
				}

				return sb.toString();
			});
		} catch (Exception e) {
			logger.error("Error getting variables", e);
			return "Error: " + e.getMessage();
		}
	}

	/**
	 * Evaluate an expression in the current debug context.
	 *
	 * @param expression Expression to evaluate
	 * @param stackFrameIndex Index of the stack frame (0 = top)
	 * @return Expression result
	 */
	public String evaluateExpression(String expression, Integer stackFrameIndex) {
		try {
			return uiSync.syncCall(() -> {
				if (expression == null || expression.trim().isEmpty()) {
					return "Error: Expression cannot be empty";
				}

				IDebugTarget debugTarget = getActiveDebugTarget();
				if (debugTarget == null) {
					return "Error: No active debug session. Please launch a debug configuration first.";
				}

				IThread suspendedThread = getSuspendedThread(debugTarget);
				if (suspendedThread == null) {
					return "Error: Debug session is not suspended. Set a breakpoint or suspend execution first.";
				}

				int frameIndex = (stackFrameIndex != null && stackFrameIndex >= 0) ? stackFrameIndex : 0;

				IStackFrame[] frames = suspendedThread.getStackFrames();
				if (frameIndex >= frames.length) {
					return "Error: Stack frame index " + frameIndex + " out of bounds (max: " + (frames.length - 1) + ")";
				}

				IStackFrame frame = frames[frameIndex];

				// For Java frames, we would use ASTEvaluationEngine here
				// However, that requires more complex setup with ICompiledExpression
				// For now, we'll return a message indicating evaluation would happen here
				// Full implementation would require:
				// 1. Get IJavaStackFrame
				// 2. Create ASTEvaluationEngine
				// 3. Compile expression
				// 4. Evaluate in frame context
				// 5. Return result

				if (frame instanceof IJavaStackFrame) {
					IJavaStackFrame jFrame = (IJavaStackFrame) frame;

					// Try simple variable lookup first
					IVariable[] variables = frame.getVariables();
					for (IVariable var : variables) {
						if (var.getName().equals(expression)) {
							String value = var.getValue().getValueString();
							String type = var.getValue().getReferenceTypeName();
							return String.format("%s = %s (%s)", expression, value, type);
						}
					}

					// For complex expressions, we'd need ASTEvaluationEngine
					return "Error: Expression evaluation requires ASTEvaluationEngine (not yet fully implemented). " +
							"Try using getVariables to inspect local variables instead.";
				}

				return "Error: Expression evaluation only supported for Java debug sessions.";
			});
		} catch (Exception e) {
			logger.error("Error evaluating expression", e);
			return "Error: " + e.getMessage();
		}
	}
}
