package eclipsectlmcp.tests.mcp.services;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.UUID;

import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.e4.core.contexts.ContextInjectionFactory;
import org.eclipse.e4.core.contexts.EclipseContextFactory;
import org.eclipse.e4.core.contexts.IEclipseContext;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.jdt.core.*;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.FrameworkUtil;

import eclipsectlmcp.mcp.services.RefactoringService;

/** Real resource/JDT refactorings: exceptions fail JUnit, and success requires saved changes. */
public class RefactorServiceTest {
    private static final String CALCULATOR = "src/com/example/Calculator.java";
    private static final String STRINGS = "src/com/example/StringUtils.java";
    private static final String CALLER = "src/com/client/MainApp.java";
    private IProject project;
    private IEclipseContext context;
    private RefactoringService service;
    private Display display;
    private final NullProgressMonitor monitor = new NullProgressMonitor();

    @BeforeEach
    public void beforeEach() throws Exception {
        project = ResourcesPlugin.getWorkspace().getRoot().getProject(
                "RefactorTest_" + UUID.randomUUID().toString().replace("-", ""));
        project.create(monitor);
        project.open(monitor);
        IProjectDescription description = project.getDescription();
        description.setNatureIds(new String[] {JavaCore.NATURE_ID});
        project.setDescription(description, monitor);

        IJavaProject javaProject = JavaCore.create(project);
        var options = new HashMap<String, String>();
        JavaCore.setComplianceOptions(JavaCore.VERSION_17, options);
        javaProject.setOptions(options);
        createFolder("src");
        javaProject.setRawClasspath(new IClasspathEntry[] {
            JavaCore.newSourceEntry(project.getFolder("src").getFullPath()),
            JavaCore.newContainerEntry(new Path(JavaRuntime.JRE_CONTAINER))
        }, project.getFullPath().append("bin"), monitor);
        createFile(CALCULATOR, """
            package com.example;

            public class Calculator {
                public int compute(int a, int b) {
                    int sum = a + b;
                    int result = sum * 2;
                    return result;
                }
                public int subtract(int a, int b) { return a - b; }
            }
            """);
        createFile(STRINGS, """
            package com.example;
            public class StringUtils {
                public static String reverse(String input) {
                    return new StringBuilder(input).reverse().toString();
                }
            }
            """);
        createFile(CALLER, """
            package com.client;
            import com.example.Calculator;
            import com.example.StringUtils;
            public class MainApp {
                public static void main(String[] args) {
                    Calculator calc = new Calculator();
                    System.out.println(calc.compute(3, 4));
                    System.out.println(StringUtils.reverse("hello"));
                }
            }
            """);
        buildWithoutErrors();

        assertTrue(PlatformUI.isWorkbenchRunning(), "These PDE tests require a real workbench");
        display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> {
            var window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            assertNotNull(window, "A workbench window must be active");
            assertNotNull(window.getActivePage(), "A workbench page must be active");
        });
        context = EclipseContextFactory.create();
        context.set(ILog.class, Platform.getLog(FrameworkUtil.getBundle(RefactorServiceTest.class)));
        context.set(UISynchronize.class, new UISynchronize() {
            @Override public void syncExec(Runnable runnable) { display.syncExec(runnable); }
            // Finish editor callbacks before assertions/cleanup; propagate their failures to JUnit.
            @Override public void asyncExec(Runnable runnable) { display.syncExec(runnable); }
            @Override protected boolean isUIThread(Thread thread) { return thread == display.getThread(); }
            @Override protected void showBusyWhile(Runnable runnable) { display.syncExec(runnable); }
            @Override protected boolean dispatchEvents() { return false; }
        });
        service = ContextInjectionFactory.make(RefactoringService.class, context);
    }

    @AfterEach
    public void afterEach() throws Exception {
        try {
            if (display != null && !display.isDisposed()) {
                display.syncExec(() -> {
                    for (var window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
                        for (var page : window.getPages()) {
                            for (var reference : page.getEditorReferences()) {
                                var editor = reference.getEditor(false);
                                if (editor != null && editor.getEditorInput() instanceof IFileEditorInput input
                                        && input.getFile().getProject().equals(project)) {
                                    assertTrue(page.closeEditor(editor, false), "Test editor must close");
                                }
                            }
                        }
                    }
                });
            }
        } finally {
            try {
                if (context != null) context.dispose();
            } finally {
                if (project != null && project.exists()) project.delete(true, true, monitor);
            }
        }
    }

    @Test
    public void testRenameFile() throws Exception {
        createFile("docs/notes.txt", "Some notes");
        assertSuccess(service.renameFile(project.getName(), "docs/notes.txt", "readme.txt"));
        assertFalse(project.getFile("docs/notes.txt").exists(), "Original file must disappear");
        assertEquals("Some notes", savedText("docs/readme.txt"));
        buildWithoutErrors();
    }

    @Test
    public void testRefactorRenameJavaType() throws Exception {
        assertSuccess(service.refactorRenameJavaType(project.getName(), CALCULATOR, "MathHelper"));
        assertFalse(project.getFile(CALCULATOR).exists(), "Original type file must disappear");
        assertTrue(savedText("src/com/example/MathHelper.java").contains("public class MathHelper"));
        String caller = savedText(CALLER);
        assertTrue(caller.contains("import com.example.MathHelper;"), caller);
        assertTrue(caller.contains("MathHelper calc = new MathHelper();"), caller);
        assertFalse(caller.contains("Calculator"), caller);
        buildWithoutErrors();
    }

    @Test
    public void testRefactorMoveJavaType() throws Exception {
        assertSuccess(service.refactorMoveJavaType(project.getName(), STRINGS, "com.example.util"));
        assertFalse(project.getFile(STRINGS).exists(), "Original type file must disappear");
        assertTrue(savedText("src/com/example/util/StringUtils.java").contains("package com.example.util;"));
        String caller = savedText(CALLER);
        assertTrue(caller.contains("import com.example.util.StringUtils;"), caller);
        assertFalse(caller.contains("import com.example.StringUtils;"), caller);
        buildWithoutErrors();
    }

    @Test
    public void testRefactorRenamePackage() throws Exception {
        assertSuccess(service.refactorRenamePackage(project.getName(), "com.example", "com.sample"));
        assertFalse(project.getFile(CALCULATOR).exists(), "Original type file must disappear");
        assertFalse(project.getFile(STRINGS).exists(), "Original type file must disappear");
        assertTrue(savedText("src/com/sample/Calculator.java").contains("package com.sample;"));
        assertTrue(savedText("src/com/sample/StringUtils.java").contains("package com.sample;"));
        String caller = savedText(CALLER);
        assertTrue(caller.contains("import com.sample.Calculator;"), caller);
        assertTrue(caller.contains("import com.sample.StringUtils;"), caller);
        assertFalse(caller.contains("com.example"), caller);
        buildWithoutErrors();
    }

    @Test
    public void testMoveResource() throws Exception {
        createFile("docs/guide.txt", "User guide content");
        assertSuccess(service.moveResource(project.getName(), "docs/guide.txt", "archive"));
        assertFalse(project.getFile("docs/guide.txt").exists(), "Original resource must disappear");
        assertEquals("User guide content", savedText("archive/guide.txt"));
        buildWithoutErrors();
    }

    @Test
    public void testRefactorExtractMethod() throws Exception {
        // Lines 5-6 select both assignments, leaving the return in compute.
        String result = service.refactorExtractMethod(project.getName(), CALCULATOR, 5, 6, "calculateSum", "private");
        assertTrue(result.startsWith("Extract Method Refactoring: Success"), result);
        var unit = (ICompilationUnit) JavaCore.create(project.getFile(CALCULATOR));
        var type = unit.getType("Calculator");
        var extracted = type.getMethod("calculateSum", new String[] {"I", "I"});
        assertTrue(extracted.exists(), "Extracted method must exist with both int parameters");
        assertTrue(Flags.isPrivate(extracted.getFlags()), "Extracted method must be private");
        assertEquals("I", extracted.getReturnType());
        assertTrue(extracted.getSource().contains("int sum = a + b;"));
        assertTrue(extracted.getSource().contains("int result = sum * 2;"));
        String compute = type.getMethod("compute", new String[] {"I", "I"}).getSource();
        assertTrue(compute.contains("calculateSum(a, b)"), compute);
        assertTrue(compute.contains("return result;"), compute);
        assertFalse(compute.contains("a + b"), compute);
        assertFalse(compute.contains("sum * 2"), compute);
        assertTrue(savedText(CALCULATOR).contains("calculateSum(a, b)"));
        buildWithoutErrors();
    }

    private static void assertSuccess(String result) {
        assertNotNull(result);
        assertTrue(result.startsWith("Success:"), result);
    }

    private String savedText(String path) throws Exception {
        IFile file = project.getFile(path);
        assertTrue(file.exists(), "Expected saved file: " + path);
        try (var contents = file.getContents()) {
            return new String(contents.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void buildWithoutErrors() throws CoreException {
        project.build(IncrementalProjectBuilder.FULL_BUILD, monitor);
        for (IMarker marker : project.findMarkers(IJavaModelMarker.JAVA_MODEL_PROBLEM_MARKER,
                true, IResource.DEPTH_INFINITE)) {
            assertNotEquals(IMarker.SEVERITY_ERROR, marker.getAttribute(IMarker.SEVERITY, 0),
                    marker.getAttribute(IMarker.MESSAGE, ""));
        }
    }

    private void createFolder(String path) throws CoreException {
        IFolder folder = project.getFolder(path);
        if (folder.exists()) return;
        if (folder.getParent() instanceof IFolder parent) createFolder(parent.getProjectRelativePath().toString());
        folder.create(true, true, monitor);
    }

    private void createFile(String path, String content) throws CoreException {
        IFile file = project.getFile(path);
        if (file.getParent() instanceof IFolder parent) createFolder(parent.getProjectRelativePath().toString());
        file.create(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), true, monitor);
        file.setCharset(StandardCharsets.UTF_8.name(), monitor);
    }
}
