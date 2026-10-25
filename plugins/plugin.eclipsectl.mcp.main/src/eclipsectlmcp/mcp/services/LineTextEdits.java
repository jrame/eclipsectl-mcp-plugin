package eclipsectlmcp.mcp.services;

import java.util.ArrayList;
import java.util.List;

/** Offset based line edits: untouched text and its delimiters are never reconstructed. */
public final class LineTextEdits {
    private final String text;
    private final List<Integer> starts = new ArrayList<>();
    private final List<String> delimiters = new ArrayList<>();

    public LineTextEdits(String text) {
        this.text = text;
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r' || c == '\n') {
                String delimiter = String.valueOf(c);
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    delimiter = "\r\n";
                    i++;
                }
                delimiters.add(delimiter);
                if (i + 1 < text.length()) starts.add(i + 1);
            }
        }
    }

    public int lineCount() { return starts.size(); }

    private void range(int first, int last) {
        if (first < 1 || last < first || last > lineCount()) {
            throw new IllegalArgumentException("Error: Invalid line range " + first + "-" + last
                    + ". File has " + lineCount() + " lines (1-based, inclusive).");
        }
    }

    private int after(int line) { return line == lineCount() ? text.length() : starts.get(line); }
    private boolean terminated(String value) { return value.endsWith("\n") || value.endsWith("\r"); }
    private String delimiter(int line) {
        return delimiters.isEmpty() ? "\n" : delimiters.get(Math.min(line - 1, delimiters.size() - 1));
    }

    public String replaceString(String oldText, String newText, Integer first, Integer last) {
        if (oldText.isEmpty()) throw new IllegalArgumentException("Error: Search string cannot be empty.");
        int start = first == null ? 1 : first;
        int end = last == null ? lineCount() : last;
        range(start, end);
        int from = starts.get(start - 1), to = after(end);
        String selected = text.substring(from, to);
        if (!selected.contains(oldText)) throw new IllegalArgumentException("Error: The specified string was not found in the file within the selected lines.");
        return text.substring(0, from) + selected.replace(oldText, newText) + text.substring(to);
    }

    public String replaceLines(String replacement, int first, int last) {
        range(first, last);
        int from = starts.get(first - 1), to = after(last);
        String value = replacement;
        if (!value.isEmpty() && !terminated(value) && (last < lineCount() || terminated(text))) {
            value += delimiter(last);
        }
        String result = text.substring(0, from) + value + text.substring(to);
        // Deleting the final unterminated line must not introduce a final newline.
        if (value.isEmpty() && last == lineCount() && !terminated(text) && terminated(result)) {
            result = result.substring(0, result.length() - delimiter(first - 1).length());
        }
        return result;
    }

    public String insert(String content, int atLine) {
        if (atLine < 1 || atLine > lineCount() + 1) {
            throw new IllegalArgumentException("Error: Invalid insertion line " + atLine + ". Expected 1 to " + (lineCount() + 1) + ".");
        }
        if (content.isEmpty()) return text;
        if (atLine <= lineCount()) {
            int offset = starts.get(atLine - 1);
            String value = content;
            if (!terminated(value) && (offset < text.length() || terminated(text))) value += delimiter(atLine);
            return text.substring(0, offset) + value + text.substring(offset);
        }
        String separator = delimiter(lineCount());
        String prefix = text.isEmpty() || terminated(text) ? text : text + separator;
        return prefix + content + (terminated(text) && !terminated(content) ? separator : "");
    }
}
