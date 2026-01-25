package eclipsectlmcp.mcp.services;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.search.core.text.TextSearchEngine;
import org.eclipse.search.core.text.TextSearchMatchAccess;
import org.eclipse.search.core.text.TextSearchRequestor;
import org.eclipse.search.core.text.TextSearchScope;
import org.eclipse.search.ui.text.FileTextSearchScope;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;

public class EclipseEditService {
	private static ILog logger = Platform.getLog(EclipseEditService.class);

   /**
     * Search and replace text in multiple files within the Eclipse workspace.
     * 
     * @param remove           Text to search/remove (null for insert-only mode)
     * @param removeRegex      If true, 'remove' is interpreted as a regex pattern
     * @param add              Text to insert as replacement (null for search-only mode)
     * @param addRegex         If true, supports backreferences ($1, $2...) in replacement
     * @param fileNamePatterns Comma-separated file patterns (e.g., "*.java,*.xml")
     * @param lineColumn       Selection range in format "startLine:startCol-endLine:endCol" (1-based)
     * @param replaceAll       If true, replace all occurrences; otherwise only first per file
     * @return Summary of matches found or replacements made
     */
    public static String edit(String remove, Boolean removeRegex, String add, Boolean addRegex,
            String fileNamePatterns, String lineColumn, Boolean replaceAll) {
        
        // Default values
        String searchText = remove != null ? remove : "";
        String[] patterns = parseFilePatterns(fileNamePatterns);
        boolean isRegex = removeRegex != null && removeRegex;
        boolean doReplaceAll = replaceAll != null && replaceAll;
        boolean searchOnly = (add == null);
        
        // Parse line:column selection range
        SelectionRange selection = parseLineColumn(lineColumn);
        
        // Nothing to do if both search and replace texts are empty
        if (searchText.isEmpty() && add == null) {
            return "Nothing to do: both remove and add are empty/null";
        }
        
        // Insert-only mode: add text at selection position
        if (searchText.isEmpty() && add != null) {
            if (selection == null) {
                return "Insert mode requires 'line_column' parameter to specify insertion point";
            }
            return insertAtPosition(patterns, selection, add);
        }
        
        List<MatchInfo> matches = new ArrayList<>();
        
        try {
            // Create search scope from all open projects
            IResource[] roots = getSearchRoots();
            TextSearchScope scope = FileTextSearchScope.newSearchScope(roots, patterns, false);
            
            // Use Eclipse search engine to find matches
            Pattern searchPattern = createSearchPattern(searchText, isRegex);
            
            TextSearchRequestor requestor = new TextSearchRequestor() {
                @Override
                public boolean acceptPatternMatch(TextSearchMatchAccess matchAccess) throws CoreException {
                    IFile file = matchAccess.getFile();
                    int matchOffset = matchAccess.getMatchOffset();
                    int matchLength = matchAccess.getMatchLength();
                    
                    // Get file content
                    String fileContent = matchAccess.getFileContent(0, matchAccess.getFileContentLength());
                    
                    // Calculate line and column of match
                    int[] lineCol = getLineAndColumn(fileContent, matchOffset);
                    int matchLine = lineCol[0];
                    int matchCol = lineCol[1];
                    
                    // Check selection constraints if specified
                    if (selection != null) {
                        if (!isWithinSelection(matchLine, matchCol, matchOffset + matchLength, fileContent, selection)) {
                            return true; // Continue searching, this match is outside selection
                        }
                    }
                    
                    String matchedText = fileContent.substring(matchOffset, matchOffset + matchLength);
                    String lineContext = getLineContext(fileContent, matchOffset);
                    
                    matches.add(new MatchInfo(file, matchOffset, matchLength, matchedText, matchLine, matchCol, lineContext));
                    
                    // Stop after first match per file if not replacing all (and not in search mode)
                    if (!doReplaceAll && !searchOnly) {
                        return false;
                    }
                    return true;
                }
            };
            
            // Execute search
            TextSearchEngine.create().search(scope, requestor, searchPattern, new NullProgressMonitor());
            
            // Search-only mode: return matches without modifying files
            if (searchOnly) {
                return formatSearchResults(matches);
            }
            
            // Perform replacements
            return performReplacements(matches, searchText, add, isRegex, addRegex);
            
        } catch (Exception e) {
            return "Error during operation: " + e.getMessage();
        }
    }
    
    /**
     * Selection range parsed from line_column parameter
     */
    private static class SelectionRange {
        final int startLine;   // 1-based
        final int startColumn; // 0-based
        final int endLine;     // 1-based
        final int endColumn;   // 0-based
        
        SelectionRange(int startLine, int startColumn, int endLine, int endColumn) {
            this.startLine = startLine;
            this.startColumn = startColumn;
            this.endLine = endLine;
            this.endColumn = endColumn;
        }
    }
    
    /**
     * Parse line_column parameter in format "startLine:startCol-endLine:endCol"
     * Example: "61:18-71:10" or "10:0-11:0"
     */
    private static SelectionRange parseLineColumn(String lineColumn) {
        if (lineColumn == null || lineColumn.trim().isEmpty()) {
            return null;
        }
        
        try {
            // Format: startLine:startCol-endLine:endCol
            String[] parts = lineColumn.split("-");
            if (parts.length != 2) {
                return null;
            }
            
            String[] start = parts[0].split(":");
            String[] end = parts[1].split(":");
            
            if (start.length != 2 || end.length != 2) {
                return null;
            }
            
            return new SelectionRange(
                    Integer.parseInt(start[0].trim()),
                    Integer.parseInt(start[1].trim()),
                    Integer.parseInt(end[0].trim()),
                    Integer.parseInt(end[1].trim())
            );
        } catch (NumberFormatException e) {
            return null;
        }
    }
    
    /**
     * Check if a match is within the selection range
     */
    private static boolean isWithinSelection(int matchLine, int matchCol, int matchEndOffset, 
            String content, SelectionRange selection) {
        
        // Calculate end line and column of match
        int[] endLineCol = getLineAndColumn(content, matchEndOffset - 1);
        int matchEndLine = endLineCol[0];
        int matchEndCol = endLineCol[1] + 1;
        
        // Check if match start is at or after selection start
        boolean afterStart = (matchLine > selection.startLine) || 
                (matchLine == selection.startLine && matchCol >= selection.startColumn);
        
        // Check if match end is at or before selection end
        boolean beforeEnd = (matchEndLine < selection.endLine) || 
                (matchEndLine == selection.endLine && matchEndCol <= selection.endColumn);
        
        return afterStart && beforeEnd;
    }
    
    /**
     * Insert text at the specified position
     */
    private static String insertAtPosition(String[] patterns, SelectionRange selection, String textToAdd) {
        StringBuilder result = new StringBuilder();
        int filesModified = 0;
        
        try {
            for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
                if (project.isOpen()) {
                    filesModified += insertInProject(project, patterns, selection, textToAdd, result);
                }
            }
            
            if (filesModified == 0) {
                return "No matching files found";
            }
            
            return String.format("Inserted text in %d file(s):%n%s", filesModified, result.toString());
            
        } catch (Exception e) {
            return "Error during insert: " + e.getMessage();
        }
    }
    
    /**
     * Insert text in all matching files within a project
     */
    private static int insertInProject(IResource resource, String[] patterns, 
            SelectionRange selection, String textToAdd, StringBuilder result) throws CoreException {
        
        int count = 0;
        
        if (resource instanceof IFile) {
            IFile file = (IFile) resource;
            if (matchesPattern(file.getName(), patterns)) {
                String content = readFile(file);
                int offset = getOffsetFromLineColumn(content, selection.startLine, selection.startColumn);
                
                if (offset >= 0 && offset <= content.length()) {
                    // If selection has a range, delete the selected text first
                    int endOffset = getOffsetFromLineColumn(content, selection.endLine, selection.endColumn);
                    
                    String newContent;
                    if (endOffset > offset) {
                        // Replace selection with new text
                        newContent = content.substring(0, offset) + textToAdd + content.substring(endOffset);
                    } else {
                        // Just insert at position
                        newContent = content.substring(0, offset) + textToAdd + content.substring(offset);
                    }
                    
                    saveFile(file, newContent);
                    result.append(String.format("  %s at %d:%d%n", file.getFullPath(), 
                            selection.startLine, selection.startColumn));
                    count++;
                }
            }
        } else if (resource instanceof IContainer) {
            for (IResource child : ((IContainer) resource).members()) {
                count += insertInProject(child, patterns, selection, textToAdd, result);
            }
        }
        
        return count;
    }
    
    /**
     * Get character offset from line and column (1-based line, 0-based column)
     */
    private static int getOffsetFromLineColumn(String content, int line, int column) {
        int currentLine = 1;
        int offset = 0;
        
        while (offset < content.length() && currentLine < line) {
            if (content.charAt(offset) == '\n') {
                currentLine++;
            }
            offset++;
        }
        
        // Add column offset
        return Math.min(offset + column, content.length());
    }
    
    /**
     * Get line (1-based) and column (0-based) for a given offset
     */
    private static int[] getLineAndColumn(String content, int offset) {
        int line = 1;
        int lastNewline = -1;
        
        for (int i = 0; i < offset && i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                line++;
                lastNewline = i;
            }
        }
        
        int column = offset - lastNewline - 1;
        return new int[] { line, column };
    }
    
    /**
     * Format search results for display
     */
    private static String formatSearchResults(List<MatchInfo> matches) {
        if (matches.isEmpty()) {
            return "No matches found";
        }
        
        StringBuilder result = new StringBuilder();
        result.append(String.format("Found %d match(es):%n%n", matches.size()));
        
        String lastFilePath = null;
        for (MatchInfo info : matches) {
            String filePath = info.file.getFullPath().toString();
            
            // Print file header when file changes
            if (!filePath.equals(lastFilePath)) {
                if (lastFilePath != null) {
                    result.append("\n");
                }
                result.append(String.format("=== %s ===%n", filePath));
                lastFilePath = filePath;
            }
            
            // Print match details with line:column
            result.append(String.format("  %d:%d - %s%n", 
                    info.line, 
                    info.column,
                    truncate(info.lineContext.trim(), 100)));
        }
        
        return result.toString();
    }
    
    /**
     * Perform replacements on collected matches
     */
    private static String performReplacements(List<MatchInfo> matches, String searchText, 
            String replaceText, boolean isRegex, Boolean addRegex) throws CoreException {
        
        if (matches.isEmpty()) {
            return "No matches found";
        }
        
        StringBuilder result = new StringBuilder();
        int filesModified = 0;
        int totalReplacements = 0;
        
        // Sort matches by file path and then by offset (descending to avoid offset shifting)
        matches.sort((a, b) -> {
            int fileCompare = a.file.getFullPath().toString().compareTo(b.file.getFullPath().toString());
            if (fileCompare != 0) return fileCompare;
            return Integer.compare(b.offset, a.offset);
        });
        
        IFile currentFile = null;
        String currentContent = null;
        
        for (MatchInfo info : matches) {
            // Save previous file and load new one when file changes
            if (currentFile == null || !currentFile.equals(info.file)) {
                if (currentFile != null && currentContent != null) {
                    saveFile(currentFile, currentContent);
                    filesModified++;
                }
                currentFile = info.file;
                currentContent = readFile(currentFile);
            }
            
            // Calculate replacement text (support regex backreferences if enabled)
            String replacement = replaceText;
            if (addRegex != null && addRegex && isRegex) {
                Pattern p = createSearchPattern(searchText, true);
                Matcher m = p.matcher(info.matchedText);
                if (m.matches()) {
                    replacement = m.replaceFirst(replaceText);
                }
            }
            
            // Apply replacement
            currentContent = currentContent.substring(0, info.offset) 
                    + replacement 
                    + currentContent.substring(info.offset + info.length);
            totalReplacements++;
            
            result.append(String.format("  %s (%d:%d): '%s' -> '%s'%n", 
                    info.file.getFullPath(), 
                    info.line,
                    info.column,
                    truncate(info.matchedText, 50), 
                    truncate(replacement, 50)));
        }
        
        // Save the last file
        if (currentFile != null && currentContent != null) {
            saveFile(currentFile, currentContent);
            filesModified++;
        }
        
        return String.format("Replaced %d occurrence(s) in %d file(s):%n%s", 
                totalReplacements, filesModified, result.toString());
    }
    
    /**
     * Parse comma-separated file patterns
     */
    private static String[] parseFilePatterns(String fileNamePatterns) {
        if (fileNamePatterns == null || fileNamePatterns.trim().isEmpty()) {
            return new String[] { "*" };
        }
        String[] patterns = fileNamePatterns.split(",");
        for (int i = 0; i < patterns.length; i++) {
            patterns[i] = patterns[i].trim();
        }
        return patterns;
    }
    
    /**
     * Get all open projects as search roots
     */
    private static IResource[] getSearchRoots() {
        List<IResource> roots = new ArrayList<>();
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (project.isOpen()) {
                roots.add(project);
            }
        }
        return roots.toArray(new IResource[0]);
    }
    
    /**
     * Create search pattern from text
     */
    private static Pattern createSearchPattern(String searchText, boolean isRegex) {
        if (isRegex) {
            return Pattern.compile(searchText, Pattern.MULTILINE);
        } else {
            return Pattern.compile(Pattern.quote(searchText));
        }
    }
    
    /**
     * Get the full line containing the match for context display
     */
    private static String getLineContext(String content, int offset) {
        int lineStart = offset;
        while (lineStart > 0 && content.charAt(lineStart - 1) != '\n') {
            lineStart--;
        }
        
        int lineEnd = offset;
        while (lineEnd < content.length() && content.charAt(lineEnd) != '\n') {
            lineEnd++;
        }
        
        return content.substring(lineStart, lineEnd);
    }
    
    /**
     * Read file content as string
     */
    private static String readFile(IFile file) throws CoreException {
        try (var stream = file.getContents()) {
            String charset = file.getCharset() != null ? file.getCharset() : StandardCharsets.UTF_8.name();
            return new String(stream.readAllBytes(), charset);
        } catch (Exception e) {
            throw new CoreException(null);
        }
    }
    
    /**
     * Save content to file
     */
    private static void saveFile(IFile file, String content) throws CoreException {
        try {
            String charset = file.getCharset() != null ? file.getCharset() : StandardCharsets.UTF_8.name();
            ByteArrayInputStream stream = new ByteArrayInputStream(content.getBytes(charset));
            file.setContents(stream, IResource.FORCE | IResource.KEEP_HISTORY, new NullProgressMonitor());
        } catch (Exception e) {
            throw new CoreException(null);
        }
    }
    
    /**
     * Check if filename matches any of the glob patterns
     */
    private static boolean matchesPattern(String fileName, String[] patterns) {
        for (String pattern : patterns) {
            if (matchesGlob(fileName, pattern)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Simple glob pattern matching
     */
    private static boolean matchesGlob(String text, String glob) {
        String regex = glob.replace(".", "\\.").replace("*", ".*").replace("?", ".");
        return text.matches(regex);
    }
    
    /**
     * Truncate and escape string for display
     */
    private static String truncate(String text, int maxLength) {
        if (text == null) return "";
        String escaped = text.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        if (escaped.length() <= maxLength) return escaped;
        return escaped.substring(0, maxLength - 3) + "...";
    }
    
    /**
     * Internal class to store match information
     */
    private static class MatchInfo {
        final IFile file;
        final int offset;
        final int length;
        final String matchedText;
        final int line;      // 1-based
        final int column;    // 0-based
        final String lineContext;
        
        MatchInfo(IFile file, int offset, int length, String matchedText, int line, int column, String lineContext) {
            this.file = file;
            this.offset = offset;
            this.length = length;
            this.matchedText = matchedText;
            this.line = line;
            this.column = column;
            this.lineContext = lineContext;
        }
    }
}
