package eclipsectlmcp.mcp.services;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.function.Consumer;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.JavaCore;

/** Checks disk contents as well as Eclipse buffers; resource timestamps alone miss external edits. */
record RefactoringSourceSnapshot(IFile file, long stamp, String location, String charset, byte[] digest,
        String source) {
    static RefactoringSourceSnapshot capture(IFile file, Consumer<IFile> cleanEditors) throws Exception {
        cleanEditors.accept(file);
        String charset = file.getCharset();
        byte[] bytes;
        try (var input = file.getContents(true)) {
            bytes = input.readAllBytes();
        }
        String source = new String(bytes, Charset.forName(charset));
        if (source.startsWith("\uFEFF")) source = source.substring(1);
        requireSavedBuffers(file, source);
        return new RefactoringSourceSnapshot(file, file.getModificationStamp(),
                String.valueOf(file.getLocationURI()), charset, MessageDigest.getInstance("SHA-256").digest(bytes), source);
    }

    void validate(Consumer<IFile> cleanEditors) throws Exception {
        if (!file.exists() || !file.isAccessible()) throw changed();
        var current = capture(file, cleanEditors);
        if (stamp != current.stamp || !location.equals(current.location) || !charset.equals(current.charset)
                || !Arrays.equals(digest, current.digest) || !source.equals(current.source)) throw changed();
    }

    private IllegalStateException changed() {
        return new IllegalStateException("Preview is stale: " + file.getFullPath()
                + " changed since preview. No files were changed; create a new preview.");
    }

    private static void requireSavedBuffers(IFile file, String source) throws Exception {
        if (JavaCore.create(file) instanceof ICompilationUnit unit) {
            if (unit.hasUnsavedChanges())
                throw new IllegalStateException("File " + file.getFullPath()
                        + " has unsaved Java working-copy changes. Save or discard them, then preview again.");
            if (!source.equals(unit.getSource())) throw inconsistent(file);
        }
        var buffer = FileBuffers.getTextFileBufferManager().getTextFileBuffer(file.getFullPath(), LocationKind.IFILE);
        if (buffer != null && !source.equals(buffer.getDocument().get())) throw inconsistent(file);
    }

    private static IllegalStateException inconsistent(IFile file) {
        return new IllegalStateException("Saved source and Eclipse buffer differ for " + file.getFullPath()
                + ". Refresh the file and create a new preview. No files were changed.");
    }
}
