package eclipsectlmcp.mcp.services;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Immutable UTF-16 text and line index captured from the search engine. */
public final class SearchTextSnapshot
{
    private final String content;
    private final int[] starts;

    public SearchTextSnapshot(String content)
    {
        this.content = Objects.requireNonNull(content, "content");
        var offsets = new ArrayList<Integer>();
        offsets.add(0);
        for (int i = 0; i < content.length(); i++)
        {
            char c = content.charAt(i);
            if (c == '\r' || c == '\n')
            {
                if (c == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n')
                    i++;
                offsets.add(i + 1);
            }
        }
        starts = offsets.stream().mapToInt(Integer::intValue).toArray();
    }

    public record LineInfo(int lineNumber, String lineContent) {}

    /** EOF is valid for zero-length regex matches, including after a final delimiter. */
    public LineInfo lineAt(int offset)
    {
        if (offset < 0 || offset > content.length())
            throw new IllegalArgumentException("Search offset outside captured text: " + offset);
        int line = Arrays.binarySearch(starts, offset);
        if (line < 0)
            line = -line - 2;
        int end = line + 1 < starts.length ? starts[line + 1] : content.length();
        if (end > starts[line] && content.charAt(end - 1) == '\n')
            end--;
        if (end > starts[line] && content.charAt(end - 1) == '\r')
            end--;
        return new LineInfo(line + 1, content.substring(starts[line], end));
    }

    /** One cache per search request; the supplier must run inside the match callback. */
    public static final class Cache<K>
    {
        private final Map<K, SearchTextSnapshot> snapshots = new HashMap<>();

        public synchronized SearchTextSnapshot get(K file, Supplier<String> content)
        {
            return snapshots.computeIfAbsent(file, ignored -> new SearchTextSnapshot(content.get()));
        }
    }
}
