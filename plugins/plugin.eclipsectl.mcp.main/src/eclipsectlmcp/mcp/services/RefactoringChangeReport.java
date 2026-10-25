package eclipsectlmcp.mcp.services;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.HistogramDiff;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.TextChange;

/** Describes the validated LTK change without performing it or modifying editor buffers. */
public final class RefactoringChangeReport {
    private RefactoringChangeReport() {}

    record FileEdit(IFile file, TextChange change, String before, String after) {}
    public record Report(String text, List<FileEdit> edits) {}

    static String bounded(String report) {
        if (report.length() <= RefactoringPreviewStore.PAGE_CHARS) return report;
        int end = RefactoringPreviewStore.PAGE_CHARS;
        if (Character.isHighSurrogate(report.charAt(end - 1))) end--;
        return report.substring(0, end) + "\n[Report truncated. Use preview=true and refactorGetPreview to inspect complete reports before applying.]\n";
    }

    static Report describe(Change change, NullProgressMonitor monitor) throws Exception {
        List<String> files = new ArrayList<>();
        List<FileEdit> edits = new ArrayList<>();
        var projects = new TreeSet<String>();
        StringBuilder diffs = new StringBuilder();
        collect(change, monitor, files, projects, diffs, edits);
        StringBuilder result = new StringBuilder();
        result.append("Affected projects: ").append(projects.isEmpty() ? "none" : String.join(", ", projects));
        result.append("\nChanged files: ").append(files.size()).append("\n");
        for (String file : files) result.append("  ").append(file).append("\n");
        if (files.isEmpty()) result.append("No text changes found.\n");
        result.append("\n").append(diffs);
        return new Report(result.toString(), List.copyOf(edits));
    }

    private static void collect(Change change, NullProgressMonitor monitor, List<String> files,
            TreeSet<String> projects, StringBuilder diffs, List<FileEdit> edits) throws Exception {
        if (!change.isEnabled()) return;
        if (change instanceof CompositeChange composite) {
            for (Change child : composite.getChildren()) collect(child, monitor, files, projects, diffs, edits);
        } else if (change instanceof TextChange text) {
            Object modified = text.getModifiedElement();
            if (modified instanceof ICompilationUnit unit) modified = unit.getResource();
            if (!(modified instanceof IFile file))
                throw new IllegalArgumentException("Cannot preview text change: " + change.getName());
            String before = text.getCurrentContent(monitor);
            String after = text.getPreviewContent(monitor);
            edits.add(new FileEdit(file, text, before, after));
            if (before.equals(after)) return;
            String path = file.getFullPath().toPortableString();
            files.add(path);
            projects.add(file.getProject().getName());
            {
                var oldText = new RawText(before.getBytes(StandardCharsets.UTF_8));
                var newText = new RawText(after.getBytes(StandardCharsets.UTF_8));
                diffs.append("--- ").append(path).append("\n+++ ").append(path).append("\n");
                try (var bytes = new ByteArrayOutputStream(); var formatter = new DiffFormatter(bytes)) {
                    formatter.setContext(3);
                    formatter.format(new HistogramDiff().diff(RawTextComparator.DEFAULT, oldText, newText), oldText, newText);
                    formatter.flush();
                    diffs.append(bytes.toString(StandardCharsets.UTF_8));
                }
            }
        } else {
            // Never claim that a preview is complete when a participant adds an unsupported change.
            throw new IllegalArgumentException("Cannot describe refactoring change: " + change.getName());
        }
    }
}
