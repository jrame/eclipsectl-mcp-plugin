package eclipsectlmcp.mcp.services;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.search.core.text.TextSearchEngine;
import org.eclipse.search.core.text.TextSearchMatchAccess;
import org.eclipse.search.core.text.TextSearchRequestor;
import org.eclipse.search.core.text.TextSearchScope;

import eclipsectlmcp.tools.ResourceUtilities;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Workspace file search based on Eclipse's {@link TextSearchEngine}.
 */
@Creatable
@Singleton
public class SearchService
{
    @Inject
    private ILog logger;

    public record SearchResult(IFile file, int lineNumber, String lineContent)
    {
        /**
         * Returns a human-readable format: path:line - content
         * Example: HelloWorld/src/Main.java:42 - public static void main
         */
        @Override
        public String toString()
        {
            String path = file != null ? file.getFullPath().toString() : "<unknown>";
            // Remove leading slash from workspace-relative path
            if (path.startsWith("L/"))
            {
                path = path.substring(2);
            }
            else if (path.startsWith("/"))
            {
                path = path.substring(1);
            }

            return String.format("%s:%d - %s", path, lineNumber, lineContent.trim());
        }
    }

    public record SearchAndReplaceResult(IFile file, int matchesFound, int replacementsMade)
    {
        /**
         * Returns a human-readable format: path - N matches, N replacements
         */
        @Override
        public String toString()
        {
            String path = file != null ? file.getFullPath().toString() : "<unknown>";
            // Remove leading slash from workspace-relative path
            if (path.startsWith("L/"))
            {
                path = path.substring(2);
            }
            else if (path.startsWith("/"))
            {
                path = path.substring(1);
            }

            return String.format("%s - %d matches, %d replacements", path, matchesFound, replacementsMade);
        }
    }

    public SearchService()
    {
    }

    /**
     * Formats search results for human-readable CLI output.
     * Returns empty message if no results, or formatted list with summary.
     */
    public static String formatSearchResults(List<SearchResult> results, String searchPattern)
    {
        if (results == null || results.isEmpty())
        {
            return "No results found for: " + searchPattern;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(results.size()).append(" result");
        if (results.size() != 1)
        {
            sb.append("s");
        }
        sb.append(" for \"").append(searchPattern).append("\":\n\n");

        for (SearchResult result : results)
        {
            sb.append(result.toString()).append("\n");
        }

        return sb.toString();
    }

    /**
     * Formats search and replace results for human-readable CLI output.
     */
    public static String formatReplaceResults(List<SearchAndReplaceResult> results)
    {
        if (results == null || results.isEmpty())
        {
            return "No files modified";
        }

        StringBuilder sb = new StringBuilder();
        int totalMatches = results.stream().mapToInt(SearchAndReplaceResult::matchesFound).sum();
        int totalReplacements = results.stream().mapToInt(SearchAndReplaceResult::replacementsMade).sum();

        sb.append("Modified ").append(results.size()).append(" file");
        if (results.size() != 1)
        {
            sb.append("s");
        }
        sb.append(" (").append(totalMatches).append(" matches, ");
        sb.append(totalReplacements).append(" replacements):\n\n");

        for (SearchAndReplaceResult result : results)
        {
            sb.append(result.toString()).append("\n");
        }

        return sb.toString();
    }

    /**
     * Plain substring search (not regex).
     */
    public List<SearchResult> fileSearch(String containingText, String... fileNamePatterns)
    {
        if (containingText == null || containingText.isBlank())
        {
            throw new IllegalArgumentException("containingText must not be null/blank");
        }

        return search(Pattern.compile(Pattern.quote(containingText)), fileNamePatterns);
    }

    /**
     * Regex search using Java {@link Pattern} syntax.
     */
    public List<SearchResult> fileSearchRegExp(String pattern, String... fileNamePatterns)
    {
        if (pattern == null || pattern.isBlank())
        {
            throw new IllegalArgumentException("pattern must not be null/blank");
        }

        return search(Pattern.compile(pattern), fileNamePatterns);
    }

    /**
     * Search and replace across the workspace using Eclipse's {@link TextSearchEngine}.
     * <p>
     * This method:
     * <ul>
     * <li>searches only in open projects</li>
     * <li>matches by plain substring (not regex)</li>
     * <li>replaces every occurrence in each matched line</li>
     * </ul>
     *
     * @param containingText Plain text to find
     * @param replacementText Replacement (can be empty but not null)
     * @param fileNamePatterns Optional glob patterns like "*.java" (empty => all files)
     */
    public List<SearchAndReplaceResult> searchAndReplace(String containingText, String replacementText,
            String... fileNamePatterns)
    {
        if (containingText == null || containingText.isBlank())
        {
            throw new IllegalArgumentException("containingText must not be null/blank");
        }
        if (replacementText == null)
        {
            throw new IllegalArgumentException("replacementText must not be null");
        }

        Pattern matchPattern = Pattern.compile(Pattern.quote(containingText));

        IResource[] roots = getOpenProjectsAsRoots();
        if (roots.length == 0)
        {
            return List.of();
        }

        Pattern fileNamePattern = ResourceUtilities.globPatternsToRegex(fileNamePatterns);
        TextSearchScope scope = TextSearchScope.newSearchScope(roots, fileNamePattern, true);
        TextSearchEngine engine = TextSearchEngine.createDefault();

        // Collect all matches first: TextSearchRequestor may be called concurrently.
        Map<IFile, List<LineMatch>> matchesByFile = new LinkedHashMap<>();

        TextSearchRequestor requestor = new TextSearchRequestor()
        {
            @Override
            public boolean acceptFile(IFile file) throws CoreException
            {
                return file != null && file.isAccessible();
            }

            @Override
            public boolean acceptPatternMatch(TextSearchMatchAccess matchAccess) throws CoreException
            {
                IFile file = matchAccess.getFile();
                int offset = matchAccess.getMatchOffset();
                int length = matchAccess.getMatchLength();

                LineInfo lineInfo = getLineInfo(file, offset);
                synchronized (matchesByFile)
                {
                    matchesByFile.computeIfAbsent(file, f -> new ArrayList<>())
                            .add(new LineMatch(lineInfo.lineNumber, offset, length));
                }

                return true;
            }
        };

        IProgressMonitor monitor = new NullProgressMonitor();

        try
        {
            engine.search(scope, requestor, matchPattern, monitor);

            if (matchesByFile.isEmpty())
            {
                return List.of();
            }

            List<SearchAndReplaceResult> results = new ArrayList<>();
            for (Map.Entry<IFile, List<LineMatch>> entry : matchesByFile.entrySet())
            {
                IFile file = entry.getKey();

                // ensure deterministic
                int matchesFound = entry.getValue().size();
                int replacements = replaceInFile(file, containingText, replacementText);

                results.add(new SearchAndReplaceResult(file, matchesFound, replacements));
            }

            return results;
        }
        catch (Exception e)
        {
            logger.error(e.getMessage(), e);
            throw new RuntimeException("Error searchAndReplace: " + ExceptionUtils.getRootCauseMessage(e), e);
        }
    }

    private static int replaceInFile(IFile file, String containingText, String replacementText)
            throws CoreException, IOException
    {
        if (file == null || !file.exists())
        {
            return 0;
        }

        Charset charset = Charset.forName(file.getCharset());
        String original = ResourceUtilities.readFileContent(file);

        int replacements = countOccurrences(original, containingText);
        if (replacements == 0)
        {
            return 0;
        }

        String modified = original.replace(containingText, replacementText);
        byte[] bytes = modified.getBytes(charset);
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes))
        {
            file.setContents(in, IResource.FORCE, null);
        }

        file.refreshLocal(IResource.DEPTH_ZERO, null);
        return replacements;
    }

    private static int countOccurrences(String text, String needle)
    {
        if (text == null || text.isEmpty() || needle == null || needle.isEmpty())
        {
            return 0;
        }

        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0)
        {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private record LineMatch(int lineNumber, int offset, int length)
    {

    }

    private List<SearchResult> search(Pattern pattern, String... fileNamePatterns)
    {
        Objects.requireNonNull(pattern, "pattern");

        IResource[] roots = getOpenProjectsAsRoots();
        if (roots.length == 0)
        {
            return List.of();
        }

        // Use core TextSearchScope to avoid UI bundle dependency.
        // In this Eclipse version TextSearchScope.newSearchScope expects a regex Pattern.
        Pattern fileNamePattern = ResourceUtilities.globPatternsToRegex(fileNamePatterns);
        TextSearchScope scope = TextSearchScope.newSearchScope(roots, fileNamePattern, true);
        TextSearchEngine engine = TextSearchEngine.createDefault();

        List<SearchResult> results = new ArrayList<>();

        TextSearchRequestor requestor = new TextSearchRequestor()
        {
            @Override
            public boolean acceptFile(IFile file) throws CoreException
            {
                return file != null && file.isAccessible();
            }

            @Override
            public boolean acceptPatternMatch(TextSearchMatchAccess matchAccess) throws CoreException
            {
                IFile file = matchAccess.getFile();

                // Convert offset -> line number/content by reading the file.
                int matchOffset = matchAccess.getMatchOffset();

                LineInfo lineInfo = getLineInfo(file, matchOffset);
                results.add(new SearchResult(file, lineInfo.lineNumber, lineInfo.lineContent));
                return true;
            }
        };

        IProgressMonitor monitor = new NullProgressMonitor();

        try
        {
            engine.search(scope, requestor, pattern, monitor);
            return results;
        }
        catch (Exception e)
        {
            logger.error(e.getMessage(), e);
            throw new RuntimeException("Error searching files: " + ExceptionUtils.getRootCauseMessage(e), e);
        }
    }

    private record LineInfo(int lineNumber, String lineContent)
    {

    }

    private static LineInfo getLineInfo(IFile file, int offset)
    {
        if (file == null)
        {
            return new LineInfo(-1, "");
        }

        try
        {
            List<String> lines = ResourceUtilities.readFileLines(file);

            // Compute line number from offset by walking through lines and accounting for '\n'.
            int charCount = 0;
            for (int i = 0; i < lines.size(); i++)
            {
                String line = lines.get(i);
                int nextCharCount = charCount + line.length() + 1;
                if (offset < nextCharCount)
                {
                    return new LineInfo(i + 1, line);
                }
                charCount = nextCharCount;
            }

            return new LineInfo(-1, "");
        }
        catch (CoreException | IOException e)
        {
            return new LineInfo(-1, "");
        }
    }

    private static IResource[] getOpenProjectsAsRoots()
    {
        IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
        List<IResource> roots = new ArrayList<>();
        for (IProject project : projects)
        {
            if (project != null && project.exists() && project.isOpen())
            {
                roots.add(project);
            }
        }
        return roots.toArray(IResource[]::new);
    }
}
