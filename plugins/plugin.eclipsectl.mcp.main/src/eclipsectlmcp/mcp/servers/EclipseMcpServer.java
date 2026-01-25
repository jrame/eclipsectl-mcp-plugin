package eclipsectlmcp.mcp.servers;

import java.util.Optional;

import org.eclipse.e4.core.di.annotations.Creatable;

import eclipsectlmcp.mcp.annotations.McpServer;
import eclipsectlmcp.mcp.annotations.Tool;
import eclipsectlmcp.mcp.annotations.ToolParam;
import eclipsectlmcp.mcp.services.FileUtilityService;
import eclipsectlmcp.mcp.services.EclipseProblemService;
import eclipsectlmcp.mcp.services.EclipseRunService;
import eclipsectlmcp.mcp.services.EclipseSearchService;
import eclipsectlmcp.mcp.services.EditorService;
import eclipsectlmcp.mcp.services.QuickFixService;
import jakarta.inject.Inject;


@Creatable
@McpServer(name = "eclipse")
public class EclipseMcpServer
{
    @Inject
    private FileUtilityService codeEditingService;
	
    @Inject
    private EclipseRunService eclipseRunService;
    
    @Inject
    private EclipseProblemService eclipseProblemService;

    @Inject
    private QuickFixService quickFixService;

    @Inject
    private EditorService editorService;
    
	@Tool(name="open_type", description="Use open type search of Eclipse", type="object")
	public String openType(@ToolParam(name="name", description="Type name prefix or pattern (*, ?, or camel case)", required=true) String typeName)
	{
	    return EclipseSearchService.openType(typeName);
	}
	
	@Tool(name="call_hierarchy", description="Use call hierarchy search of Eclipse, return callers and callees", type="object")
    public String callHierarchy(
            @ToolParam(name="fullyQualifiedClassName", description="The fully qualified name of the class containing the method", required=true) String fullyQualifiedClassName,
            @ToolParam(name="methodName", description="The name of the method to analyze", required=true) String methodName,
            @ToolParam(name="methodSignature", description="The signature of the method (optional, required if method is overloaded)", required=false) String methodSignature)
    {
        return EclipseSearchService.callHierarchy( fullyQualifiedClassName, methodName, methodSignature);
    }

	@Tool(name="findReferences", description="Find all references (usages) of a Java element across the workspace. This is essential for understanding the impact of changes before refactoring or modifying code. Returns locations with line numbers and code context.", type="object")
    public String findReferences(
            @ToolParam(name="elementType", description="The type of element to search for: 'type' (class/interface), 'method', 'field', 'package', 'constructor'", required=true) String elementType,
            @ToolParam(name="elementName", description="The name of the element. For types: fully qualified name (e.g., 'com.example.User'). For methods/fields: simple name (e.g., 'getName') or qualified name (e.g., 'com.example.User.getName')", required=true) String elementName,
            @ToolParam(name="projectName", description="Optional project name to limit the search scope. If not specified, searches the entire workspace.", required=false) String projectName,
            @ToolParam(name="scope", description="Search scope: 'workspace' (default) searches all projects, 'project' searches only the specified project", required=false) String scope,
            @ToolParam(name="includeSubtypes", description="For type searches, include references to subtypes. Defaults to false.", required=false) Boolean includeSubtypes)
    {
        return EclipseSearchService.findReferences(
            elementType,
            elementName,
            projectName,
            scope != null ? scope : "workspace",
            includeSubtypes != null ? includeSubtypes : false
        );
    }
	
	@Tool(name="run", description="Run an Eclipse launch configuration. Async by default with file logging.", type="object")
    public String run(
            @ToolParam(name="configuration", description="Configuration name, if empty will return all configuration names", required=false) String configuration,
            @ToolParam(name="waitForCompletion", description="If true, waits for the process to terminate (max 120s). Default: false", required=false) Boolean waitForCompletion)
    {
        return eclipseRunService.run(configuration, waitForCompletion != null && waitForCompletion);
    }
	
	@Tool(name="problems", description="get problems panel of Eclipse, error compilation", type="object")
    public String problems(
    		@ToolParam(name="severity", description="Filter by severity level: 'error' (default), 'warning', or 'all'", required=false) String severity,
            @ToolParam(name="maxResults", description="Maximum number of problems to return (default: 10)", required=false) String maxResults,
            @ToolParam(name="skipUnknownLocation", description="If true, skip problems without a valid file location or line number (default: false)", required=false) Boolean skipUnknownLocation)
    {
        return eclipseProblemService.getProblems(severity, Optional.ofNullable( maxResults ).map( Integer::parseInt ).orElse( 0 ),
                skipUnknownLocation != null && skipUnknownLocation);
    }

	@Tool(name="getQuickFixes", description="Get available Quick Fixes (marker resolutions) for problems in a file. This lists all the automatic fixes Eclipse can apply to resolve compilation errors, warnings, and other issues. Use this after identifying problems with the 'problems' tool.", type="object")
    public String getQuickFixes(
    		@ToolParam(name="projectName", description="The name of the project containing the file. Optional if file path is unique across projects or starts with /projectName/", required=false) String projectName,
            @ToolParam(name="filePath", description="The path to the file relative to the project root (e.g., 'src/com/example/Main.java') or absolute path starting with /projectName/", required=true) String filePath,
            @ToolParam(name="lineNumber", description="Optional line number to filter problems at a specific line (1-based index)", required=false) String lineNumber,
            @ToolParam(name="problemId", description="Optional problem ID to filter a specific problem", required=false) String problemId)
    {
        Integer line = lineNumber != null ? Integer.parseInt(lineNumber) : null;
        return quickFixService.getQuickFixes(projectName, filePath, line, problemId);
    }

	@Tool(name="applyQuickFix", description="Apply a specific Quick Fix to resolve a problem. Use the fix index from the 'getQuickFixes' output to identify which fix to apply. This will automatically modify the code to resolve the issue.", type="object")
    public String applyQuickFix(
    		@ToolParam(name="projectName", description="The name of the project containing the file. Optional if file path is unique across projects or starts with /projectName/", required=false) String projectName,
            @ToolParam(name="filePath", description="The path to the file relative to the project root (e.g., 'src/com/example/Main.java') or absolute path starting with /projectName/", required=true) String filePath,
            @ToolParam(name="lineNumber", description="The line number where the problem is located (1-based index)", required=true) int lineNumber,
            @ToolParam(name="fixIndex", description="The index of the Quick Fix to apply (1-based, from getQuickFixes output)", required=true) int fixIndex)
    {
        return quickFixService.applyQuickFix(projectName, filePath, lineNumber, fixIndex);
    }
	
	@Tool(name="getTypeHierarchy", description="Get the type hierarchy (superclasses, subclasses, interfaces) of a Java class or interface", type="object")
    public String getTypeHierarchy(
            @ToolParam(name="fullyQualifiedClassName", description="The fully qualified name of the class or interface (e.g., 'com.example.MyClass')", required=true) String fullyQualifiedClassName)
    {
        return EclipseSearchService.getTypeHierarchy(fullyQualifiedClassName);
    }

	@Tool(name="getImplementations", description="Find all implementations of an interface or abstract class/method", type="object")
    public String getImplementations(
            @ToolParam(name="fullyQualifiedClassName", description="The fully qualified name of the interface or abstract class", required=true) String fullyQualifiedClassName,
            @ToolParam(name="methodName", description="Optional method name to find concrete implementations of a specific method", required=false) String methodName)
    {
        return EclipseSearchService.getImplementations(fullyQualifiedClassName, methodName);
    }

	@Tool(name="getMarkers", description="Get all markers (errors, warnings, infos, tasks) for a specific file", type="object")
    public String getMarkers(
            @ToolParam(name="projectName", description="The name of the project containing the file. Optional if file path is unique.", required=false) String projectName,
            @ToolParam(name="filePath", description="The path to the file relative to the project root", required=true) String filePath)
    {
        return eclipseProblemService.getMarkers(projectName, filePath);
    }

	@Tool(name="refresh", description="refresh eclipse files or directories", type="object")
    public String refresh(
    		@ToolParam(name="path", description="list of path of file or directory separated by comma", required=true) String path)
    {
        return codeEditingService.refresh(path);
    }

	@Tool(name="refreshAndSave", description="Refresh files from disk and save them in Eclipse, triggering configured save actions (format on save, organize imports, etc.). Use this after modifying files externally to apply Eclipse formatting preferences automatically.", type="object")
    public String refreshAndSave(
    		@ToolParam(name="paths", description="Comma-separated list of file paths relative to project root", required=true) String paths)
    {
        return codeEditingService.refreshAndSave(paths);
    }

	@Tool(name="openFile", description="Open a file in the Eclipse editor, optionally navigating to a specific line.", type="object")
    public String openFile(
    		@ToolParam(name="filePath", description="File path relative to project root (e.g., 'src/Main.java') or absolute path starting with /ProjectName/", required=true) String filePath,
    		@ToolParam(name="lineNumber", description="Line number to navigate to (1-based). Optional.", required=false) Integer lineNumber,
    		@ToolParam(name="projectName", description="Project name to help resolve relative paths. Optional.", required=false) String projectName)
    {
        return editorService.openFile(filePath, lineNumber, projectName);
    }

}
