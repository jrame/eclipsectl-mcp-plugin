
package eclipsectlmcp.mcp.servers;

import org.eclipse.e4.core.di.annotations.Creatable;

import eclipsectlmcp.mcp.annotations.McpServer;
import eclipsectlmcp.mcp.annotations.Tool;
import eclipsectlmcp.mcp.annotations.ToolParam;
import eclipsectlmcp.mcp.services.RefactoringService;
import eclipsectlmcp.mcp.services.JavaRefactoringService;
import org.eclipse.jdt.core.IJavaElement;
import jakarta.inject.Inject;

@Creatable
@McpServer(name = "eclipse-refactor")
public class EclipseRefactorMcpServer 
{
    @Inject
    private RefactoringService codeEditingService;
    
    @Inject
    private JavaRefactoringService javaRefactoringService;

    @Tool(name="refactorApplyPreview", description="Apply exactly the frozen position-based Java refactoring identified by a previewId. Refuses changed or unsaved affected sources before writing any files. Tokens expire after 15 minutes and are consumed on an apply attempt; read all report pages first. Preserves Eclipse refactoring undo.", type="object")
    public String refactorApplyPreview(
        @ToolParam(name="previewId", description="One-use ID returned by a position-based refactoring with preview=true", required=true) String previewId)
    {
        return javaRefactoringService.applyPreview(previewId);
    }

    @Tool(name="refactorGetPreview", description="Read the saved full refactoring report in pages of at most 100000 characters. Follow the returned offsets in order to read the entire diff before refactorApplyPreview. Does not recalculate or change files.", type="object")
    public String refactorGetPreview(
        @ToolParam(name="previewId", description="ID returned by a position-based refactoring with preview=true", required=true) String previewId,
        @ToolParam(name="offset", description="Zero-based report character offset (default: 0); use the next offset returned by the preceding page", required=false) Integer offset)
    {
        return javaRefactoringService.getPreview(previewId, offset);
    }

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

    @Tool(name="refactorRenameMethod", description="Rename a Java method and update its references.", type="object")
    public String refactorRenameMethod(
        @ToolParam(name="filePath", description="Java file relative to project, /project/path, or native absolute path", required=true) String filePath,
        @ToolParam(name="offset", description="Zero-based UTF-16 offset in the saved Java source", required=true) int offset,
        @ToolParam(name="newName", description="newName", required=true) String newName,
        @ToolParam(name="projectName", description="Optional project name", required=false) String projectName,
        @ToolParam(name="preview", description="Create a frozen preview and one-use previewId for refactorApplyPreview (default: false). False applies directly against current source without preview protection.", required=false, type="boolean") Boolean preview)
    {
        return javaRefactoringService.rename(projectName, filePath, offset, newName, IJavaElement.METHOD, Boolean.TRUE.equals(preview));
    }


    @Tool(name="refactorRenameField", description="Rename a Java field and update its references.", type="object")
    public String refactorRenameField(
        @ToolParam(name="filePath", description="Java file relative to project, /project/path, or native absolute path", required=true) String filePath,
        @ToolParam(name="offset", description="Zero-based UTF-16 offset in the saved Java source", required=true) int offset,
        @ToolParam(name="newName", description="newName", required=true) String newName,
        @ToolParam(name="projectName", description="Optional project name", required=false) String projectName,
        @ToolParam(name="preview", description="Create a frozen preview and one-use previewId for refactorApplyPreview (default: false). False applies directly against current source without preview protection.", required=false, type="boolean") Boolean preview)
    {
        return javaRefactoringService.rename(projectName, filePath, offset, newName, IJavaElement.FIELD, Boolean.TRUE.equals(preview));
    }


    @Tool(name="refactorRenameVariable", description="Rename a local Java variable or parameter and update its references.", type="object")
    public String refactorRenameVariable(
        @ToolParam(name="filePath", description="Java file path or unique simple class name (for named selection)", required=true) String filePath,
        @ToolParam(name="offset", description="Zero-based UTF-16 offset; exclusive with oldName, lineNumber and methodName", required=false, type="integer") Integer offset,
        @ToolParam(name="newName", description="newName", required=true) String newName,
        @ToolParam(name="projectName", description="Optional project name", required=false) String projectName,
        @ToolParam(name="preview", description="Create a frozen preview and one-use previewId for refactorApplyPreview (default: false). False applies directly against current source without preview protection.", required=false, type="boolean") Boolean preview,
        @ToolParam(name="oldName", description="Existing local variable or parameter name; requires lineNumber and/or methodName", required=false) String oldName,
        @ToolParam(name="lineNumber", description="1-based line containing a declaration or reference; combine with methodName to narrow selection", required=false, type="integer") Integer lineNumber,
        @ToolParam(name="methodName", description="Enclosing method name; ambiguous bindings or overloads are refused", required=false) String methodName)
    {
        return javaRefactoringService.renameVariable(projectName, filePath, offset, oldName, newName,
                lineNumber, methodName, Boolean.TRUE.equals(preview));
    }


    @Tool(name="refactorChangeSignature", description="Change a Java method signature and update its callers.", type="object")
    public String refactorChangeSignature(
        @ToolParam(name="filePath", description="Java file relative to project, /project/path, or native absolute path", required=true) String filePath,
        @ToolParam(name="offset", description="Zero-based UTF-16 offset in the saved Java source", required=true) int offset,
        @ToolParam(name="newName", description="newName", required=false) String newName,
        @ToolParam(name="returnType", description="returnType", required=false) String returnType,
        @ToolParam(name="visibility", description="Visibility: public, protected, private or package", required=false) String visibility,
        @ToolParam(name="parameters", description="JSON array in desired order: {\"oldIndex\":0,\"name\":\"value\",\"type\":\"int\"}; new parameters use oldIndex -1 and defaultValue; [] removes all parameters", required=false) String parameters,
        @ToolParam(name="projectName", description="Optional project name", required=false) String projectName,
        @ToolParam(name="preview", description="Create a frozen preview and one-use previewId for refactorApplyPreview (default: false). False applies directly against current source without preview protection.", required=false, type="boolean") Boolean preview)
    {
        return javaRefactoringService.changeSignature(projectName, filePath, offset, newName, returnType, visibility, parameters, Boolean.TRUE.equals(preview));
    }


    @Tool(name="refactorExtractVariable", description="Extract a Java expression into a local variable.", type="object")
    public String refactorExtractVariable(
        @ToolParam(name="filePath", description="Java file relative to project, /project/path, or native absolute path", required=true) String filePath,
        @ToolParam(name="offset", description="Zero-based UTF-16 offset in the saved Java source", required=true) int offset,
        @ToolParam(name="length", description="Selection length in UTF-16 code units (positive)", required=true) int length,
        @ToolParam(name="variableName", description="variableName", required=true) String variableName,
        @ToolParam(name="replaceAll", description="Replace all matching occurrences (plugin default: false)", required=false, type="boolean") Boolean replaceAll,
        @ToolParam(name="declareFinal", description="Declare the extracted variable final (plugin default: false)", required=false, type="boolean") Boolean declareFinal,
        @ToolParam(name="projectName", description="Optional project name", required=false) String projectName,
        @ToolParam(name="preview", description="Create a frozen preview and one-use previewId for refactorApplyPreview (default: false). False applies directly against current source without preview protection.", required=false, type="boolean") Boolean preview)
    {
        return javaRefactoringService.extractVariable(projectName, filePath, offset, length, variableName, replaceAll, declareFinal, Boolean.TRUE.equals(preview));
    }


    @Tool(name="refactorExtractConstant", description="Extract a Java expression into a static final constant.", type="object")
    public String refactorExtractConstant(
        @ToolParam(name="filePath", description="Java file relative to project, /project/path, or native absolute path", required=true) String filePath,
        @ToolParam(name="offset", description="Zero-based UTF-16 offset in the saved Java source", required=true) int offset,
        @ToolParam(name="length", description="Selection length in UTF-16 code units (positive)", required=true) int length,
        @ToolParam(name="constantName", description="constantName", required=true) String constantName,
        @ToolParam(name="visibility", description="Visibility: public, protected, private or package", required=false) String visibility,
        @ToolParam(name="replaceAll", description="Replace all matching occurrences (plugin default: false)", required=false, type="boolean") Boolean replaceAll,
        @ToolParam(name="projectName", description="Optional project name", required=false) String projectName,
        @ToolParam(name="preview", description="Create a frozen preview and one-use previewId for refactorApplyPreview (default: false). False applies directly against current source without preview protection.", required=false, type="boolean") Boolean preview)
    {
        return javaRefactoringService.extractConstant(projectName, filePath, offset, length, constantName, visibility, replaceAll, Boolean.TRUE.equals(preview));
    }


    @Tool(name="refactorInlineMethod", description="Inline a Java method declaration or invocation.", type="object")
    public String refactorInlineMethod(
        @ToolParam(name="filePath", description="Java file relative to project, /project/path, or native absolute path", required=true) String filePath,
        @ToolParam(name="offset", description="Zero-based UTF-16 offset in the saved Java source", required=true) int offset,
        @ToolParam(name="inlineAll", description="Inline all invocations (default: true at declaration, false at invocation)", required=false, type="boolean") Boolean inlineAll,
        @ToolParam(name="deleteSource", description="Delete the method declaration (requires all invocations; default: false)", required=false, type="boolean") Boolean deleteSource,
        @ToolParam(name="projectName", description="Optional project name", required=false) String projectName,
        @ToolParam(name="preview", description="Create a frozen preview and one-use previewId for refactorApplyPreview (default: false). False applies directly against current source without preview protection.", required=false, type="boolean") Boolean preview)
    {
        return javaRefactoringService.inlineMethod(projectName, filePath, offset, inlineAll, deleteSource, Boolean.TRUE.equals(preview));
    }


    @Tool(name="refactorInlineVariable", description="Inline a local Java variable using its initializer.", type="object")
    public String refactorInlineVariable(
        @ToolParam(name="filePath", description="Java file relative to project, /project/path, or native absolute path", required=true) String filePath,
        @ToolParam(name="offset", description="Zero-based UTF-16 offset in the saved Java source", required=true) int offset,
        @ToolParam(name="projectName", description="Optional project name", required=false) String projectName,
        @ToolParam(name="preview", description="Create a frozen preview and one-use previewId for refactorApplyPreview (default: false). False applies directly against current source without preview protection.", required=false, type="boolean") Boolean preview)
    {
        return javaRefactoringService.inlineVariable(projectName, filePath, offset, Boolean.TRUE.equals(preview));
    }

}
