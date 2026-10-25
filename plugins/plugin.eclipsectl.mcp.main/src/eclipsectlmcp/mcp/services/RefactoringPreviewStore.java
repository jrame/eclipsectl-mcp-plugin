package eclipsectlmcp.mcp.services;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import org.eclipse.ltk.core.refactoring.Change;

/** UI-thread-owned, bounded cache. Each token owns one frozen LTK change until consumed or evicted. */
public final class RefactoringPreviewStore {
    public static final int PAGE_CHARS = 100_000;
    private static final int MAX_PREVIEWS = 20;
    private static final Duration LIFETIME = Duration.ofMinutes(15);
    private final Clock clock;
    private final LinkedHashMap<String, Preview> previews = new LinkedHashMap<>();

    public static final class Preview {
        final Change change;
        final String name;
        final String report;
        final List<RefactoringSourceSnapshot> sources;
        final RefactoringChangeReport.Report edits;
        final long expires;
        int readThrough;

        Preview(Change change, String name, String report, List<RefactoringSourceSnapshot> sources,
                RefactoringChangeReport.Report edits, long expires) {
            this.change = change;
            this.name = name;
            this.report = report;
            this.sources = List.copyOf(sources);
            this.edits = edits;
            this.expires = expires;
        }

        public Change change() { return change; }
    }

    public RefactoringPreviewStore() { this(Clock.systemUTC()); }
    public RefactoringPreviewStore(Clock clock) { this.clock = clock; }

    public String add(Change change, String name, String report, List<RefactoringSourceSnapshot> sources,
            RefactoringChangeReport.Report edits) {
        expire();
        while (previews.size() >= MAX_PREVIEWS) remove(previews.firstEntry().getKey());
        String id = UUID.randomUUID().toString();
        previews.put(id, new Preview(change, name, report, sources, edits, clock.millis() + LIFETIME.toMillis()));
        return id;
    }

    public String read(String id, int offset) {
        Preview preview = find(id);
        if (offset < 0 || offset > preview.report.length())
            throw new IllegalArgumentException("Preview offset must be between 0 and " + preview.report.length() + ".");
        int end = Math.min(preview.report.length(), offset + Math.min(PAGE_CHARS, preview.report.length() - offset));
        if (end < preview.report.length() && end > offset && Character.isHighSurrogate(preview.report.charAt(end - 1))) end--;
        if (offset <= preview.readThrough) preview.readThrough = Math.max(preview.readThrough, end);
        String footer = end < preview.report.length()
                ? "\n[Preview continues. Call refactorGetPreview with previewId=" + id + " and offset=" + end + ".]\n"
                : "\n[End of preview.]\n";
        return "Preview ID: " + id + "\nReport characters " + offset + "-" + end + " of " + preview.report.length()
                + " (zero-based).\n" + preview.report.substring(offset, end) + footer;
    }

    public Preview consume(String id) {
        Preview preview = find(id);
        if (preview.readThrough < preview.report.length())
            throw new IllegalStateException("Preview is incomplete. Read the remaining diff with refactorGetPreview, previewId="
                    + id + " and offset=" + preview.readThrough + " before applying. No files were changed.");
        previews.remove(id);
        return preview;
    }

    private Preview find(String id) {
        expire();
        Preview preview = previews.get(id);
        if (preview == null)
            throw new IllegalArgumentException("Unknown, expired, or already used preview ID. Create a new preview; no files were changed.");
        return preview;
    }

    private void expire() {
        for (String id : previews.entrySet().stream().filter(e -> e.getValue().expires <= clock.millis())
                .map(e -> e.getKey()).toList()) remove(id);
    }

    private void remove(String id) {
        previews.remove(id).change.dispose();
    }

    public void clear() {
        for (Preview preview : previews.values()) preview.change.dispose();
        previews.clear();
    }
}
