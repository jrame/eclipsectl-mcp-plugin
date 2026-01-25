package eclipsectlmcp.mcp.services;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.formatter.CodeFormatter;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.text.edits.MalformedTreeException;
import org.eclipse.text.edits.TextEdit;

/**
 * Service for formatting Java code.
 */
@Creatable
public class CodeFormattingService extends CodeEditingServiceBase {

    public String formatFile(String projectName, String filePath) {
        Objects.requireNonNull(filePath);

        if (filePath.isEmpty()) {
            throw new IllegalArgumentException("Error: File path cannot be empty.");
        }

        try {
            IProject project = resolveProject(projectName, filePath);

            if (!project.isOpen()) {
                throw new RuntimeException("Error: Project '" + project.getName() + "' is closed.");
            }

            IJavaProject javaProject = JavaCore.create(project);
            if (javaProject == null || !javaProject.exists()) {
                throw new RuntimeException("Error: Project '" + project.getName() + "' is not a Java project.");
            }

            IFile file = resolveFile(project, filePath);

            if (!filePath.endsWith(".java")) {
                throw new RuntimeException("Error: File '" + filePath + "' is not a Java file.");
            }

            IJavaElement javaElement = JavaCore.create(file);
            if (!(javaElement instanceof ICompilationUnit)) {
                throw new RuntimeException("Error: Could not resolve Java compilation unit for file '" + filePath + "'.");
            }

            ICompilationUnit compilationUnit = (ICompilationUnit) javaElement;

            String originalSource = compilationUnit.getSource();

            Map<String, String> options = javaProject.getOptions(true);

            CodeFormatter formatter = ToolFactory.createCodeFormatter(options);

            TextEdit textEdit = formatter.format(
                CodeFormatter.K_COMPILATION_UNIT | CodeFormatter.F_INCLUDE_COMMENTS,
                originalSource,
                0,
                originalSource.length(),
                0,
                null
            );

            if (textEdit == null) {
                return "Success: File '" + filePath + "' was already properly formatted.";
            }

            IDocument document = new Document(originalSource);
            textEdit.apply(document);
            String formattedSource = document.get();

            if (originalSource.equals(formattedSource)) {
                return "Success: File '" + filePath + "' was already properly formatted.";
            }

            ByteArrayInputStream inputStream = new ByteArrayInputStream(formattedSource.getBytes(Charset.forName(file.getCharset())));
            file.setContents(inputStream, IResource.KEEP_HISTORY, new NullProgressMonitor());

            sync.asyncExec(() -> {
                refreshEditor(file);
            });

            return "Success: File '" + filePath + "' has been formatted.";
        } catch (CoreException | MalformedTreeException | BadLocationException e) {
            throw new RuntimeException("Error during file formatting: " + ExceptionUtils.getRootCauseMessage(e), e);
        }
    }

    public String formatFileRegion(String projectName, String filePath, int startLine, int endLine) {
        Objects.requireNonNull(filePath);

        if (filePath.isEmpty()) {
            throw new IllegalArgumentException("Error: File path cannot be empty.");
        }

        if (startLine < 1) {
            throw new IllegalArgumentException("Error: Start line must be >= 1.");
        }

        if (endLine < startLine) {
            throw new IllegalArgumentException("Error: End line must be >= start line.");
        }

        try {
            IProject project = resolveProject(projectName, filePath);

            if (!project.isOpen()) {
                throw new RuntimeException("Error: Project '" + project.getName() + "' is closed.");
            }

            IJavaProject javaProject = JavaCore.create(project);
            if (javaProject == null || !javaProject.exists()) {
                throw new RuntimeException("Error: Project '" + project.getName() + "' is not a Java project.");
            }

            IFile file = resolveFile(project, filePath);

            if (!filePath.endsWith(".java")) {
                throw new RuntimeException("Error: File '" + filePath + "' is not a Java file.");
            }

            IJavaElement javaElement = JavaCore.create(file);
            if (!(javaElement instanceof ICompilationUnit)) {
                throw new RuntimeException("Error: Could not resolve Java compilation unit for file '" + filePath + "'.");
            }

            ICompilationUnit compilationUnit = (ICompilationUnit) javaElement;

            String originalSource = compilationUnit.getSource();

            IDocument document = new Document(originalSource);
            int totalLines = document.getNumberOfLines();

            if (startLine > totalLines) {
                throw new IllegalArgumentException("Error: Start line " + startLine + " exceeds total lines " + totalLines + " in file.");
            }

            if (endLine > totalLines) {
                endLine = totalLines;
            }

            int startLineIndex = startLine - 1;
            int endLineIndex = endLine - 1;

            int offset = document.getLineOffset(startLineIndex);
            int endOffset = document.getLineOffset(endLineIndex) + document.getLineLength(endLineIndex);
            int length = endOffset - offset;

            Map<String, String> options = javaProject.getOptions(true);

            CodeFormatter formatter = ToolFactory.createCodeFormatter(options);

            TextEdit textEdit = formatter.format(
                CodeFormatter.K_COMPILATION_UNIT | CodeFormatter.F_INCLUDE_COMMENTS,
                originalSource,
                offset,
                length,
                0,
                null
            );

            if (textEdit == null) {
                return "Success: Lines " + startLine + "-" + endLine + " in file '" + filePath + "' were already properly formatted.";
            }

            textEdit.apply(document);
            String formattedSource = document.get();

            if (originalSource.equals(formattedSource)) {
                return "Success: Lines " + startLine + "-" + endLine + " in file '" + filePath + "' were already properly formatted.";
            }

            ByteArrayInputStream inputStream = new ByteArrayInputStream(formattedSource.getBytes(Charset.forName(file.getCharset())));
            file.setContents(inputStream, IResource.KEEP_HISTORY, new NullProgressMonitor());

            sync.asyncExec(() -> {
                refreshEditor(file);
            });

            return "Success: Lines " + startLine + "-" + endLine + " in file '" + filePath + "' have been formatted.";
        } catch (CoreException | MalformedTreeException | BadLocationException e) {
            throw new RuntimeException("Error during region formatting: " + ExceptionUtils.getRootCauseMessage(e), e);
        }
    }

    public String formatFileRanges(String projectName, String filePath, String lines) {
        Objects.requireNonNull(filePath);
        Objects.requireNonNull(lines);

        if (filePath.isEmpty()) {
            throw new IllegalArgumentException("Error: File path cannot be empty.");
        }
        if (lines.isBlank()) {
            throw new IllegalArgumentException("Error: Lines parameter cannot be empty.");
        }

        // Parse ranges (same format as removeLines: "1-5,65,100-105")
        String[] parts = lines.split(",");
        int[][] ranges = new int[parts.length][2];
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            if (part.contains("-")) {
                String[] bounds = part.split("-", 2);
                ranges[i][0] = Integer.parseInt(bounds[0].trim());
                ranges[i][1] = Integer.parseInt(bounds[1].trim());
            } else {
                int line = Integer.parseInt(part);
                ranges[i][0] = line;
                ranges[i][1] = line;
            }
            if (ranges[i][0] < 1 || ranges[i][1] < ranges[i][0]) {
                throw new IllegalArgumentException("Error: Invalid line range '" + part + "'.");
            }
        }

        // Sort ranges descending by start line so formatting from bottom up avoids offset shifts
        java.util.Arrays.sort(ranges, (a, b) -> Integer.compare(b[0], a[0]));

        StringBuilder summary = new StringBuilder();
        for (int[] range : ranges) {
            String result = formatFileRegion(projectName, filePath, range[0], range[1]);
            summary.append(result).append("\n");
        }

        return summary.toString().trim();
    }

    public String formatCode(String code, String projectName) {
        if (code == null || code.isEmpty()) {
            return "Error: Code parameter cannot be null or empty.";
        }
        try {
            Map<String, String> options;

            if (projectName != null && !projectName.isEmpty()) {
                IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
                if (project.exists() && project.isOpen()) {
                    IJavaProject javaProject = JavaCore.create(project);
                    options = javaProject.getOptions(true);
                } else {
                    options = JavaCore.getOptions();
                }
            } else {
                options = JavaCore.getOptions();
            }

            CodeFormatter formatter = ToolFactory.createCodeFormatter(options);

            TextEdit textEdit = formatter.format(CodeFormatter.K_COMPILATION_UNIT | CodeFormatter.F_INCLUDE_COMMENTS,
                    code, 0, code.length(), 0, null);

            if (textEdit == null) {
                logger.warn("Code formatting failed - returning unformatted code");
                return code;
            }

            IDocument document = new Document(code);
            textEdit.apply(document);

            return document.get();
        } catch (MalformedTreeException | BadLocationException e) {
            logger.error("Error during code formatting: " + e.getMessage(), e);
            throw new RuntimeException("Error formatting code: " + e.getMessage(), e);
        }
    }
}
