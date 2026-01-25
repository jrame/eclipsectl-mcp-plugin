package eclipsectlmcp.mcp.servers;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.eclipse.e4.core.di.annotations.Creatable;

import eclipsectlmcp.mcp.annotations.McpServer;
import eclipsectlmcp.mcp.annotations.Tool;
import eclipsectlmcp.mcp.annotations.ToolParam;
import eclipsectlmcp.mcp.services.CodeGenerationService;
import jakarta.inject.Inject;

/**
 * MCP server for Java code generation operations like generating getters/setters.
 */
@Creatable
@McpServer(name = "eclipse-codegen")
public class EclipseCodeGenerationMcpServer {

    @Inject
    private CodeGenerationService codeGenerationService;

    @Tool(name="generateGettersSetters",
         description="Generate getter and/or setter methods for fields in a Java class. This is one of the most frequent boilerplate code generation operations. The tool will automatically detect field types, generate appropriate method signatures, and avoid creating duplicate methods.",
         type="object")
    public String generateGettersSetters(
        @ToolParam(name="projectName", description="The name of the project containing the Java file. Optional if file path is unique across projects or starts with /projectName/", required=false) String projectName,
        @ToolParam(name="filePath", description="The path to the Java file relative to the project root (e.g., 'src/com/example/User.java') or absolute path starting with /projectName/", required=true) String filePath,
        @ToolParam(name="className", description="The name of the class within the file. Optional if the file contains only one class.", required=false) String className,
        @ToolParam(name="fieldNames", description="Comma-separated list of field names to generate methods for. If not specified, all fields will be processed.", required=false) String fieldNames,
        @ToolParam(name="generateGetters", description="Whether to generate getter methods. Defaults to true.", required=false) Boolean generateGetters,
        @ToolParam(name="generateSetters", description="Whether to generate setter methods. Defaults to true.", required=false) Boolean generateSetters,
        @ToolParam(name="insertionPoint", description="Where to insert the generated methods: 'end' (default) to add at the end of the class, or a specific line number", required=false) String insertionPoint)
    {
        List<String> fields = (fieldNames != null && !fieldNames.isEmpty())
            ? Arrays.asList(fieldNames.split(",\\s*"))
            : Collections.emptyList();

        return codeGenerationService.generateGettersSetters(
            projectName,
            filePath,
            className,
            fields,
            generateGetters != null ? generateGetters : true,
            generateSetters != null ? generateSetters : true,
            insertionPoint != null ? insertionPoint : "end");
    }

    @Tool(name="generateToStringEqualsHashCode",
         description="Generate toString(), equals(), and/or hashCode() methods for a Java class based on its fields. These are standard methods used for object representation, equality comparison, and hash-based collections. The tool will generate methods following Java best practices and avoid creating duplicates.",
         type="object")
    public String generateToStringEqualsHashCode(
        @ToolParam(name="projectName", description="The name of the project containing the Java file. Optional if file path is unique across projects or starts with /projectName/", required=false) String projectName,
        @ToolParam(name="filePath", description="The path to the Java file relative to the project root (e.g., 'src/com/example/User.java') or absolute path starting with /projectName/", required=true) String filePath,
        @ToolParam(name="className", description="The name of the class within the file. Optional if the file contains only one class.", required=false) String className,
        @ToolParam(name="fieldNames", description="Comma-separated list of field names to include in the generated methods. If not specified, all fields will be included.", required=false) String fieldNames,
        @ToolParam(name="generateToString", description="Whether to generate toString() method. Defaults to true.", required=false) Boolean generateToString,
        @ToolParam(name="generateEquals", description="Whether to generate equals() method. Defaults to true.", required=false) Boolean generateEquals,
        @ToolParam(name="generateHashCode", description="Whether to generate hashCode() method. Defaults to true.", required=false) Boolean generateHashCode)
    {
        List<String> fields = (fieldNames != null && !fieldNames.isEmpty())
            ? Arrays.asList(fieldNames.split(",\\s*"))
            : Collections.emptyList();

        return codeGenerationService.generateToStringEqualsHashCode(
            projectName,
            filePath,
            className,
            fields,
            generateToString != null ? generateToString : true,
            generateEquals != null ? generateEquals : true,
            generateHashCode != null ? generateHashCode : true);
    }

    @Tool(name="implementOverrideMethods",
         description="Implement or override methods from superclass or interfaces. This tool finds unimplemented abstract methods and generates skeleton implementations with appropriate @Override annotations and TODO comments. Useful when implementing interfaces or extending abstract classes.",
         type="object")
    public String implementOverrideMethods(
        @ToolParam(name="projectName", description="The name of the project containing the Java file. Optional if file path is unique across projects or starts with /projectName/", required=false) String projectName,
        @ToolParam(name="filePath", description="The path to the Java file relative to the project root (e.g., 'src/com/example/MyService.java') or absolute path starting with /projectName/", required=true) String filePath,
        @ToolParam(name="className", description="The name of the class within the file. Optional if the file contains only one class.", required=false) String className,
        @ToolParam(name="methodNames", description="Comma-separated list of specific method names to implement. If not specified, all unimplemented abstract methods will be generated.", required=false) String methodNames)
    {
        List<String> methods = (methodNames != null && !methodNames.isEmpty())
            ? Arrays.asList(methodNames.split(",\\s*"))
            : Collections.emptyList();

        return codeGenerationService.implementOverrideMethods(
            projectName,
            filePath,
            className,
            methods);
    }
}
