package eclipsectlmcp.mcp.services;

import org.eclipse.e4.core.di.annotations.Creatable;

import jakarta.inject.Inject;

/**
 * Facade for source editing operations. Delegates to specialized services:
 * - OrganizeImportsService: Import organization
 * - CodeFormattingService: Code formatting
 * - JavaMemberEditService: Structured Java member editing (read/write/remove)
 */
@Creatable
public class SourceEditingService {

    @Inject
    private OrganizeImportsService organizeImportsService;

    @Inject
    private CodeFormattingService codeFormattingService;

    @Inject
    private JavaMemberEditService javaMemberEditService;

    // === Import Organization ===

    public String organizeImports(String projectName, String filePath) {
        return organizeImportsService.organizeImports(projectName, filePath);
    }

    public String organizeImportsInPackage(String projectName, String packageName) {
        return organizeImportsService.organizeImportsInPackage(projectName, packageName);
    }

    // === Code Formatting ===

    public String formatFile(String projectName, String filePath) {
        return codeFormattingService.formatFile(projectName, filePath);
    }

    public String formatFileRegion(String projectName, String filePath, int startLine, int endLine) {
        return codeFormattingService.formatFileRegion(projectName, filePath, startLine, endLine);
    }

    public String formatFileRanges(String projectName, String filePath, String lines) {
        return codeFormattingService.formatFileRanges(projectName, filePath, lines);
    }

    public String formatCode(String code, String projectName) {
        return codeFormattingService.formatCode(code, projectName);
    }

    // === Java Member Editing ===

    public String readJava(String projectName, String filePath, String className) {
        return javaMemberEditService.readJava(projectName, filePath, className);
    }

    public String writeJava(String projectName, String filePath, String className, String newSource) {
        return javaMemberEditService.writeJava(projectName, filePath, className, newSource);
    }

    public String removeJava(String projectName, String filePath, String className, String members) {
        return javaMemberEditService.removeJava(projectName, filePath, className, members);
    }

    public String removeLines(String projectName, String filePath, String lines) {
        return javaMemberEditService.removeLines(projectName, filePath, lines);
    }

    public String resetReadGate(String projectName, String filePath) {
        return javaMemberEditService.resetReadGate(projectName, filePath);
    }
}
