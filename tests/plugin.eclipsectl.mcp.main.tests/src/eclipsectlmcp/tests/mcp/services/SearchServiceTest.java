package eclipsectlmcp.tests.mcp.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.e4.core.contexts.EclipseContextFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.JavaRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.util.tracker.ServiceTracker;

import eclipsectlmcp.Activator;
import eclipsectlmcp.mcp.services.SearchService;
import eclipsectlmcp.mcp.services.SearchService.SearchAndReplaceResult;
import eclipsectlmcp.mcp.services.SearchService.SearchResult;

/**
 * Tests for SearchService including output formatting.
 */
public class SearchServiceTest
{
    private static final String TEST_PROJECT_NAME = "SearchTestProject";
    private IProject project;
    private IJavaProject javaProject;
    private NullProgressMonitor monitor = new NullProgressMonitor();
    private SearchService service;

    @BeforeEach
    public void beforeEach() throws Exception
    {
        // Get workspace through OSGi service tracker
        BundleContext bundleContext = FrameworkUtil.getBundle(SearchServiceTest.class).getBundleContext();
        ServiceTracker<IWorkspace, IWorkspace> workspaceTracker = new ServiceTracker<>(bundleContext,
                IWorkspace.class, null);

        workspaceTracker.open();
        IWorkspace workspace = workspaceTracker.getService();
        IWorkspaceRoot root = workspace.getRoot();

        // Delete the project if it exists
        project = root.getProject(TEST_PROJECT_NAME);
        if (project.exists())
        {
            project.delete(true, true, monitor);
        }

        // Create a test project
        project = root.getProject(TEST_PROJECT_NAME);
        IProjectDescription desc = project.getWorkspace().newProjectDescription(project.getName());
        desc.setNatureIds(new String[] { JavaCore.NATURE_ID }); // set Java nature
        project.create(desc, monitor);
        project.open(monitor);

        // Set up Java project
        javaProject = JavaCore.create(project);

        // Create output folder (bin)
        IFolder binFolder = project.getFolder("bin");
        if (!binFolder.exists())
        {
            binFolder.create(true, true, monitor);
        }

        // Set output location
        javaProject.setOutputLocation(binFolder.getFullPath(), monitor);

        // Create source folder
        IFolder srcFolder = project.getFolder("src");
        if (!srcFolder.exists())
        {
            srcFolder.create(IResource.NONE, true, monitor);
        }

        // Set classpath with source folder and JRE
        javaProject.setRawClasspath(new org.eclipse.jdt.core.IClasspathEntry[] {
                JavaCore.newSourceEntry(project.getFullPath().append("src")),
                JavaRuntime.getDefaultJREContainerEntry() }, monitor);

        // Create test files
        createTestFiles();

        // Force a full build of the project
        project.build(IncrementalProjectBuilder.FULL_BUILD, monitor);

        // Wait a moment for the build to complete
        Thread.sleep(1000);

        // Initialize service with DI context
        IEclipseContext context = EclipseContextFactory.create();
        context.set(ILog.class, Activator.getDefault().getLog());
        service = ContextInjectionFactory.make(SearchService.class, context);
    }

    @AfterEach
    public void afterEach() throws Exception
    {
        // Clean up the test project
        if (project != null && project.exists())
        {
            // Refresh the project to ensure all resources are synchronized
            project.refreshLocal(IResource.DEPTH_INFINITE, monitor);

            // Wait a bit to ensure all streams are released
            Thread.sleep(500);

            // Force delete with both flags set to true
            project.delete(IResource.FORCE | IResource.ALWAYS_DELETE_PROJECT_CONTENT, monitor);
        }
    }

    private void createTestFiles() throws CoreException
    {
        // Create package structure
        IFolder comFolder = project.getFolder("src/com");
        if (!comFolder.exists())
        {
            comFolder.create(IResource.NONE, true, monitor);
        }

        IFolder exampleFolder = project.getFolder("src/com/example");
        if (!exampleFolder.exists())
        {
            exampleFolder.create(IResource.NONE, true, monitor);
        }

        // Create test file with javadoc comments
        String testClass = "package com.example;\n\n" + "/**\n" + " * This is a test class with javadoc.\n"
                + " */\n" + "public class TestClass {\n" + "    /**\n" + "     * This method has javadoc too.\n"
                + "     */\n" + "    public void testMethod() {\n" + "        System.out.println(\"test\");\n"
                + "    }\n" + "}\n";

        createFile("src/com/example/TestClass.java", testClass);

        // Create another test file
        String utils = "package com.example;\n\n" + "public class Utils {\n" + "    // Helper method\n"
                + "    public static void helper() {\n" + "        // TODO: implement\n" + "    }\n" + "}\n";

        createFile("src/com/example/Utils.java", utils);
    }

    private IFile createFile(String path, String content) throws CoreException
    {
        IFile file = project.getFile(new Path(path));
        ByteArrayInputStream source = new ByteArrayInputStream(content.getBytes());

        if (file.exists())
        {
            file.setContents(source, true, true, monitor);
        }
        else
        {
            file.create(source, true, monitor);
        }

        return file;
    }

    @Test
    public void testSearchResultToString() throws CoreException
    {
        // Create a test file
        IFile testFile = project.getFile("src/test.txt");
        String content = "Line 1: test content\nLine 2: more content\nLine 3: javadoc here";
        testFile.create(new ByteArrayInputStream(content.getBytes()), true, monitor);

        // Create SearchResult
        SearchResult result = new SearchResult(testFile, 3, "Line 3: javadoc here");

        // Test toString format
        String formatted = result.toString();

        // Should be: SearchTestProject/src/test.txt:3 - Line 3: javadoc here
        assertTrue(formatted.contains("SearchTestProject/src/test.txt:3"),
                "Should contain path:line. Got: " + formatted);
        assertTrue(formatted.contains("Line 3: javadoc here"), "Should contain line content. Got: " + formatted);
        assertFalse(formatted.contains("SearchResult["), "Should NOT contain Java toString format. Got: " + formatted);
    }

    @Test
    public void testFileSearch() throws Exception
    {
        // Search for "javadoc" in the test files
        List<SearchResult> results = service.fileSearch("javadoc");

        assertNotNull(results, "Results should not be null");
        assertTrue(results.size() > 0, "Should find at least one result");

        // Check that results contain expected content
        boolean foundTestClass = results.stream()
                .anyMatch(r -> r.file().getName().equals("TestClass.java") && r.lineContent().contains("javadoc"));

        assertTrue(foundTestClass, "Should find javadoc in TestClass.java");
    }

    @Test
    public void testFileSearchWithFilePattern() throws Exception
    {
        // Search only in .java files
        List<SearchResult> results = service.fileSearch("class", "*.java");

        assertNotNull(results, "Results should not be null");
        assertTrue(results.size() > 0, "Should find results in .java files");

        // All results should be from .java files
        assertTrue(results.stream().allMatch(r -> r.file().getName().endsWith(".java")),
                "All results should be from .java files");
    }

    @Test
    public void testFileSearchRegExp() throws Exception
    {
        // Search for pattern "public.*void" (regex)
        List<SearchResult> results = service.fileSearchRegExp("public\\s+void");

        assertNotNull(results, "Results should not be null");
        assertTrue(results.size() > 0, "Should find at least one public void method");

        // Check that results match the pattern
        boolean foundMethod = results.stream().anyMatch(r -> r.lineContent().matches(".*public\\s+void.*"));

        assertTrue(foundMethod, "Should find lines matching 'public void' pattern");
    }

    @Test
    public void testFormatSearchResults_WithResults()
    {
        // Create mock results
        IFile mockFile = project.getFile("src/test.txt");
        List<SearchResult> results = List.of(new SearchResult(mockFile, 1, "line one"),
                new SearchResult(mockFile, 2, "line two"), new SearchResult(mockFile, 3, "line three"));

        String formatted = SearchService.formatSearchResults(results, "test");

        // Should contain header
        assertTrue(formatted.contains("Found 3 results for \"test\""), "Should have header. Got: " + formatted);

        // Should contain all results
        assertTrue(formatted.contains("line one"), "Should contain result 1. Got: " + formatted);
        assertTrue(formatted.contains("line two"), "Should contain result 2. Got: " + formatted);
        assertTrue(formatted.contains("line three"), "Should contain result 3. Got: " + formatted);

        // Should NOT contain Java toString format
        assertFalse(formatted.contains("SearchResult["), "Should NOT contain Java format. Got: " + formatted);
        assertFalse(formatted.contains("["), "Should NOT contain brackets. Got: " + formatted);
    }

    @Test
    public void testFormatSearchResults_EmptyResults()
    {
        List<SearchResult> results = List.of();
        String formatted = SearchService.formatSearchResults(results, "nonexistent");

        assertEquals("No results found for: nonexistent", formatted, "Should return friendly empty message");
    }

    @Test
    public void testFormatSearchResults_SingleResult()
    {
        IFile mockFile = project.getFile("src/test.txt");
        List<SearchResult> results = List.of(new SearchResult(mockFile, 42, "the answer"));

        String formatted = SearchService.formatSearchResults(results, "answer");

        // Should use singular "result" not "results"
        assertTrue(formatted.contains("Found 1 result for \"answer\""), "Should use singular. Got: " + formatted);
    }

    @Test
    public void testSearchResultPathFormatting() throws CoreException
    {
        // Test that path formatting removes "L/" prefix correctly
        IFile testFile = project.getFile("src/com/example/Test.java");
        testFile.create(new ByteArrayInputStream("test content".getBytes()), true, monitor);

        SearchResult result = new SearchResult(testFile, 1, "test content");
        String formatted = result.toString();

        // Should start with project name, not with "L/" or "/"
        assertTrue(formatted.startsWith(TEST_PROJECT_NAME + "/"), "Should start with project name. Got: " + formatted);
        assertFalse(formatted.startsWith("L/"), "Should NOT start with L/. Got: " + formatted);
        assertFalse(formatted.startsWith("/"), "Should NOT start with /. Got: " + formatted);
    }

    // ==================== MCP Output Layer Tests ====================
    // These test the complete output format that MCP clients will receive
    // Simulates what EclipseIntegrationsMcpServer.fileSearch() returns

    @Test
    public void testMcpFileSearch_CompleteOutput() throws Exception
    {
        // Simulate complete MCP flow: service.fileSearch() → formatSearchResults()
        List<SearchResult> results = service.fileSearch("javadoc");
        String mcpOutput = SearchService.formatSearchResults(results, "javadoc");

        System.out.println("=== MCP fileSearch Output ===");
        System.out.println(mcpOutput);
        System.out.println("=============================");

        // Verify formatted output (not Java toString)
        assertFalse(mcpOutput.contains("SearchResult["), "MCP output should NOT contain Java toString format. Got: " + mcpOutput);
        assertFalse(mcpOutput.contains("[SearchResult"), "MCP output should NOT contain list toString. Got: " + mcpOutput);

        // Verify header is present
        assertTrue(mcpOutput.contains("Found") && mcpOutput.contains("result"),
                "MCP output should have 'Found N results' header. Got: " + mcpOutput);

        // Verify contains expected content
        assertTrue(mcpOutput.contains("javadoc"), "MCP output should contain search term. Got: " + mcpOutput);

        // Verify format is path:line - content
        assertTrue(mcpOutput.contains(":") && mcpOutput.contains(" - "),
                "MCP output should use 'path:line - content' format. Got: " + mcpOutput);

        // Verify no JSON
        assertFalse(mcpOutput.trim().startsWith("{"), "Should NOT be JSON. Got: " + mcpOutput);
        assertFalse(mcpOutput.contains("\"result\":"), "Should NOT contain JSON keys. Got: " + mcpOutput);
    }

    @Test
    public void testMcpFileSearchRegExp_CompleteOutput() throws Exception
    {
        // Simulate MCP flow with regex
        List<SearchResult> results = service.fileSearchRegExp("public\\s+void");
        String mcpOutput = SearchService.formatSearchResults(results, "public\\s+void");

        System.out.println("=== MCP fileSearchRegExp Output ===");
        System.out.println(mcpOutput);
        System.out.println("===================================");

        // Verify formatted output
        assertFalse(mcpOutput.contains("SearchResult["), "MCP output should NOT contain Java toString. Got: " + mcpOutput);

        // Verify header
        assertTrue(mcpOutput.contains("Found") && mcpOutput.contains("result"),
                "MCP output should have header. Got: " + mcpOutput);

        // Verify format
        assertTrue(mcpOutput.contains(":") && mcpOutput.contains(" - "),
                "MCP output should be formatted. Got: " + mcpOutput);

        // Verify content
        assertTrue(mcpOutput.contains("public") && mcpOutput.contains("void"),
                "Should contain matched content. Got: " + mcpOutput);
    }

    @Test
    public void testMcpFileSearch_NoResults() throws Exception
    {
        // Simulate MCP flow with no results
        List<SearchResult> results = service.fileSearch("XYZNONEXISTENT123");
        String mcpOutput = SearchService.formatSearchResults(results, "XYZNONEXISTENT123");

        System.out.println("=== MCP fileSearch (No Results) ===");
        System.out.println(mcpOutput);
        System.out.println("===================================");

        // Verify friendly message
        assertTrue(mcpOutput.contains("No results found"), "Should return friendly 'No results found' message. Got: " + mcpOutput);
        assertEquals("No results found for: XYZNONEXISTENT123", mcpOutput,
                "Empty result should have clear message");
    }

    @Test
    public void testMcpFileSearch_WithFilePattern() throws Exception
    {
        // Simulate MCP flow with file pattern
        List<SearchResult> results = service.fileSearch("class", "*.java");
        String mcpOutput = SearchService.formatSearchResults(results, "class");

        System.out.println("=== MCP fileSearch with Pattern ===");
        System.out.println(mcpOutput);
        System.out.println("===================================");

        // Verify formatted and contains .java files
        assertFalse(mcpOutput.contains("SearchResult["), "Should be formatted. Got: " + mcpOutput);
        assertTrue(mcpOutput.contains(".java:"), "Should contain .java files. Got: " + mcpOutput);
    }

    @Test
    public void testMcpOutput_ConsoleReadability() throws Exception
    {
        // Test that output is human-readable in console
        List<SearchResult> results = service.fileSearch("public");
        String mcpOutput = SearchService.formatSearchResults(results, "public");

        System.out.println("=== MCP Console Output Test ===");
        System.out.println(mcpOutput);
        System.out.println("================================");

        // Verify readability
        String[] lines = mcpOutput.split("\n");
        assertTrue(lines.length > 1, "Should have multiple lines");

        // First line should be header
        assertTrue(lines[0].contains("Found"), "First line should be header. Got: " + lines[0]);

        // Other lines should be results (skip empty lines)
        for (int i = 1; i < lines.length; i++)
        {
            String line = lines[i].trim();
            if (!line.isEmpty())
            {
                // Should be in format: path:line - content
                assertTrue(line.contains(":") && line.contains(" - "),
                        "Result line should have 'path:line - content' format. Got: " + line);
            }
        }
    }

    @Test
    public void testMcpOutput_FormattingComparison() throws Exception
    {
        // Compare list.toString() vs formatSearchResults()
        List<SearchResult> results = service.fileSearch("test");

        // Direct list toString - still has brackets but records are formatted
        String listOutput = results.toString();

        // MCP formatted output - clean with header
        String mcpOutput = SearchService.formatSearchResults(results, "test");

        System.out.println("=== List toString (has brackets) ===");
        System.out.println(listOutput);
        System.out.println("====================================");
        System.out.println("=== MCP formatted (clean) ===");
        System.out.println(mcpOutput);
        System.out.println("==============================");

        // List toString still has brackets (from List, not SearchResult)
        assertTrue(listOutput.startsWith("["), "List toString should start with [");
        assertTrue(listOutput.endsWith("]"), "List toString should end with ]");

        // But individual SearchResults are formatted (no "SearchResult[" prefix)
        assertFalse(listOutput.contains("SearchResult["),
                "SearchResult.toString() override should remove 'SearchResult[' prefix. Got: " + listOutput);

        // MCP output has NO brackets and has header
        assertFalse(mcpOutput.contains("["), "MCP output should NOT have brackets. Got: " + mcpOutput);
        assertTrue(mcpOutput.contains("Found"), "MCP output should have 'Found' header. Got: " + mcpOutput);

        // MCP output is cleaner and more readable
        assertTrue(mcpOutput.split("\n").length > listOutput.split(",").length,
                "MCP output should have more lines (one per result) than list has commas");
    }

    // ==================== searchAndReplace Tests ====================

    @Test
    public void testSearchAndReplace() throws Exception
    {
        // Replace "test" with "demo" in .java files
        List<SearchAndReplaceResult> results = service.searchAndReplace("testMethod", "demoMethod", "*.java");

        assertNotNull(results, "Results should not be null");
        assertTrue(results.size() > 0, "Should have replaced in at least one file");

        // Verify the file was actually changed
        IFile file = project.getFile("src/com/example/TestClass.java");
        file.refreshLocal(IResource.DEPTH_ZERO, monitor);
        String content = new String(file.getContents().readAllBytes());
        assertTrue(content.contains("demoMethod"), "File should contain replacement text");
        assertFalse(content.contains("testMethod"), "File should no longer contain original text");
    }

    @Test
    public void testSearchAndReplace_NoMatches() throws Exception
    {
        List<SearchAndReplaceResult> results = service.searchAndReplace("XYZNONEXISTENT999", "replacement", "*.java");

        assertNotNull(results, "Results should not be null");
        assertEquals(0, results.size(), "Should have no results for non-existent text");
    }

    @Test
    public void testFormatReplaceResults() throws Exception
    {
        List<SearchAndReplaceResult> results = service.searchAndReplace("helper", "utility", "*.java");
        String formatted = SearchService.formatReplaceResults(results);

        System.out.println("=== formatReplaceResults Output ===");
        System.out.println(formatted);
        System.out.println("===================================");

        assertNotNull(formatted);
        assertFalse(formatted.contains("SearchAndReplaceResult["), "Should NOT contain Java toString format");
    }

    @Test
    public void testFormatReplaceResults_Empty()
    {
        String formatted = SearchService.formatReplaceResults(List.of());

        assertNotNull(formatted);
        assertTrue(formatted.toLowerCase().contains("no"), "Should indicate no replacements were made");
    }
}
