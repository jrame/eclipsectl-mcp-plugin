package eclipsectlmcp.mcp.servers;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.eclipse.e4.core.di.annotations.Creatable;

import eclipsectlmcp.mcp.annotations.McpServer;
import eclipsectlmcp.mcp.annotations.Tool;
import eclipsectlmcp.mcp.annotations.ToolParam;
import eclipsectlmcp.mcp.services.CodeAnalysisService;
import eclipsectlmcp.mcp.services.FileCreationService;
import eclipsectlmcp.mcp.services.SourceEditingService;
import eclipsectlmcp.mcp.services.ConsoleService;
import eclipsectlmcp.mcp.services.EditorService;
import eclipsectlmcp.mcp.services.JavaDocService;
import eclipsectlmcp.mcp.services.MavenService;
import eclipsectlmcp.mcp.services.ProjectService;
import eclipsectlmcp.mcp.services.ResourceService;
import eclipsectlmcp.mcp.services.SearchService;
import eclipsectlmcp.mcp.services.UnitTestService;
import jakarta.inject.Inject;

@Creatable
@McpServer(name = "eclipse-ide")
public class EclipseIntegrationsMcpServer
{
    @Inject
    private JavaDocService javaDocService;

    @Inject
    private ProjectService projectService;

    @Inject
    private ResourceService resourceService;

    @Inject
    private SearchService searchService;

    @Inject
    private EditorService editorService;

    @Inject
    private ConsoleService consoleService;

    @Inject
    private SourceEditingService codeEditingService;

    @Inject
    private UnitTestService unitTestService;

    @Inject
    private MavenService mavenService;

    @Inject
    private CodeAnalysisService codeAnalysisService;

    @Inject
    private FileCreationService fileCreationService;

    @Tool(name = "formatCode", description = "Formats code according to the current Eclipse formatter settings.", type = "object")
    public String formatCode(
            @ToolParam(name = "code", description = "The code to be formatted", required = true) String code,
            @ToolParam(name = "projectName", description = "Optional project name to use project-specific formatter settings", required = false) String projectName)
    {
        return codeEditingService.formatCode(code, projectName);
    }

    @Tool(name = "getJavaDoc", description = "Get the JavaDoc for the given compilation unit.  For example,a class B defined as a member type of a class A in package x.y should have athe fully qualified name \"x.y.A.B\".Note that in order to be found, a type name (or its top level enclosingtype name) must match its corresponding compilation unit name.", type = "object")
    public String getJavaDoc(
            @ToolParam(name = "fullyQualifiedName", description = "A fully qualified name of the compilation unit", required = true) String fullyQualifiedClassName)
    {
        return javaDocService.getJavaDoc(fullyQualifiedClassName);
    }

    @Tool(name = "getSource", description = "Get the source for the given class.", type = "object")
    public String getSource(
            @ToolParam(name = "fullyQualifiedClassName", description = "A fully qualified class name of the Java class", required = true) String fullyQualifiedClassName)
    {
        return javaDocService.getSource(fullyQualifiedClassName);
    }

    @Tool(name = "getProjectProperties", description = "Retrieves the properties and configuration of a specified project.", type = "object")
    public String getProjectProperties(
            @ToolParam(name = "projectName", description = "The name of the project to analyze", required = true) String projectName)
    {
        return projectService.getProjectProperties(projectName);
    }

    @Tool(name = "getProjectLayout", description = "Get the file and folder structure of a specified project in a hierarchical format suitable for LLM processing.", type = "object")
    public String getProjectLayout(
            @ToolParam(name = "projectName", description = "The name of the project to analyze", required = true) String projectName)
    {
        return projectService.getProjectLayout(projectName);
    }

    @Tool(name = "readProjectResource", description = "Read the content of a text resource from a specified project.", type = "object")
    public String readProjectResource(
            @ToolParam(name = "projectName", description = "The name of the project containing the resource", required = true) String projectName,
            @ToolParam(name = "resourcePath", description = "The path to the resource relative to the project root", required = true) String resourcePath)
    {
        return resourceService.readProjectResource(projectName, resourcePath);
    }

    @Tool(name = "listProjects", description = "List all available projects in the workspace with their detected natures (Java, C/C++, Python, etc.).", type = "object")
    public String listProjects()
    {
        return projectService.listProjects();
    }

    @Tool(name = "importProject", description = "Import a project from a directory into the Eclipse workspace. Automatically detects project type (Eclipse project with .project file, Maven project with pom.xml, or generic project).", type = "object")
    public String importProject(
            @ToolParam(name = "projectPath", description = "The absolute path to the project directory to import", required = true) String projectPath,
            @ToolParam(name = "projectName", description = "Optional custom name for the project. If not specified, uses the directory name.", required = false) String projectName)
    {
        return projectService.importProject(projectPath, projectName);
    }

    @Tool(name = "getCurrentlyOpenedFile", description = "Gets information about the currently active file in the Eclipse editor.", type = "object")
    public String getCurrentlyOpenedFile()
    {
        return editorService.getCurrentlyOpenedFileContent();
    }

    @Tool(name = "getEditorSelection", description = "Gets the currently selected text or lines in the active editor.", type = "object")
    public String getEditorSelection()
    {
        return editorService.getEditorSelection();
    }

    @Tool(name = "getConsoleOutput", description = "Retrieves the recent output from Eclipse console(s).", type = "object")
    public String getConsoleOutput(
            @ToolParam(name = "consoleName", description = "Name of the specific console to retrieve (optional, leave empty for all or most recent console)", required = false) String consoleName,
            @ToolParam(name = "maxLines", description = "Maximum number of lines to retrieve (default: 100)", required = false) String maxLines,
            @ToolParam(name = "includeAllConsoles", description = "Whether to include output from all available consoles (default: false)", required = false) Boolean includeAllConsoles)
    {
        return consoleService.getConsoleOutput(consoleName,
                Optional.ofNullable(maxLines).map(Integer::parseInt).orElse(0), includeAllConsoles);
    }

    // Unit Test Service Tools

    @Tool(name = "runAllTests", description = "Runs all tests in the workspace or in a specified project if projectName is provided.", type = "object")
    public String runAllTests(
            @ToolParam(name = "projectName", description = "Optional project name to run tests from. If not specified, runs tests from all projects in the workspace.", required = false) String projectName,
            @ToolParam(name = "timeout", description = "Maximum time in seconds to wait for test completion (default: 60)", required = false) String timeout)
    {
        return unitTestService.runAllTests(projectName, Optional.ofNullable(timeout).map(Integer::parseInt).orElse(60));
    }

    @Tool(name = "runPackageTests", description = "Runs tests in a specific package and returns the results.", type = "object")
    public String runPackageTests(
            @ToolParam(name = "projectName", description = "The name of the project containing the tests", required = true) String projectName,
            @ToolParam(name = "packageName", description = "The fully qualified package name containing the tests", required = true) String packageName,
            @ToolParam(name = "timeout", description = "Maximum time in seconds to wait for test completion (default: 60)", required = false) String timeout)
    {
        return unitTestService.runPackageTests(projectName, packageName,
                Optional.ofNullable(timeout).map(Integer::parseInt).orElse(60));
    }

    @Tool(name = "runClassTests", description = "Runs tests for a specific class and returns the results.", type = "object")
    public String runClassTests(
            @ToolParam(name = "projectName", description = "The name of the project containing the tests", required = true) String projectName,
            @ToolParam(name = "className", description = "The fully qualified name of the test class", required = true) String className,
            @ToolParam(name = "timeout", description = "Maximum time in seconds to wait for test completion (default: 60)", required = false) String timeout)
    {
        return unitTestService.runClassTests(projectName, className,
                Optional.ofNullable(timeout).map(Integer::parseInt).orElse(60));
    }

    @Tool(name = "runTestMethod", description = "Runs a specific test method and returns the results.", type = "object")
    public String runTestMethod(
            @ToolParam(name = "projectName", description = "The name of the project containing the tests", required = true) String projectName,
            @ToolParam(name = "className", description = "The fully qualified name of the test class", required = true) String className,
            @ToolParam(name = "methodName", description = "The name of the test method to run", required = true) String methodName,
            @ToolParam(name = "timeout", description = "Maximum time in seconds to wait for test completion (default: 60)", required = false) String timeout)
    {
        return unitTestService.runTestMethod(projectName, className, methodName,
                Optional.ofNullable(timeout).map(Integer::parseInt).orElse(60));
    }

    @Tool(name = "findTestClasses", description = "Finds all test classes in a project.", type = "object")
    public String findTestClasses(
            @ToolParam(name = "projectName", description = "The name of the project to search", required = true) String projectName)
    {
        return unitTestService.findTestClasses(projectName);
    }

    // Maven Service Tools

    @Tool(name = "runMavenBuild", description = "Runs a Maven build with the specified goals on a project.", type = "object")
    public String runMavenBuild(
            @ToolParam(name = "projectName", description = "The name of the project to build", required = true) String projectName,
            @ToolParam(name = "goals", description = "The Maven goals to execute (e.g., \"clean install\")", required = true) String goals,
            @ToolParam(name = "profiles", description = "Optional Maven profiles to activate", required = false) String profiles,
            @ToolParam(name = "timeout", description = "Maximum time in seconds to wait for build completion (0 for no timeout)", required = false) String timeout)
    {
        return mavenService.runMavenBuild(projectName, goals, profiles,
                Optional.ofNullable(timeout).map(Integer::parseInt).orElse(0));
    }

    @Tool(name = "getEffectivePom", description = "Gets the effective POM for a Maven project.", type = "object")
    public String getEffectivePom(
            @ToolParam(name = "projectName", description = "The name of the Maven project", required = true) String projectName)
    {
        return mavenService.getEffectivePom(projectName);
    }

    @Tool(name = "listMavenProjects", description = "Lists all available Maven projects in the workspace.", type = "object")
    public String listMavenProjects()
    {
        return mavenService.listMavenProjects();
    }

    @Tool(name = "getProjectDependencies", description = "Gets Maven project dependencies.", type = "object")
    public String getProjectDependencies(
            @ToolParam(name = "projectName", description = "The name of the Maven project", required = true) String projectName)
    {
        return mavenService.getProjectDependencies(projectName);
    }

    // Search Service Tools

    @Tool(name = "fileSearch", description = "Searches for a plain substring in workspace files using Eclipse's text search engine.", type = "object")
    public String fileSearch(
            @ToolParam(name = "containingText", description = "Text that must be contained in a line (plain substring, not regex)", required = true) String containingText,
            @ToolParam(name = "fileNamePatterns", description = "Optional file name patterns. Accepts either an array (e.g. [\"*.java\", \"*.xml\"]) or a string (e.g. \"*.java,*.xml\"). If omitted, all files are searched.", required = false) Object fileNamePatterns)
    {
        String[] patterns = normalizeFileNamePatterns(fileNamePatterns);
        return SearchService.formatSearchResults(searchService.fileSearch(containingText, patterns), containingText);
    }

    @Tool(name = "fileSearchRegExp", description = "Searches workspace files using a Java regular expression via Eclipse's text search engine.", type = "object")
    public String fileSearchRegExp(
            @ToolParam(name = "pattern", description = "Java regular expression", required = true) String pattern,
            @ToolParam(name = "fileNamePatterns", description = "Optional file name patterns. Accepts either an array (e.g. [\"*.java\", \"*.xml\"]) or a string (e.g. \"*.java,*.xml\"). If omitted, all files are searched.", required = false) Object fileNamePatterns)
    {
        String[] patterns = normalizeFileNamePatterns(fileNamePatterns);
        return SearchService.formatSearchResults(searchService.fileSearchRegExp(pattern, patterns), pattern);
    }

    @Tool(name = "findFiles", description = "Finds workspace files matching the given glob patterns.", type = "object")
    public String findFiles(
            @ToolParam(name = "fileNamePatterns", description = "Glob patterns. Accepts either an array (e.g. [\"*.java\", \"pom.xml\"]) or a string (e.g. \"*.java, pom.xml\"). If omitted, defaults to '*'", required = false) Object fileNamePatterns,
            @ToolParam(name = "maxResults", description = "Maximum number of results to return (default: 200)", required = false) String maxResults)
    {
        String[] patterns = normalizeFileNamePatterns(fileNamePatterns);
        int limit = Optional.ofNullable(maxResults).map(Integer::parseInt).orElse(0);
        List<String> files = resourceService.findFiles(patterns, limit);

        if (files.isEmpty())
        {
            return "No files found";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(files.size()).append(" file");
        if (files.size() != 1)
        {
            sb.append("s");
        }
        sb.append(":\n\n");

        for (String file : files)
        {
            sb.append(file).append("\n");
        }

        return sb.toString();
    }

    private static String[] normalizeFileNamePatterns(Object fileNamePatterns)
    {
        if (fileNamePatterns == null)
        {
            return new String[0];
        }

        if (fileNamePatterns instanceof String[])
        {
            return (String[]) fileNamePatterns;
        }

        if (fileNamePatterns instanceof List)
        {
            @SuppressWarnings("rawtypes")
            List list = (List) fileNamePatterns;
            List<String> out = new ArrayList<>();
            for (Object o : list)
            {
                if (o != null)
                {
                    String s = String.valueOf(o).trim();
                    if (!s.isEmpty())
                    {
                        out.add(s);
                    }
                }
            }
            return out.toArray(String[]::new);
        }

        if (fileNamePatterns instanceof String)
        {
            String s = ((String) fileNamePatterns).trim();
            if (s.isEmpty())
            {
                return new String[0];
            }

            // allow comma-separated patterns: "*.java, *.xml, test.http"
            return s.split("\\s*,\\s*");
        }

        // Fallback: accept any scalar and treat it as a single pattern
        String s = String.valueOf(fileNamePatterns).trim();
        return s.isEmpty() ? new String[0] : new String[] { s };
    }

    @Tool(name = "getMethodCallHierarchy",
          description = "Retrieves the call hierarchy for a specified method, showing which methods call this method (callers) and which methods this method calls (callees).",
          type = "object")
    public String getMethodCallHierarchy(
            @ToolParam(name = "fullyQualifiedClassName", description = "The fully qualified name of the class containing the method", required = true) String fullyQualifiedClassName,
            @ToolParam(name = "methodName", description = "The name of the method to analyze", required = true) String methodName,
            @ToolParam(name = "methodSignature", description = "The signature of the method (optional)", required = false) String methodSignature,
            @ToolParam(name = "maxDepth", description = "Maximum depth of the call hierarchy to retrieve (default: 3)", required = false) Integer maxDepth)
    {
        return codeAnalysisService.getMethodCallHierarchy(fullyQualifiedClassName, methodName, methodSignature, maxDepth);
    }

    @Tool(name = "getCompilationErrors",
          description = "Retrieves compilation errors and problems from the workspace or a specific project. Can filter by severity level.",
          type = "object")
    public String getCompilationErrors(
            @ToolParam(name = "projectName", description = "The name of the project to check (optional, if not specified checks all projects)", required = false) String projectName,
            @ToolParam(name = "severity", description = "Filter by severity level: 'ERROR', 'WARNING', or 'ALL' (default: 'ALL')", required = false) String severity,
            @ToolParam(name = "maxResults", description = "Maximum number of problems to return (default: 100)", required = false) Integer maxResults)
    {
        return codeAnalysisService.getCompilationErrors(projectName, severity, maxResults);
    }

    // Workspace Info

    @Tool(name = "getWorkspaceInfo",
          description = "Gets workspace information including location, project counts, Java version, and Eclipse product info.",
          type = "object")
    public String getWorkspaceInfo()
    {
        return projectService.getWorkspaceInfo();
    }

    // Editor Tools

    @Tool(name = "getOpenEditors",
          description = "Lists all open editors in Eclipse, marking the active editor with *. Shows file paths and modified (unsaved) status.",
          type = "object")
    public String getOpenEditors()
    {
        return editorService.getOpenEditors();
    }

    @Tool(name = "createDirectories",
          description = "Creates a directory (and any missing parent directories) within a project. Useful for creating package structures or resource folders.",
          type = "object")
    public String createDirectories(
            @ToolParam(name = "projectName", description = "The name of the project where directories will be created", required = true) String projectName,
            @ToolParam(name = "directoryPath", description = "The directory path relative to the project root (e.g., 'src/com/example/newpackage')", required = true) String directoryPath)
    {
        return fileCreationService.createDirectories(projectName, directoryPath);
    }

    @Tool(name = "searchAndReplace",
          description = "Searches for a plain text string across workspace files and replaces all occurrences with the replacement text. Returns a summary of files modified and match counts.",
          type = "object")
    public String searchAndReplace(
            @ToolParam(name = "containingText", description = "The text to search for (plain substring, not regex)", required = true) String containingText,
            @ToolParam(name = "replacementText", description = "The replacement text", required = true) String replacementText,
            @ToolParam(name = "fileNamePatterns", description = "Optional file name patterns. Accepts either an array (e.g. [\"*.java\", \"*.xml\"]) or a string (e.g. \"*.java,*.xml\"). If omitted, all files are searched.", required = false) Object fileNamePatterns)
    {
        String[] patterns = normalizeFileNamePatterns(fileNamePatterns);
        return SearchService.formatReplaceResults(searchService.searchAndReplace(containingText, replacementText, patterns));
    }
}
