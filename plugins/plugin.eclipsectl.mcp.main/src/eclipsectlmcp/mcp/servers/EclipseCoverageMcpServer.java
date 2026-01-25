package eclipsectlmcp.mcp.servers;

import jakarta.inject.Inject;

import org.eclipse.e4.core.di.annotations.Creatable;

import eclipsectlmcp.mcp.annotations.McpServer;
import eclipsectlmcp.mcp.annotations.Tool;
import eclipsectlmcp.mcp.annotations.ToolParam;
import eclipsectlmcp.mcp.services.CoverageService;

/**
 * MCP server providing Eclipse code coverage capabilities via EclEmma.
 * Exposes 3 tools for running tests with coverage and analyzing coverage reports.
 *
 * Requires EclEmma (Eclipse Code Coverage) plugin to be installed.
 */
@Creatable
@McpServer(
	name = "eclipse-coverage",
	version = "1.0.0",
	description = "Eclipse code coverage tools using EclEmma for test coverage analysis"
)
public class EclipseCoverageMcpServer {

	@Inject
	private CoverageService coverageService;

	@Tool(
		name = "runCoverage",
		description = "Launch a test configuration with EclEmma code coverage analysis enabled. Async by default with file logging. Requires EclEmma plugin to be installed."
	)
	public String runCoverage(
			@ToolParam(name = "configName", description = "Name of the launch configuration to run with coverage", required = true) String configName,
			@ToolParam(name = "waitForCompletion", description = "If true, waits for the process to terminate (max 120s). Default: false", required = false) Boolean waitForCompletion) {
		return coverageService.runCoverage(configName, waitForCompletion != null && waitForCompletion);
	}

	@Tool(
		name = "getCoverageReport",
		description = "Get overall coverage report for the last coverage session. Shows which code has been executed during tests."
	)
	public String getCoverageReport() {
		return coverageService.getCoverageReport();
	}

	@Tool(
		name = "getClassCoverage",
		description = "Get detailed coverage information for a specific class, including line-by-line coverage and branch coverage. Returns annotated source with covered/uncovered lines."
	)
	public String getClassCoverage(
			@ToolParam(name = "className", description = "Class name: fully qualified (e.g., 'com.example.Calculator'), simple name (e.g., 'Calculator'), or filename (e.g., 'Calculator.java')", required = true) String className) {
		return coverageService.getClassCoverage(className);
	}
}
