package eclipsectlmcp.tests.mcp.services;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import eclipsectlmcp.mcp.services.LineTextEdits;

public class LineTextEditsTest {
    @Test public void stringReplacementPreservesMixedDelimitersAndEof() {
        assertEquals("a\r\nB\nc\rd", new LineTextEdits("a\r\nb\nc\rd").replaceString("b", "B", null, null));
        assertEquals("a\r\nB\r\n", new LineTextEdits("a\r\nb\r\n").replaceString("b", "B", 2, 2));
    }
    @Test public void replaceAndDeletePreserveBoundaries() {
        assertEquals("a\r\nB\nc", new LineTextEdits("a\r\nb\nc").replaceLines("B", 2, 2));
        assertEquals("a\r\nc", new LineTextEdits("a\r\nb\nc").replaceLines("", 2, 2));
        assertEquals("a", new LineTextEdits("a\r\nb").replaceLines("", 2, 2));
        assertEquals("a\r\n", new LineTextEdits("a\r\nb\r\n").replaceLines("", 2, 2));
        assertEquals("B", new LineTextEdits("a").replaceLines("B", 1, 1));
    }
    @Test public void insertionPreservesCrlfAndFinalNewlineState() {
        assertEquals("a\r\nx\r\nb", new LineTextEdits("a\r\nb").insert("x", 2));
        assertEquals("a\r\nb\r\nx", new LineTextEdits("a\r\nb").insert("x", 3));
        assertEquals("a\r\nx\r\n", new LineTextEdits("a\r\n").insert("x", 2));
        assertEquals("x", new LineTextEdits("").insert("x", 1));
        assertEquals("a", new LineTextEdits("a").insert("", 1));
    }
    @Test public void rangesAreStrictlyOneBasedAndInclusive() {
        var edit = new LineTextEdits("a\nb");
        assertThrows(IllegalArgumentException.class, () -> edit.replaceLines("x", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> edit.replaceLines("x", 1, 3));
        assertThrows(IllegalArgumentException.class, () -> edit.replaceLines("x", 2, 1));
        assertThrows(IllegalArgumentException.class, () -> edit.replaceString("a", "x", -1, null));
        assertThrows(IllegalArgumentException.class, () -> edit.insert("x", 0));
        assertThrows(IllegalArgumentException.class, () -> edit.insert("x", 4));
        assertThrows(IllegalArgumentException.class, () -> edit.replaceString("", "x", null, null));
    }
}
