package eclipsectlmcp.mcp.servers;

import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.eclipse.e4.core.di.annotations.Creatable;

import eclipsectlmcp.mcp.annotations.McpServer;
import eclipsectlmcp.mcp.annotations.Tool;
import eclipsectlmcp.mcp.annotations.ToolParam;
import eclipsectlmcp.mcp.services.BreakpointManagementService;
import eclipsectlmcp.mcp.services.DebugSessionControlService;
import eclipsectlmcp.mcp.services.DebugInspectionService;
import eclipsectlmcp.mcp.services.DebugContextService;
import eclipsectlmcp.mcp.services.LaunchConfigurationService;

/**
 * MCP server providing Eclipse debugging capabilities.
 * Exposes tools for breakpoint management, launch configuration management,
 * debug session control, and runtime inspection.
 */
@Creatable
@McpServer(
	name = "eclipse-debug",
	version = "1.0.0",
	description = "Eclipse debugging tools for breakpoint management, debug session control, and runtime inspection"
)
public class EclipseDebugMcpServer {

	@Inject
	private BreakpointManagementService breakpointService;

	@Inject
	private DebugSessionControlService sessionControlService;

	@Inject
	private DebugInspectionService inspectionService;

	@Inject
	private DebugContextService contextService;

	@Inject
	private LaunchConfigurationService launchConfigurationService;

	// ========== Breakpoint Management ==========

	@Tool(
		name = "addBreakpoint",
		description = "Add a line breakpoint to a Java file with optional condition and hit count"
	)
	public String addBreakpoint(
			@ToolParam(name = "filePath", description = "Path to the file (relative or absolute)", required = true) String filePath,
			@ToolParam(name = "lineNumber", description = "Line number (1-based)", required = true) int lineNumber,
			@ToolParam(name = "projectName", description = "Optional project name for path resolution") String projectName,
			@ToolParam(name = "condition", description = "Optional breakpoint condition expression (e.g., 'userId == null')") String condition,
			@ToolParam(name = "hitCount", description = "Optional hit count (breakpoint triggers after N hits)") Integer hitCount) {
		return breakpointService.addBreakpoint(filePath, lineNumber, projectName, condition, hitCount);
	}

	@Tool(
		name = "removeBreakpoint",
		description = "Remove a breakpoint from a Java file"
	)
	public String removeBreakpoint(
			@ToolParam(name = "filePath", description = "Path to the file", required = true) String filePath,
			@ToolParam(name = "lineNumber", description = "Line number", required = true) int lineNumber,
			@ToolParam(name = "projectName", description = "Optional project name") String projectName) {
		return breakpointService.removeBreakpoint(filePath, lineNumber, projectName);
	}

	@Tool(
		name = "listBreakpoints",
		description = "List all breakpoints in the workspace with their status, conditions, and locations"
	)
	public String listBreakpoints() {
		return breakpointService.listBreakpoints();
	}

	@Tool(
		name = "enableBreakpoint",
		description = "Enable a previously disabled breakpoint"
	)
	public String enableBreakpoint(
			@ToolParam(name = "filePath", description = "Path to the file", required = true) String filePath,
			@ToolParam(name = "lineNumber", description = "Line number", required = true) int lineNumber,
			@ToolParam(name = "projectName", description = "Optional project name") String projectName) {
		return breakpointService.enableBreakpoint(filePath, lineNumber, projectName);
	}

	@Tool(
		name = "disableBreakpoint",
		description = "Disable a breakpoint without removing it (keeps condition and hit count)"
	)
	public String disableBreakpoint(
			@ToolParam(name = "filePath", description = "Path to the file", required = true) String filePath,
			@ToolParam(name = "lineNumber", description = "Line number", required = true) int lineNumber,
			@ToolParam(name = "projectName", description = "Optional project name") String projectName) {
		return breakpointService.disableBreakpoint(filePath, lineNumber, projectName);
	}

	// ========== Thread Management ==========

	@Tool(
		name = "listThreads",
		description = "List all threads in the debug session with their state (running/suspended/stepping), current location if suspended, and which thread is focused"
	)
	public String listThreads(
			@ToolParam(name = "sessionName", description = "Optional debug session name (uses focused or first active session if omitted)") String sessionName) {
		return contextService.listThreads(sessionName);
	}

	@Tool(
		name = "selectThread",
		description = "Focus a thread by name for subsequent debug commands (step, getVariables, etc.). Pass empty threadName to reset to auto-select mode."
	)
	public String selectThread(
			@ToolParam(name = "threadName", description = "Name of the thread to focus (e.g., 'main'). Empty to reset focus.", required = true) String threadName,
			@ToolParam(name = "sessionName", description = "Optional debug session name") String sessionName) {
		return contextService.selectThread(threadName, sessionName);
	}

	@Tool(
		name = "listProcesses",
		description = "List all active processes (run, debug, coverage modes) with their status and configuration names"
	)
	public String listProcesses() {
		return contextService.listProcesses();
	}

	// ========== Debug Session Control ==========

	@Tool(
		name = "runDebug",
		description = "Launch a debug configuration by name. Async by default with file logging. Use listDebugConfigurations to see available configurations."
	)
	public String runDebug(
			@ToolParam(name = "configName", description = "Name of the debug configuration", required = true) String configName,
			@ToolParam(name = "waitForCompletion", description = "If true, waits for the process to terminate (max 120s). Default: false", required = false) Boolean waitForCompletion) {
		return sessionControlService.runDebug(configName, waitForCompletion != null && waitForCompletion);
	}

	@Tool(
		name = "listRunConfigurations",
		description = "List all available run configurations (launch configurations) in the workspace, including debug, run, and coverage modes"
	)
	public String listRunConfigurations() {
		return sessionControlService.listRunConfigurations();
	}

	@Tool(
		name = "createJavaLaunchConfiguration",
		description = "Create a local Java Application launch configuration. Resolves the project automatically when the runnable main class is unique in the workspace. Existing names are rejected by default."
	)
	public String createJavaLaunchConfiguration(
			@ToolParam(name = "mainClass", description = "Runnable Java main class. Prefer a fully qualified name such as 'com.example.Application'.", required = true) String mainClass,
			@ToolParam(name = "configName", description = "Configuration name. Defaults to the main class simple name.", required = false) String configName,
			@ToolParam(name = "projectName", description = "Optional Java project name. Inferred when the main class has exactly one workspace match.", required = false) String projectName,
			@ToolParam(name = "programArguments", description = "Optional program arguments exactly as they should appear on the command line.", required = false) String programArguments,
			@ToolParam(name = "vmArguments", description = "Optional JVM arguments exactly as they should appear on the command line.", required = false) String vmArguments,
			@ToolParam(name = "workingDirectory", description = "Optional absolute or workspace-relative working directory. Defaults to the project directory.", required = false) String workingDirectory,
			@ToolParam(name = "environmentVariables", description = "Optional object mapping environment variable names to string values.", required = false, type = "object") Map<String, String> environmentVariables,
			@ToolParam(name = "appendSystemEnvironment", description = "Whether configured variables are appended to the native environment. Default: true.", required = false, type = "boolean") Boolean appendSystemEnvironment,
			@ToolParam(name = "nameConflict", description = "Name collision policy: 'error' (default) or 'generate' to let Eclipse create a unique suffixed name.", required = false) String nameConflict) {
		return launchConfigurationService.createJavaLaunchConfiguration(mainClass, configName, projectName,
				programArguments, vmArguments, workingDirectory, environmentVariables,
				appendSystemEnvironment, nameConflict);
	}

	@Tool(
		name = "getLaunchConfiguration",
		description = "Show the type, modes, Java launch attributes, working directory, and environment of a launch configuration. Environment values are hidden by default."
	)
	public String getLaunchConfiguration(
			@ToolParam(name = "configName", description = "Exact launch configuration name", required = true) String configName,
			@ToolParam(name = "includeEnvironmentValues", description = "If true, includes environment values, which may expose secrets. Default: false.", required = false, type = "boolean") Boolean includeEnvironmentValues) {
		return launchConfigurationService.getLaunchConfiguration(configName, includeEnvironmentValues);
	}

	@Tool(
		name = "updateLaunchEnvironment",
		description = "Update environment variables for an existing launch configuration. Supports merging, full replacement, explicit removal, and native environment inheritance."
	)
	public String updateLaunchEnvironment(
			@ToolParam(name = "configName", description = "Exact launch configuration name", required = true) String configName,
			@ToolParam(name = "variables", description = "Optional object mapping variable names to string values to merge or use as the complete replacement.", required = false, type = "object") Map<String, String> variables,
			@ToolParam(name = "removeVariables", description = "Optional array of variable names to remove in merge mode.", required = false, type = "array") List<String> removeVariables,
			@ToolParam(name = "updateMode", description = "Environment update mode: 'merge' (default) or 'replace'.", required = false) String updateMode,
			@ToolParam(name = "appendSystemEnvironment", description = "Optional native environment inheritance setting. Omit to keep the current value.", required = false, type = "boolean") Boolean appendSystemEnvironment) {
		return launchConfigurationService.updateLaunchEnvironment(configName, variables, removeVariables,
				updateMode, appendSystemEnvironment);
	}

	@Tool(
		name = "stepInto",
		description = "Step into the next method call (F5). Requires suspended debug session."
	)
	public String stepInto() {
		return sessionControlService.stepInto();
	}

	@Tool(
		name = "stepOver",
		description = "Step over the current line (F6). Requires suspended debug session."
	)
	public String stepOver() {
		return sessionControlService.stepOver();
	}

	@Tool(
		name = "stepReturn",
		description = "Step out of the current method (F7). Requires suspended debug session."
	)
	public String stepReturn() {
		return sessionControlService.stepReturn();
	}

	@Tool(
		name = "resume",
		description = "Resume execution until next breakpoint or program termination (F8)"
	)
	public String resume() {
		return sessionControlService.resume();
	}

	@Tool(
		name = "suspend",
		description = "Suspend the current debug session to inspect state"
	)
	public String suspend() {
		return sessionControlService.suspend();
	}

	@Tool(
		name = "terminate",
		description = "Terminate the current debug session and stop the running program"
	)
	public String terminate() {
		return sessionControlService.terminate();
	}

	// ========== Runtime Inspection ==========

	@Tool(
		name = "getStackTrace",
		description = "Get the current stack trace with smart filtering to avoid overwhelming framework dumps"
	)
	public String getStackTrace(
			@ToolParam(name = "maxDepth", description = "Maximum number of frames to return (default 10)") Integer maxDepth,
			@ToolParam(name = "packageFilter", description = "Only include frames from matching packages (e.g., 'com.example')") String packageFilter,
			@ToolParam(name = "skipFramework", description = "Skip framework packages like java.*, javax.*, org.eclipse.* (default true)") Boolean skipFramework) {
		return inspectionService.getStackTrace(maxDepth, packageFilter, skipFramework);
	}

	@Tool(
		name = "getVariables",
		description = "Get local variables from a stack frame with their names, types, and values"
	)
	public String getVariables(
			@ToolParam(name = "stackFrameIndex", description = "Index of the stack frame (0 = top, default 0)") Integer stackFrameIndex) {
		return inspectionService.getVariables(stackFrameIndex);
	}

	@Tool(
		name = "evaluateExpression",
		description = "Evaluate an expression in the current debug context (limited implementation - use getVariables for simple variable inspection)"
	)
	public String evaluateExpression(
			@ToolParam(name = "expression", description = "Expression to evaluate", required = true) String expression,
			@ToolParam(name = "stackFrameIndex", description = "Index of the stack frame (0 = top, default 0)") Integer stackFrameIndex) {
		return inspectionService.evaluateExpression(expression, stackFrameIndex);
	}

	@Tool(
		name = "getDebugStatus",
		description = "Get the current debug session status including state, location, thread count, and breakpoint count"
	)
	public String getDebugStatus() {
		return contextService.getDebugStatus();
	}
}
