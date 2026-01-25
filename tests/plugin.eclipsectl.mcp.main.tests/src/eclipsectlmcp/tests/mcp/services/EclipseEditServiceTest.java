package eclipsectlmcp.tests.mcp.services;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.JavaRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.util.tracker.ServiceTracker;

import eclipsectlmcp.mcp.services.EclipseEditService;

public class EclipseEditServiceTest {

    private static final String TEST_PROJECT_NAME = "SearchServiceTestProject";
    private IProject project;
    private IJavaProject javaProject;
    private NullProgressMonitor monitor = new NullProgressMonitor();

    @BeforeEach
    public void beforeEach() throws CoreException, IOException, InterruptedException {
        BundleContext bundleContext = FrameworkUtil.getBundle(EclipseSearchServiceTest.class).getBundleContext();
        ServiceTracker<IWorkspace, IWorkspace> workspaceTracker = new ServiceTracker<>(bundleContext, IWorkspace.class, null);

        workspaceTracker.open();
        IWorkspace workspace = workspaceTracker.getService();
        IWorkspaceRoot root = workspace.getRoot();

        // Delete the project if it exists
        project = root.getProject(TEST_PROJECT_NAME);
        if (project.exists()) {
            project.delete(true, true, monitor);
        }

        // Create a test project
        project = root.getProject(TEST_PROJECT_NAME);
        IProjectDescription desc = project.getWorkspace().newProjectDescription(project.getName());
        desc.setNatureIds(new String[] { JavaCore.NATURE_ID });
        project.create(desc, monitor);
        project.open(monitor);

        // Set up Java project
        javaProject = JavaCore.create(project);

        // Create output folder (bin)
        IFolder binFolder = project.getFolder("bin");
        if (!binFolder.exists()) {
            binFolder.create(true, true, monitor);
        }
        javaProject.setOutputLocation(binFolder.getFullPath(), monitor);

        // Create source folder
        IFolder srcFolder = project.getFolder("src");
        if (!srcFolder.exists()) {
            srcFolder.create(IResource.NONE, true, monitor);
        }

        // Set classpath
        javaProject.setRawClasspath(
                new org.eclipse.jdt.core.IClasspathEntry[] {
                        JavaCore.newSourceEntry(project.getFullPath().append("src")),
                        JavaRuntime.getDefaultJREContainerEntry()
                },
                monitor);

        // Create package structure
        createPackageStructure();
    }

    @AfterEach
    public void afterEach() throws CoreException, InterruptedException {
        if (project != null && project.exists()) {
            // Refresh the project to ensure all resources are synchronized
            project.refreshLocal(IResource.DEPTH_INFINITE, monitor);

            // Wait a bit to ensure all streams are released
            Thread.sleep(500);

            // Force delete with both flags set to true
            project.delete(IResource.FORCE | IResource.ALWAYS_DELETE_PROJECT_CONTENT, monitor);
        }
    }

    // ==================== SEARCH ONLY TESTS ====================

    @Test
    public void testSearchOnly_FindsMatches() throws CoreException {
        createFile("src/com/example/Test.java",
                "package com.example;\n" +
                "public class Test {\n" +
                "    String foo = \"hello\";\n" +
                "    String bar = \"hello world\";\n" +
                "}\n");

        String result = EclipseEditService.edit(
                "hello",    // remove (search text)
                false,      // removeRegex
                null,       // add (null = search only)
                false,      // addRegex
                "*.java",   // fileNamePatterns
                null,       // lineColumn
                true);      // replaceAll

        assertTrue(result.contains("Found 2 match"));
        assertTrue(result.contains("hello"));
    }

    @Test
    public void testSearchOnly_NoMatches() throws CoreException {
        createFile("src/com/example/Test.java",
                "package com.example;\n" +
                "public class Test {}\n");

        String result = EclipseEditService.edit(
                "nonexistent", null, null, null, "*.java", null, true);

        assertTrue(result.contains("No matches found"));
    }

    @Test
    public void testSearchOnly_WithRegex() throws CoreException {
        createFile("src/com/example/Test.java",
                "package com.example;\n" +
                "public class Test {\n" +
                "    int value1 = 10;\n" +
                "    int value2 = 20;\n" +
                "    int other = 30;\n" +
                "}\n");

        String result = EclipseEditService.edit(
                "value\\d",  // regex pattern
                true,        // removeRegex = true
                null,        // search only
                false,
                "*.java",
                null,
                true);

        assertTrue(result.contains("Found 2 match"));
    }

    // ==================== SIMPLE REPLACE TESTS ====================

    @Test
    public void testReplace_SimpleText() throws CoreException {
        createFile("src/com/example/Test.java",
                "package com.example;\n" +
                "public class Test {\n" +
                "    String old = \"old value\";\n" +
                "}\n");

        String result = EclipseEditService.edit(
                "old",      // remove
                false,      // removeRegex
                "new",      // add
                false,      // addRegex
                "*.java",
                null,
                true);

        assertTrue(result.contains("Replaced"));

        // Verify file content
        String content = readFile("src/com/example/Test.java");
        assertTrue(content.contains("new value"));
        assertTrue(content.contains("String new"));
        assertFalse(content.contains("String old"));
    }

    @Test
    public void testReplace_FirstOccurrenceOnly() throws CoreException {
        createFile("src/com/example/Test.java",
                "package com.example;\n" +
                "public class Test {\n" +
                "    String a = \"foo\";\n" +
                "    String b = \"foo\";\n" +
                "    String c = \"foo\";\n" +
                "}\n");

        String result = EclipseEditService.edit(
                "foo",
                false,
                "bar",
                false,
                "*.java",
                null,
                false);  // replaceAll = false

        assertTrue(result.contains("Replaced 1 occurrence"));

        String content = readFile("src/com/example/Test.java");
        // Should have one "bar" and two "foo" remaining
        assertEquals(2, countOccurrences(content, "foo"));
        assertEquals(1, countOccurrences(content, "bar"));
    }

    @Test
    public void testReplace_AllOccurrences() throws CoreException {
        createFile("src/com/example/Test.java",
                "package com.example;\n" +
                "public class Test {\n" +
                "    String a = \"foo\";\n" +
                "    String b = \"foo\";\n" +
                "    String c = \"foo\";\n" +
                "}\n");

        String result = EclipseEditService.edit(
                "foo",
                false,
                "bar",
                false,
                "*.java",
                null,
                true);  // replaceAll = true

        assertTrue(result.contains("Replaced 3 occurrence"));

        String content = readFile("src/com/example/Test.java");
        assertEquals(0, countOccurrences(content, "foo"));
        assertEquals(3, countOccurrences(content, "bar"));
    }

    // ==================== REGEX REPLACE TESTS ====================

    @Test
    public void testReplace_WithRegex() throws CoreException {
        createFile("src/com/example/Test.java",
                "package com.example;\n" +
                "public class Test {\n" +
                "    int val123 = 1;\n" +
                "    int val456 = 2;\n" +
                "}\n");

        String result = EclipseEditService.edit(
                "val\\d+",   // regex pattern
                true,        // removeRegex = true
                "value",     // replacement
                false,
                "*.java",
                null,
                true);

        assertTrue(result.contains("Replaced 2 occurrence"));

        String content = readFile("src/com/example/Test.java");
        assertTrue(content.contains("int value = 1"));
        assertTrue(content.contains("int value = 2"));
    }

    @Test
    public void testReplace_WithRegexBackreferences() throws CoreException {
        createFile("src/com/example/Test.java",
                "package com.example;\n" +
                "public class Test {\n" +
                "    String name1 = \"hello\";\n" +
                "    String name2 = \"world\";\n" +
                "}\n");

        String result = EclipseEditService.edit(
                "(name)(\\d)",    // regex with groups
                true,             // removeRegex
                "var$2_$1",       // replacement with backreferences
                true,             // addRegex = true (enable backreferences)
                "*.java",
                null,
                true);

        assertTrue(result.contains("Replaced"));

        String content = readFile("src/com/example/Test.java");
        assertTrue(content.contains("var1_name"));
        assertTrue(content.contains("var2_name"));
    }

    // ==================== INSERT AT POSITION TESTS ====================

    @Test
    public void testInsert_AtPosition() throws CoreException {
        createFile("src/com/example/Test.java",
                "package com.example;\n" +
                "public class Test {\n" +
                "}\n");

        // Insert at line 3, column 0 (before the closing brace)
        String result = EclipseEditService.edit(
                "",                              // empty = insert mode
                false,
                "    // Inserted comment\n",     // text to insert
                false,
                "*.java",
                "3:0-3:0",                       // line:column
                true);

        assertTrue(result.contains("Inserted"));

        String content = readFile("src/com/example/Test.java");
        assertTrue(content.contains("// Inserted comment"));
    }

    @Test
    public void testInsert_ReplaceSelection() throws CoreException {
        createFile("src/com/example/Test.java",
                "package com.example;\n" +
                "public class Test {\n" +
                "    String old = \"value\";\n" +
                "}\n");

        // Replace text from line 3, col 11 to line 3, col 14 ("old" -> "new")
        String result = EclipseEditService.edit(
                "",          // empty = insert/replace mode
                false,
                "new",       // replacement
                false,
                "*.java",
                "3:11-3:14", // select "old"
                true);

        assertTrue(result.contains("Inserted"));

        String content = readFile("src/com/example/Test.java");
        assertTrue(content.contains("String new = \"value\""));
    }

    @Test
    public void testInsert_RequiresLineColumn() throws CoreException {
        createFile("src/com/example/Test.java", "package com.example;\n");

        String result = EclipseEditService.edit(
                "",          // insert mode
                false,
                "text",
                false,
                "*.java",
                null,        // missing lineColumn
                true);

        assertTrue(result.contains("requires 'line_column' parameter"));
    }

    // ==================== FILE PATTERN TESTS ====================

    @Test
    public void testFilePattern_JavaOnly() throws CoreException {
        createFile("src/com/example/Test.java", "hello world");
        createFile("src/com/example/test.txt", "hello world");
        createFile("src/com/example/test.xml", "hello world");

        String result = EclipseEditService.edit(
                "hello",
                false,
                "goodbye",
                false,
                "*.java",    // only Java files
                null,
                true);

        assertTrue(result.contains("Replaced 1 occurrence"));
        assertEquals("goodbye world", readFile("src/com/example/Test.java").trim());
        assertEquals("hello world", readFile("src/com/example/test.txt").trim());
    }

    @Test
    public void testFilePattern_MultiplePatterns() throws CoreException {
        createFile("src/com/example/Test.java", "hello");
        createFile("src/com/example/test.txt", "hello");
        createFile("src/com/example/test.xml", "hello");

        String result = EclipseEditService.edit(
                "hello",
                false,
                "goodbye",
                false,
                "*.java,*.txt",  // Java and txt files
                null,
                true);

        assertTrue(result.contains("Replaced 2 occurrence"));
        assertEquals("goodbye", readFile("src/com/example/Test.java").trim());
        assertEquals("goodbye", readFile("src/com/example/test.txt").trim());
        assertEquals("hello", readFile("src/com/example/test.xml").trim());
    }

    @Test
    public void testFilePattern_AllFiles() throws CoreException {
        createFile("src/com/example/Test.java", "hello");
        createFile("src/com/example/test.txt", "hello");

        String result = EclipseEditService.edit(
                "hello",
                false,
                "goodbye",
                false,
                null,   // null = all files (*)
                null,
                true);

        assertTrue(result.contains("Replaced 2 occurrence"));
    }

    // ==================== LINE COLUMN SELECTION TESTS ====================

    @Test
    public void testReplace_WithinSelection() throws CoreException {
        createFile("src/com/example/Test.java",
                "line1 foo\n" +
                "line2 foo\n" +
                "line3 foo\n" +
                "line4 foo\n");

        // Only replace "foo" on lines 2-3
        String result = EclipseEditService.edit(
                "foo",
                false,
                "bar",
                false,
                "*.java",
                "2:0-4:0",   // lines 2-3 (line 4 col 0 is exclusive)
                true);

        String content = readFile("src/com/example/Test.java");
        assertTrue(content.contains("line1 foo"));  // unchanged
        assertTrue(content.contains("line2 bar"));  // replaced
        assertTrue(content.contains("line3 bar"));  // replaced
        assertTrue(content.contains("line4 foo"));  // unchanged
    }
    
    @Test
    public void testReplace2_WithinSelection() throws CoreException {
        createFile("src/com/example/Test.java",
                "line1 foo\n" +
                "line2 foo\n" +
                "line3 foo\n" +
                "line4 foo\n");

        // Only replace "foo" on lines 2-3
        String result = EclipseEditService.edit(
                "foo",
                false,
                "bar\nbar",
                false,
                "*.java",
                "2:0-4:0",   // lines 2-3 (line 4 col 0 is exclusive)
                true);

        String content = readFile("src/com/example/Test.java");
        assertTrue(content.contains("line1 foo"));  // unchanged
        assertTrue(content.contains("line2 bar"));  // replaced
        assertTrue(content.contains("line3 bar"));  // replaced
        assertTrue(content.contains("line4 foo"));  // unchanged
    }

    // ==================== EDGE CASES ====================

    @Test
    public void testReplace_EmptyBothParams() throws CoreException {
        String result = EclipseEditService.edit(
                null, null, null, null, null, null, null);

        assertTrue(result.contains("Nothing to do"));
    }

    @Test
    public void testReplace_EmptySearchText() throws CoreException {
        String result = EclipseEditService.edit(
                "", false, null, false, "*.java", null, true);

        assertTrue(result.contains("Nothing to do") || result.contains("requires"));
    }

    @Test
    public void testReplace_MultipleFiles() throws CoreException {
        createFile("src/com/example/File1.java", "TODO: implement");
        createFile("src/com/example/File2.java", "TODO: fix bug");
        createFile("src/com/example/File3.java", "TODO: refactor");

        String result = EclipseEditService.edit(
                "TODO:",
                false,
                "DONE:",
                false,
                "*.java",
                null,
                true);

        assertTrue(result.contains("Replaced 3 occurrence"));
        assertTrue(result.contains("3 file"));

        assertTrue(readFile("src/com/example/File1.java").contains("DONE:"));
        assertTrue(readFile("src/com/example/File2.java").contains("DONE:"));
        assertTrue(readFile("src/com/example/File3.java").contains("DONE:"));
    }

    @Test
    public void testReplace_SpecialCharacters() throws CoreException {
        createFile("src/com/example/Test.java",
                "String s = \"Hello, World!\";\n");

        // Replace with special characters (not regex)
        String result = EclipseEditService.edit(
                "Hello, World!",
                false,          // NOT regex - literal match
                "Goodbye, World!",
                false,
                "*.java",
                null,
                true);

        assertTrue(result.contains("Replaced"));
        assertTrue(readFile("src/com/example/Test.java").contains("Goodbye, World!"));
    }

    @Test
    public void testReplace_MultilineContent() throws CoreException {
        createFile("src/com/example/Test.java",
                "public class Test {\n" +
                "    /* OLD COMMENT\n" +
                "       MULTI LINE */\n" +
                "}\n");

        String result = EclipseEditService.edit(
                "/\\*.*?\\*/",    // regex for block comments
                true,             // regex enabled
                "// replaced",
                false,
                "*.java",
                null,
                true);

        // Note: This depends on DOTALL flag behavior
        String content = readFile("src/com/example/Test.java");
        System.out.println("Multiline result: " + content);
    }

    // ==================== HELPER METHODS ====================

    private void createPackageStructure() throws CoreException {
        IFolder comFolder = project.getFolder("src/com");
        if (!comFolder.exists()) {
            comFolder.create(IResource.NONE, true, monitor);
        }

        IFolder exampleFolder = project.getFolder("src/com/example");
        if (!exampleFolder.exists()) {
            exampleFolder.create(IResource.NONE, true, monitor);
        }
    }

    private IFile createFile(String path, String content) throws CoreException {
        IFile file = project.getFile(new Path(path));
        ByteArrayInputStream source = new ByteArrayInputStream(
                content.getBytes(StandardCharsets.UTF_8));

        // Ensure parent folders exist
        createParentFolders(file);

        if (file.exists()) {
            file.setContents(source, true, true, monitor);
        } else {
            file.create(source, true, monitor);
        }

        return file;
    }

    private void createParentFolders(IFile file) throws CoreException {
        IFolder parent = (IFolder) file.getParent();
        if (!parent.exists()) {
            createParentFolders(parent);
            parent.create(IResource.NONE, true, monitor);
        }
    }

    private void createParentFolders(IFolder folder) throws CoreException {
        if (!folder.getParent().exists() && folder.getParent() instanceof IFolder) {
            createParentFolders((IFolder) folder.getParent());
        }
        if (!folder.exists()) {
            folder.create(IResource.NONE, true, monitor);
        }
    }

    private String readFile(String path) throws CoreException {
        IFile file = project.getFile(new Path(path));
        try (var stream = file.getContents()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new CoreException(null);
        }
    }

    private int countOccurrences(String text, String search) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(search, index)) != -1) {
            count++;
            index += search.length();
        }
        return count;
    }
}