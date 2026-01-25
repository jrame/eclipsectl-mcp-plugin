
package eclipsectlmcp.mcp.servers;

import org.eclipse.e4.core.di.annotations.Creatable;

import eclipsectlmcp.mcp.annotations.McpServer;
import eclipsectlmcp.mcp.annotations.Tool;
import eclipsectlmcp.mcp.annotations.ToolParam;
import eclipsectlmcp.mcp.services.RefactoringService;
import jakarta.inject.Inject;

@Creatable
@McpServer(name = "eclipse-refactor")
public class EclipseRefactorMcpServer 
{
    @Inject
    private RefactoringService codeEditingService;
    
    @Tool(name="renameFile", description="Renames a file in the specified project.", type="object")
    public String renameFile(
        @ToolParam(name="projectName", description="The name of the project containing the file. Optional if file path is unique across projects or starts with /projectName/", required=false) String projectName,
        @ToolParam(name="filePath", description="The path to the file relative to the project root, or absolute path starting with /projectName/", required=true) String filePath,
        @ToolParam(name="newFileName", description="The new name for the file", required=true) String newFileName)
    {
        return codeEditingService.renameFile(projectName, filePath, newFileName);
    }

    @Tool(name="refactorRenameJavaType", description="Renames a Java class/interface/enum using Eclipse's refactoring mechanism. This updates the type name, file name, and ALL references throughout the workspace. Use this instead of renameFile for Java files to ensure all references are updated correctly.", type="object")
    public String refactorRenameJavaType(
        @ToolParam(name="projectName", description="The name of the project containing the Java file. Optional if file path is unique across projects or starts with /projectName/", required=false) String projectName,
        @ToolParam(name="filePath", description="The path to the Java file relative to the project root (e.g., 'src/com/example/MyClass.java') or absolute path starting with /projectName/", required=true) String filePath,
        @ToolParam(name="newTypeName", description="The new name for the Java type (without .java extension, e.g., 'NewClassName')", required=true) String newTypeName)
    {
        return codeEditingService.refactorRenameJavaType(projectName, filePath, newTypeName);
    }

    @Tool(name="refactorMoveJavaType", description="Moves a Java class/interface/enum to a different package using Eclipse's refactoring mechanism. This updates the package declaration and ALL references throughout the workspace. The target package will be created if it doesn't exist.", type="object")
    public String refactorMoveJavaType(
        @ToolParam(name="projectName", description="The name of the project containing the Java file. Optional if file path is unique across projects or starts with /projectName/", required=false) String projectName,
        @ToolParam(name="filePath", description="The path to the Java file relative to the project root (e.g., 'src/com/example/MyClass.java') or absolute path starting with /projectName/", required=true) String filePath,
        @ToolParam(name="targetPackage", description="The fully qualified target package name (e.g., 'com.example.newpackage')", required=true) String targetPackage)
    {
        return codeEditingService.refactorMoveJavaType(projectName, filePath, targetPackage);
    }

    @Tool(name="refactorRenamePackage", description="Renames a Java package using Eclipse's refactoring mechanism. This renames the package directory, updates all package declarations in contained files, and updates ALL references throughout the workspace.", type="object")
    public String refactorRenamePackage(
        @ToolParam(name="projectName", description="The name of the project containing the package. Optional if package is unique across projects.", required=false) String projectName,
        @ToolParam(name="packageName", description="The current fully qualified package name (e.g., 'com.example.oldpackage')", required=true) String packageName,
        @ToolParam(name="newPackageName", description="The new package name - can be fully qualified (e.g., 'com.example.newpackage') or just the last segment to rename", required=true) String newPackageName)
    {
        return codeEditingService.refactorRenamePackage(projectName, packageName, newPackageName);
    }

    @Tool(name="moveResource", description="Moves a file or folder to a different location within the project. For Java files, prefer using refactorMoveJavaType instead to ensure all references are updated.", type="object")
    public String moveResource(
        @ToolParam(name="projectName", description="The name of the project containing the resource. Optional if source path is unique across projects or starts with /projectName/", required=false) String projectName,
        @ToolParam(name="sourcePath", description="The path to the file or folder relative to the project root, or absolute path starting with /projectName/", required=true) String sourcePath,
        @ToolParam(name="targetPath", description="The target directory path relative to the project root where the resource should be moved to", required=true) String targetPath)
    {
        return codeEditingService.moveResource(projectName, sourcePath, targetPath);
    }

    @Tool(name="refactorExtractMethod", description="Extract selected code lines into a new method. This is the most commonly used refactoring operation. The refactoring will analyze the code block, determine parameters and return values automatically, create a new method, and replace the original code with a method call.", type="object")
    public String refactorExtractMethod(
        @ToolParam(name="projectName", description="The name of the project containing the Java file. Optional if file path is unique across projects or starts with /projectName/", required=false) String projectName,
        @ToolParam(name="filePath", description="The path to the Java file relative to the project root (e.g., 'src/com/example/Calculator.java') or absolute path starting with /projectName/", required=true) String filePath,
        @ToolParam(name="startLine", description="The starting line number of the code block to extract (1-based index, inclusive)", required=true) int startLine,
        @ToolParam(name="endLine", description="The ending line number of the code block to extract (1-based index, inclusive)", required=true) int endLine,
        @ToolParam(name="methodName", description="The name for the new extracted method", required=true) String methodName,
        @ToolParam(name="visibility", description="The visibility modifier for the new method: 'public', 'private', 'protected', or 'package'. Defaults to 'private'", required=false) String visibility)
    {
        return codeEditingService.refactorExtractMethod(projectName, filePath, startLine, endLine, methodName, visibility);
    }
}
