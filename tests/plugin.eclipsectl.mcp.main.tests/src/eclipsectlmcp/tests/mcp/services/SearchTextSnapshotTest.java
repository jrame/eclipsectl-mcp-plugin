package eclipsectlmcp.tests.mcp.services;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import eclipsectlmcp.mcp.services.SearchTextSnapshot;

public class SearchTextSnapshotTest
{
    @Test public void mixedDelimitersAndUtf16Offsets()
    {
        var text = new SearchTextSnapshot("a\r\nb😀\nc\rd");
        int[] lines = {1, 1, 1, 2, 2, 2, 2, 3, 3, 4, 4};
        String[] contents = {"a", "b😀", "c", "d"};
        for (int offset = 0; offset < lines.length; offset++)
        {
            var line = text.lineAt(offset);
            assertEquals(lines[offset], line.lineNumber(), "offset " + offset);
            assertEquals(contents[lines[offset] - 1], line.lineContent());
        }
    }

    @Test public void emptyLinesAndEofMatches()
    {
        assertEquals(new SearchTextSnapshot.LineInfo(1, ""), new SearchTextSnapshot("").lineAt(0));
        var text = new SearchTextSnapshot("\r\n\n\r");
        assertEquals(new SearchTextSnapshot.LineInfo(2, ""), text.lineAt(2));
        assertEquals(new SearchTextSnapshot.LineInfo(3, ""), text.lineAt(3));
        assertEquals(new SearchTextSnapshot.LineInfo(4, ""), text.lineAt(4));
        assertThrows(IllegalArgumentException.class, () -> text.lineAt(-1));
        assertThrows(IllegalArgumentException.class, () -> text.lineAt(5));
    }

    @Test public void concurrentMatchesCaptureOncePerFileAndRequest()
    {
        var captures = new AtomicInteger();
        var cache = new SearchTextSnapshot.Cache<String>();
        IntStream.range(0, 1000).parallel().forEach(i -> {
            var snapshot = cache.get("file", () -> { captures.incrementAndGet(); return "original"; });
            assertEquals("original", snapshot.lineAt(0).lineContent());
        });
        assertEquals(1, captures.get());
        assertEquals("other", cache.get("other", () -> "other").lineAt(0).lineContent());
        var nextRequest = new SearchTextSnapshot.Cache<String>();
        assertEquals("changed", nextRequest.get("file", () -> "changed").lineAt(0).lineContent());
    }
}
