
package eclipsectlmcp.mcp.servers;

import org.eclipse.e4.core.di.annotations.Creatable;

import eclipsectlmcp.mcp.annotations.McpServer;
import eclipsectlmcp.mcp.annotations.Tool;
import eclipsectlmcp.mcp.annotations.ToolParam;
import eclipsectlmcp.mcp.services.SourceEditingService;
import jakarta.inject.Inject;

@Creatable
@McpServer(name = "eclipse-source")
public class EclipseSourceMcpServer
{
    @Inject
    private SourceEditingService codeEditingService;

    @Tool(name="organizeImports", description="Organizes imports in a Java file using Eclipse's organize imports mechanism. This removes unused imports, adds missing imports, and sorts them according to project settings. Equivalent to pressing Ctrl+Shift+O in Eclipse.", type="object")
    public String organizeImports(
        @ToolParam(name="projectName", description="The name of the project containing the Java file. Optional if file path is unique across projects or starts with /projectName/", required=false) String projectName,
        @ToolParam(name="filePath", description="The path to the Java file relative to the project root (e.g., 'src/com/example/MyClass.java') or absolute path starting with /projectName/", required=true) String filePath)
    {
        return codeEditingService.organizeImports(projectName, filePath);
    }

    @Tool(name="organizeImportsInPackage", description="Organizes imports in all Java files within a package. This is useful for cleaning up imports across multiple files at once.", type="object")
    public String organizeImportsInPackage(
        @ToolParam(name="projectName", description="The name of the project containing the package. Optional if package is unique across projects.", required=false) String projectName,
        @ToolParam(name="packageName", description="The fully qualified package name (e.g., 'com.example.mypackage')", required=true) String packageName)
    {
        return codeEditingService.organizeImportsInPackage(projectName, packageName);
    }

    @Tool(name="formatFile", description="Formats a Java file using Eclipse's formatter settings. This applies proper indentation, spacing, line breaks, and code style according to project-specific or workspace formatter preferences. Equivalent to pressing Ctrl+Shift+F in Eclipse.", type="object")
    public String formatFile(
        @ToolParam(name="projectName", description="The name of the project containing the Java file. Optional if file path is unique across projects or starts with /projectName/", required=false) String projectName,
        @ToolParam(name="filePath", description="The path to the Java file relative to the project root (e.g., 'src/com/example/MyClass.java') or absolute path starting with /projectName/", required=true) String filePath,
        @ToolParam(name="lines", description="Line ranges to format (e.g., '10-20,105,150-190'). If omitted, formats entire file.", required=false) String lines)
    {
        if (lines != null && !lines.isBlank()) {
            return codeEditingService.formatFileRanges(projectName, filePath, lines);
        }
        return codeEditingService.formatFile(projectName, filePath);
    }

    @Tool(name="readJava", description="Lists the structure of a Java class: fields, methods, constructors, inner types with their line ranges and full source code. "
        + "MUST be called before writeJava or removeJava to inspect the current state of the file. "
        + "This prevents blind overwrites by ensuring you see the current class structure first.", type="object")
    public String readJava(
        @ToolParam(name="projectName", description="The name of the project. Optional if file path is unique or starts with /projectName/", required=false) String projectName,
        @ToolParam(name="filePath", description="Path to the Java file (e.g., 'src/com/example/MyClass.java')", required=true) String filePath,
        @ToolParam(name="className", description="Target class name within the file. Use dotted notation for inner classes (e.g., 'Outer.Inner'). Lists all top-level types if omitted.", required=false) String className)
    {
        return codeEditingService.readJava(projectName, filePath, className);
    }

    @Tool(name="writeJava", description="Writes a code member (method, field, constructor, inner class) into a Java class. "
        + "Parses the provided source via AST to identify the member type, then replaces an existing member with the same signature or appends it if not found. "
        + "Automatically runs organize imports afterward. Returns a diff and any compilation errors. "
        + "REQUIRES readJava to be called first on the target file.", type="object")
    public String writeJava(
        @ToolParam(name="projectName", description="The name of the project. Optional if file path is unique or starts with /projectName/", required=false) String projectName,
        @ToolParam(name="filePath", description="Path to the Java file (e.g., 'src/com/example/MyClass.java')", required=true) String filePath,
        @ToolParam(name="className", description="Target class name within the file. Use dotted notation for inner classes (e.g., 'Outer.Inner'). Defaults to the primary type if omitted.", required=false) String className,
        @ToolParam(name="newSource", description="Complete source code of the member to write. Must be a valid Java class member: method, constructor, field, or inner type declaration.", required=true) String newSource)
    {
        return codeEditingService.writeJava(projectName, filePath, className, newSource);
    }

    @Tool(name="removeJava", description="Removes named members (fields, methods, inner types) from a Java class by name. "
        + "Automatically runs organize imports afterward. Returns a diff and any compilation errors. "
        + "REQUIRES readJava to be called first on the target file.", type="object")
    public String removeJava(
        @ToolParam(name="projectName", description="The name of the project. Optional if file path is unique or starts with /projectName/", required=false) String projectName,
        @ToolParam(name="filePath", description="Path to the Java file (e.g., 'src/com/example/MyClass.java')", required=true) String filePath,
        @ToolParam(name="className", description="Target class name for member removal. Use dotted notation for inner classes. Defaults to the primary type if omitted.", required=false) String className,
        @ToolParam(name="members", description="Comma-separated member names to remove (e.g., 'getData,setData,myField'). Matches fields, methods, or inner types by name.", required=true) String members)
    {
        return codeEditingService.removeJava(projectName, filePath, className, members);
    }

    @Tool(name="removeLines", description="Removes line ranges from a Java file. "
        + "Automatically runs organize imports afterward. Returns a diff and any compilation errors. "
        + "REQUIRES readJava to be called first on the target file.", type="object")
    public String removeLines(
        @ToolParam(name="projectName", description="The name of the project. Optional if file path is unique or starts with /projectName/", required=false) String projectName,
        @ToolParam(name="filePath", description="Path to the Java file (e.g., 'src/com/example/MyClass.java')", required=true) String filePath,
        @ToolParam(name="lines", description="Line ranges to remove (e.g., '1-5,65,100-105'). Lines are 1-based.", required=true) String lines)
    {
        return codeEditingService.removeLines(projectName, filePath, lines);
    }

    @Tool(name="resetReadGate", description="Resets the readJava gate. If a filePath is provided, resets only that file. "
        + "If no filePath is provided, resets all files. Use this to force re-reading a file before write/remove operations.", type="object")
    public String resetReadGate(
        @ToolParam(name="projectName", description="The name of the project. Optional.", required=false) String projectName,
        @ToolParam(name="filePath", description="Path to a specific file to reset, or omit to reset all files.", required=false) String filePath)
    {
        return codeEditingService.resetReadGate(projectName, filePath);
    }

}
