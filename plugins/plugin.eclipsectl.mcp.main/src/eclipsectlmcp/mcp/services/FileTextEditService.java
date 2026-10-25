package eclipsectlmcp.mcp.services;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.UnaryOperator;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;

@Creatable
public class FileTextEditService extends CodeEditingServiceBase {

    public String replaceStringInFile(String projectName, String filePath, String oldString, String newString,
            Integer startLine, Integer endLine) {
        Objects.requireNonNull(oldString);
        String replacement = Objects.requireNonNullElse(newString, "");
        return edit(projectName, filePath,
                text -> new LineTextEdits(text).replaceString(oldString, replacement, startLine, endLine), "String replaced in file");
    }

    public String insertIntoFile(String projectName, String filePath, String content, int atLine) {
        String insertion = Objects.requireNonNullElse(content, "");
        return edit(projectName, filePath, text -> new LineTextEdits(text).insert(insertion, atLine));
    }

    public String replaceLines(String projectName, String filePath, String replacementContent, int startLine, int endLine) {
        String replacement = Objects.requireNonNullElse(replacementContent, "");
        return edit(projectName, filePath, text -> new LineTextEdits(text).replaceLines(replacement, startLine, endLine));
    }

    public String replaceFileContent(String projectName, String filePath, String content) {
        Objects.requireNonNull(content);
        return edit(projectName, filePath, text -> content);
    }

    private String edit(String projectName, String filePath, UnaryOperator<String> change) {
        return edit(projectName, filePath, change, "file");
    }

    private String edit(String projectName, String filePath, UnaryOperator<String> change, String successLabel) {
        Objects.requireNonNull(projectName);
        Objects.requireNonNull(filePath);
        if (projectName.isEmpty() || filePath.isEmpty()) {
            throw new IllegalArgumentException("Error: Project name and file path cannot be empty.");
        }
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!project.exists() || !project.isOpen()) {
            throw new IllegalArgumentException("Error: Project '" + projectName + "' must exist and be open.");
        }
        IFile file = resolveFile(project, filePath);
        sync.syncExec(() -> requireCleanEditors(file));
        try {
            // Do not refresh first: that would hide an external modification conflict.
            requireSynchronized(file);
            long stamp = file.getModificationStamp();
            String charset = file.getCharset();
            byte[] original = readBytes(file);
            String originalText = new String(original, Charset.forName(charset));
            String proposed = change.apply(originalText);
            String diff = generateDiff(filePath, originalText, proposed);
            byte[] updated = proposed.getBytes(Charset.forName(charset));
            sync.syncExec(() -> {
                requireCleanEditors(file);
                try {
                    file.getWorkspace().run(monitor -> {
                        requireCleanEditors(file);
                        requireSynchronized(file);
                        try {
                            if (stamp != file.getModificationStamp() || !charset.equals(file.getCharset())
                                    || !Arrays.equals(original, readBytes(file))) {
                                throw new IllegalStateException("Error: File changed during editing. Read it again and retry.");
                            }
                            // KEEP_HISTORY deliberately excludes FORCE; Eclipse rejects out-of-sync writes.
                            file.setContents(new ByteArrayInputStream(updated), IResource.KEEP_HISTORY, monitor);
                        } catch (IOException e) {
                            throw new IllegalStateException("Error: Cannot read file before writing.", e);
                        }
                    }, file, 0, null);
                    refreshEditor(file);
                } catch (CoreException e) {
                    throw new IllegalStateException("Error: File could not be saved: " + e.getMessage(), e);
                }
            });
            return "Success: " + successLabel + " '" + filePath + "' in project '" + projectName
                    + "' was updated.\nChanges:\n```diff\n" + diff + "\n```";
        } catch (CoreException | IOException e) {
            throw new IllegalStateException("Error: File could not be edited: " + e.getMessage(), e);
        }
    }

    private static String generateDiff(String path, String original, String proposed) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var formatter = new DiffFormatter(output)) {
            formatter.setContext(3);
            formatter.setDiffComparator(RawTextComparator.DEFAULT);
            RawText before = new RawText(original.getBytes(StandardCharsets.UTF_8));
            RawText after = new RawText(proposed.getBytes(StandardCharsets.UTF_8));
            formatter.format(new org.eclipse.jgit.diff.HistogramDiff().diff(RawTextComparator.DEFAULT, before, after), before, after);
        }
        return "--- /" + path + "\n+++ /" + path + "\n" + output.toString(StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(IFile file) throws CoreException, IOException {
        try (var input = file.getContents()) { return input.readAllBytes(); }
    }

    private static void requireSynchronized(IFile file) {
        if (!file.isSynchronized(IResource.DEPTH_ZERO)) {
            throw new IllegalStateException("Error: File has external changes. Refresh and review them before retrying.");
        }
    }
}
