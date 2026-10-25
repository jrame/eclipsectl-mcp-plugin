package eclipsectlmcp.tests.mcp.services;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.eclipse.ltk.core.refactoring.NullChange;
import org.junit.jupiter.api.Test;

import eclipsectlmcp.mcp.services.RefactoringChangeReport;
import eclipsectlmcp.mcp.services.RefactoringPreviewStore;

public class RefactoringPreviewStoreTest {
    private static class TrackedChange extends NullChange {
        int disposals;
        TrackedChange() { super("test preview"); }
        @Override public void dispose() { disposals++; super.dispose(); }
    }

    private String add(RefactoringPreviewStore store, TrackedChange change, String report) {
        return store.add(change, "test", report, List.of(), new RefactoringChangeReport.Report(report, List.of()));
    }

    @Test void hiddenDiffRequiresEveryPageBeforeApplyAndCanBeReadWithoutLoss() {
        var store = new RefactoringPreviewStore();
        var change = new TrackedChange();
        String report = "a".repeat(RefactoringPreviewStore.PAGE_CHARS) + "b".repeat(RefactoringPreviewStore.PAGE_CHARS) + "tail";
        String id = add(store, change, report);
        String first = store.read(id, 0);
        assertTrue(first.contains("offset=100000"));
        assertThrows(IllegalStateException.class, () -> store.consume(id));
        assertTrue(store.read(id, 200000).contains("tail"));
        assertThrows(IllegalStateException.class, () -> store.consume(id), "Skipping a report page must not authorize hidden edits.");
        String second = store.read(id, 100000);
        assertTrue(second.contains("b".repeat(RefactoringPreviewStore.PAGE_CHARS)));
        assertThrows(IllegalStateException.class, () -> store.consume(id));
        assertTrue(store.read(id, 200000).contains("End of preview"));
        assertSame(change, store.consume(id).change());
        assertThrows(IllegalArgumentException.class, () -> store.consume(id));
        change.dispose();
        assertEquals(1, change.disposals);
    }

    @Test void expiryAndBoundedEvictionDisposeRetainedChanges() {
        var clock = new Clock() {
            long millis;
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
            @Override public long millis() { return millis; }
        };
        var store = new RefactoringPreviewStore(clock);
        var first = new TrackedChange();
        String evicted = add(store, first, "report");
        for (int i = 0; i < 20; i++) add(store, new TrackedChange(), "report");
        assertEquals(1, first.disposals);
        assertThrows(IllegalArgumentException.class, () -> store.read(evicted, 0));
        var last = new TrackedChange();
        String expired = add(store, last, "report");
        clock.millis = 15 * 60 * 1000;
        assertThrows(IllegalArgumentException.class, () -> store.consume(expired));
        assertEquals(1, last.disposals);
        store.clear();
        assertEquals(1, last.disposals);
    }
}
