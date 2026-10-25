package eclipsectlmcp.tests.mcp.services;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.UUID;
import java.util.Map;
import java.time.Duration;
import java.nio.file.Files;
import org.apache.catalina.startup.Tomcat;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.jdt.core.*;
import org.eclipse.jdt.launching.JavaRuntime;
import org.junit.jupiter.api.*;
import eclipsectlmcp.tools.UISynchronizeCallable;
import eclipsectlmcp.mcp.McpServerFactory;
import eclipsectlmcp.mcp.http.HttpMcpServletConfiguration;
import eclipsectlmcp.mcp.servers.EclipseRefactorMcpServer;
import eclipsectlmcp.mcp.services.JavaRefactoringService;
import eclipsectlmcp.mcp.services.WorkspaceBuildService;
import eclipsectlmcp.mcp.servers.EclipseIntegrationsMcpServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

/** PDE tests: real JDT changes in a disposable project, with assertions on declarations and callers. */
public class JavaRefactoringServiceTest {
    private IProject project;
    private IProject dependent;
    private ICompilationUnit unit;
    private ICompilationUnit caller;
    private JavaRefactoringService service;
    private final NullProgressMonitor monitor = new NullProgressMonitor();
    private static final String SOURCE = """
        package sample;
        // UTF-16: 😀
        public class Example {
            public int count = 2;
            public int sum(int a, int b) { return a + b; }
            public int sum(int a) { return a; }
            public String join(String text, int count) { return text + count; }
            public int compute(int a) { int value = a + 1; return value * 2; }
            public int other(int a) { int value = a + 2; return value; }
            private int twice(int a) { return a * 2; }
            public int use(int a) { return twice(a) + count; }
            public int limit() { return 42 + 42; }
            public int optional(int unused) { return 7; }
            public int useOptional() { return optional(3); }
        }
        """;

    @BeforeEach
    void setup() throws Exception {
        project = ResourcesPlugin.getWorkspace().getRoot().getProject("JavaRefactor_" + UUID.randomUUID().toString().replace("-", ""));
        project.create(monitor);
        project.open(monitor);
        var description = project.getDescription();
        description.setNatureIds(new String[] {JavaCore.NATURE_ID});
        project.setDescription(description, monitor);
        IJavaProject java = JavaCore.create(project);
        var options = new HashMap<String, String>();
        JavaCore.setComplianceOptions(JavaCore.VERSION_17, options);
        java.setOptions(options);
        IFolder src = project.getFolder("src");
        src.create(true, true, monitor);
        java.setRawClasspath(new IClasspathEntry[] {
            JavaCore.newSourceEntry(src.getFullPath()),
            JavaCore.newContainerEntry(new org.eclipse.core.runtime.Path(JavaRuntime.JRE_CONTAINER))
        }, project.getFullPath().append("bin"), monitor);
        var pkg = java.getPackageFragmentRoot(src).createPackageFragment("sample", true, monitor);
        unit = pkg.createCompilationUnit("Example.java", SOURCE, true, monitor);
        caller = pkg.createCompilationUnit("Caller.java", """
            package sample;
            public class Caller {
                public long call() { return new Example().sum(2, 3) + new Example().count; }
                public String label() { return new Example().join("x", 2); }
            }
            """, true, monitor);
        var ui = new UISynchronizeCallable();
        ui.uiSync = new UISynchronize() {
            @Override public void syncExec(Runnable runnable) { org.eclipse.ui.PlatformUI.getWorkbench().getDisplay().syncExec(runnable); }
            @Override public void asyncExec(Runnable runnable) { org.eclipse.ui.PlatformUI.getWorkbench().getDisplay().asyncExec(runnable); }
            @Override protected boolean isUIThread(Thread thread) { return thread == org.eclipse.ui.PlatformUI.getWorkbench().getDisplay().getThread(); }
            @Override protected void showBusyWhile(Runnable runnable) { runnable.run(); }
            @Override protected boolean dispatchEvents() { return false; }
        };
        service = new JavaRefactoringService(ui);
        buildWithoutErrors();
    }

    @AfterEach
    void cleanup() throws Exception {
        if (service != null) service.disposePreviews();
        if (dependent != null && dependent.exists()) dependent.delete(true, true, monitor);
        if (project != null && project.exists()) project.delete(true, true, monitor);
    }

    private String name() { return project.getName(); }
    private static final String FILE = "src/sample/Example.java";
    private int at(String text) throws Exception { return unit.getSource().indexOf(text); }

    private static String previewId(String text) {
        var matcher = java.util.regex.Pattern.compile("Preview ID: ([0-9a-f-]{36})").matcher(text);
        assertTrue(matcher.find(), text);
        return matcher.group(1);
    }

    private void buildWithoutErrors() throws Exception {
        project.build(IncrementalProjectBuilder.FULL_BUILD, monitor);
        for (IMarker marker : project.findMarkers(IJavaModelMarker.JAVA_MODEL_PROBLEM_MARKER, true, IResource.DEPTH_INFINITE))
            assertNotEquals(IMarker.SEVERITY_ERROR, marker.getAttribute(IMarker.SEVERITY, 0), marker.getAttribute(IMarker.MESSAGE, ""));
    }

    @Test void renameMethodUpdatesOnlyTheSelectedOverloadAndCallers() throws Exception {
        assertTrue(service.rename(name(), FILE, at("sum(int a, int b)"), "add", IJavaElement.METHOD).startsWith("Success:"));
        assertTrue(unit.getSource().contains("add(int a, int b)"));
        assertTrue(unit.getSource().contains("sum(int a)"));
        assertTrue(caller.getSource().contains(".add(2, 3)"));
        buildWithoutErrors();
    }

    @Test void renameVariableByLineHandlesCrLfUtf16AndRepeatedReferences() throws Exception {
        String source = SOURCE.replace("return value * 2", "return value + value").replace("\n", "\r\n");
        unit.getBuffer().setContents(source);
        unit.save(monitor, true);
        assertTrue(service.renameVariable(name(), FILE, null, "value", "amount", 8, null, false).startsWith("Success:"));
        assertTrue(unit.getSource().contains("int amount = a + 1; return amount + amount"));
        assertTrue(unit.getSource().contains("int value = a + 2; return value"));
        buildWithoutErrors();
    }

    @Test void renameVariableByMethodCanSelectParametersAndPreviewWithoutWriting() throws Exception {
        String original = unit.getSource();
        String preview = service.renameVariable(name(), "Example", null, "value", "amount", null, "compute", true);
        assertTrue(preview.startsWith("Preview:"), preview);
        assertEquals(original, unit.getSource());
        service.applyPreview(previewId(preview));
        assertTrue(unit.getSource().contains("int amount = a + 1; return amount * 2"));
        assertTrue(service.renameVariable(name(), "Example.java", null, "a", "input", null, "compute", false).startsWith("Success:"));
        assertTrue(unit.getSource().contains("compute(int input) { int amount = input + 1"));
        buildWithoutErrors();
    }

    @Test void overloadedMethodsRequireLineToDisambiguateVariables() throws Exception {
        String original = unit.getSource();
        assertThrows(RuntimeException.class, () -> service.renameVariable(name(), FILE, null, "a", "input", null, "sum", false));
        assertEquals(original, unit.getSource());
        service.renameVariable(name(), FILE, null, "a", "input", 6, "sum", false);
        assertTrue(unit.getSource().contains("sum(int input) { return input; }"));
        assertTrue(unit.getSource().contains("sum(int a, int b)"));
        buildWithoutErrors();
    }

    @Test void namedSelectionIgnoresStringsAndCommentsAndRejectsMultipleScopes() throws Exception {
        String source = SOURCE.replace("int value = a + 1; return value * 2;",
                "String text = \"value\"; /* value */ int value = a + 1; return value * 2;")
                .replace("public int other(int a) { int value = a + 2; return value; }",
                "public int other(int a) { { int value = a; } { int value = a; } return a; }");
        unit.getBuffer().setContents(source);
        unit.save(monitor, true);
        assertThrows(RuntimeException.class, () -> service.renameVariable(name(), FILE, null, "value", "amount", null, "other", false));
        assertEquals(source, unit.getSource());
        service.renameVariable(name(), FILE, null, "value", "amount", 8, "compute", false);
        assertTrue(unit.getSource().contains("\"value\"; /* value */ int amount = a + 1; return amount * 2;"));
        buildWithoutErrors();
    }

    @Test void namedSelectionRejectsAbsentNamesWrongKindsAndConflictingSelectors() throws Exception {
        String original = unit.getSource();
        assertThrows(RuntimeException.class, () -> service.renameVariable(name(), FILE, null, "missing", "newName", 8, null, false));
        assertThrows(RuntimeException.class, () -> service.renameVariable(name(), FILE, null, "count", "total", 4, null, false));
        assertThrows(RuntimeException.class, () -> service.renameVariable(name(), FILE, null, "value", "amount", 8, "other", false));
        assertThrows(RuntimeException.class, () -> service.renameVariable(name(), FILE, null, "value", "amount", 999, null, false));
        assertThrows(RuntimeException.class, () -> service.renameVariable(name(), FILE, null, "value", "amount", 0, null, false));
        assertThrows(RuntimeException.class, () -> service.renameVariable(name(), FILE, 0, "value", "amount", 8, null, false));
        assertThrows(RuntimeException.class, () -> service.renameVariable(name(), FILE, null, "value", "amount", null, null, false));
        assertEquals(original, unit.getSource());
    }

    @Test void simpleClassNamesRefuseDuplicateFilesWithinAProject() throws Exception {
        var root = unit.getParent().getParent();
        var duplicate = ((IPackageFragmentRoot) root).createPackageFragment("duplicate", true, monitor)
                .createCompilationUnit("Example.java", "package duplicate; public class Example {}", true, monitor);
        String original = unit.getSource();
        assertThrows(RuntimeException.class, () -> service.renameVariable(name(), "Example", null, "value", "amount", null, "compute", false));
        assertEquals(original, unit.getSource());
        assertEquals("package duplicate; public class Example {}", duplicate.getSource());
        service.renameVariable(name(), FILE, null, "value", "amount", null, "compute", false);
        buildWithoutErrors();
    }

    @Test void previewThenApplyReportsCrossProjectReferencesAndBuilds() throws Exception {
        dependent = ResourcesPlugin.getWorkspace().getRoot().getProject(name() + "Client");
        dependent.create(monitor);
        dependent.open(monitor);
        var desc = dependent.getDescription();
        desc.setNatureIds(new String[] {JavaCore.NATURE_ID});
        desc.setReferencedProjects(new IProject[] {project});
        dependent.setDescription(desc, monitor);
        var javaClient = JavaCore.create(dependent);
        var src = dependent.getFolder("src");
        src.create(true, true, monitor);
        javaClient.setRawClasspath(new IClasspathEntry[] {
            JavaCore.newSourceEntry(src.getFullPath()), JavaCore.newProjectEntry(project.getFullPath()),
            JavaCore.newContainerEntry(new org.eclipse.core.runtime.Path(JavaRuntime.JRE_CONTAINER))
        }, dependent.getFullPath().append("bin"), monitor);
        var external = javaClient.getPackageFragmentRoot(src).createPackageFragment("client", true, monitor)
                .createCompilationUnit("Client.java", """
                    package client;
                    public class Client { public int call() { return new sample.Example().sum(1, 2); } }
                    """, true, monitor);
        var builder = new WorkspaceBuildService();
        assertTrue(builder.buildProjects(name(), "full", true, 60, 20).contains("completed: SUCCESS"));
        String original = unit.getSource();
        String originalCaller = caller.getSource();
        String originalExternal = external.getSource();
        String preview = service.rename(name(), FILE, at("sum(int a, int b)"), "add", IJavaElement.METHOD, true);
        assertTrue(preview.startsWith("Preview:"), preview);
        assertTrue(preview.contains("Changed files: 3"), preview);
        assertTrue(preview.contains("/" + dependent.getName() + "/src/client/Client.java"), preview);
        assertTrue(preview.contains("@@ -"), preview);
        assertTrue(preview.contains("+    public int add(int a, int b)"), preview);
        assertEquals(original, unit.getSource());
        assertEquals(originalCaller, caller.getSource());
        assertEquals(originalExternal, external.getSource());
        try (var saved = project.getFile(FILE).getContents()) {
            assertEquals(original, new String(saved.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        String applied = service.applyPreview(previewId(preview));
        assertTrue(applied.startsWith("Success:"), applied);
        assertEquals(preview.substring(preview.indexOf("Affected projects:"), preview.indexOf("\n[End of preview.]")),
                applied.substring(applied.indexOf("Affected projects:")));
        assertTrue(external.getSource().contains(".add(1, 2)"));
        String build = builder.buildProjects(name(), null, null, 60, 20);
        assertTrue(build.contains("completed: SUCCESS"), build);
        assertTrue(build.contains(dependent.getName() + ": 0 errors"), build);
    }

    @Test void previewSupportsEveryPositionBasedRefactoringWithoutWriting() throws Exception {
        String original = unit.getSource();
        String originalCaller = caller.getSource();
        assertTrue(service.rename(name(), FILE, at("count ="), "total", IJavaElement.FIELD, true).startsWith("Preview:"));
        assertTrue(service.rename(name(), FILE, at("value = a + 1"), "amount", IJavaElement.LOCAL_VARIABLE, true).startsWith("Preview:"));
        assertTrue(service.extractVariable(name(), FILE, at("a + 1"), 5, "incremented", false, true, true).startsWith("Preview:"));
        assertTrue(service.extractConstant(name(), FILE, at("42 + 42"), 2, "LIMIT", "private", true, true).startsWith("Preview:"));
        assertTrue(service.inlineVariable(name(), FILE, at("value = a + 1"), true).startsWith("Preview:"));
        assertTrue(service.inlineMethod(name(), FILE, at("twice(a)"), true, true, true).startsWith("Preview:"));
        assertTrue(service.changeSignature(name(), FILE, at("join(String"), "label", null, null, null, true).startsWith("Preview:"));
        assertEquals(original, unit.getSource());
        assertEquals(originalCaller, caller.getSource());
    }

    @Test void invalidPreviewLeavesSourceUnchanged() throws Exception {
        String original = unit.getSource();
        assertThrows(RuntimeException.class,
                () -> service.rename(name(), FILE, at("value = a + 1"), "a", IJavaElement.LOCAL_VARIABLE, true));
        assertEquals(original, unit.getSource());
    }

    @Test void httpClientsCanPreviewApplyAndBuildWithCorrectSchemasAndErrorStatus() throws Exception {
        var refactor = new EclipseRefactorMcpServer();
        var field = EclipseRefactorMcpServer.class.getDeclaredField("javaRefactoringService");
        field.setAccessible(true);
        field.set(refactor, service);
        var ide = new EclipseIntegrationsMcpServer();
        field = EclipseIntegrationsMcpServer.class.getDeclaredField("workspaceBuildService");
        field.setAccessible(true);
        field.set(ide, new WorkspaceBuildService());
        var mapper = new JacksonMcpJsonMapperSupplier();
        var refactorTransport = HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(mapper.get()).mcpEndpoint("/mcp/eclipse-refactor").build();
        var buildTransport = HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(mapper.get()).mcpEndpoint("/mcp/eclipse-ide").build();
        var factory = new McpServerFactory();
        var refactorServer = factory.createSyncServer(refactor, refactorTransport);
        var buildServer = factory.createSyncServer(ide, buildTransport);
        org.apache.catalina.webresources.TomcatURLStreamHandlerFactory.disable();
        var directory = Files.createTempDirectory("refactor-build-http");
        var tomcat = new Tomcat();
        tomcat.setBaseDir(directory.toString());
        tomcat.setPort(0);
        tomcat.getConnector().setProperty("address", "127.0.0.1");
        var context = tomcat.addContext("", directory.toString());
        HttpMcpServletConfiguration.configureAuthentication(context, "test-token");
        HttpMcpServletConfiguration.addServlet(context, "eclipse-refactor", refactorTransport);
        HttpMcpServletConfiguration.addServlet(context, "eclipse-ide", buildTransport);
        try {
            tomcat.start();
            String base = "http://127.0.0.1:" + tomcat.getConnector().getLocalPort();
            var refactorClient = McpClient.sync(HttpClientStreamableHttpTransport.builder(base)
                    .endpoint("/mcp/eclipse-refactor").httpRequestCustomizer((r, m, u, b, c) -> r.header("Authorization", "Bearer test-token")).build())
                    .requestTimeout(Duration.ofSeconds(60)).build();
            var buildClient = McpClient.sync(HttpClientStreamableHttpTransport.builder(base)
                    .endpoint("/mcp/eclipse-ide").httpRequestCustomizer((r, m, u, b, c) -> r.header("Authorization", "Bearer test-token")).build())
                    .requestTimeout(Duration.ofSeconds(60)).build();
            try {
                refactorClient.initialize();
                buildClient.initialize();
                var renameTool = refactorClient.listTools().tools().stream().filter(t -> t.name().equals("refactorRenameMethod")).findFirst().orElseThrow();
                assertEquals("boolean", ((Map<?, ?>) renameTool.inputSchema().properties().get("preview")).get("type"));
                var buildTool = buildClient.listTools().tools().stream().filter(t -> t.name().equals("buildProjects")).findFirst().orElseThrow();
                assertEquals("integer", ((Map<?, ?>) buildTool.inputSchema().properties().get("timeoutSeconds")).get("type"));
                String original = unit.getSource();
                var variableTool = refactorClient.listTools().tools().stream().filter(t -> t.name().equals("refactorRenameVariable")).findFirst().orElseThrow();
                assertEquals("integer", ((Map<?, ?>) variableTool.inputSchema().properties().get("lineNumber")).get("type"));
                assertEquals("integer", ((Map<?, ?>) variableTool.inputSchema().properties().get("offset")).get("type"));
                assertFalse(variableTool.inputSchema().required().contains("offset"));
                var variablePreview = refactorClient.callTool(new McpSchema.CallToolRequest("refactorRenameVariable",
                        Map.of("projectName", name(), "filePath", "Example", "oldName", "value", "newName", "amount", "lineNumber", 8, "methodName", "compute", "preview", true)));
                assertFalse(variablePreview.isError(), variablePreview.toString());
                assertEquals(original, unit.getSource());
                assertTrue(refactorClient.callTool(new McpSchema.CallToolRequest("refactorRenameVariable",
                        Map.of("projectName", name(), "filePath", FILE, "oldName", "a", "newName", "input", "methodName", "sum"))).isError());
                var offsetPreview = refactorClient.callTool(new McpSchema.CallToolRequest("refactorRenameVariable",
                        Map.of("projectName", name(), "filePath", FILE, "offset", at("value = a + 1"), "newName", "amount", "preview", true)));
                assertFalse(offsetPreview.isError(), offsetPreview.toString());
                Map<String, Object> args = Map.of("projectName", name(), "filePath", FILE, "offset", at("sum(int a, int b)"), "newName", "add", "preview", true);
                var preview = refactorClient.callTool(new McpSchema.CallToolRequest("refactorRenameMethod", args));
                assertFalse(preview.isError(), preview.toString());
                String previewText = ((McpSchema.TextContent) preview.content().getFirst()).text();
                assertTrue(previewText.startsWith("Preview:"));
                assertEquals(original, unit.getSource());
                Map<String, Object> applyArgs = Map.of("previewId", previewId(previewText));
                assertFalse(refactorClient.callTool(new McpSchema.CallToolRequest("refactorGetPreview", applyArgs)).isError());
                assertFalse(refactorClient.callTool(new McpSchema.CallToolRequest("refactorApplyPreview", applyArgs)).isError());
                assertTrue(refactorClient.callTool(new McpSchema.CallToolRequest("refactorApplyPreview", applyArgs)).isError());
                assertTrue(refactorClient.callTool(new McpSchema.CallToolRequest("refactorApplyPreview", Map.of("previewId", UUID.randomUUID().toString()))).isError());
                var build = buildClient.callTool(new McpSchema.CallToolRequest("buildProjects", Map.of("projectNames", name(), "timeoutSeconds", 60)));
                assertFalse(build.isError(), build.toString());
                assertTrue(((McpSchema.TextContent) build.content().getFirst()).text().contains("completed: SUCCESS"));
                var invalid = buildClient.callTool(new McpSchema.CallToolRequest("buildProjects", Map.of("projectNames", "missing_" + UUID.randomUUID())));
                assertTrue(invalid.isError());
            } finally {
                refactorClient.close();
                buildClient.close();
            }
        } finally {
            refactorServer.close();
            buildServer.close();
            tomcat.stop();
            tomcat.destroy();
            try (var paths = Files.walk(directory)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    @Test void changedSelectedSourceInvalidatesPreviewWithoutTouchingCaller() throws Exception {
        String id = previewId(service.rename(name(), FILE, at("count ="), "total", IJavaElement.FIELD, true));
        String originalCaller = caller.getSource();
        String changed = unit.getSource() + "\n// changed after preview\n";
        unit.getBuffer().setContents(changed);
        unit.save(monitor, true);
        var error = assertThrows(RuntimeException.class, () -> service.applyPreview(id));
        assertTrue(error.getMessage().contains("stale"), error.getMessage());
        assertEquals(changed, unit.getSource());
        assertEquals(originalCaller, caller.getSource());
        assertThrows(RuntimeException.class, () -> service.applyPreview(id));
    }

    @Test void changedCallerInvalidatesEntirePreviewBeforeAnyFileIsWritten() throws Exception {
        String id = previewId(service.rename(name(), FILE, at("count ="), "total", IJavaElement.FIELD, true));
        String original = unit.getSource();
        String changed = caller.getSource() + "\n// new user work\n";
        caller.getBuffer().setContents(changed);
        caller.save(monitor, true);
        assertThrows(RuntimeException.class, () -> service.applyPreview(id));
        assertEquals(original, unit.getSource());
        assertEquals(changed, caller.getSource());
    }

    @Test void sameSizeExternalCallerEditWithPreservedTimestampInvalidatesPreview() throws Exception {
        String id = previewId(service.rename(name(), FILE, at("count ="), "total", IJavaElement.FIELD, true));
        String original = unit.getSource();
        var path = caller.getResource().getLocation().toFile().toPath();
        var stamp = Files.getLastModifiedTime(path);
        String changed = Files.readString(path).replace(".sum(2, 3)", ".sum(4, 3)");
        Files.writeString(path, changed);
        Files.setLastModifiedTime(path, stamp);
        assertThrows(RuntimeException.class, () -> service.applyPreview(id));
        assertEquals(original, unit.getSource());
        assertEquals(changed, Files.readString(path));
    }

    @Test void unsavedCallerBufferAfterPreviewIsPreservedAndRejectsApply() throws Exception {
        String id = previewId(service.rename(name(), FILE, at("count ="), "total", IJavaElement.FIELD, true));
        var manager = org.eclipse.core.filebuffers.FileBuffers.getTextFileBufferManager();
        var path = caller.getResource().getFullPath();
        var kind = org.eclipse.core.filebuffers.LocationKind.IFILE;
        manager.connect(path, kind, monitor);
        var buffer = manager.getTextFileBuffer(path, kind);
        String original = unit.getSource();
        try {
            buffer.getDocument().set(caller.getSource() + "\n// unsaved after preview\n");
            var error = assertThrows(RuntimeException.class, () -> service.applyPreview(id));
            assertTrue(error.getMessage().contains("unsaved"), error.getMessage());
            assertEquals(original, unit.getSource());
            assertTrue(buffer.getDocument().get().contains("unsaved after preview"));
        } finally {
            buffer.revert(monitor);
            manager.disconnect(path, kind, monitor);
        }
    }

    @Test void unsavedJavaWorkingCopyWithoutEditorRejectsPreviewAndApply() throws Exception {
        String id = previewId(service.rename(name(), FILE, at("count ="), "total", IJavaElement.FIELD, true));
        String original = unit.getSource();
        caller.becomeWorkingCopy(monitor);
        try {
            caller.getBuffer().setContents(caller.getSource() + "\n// working copy\n");
            assertTrue(caller.hasUnsavedChanges());
            assertThrows(RuntimeException.class, () -> service.applyPreview(id));
            assertThrows(RuntimeException.class,
                    () -> service.rename(name(), FILE, at("count ="), "total", IJavaElement.FIELD, true));
            assertEquals(original, unit.getSource());
            assertTrue(caller.getSource().contains("working copy"));
        } finally {
            caller.discardWorkingCopy();
        }
    }

    @Test void distinctTokensApplyOnlyTheirFrozenOperationAndSupportUndo() throws Exception {
        String original = unit.getSource();
        String callerOriginal = caller.getSource();
        String method = previewId(service.rename(name(), FILE, at("sum(int a, int b)"), "add", IJavaElement.METHOD, true));
        String field = previewId(service.rename(name(), FILE, at("count ="), "total", IJavaElement.FIELD, true));
        String result = service.applyPreview(field);
        assertTrue(result.contains("exactly as previewed"), result);
        assertTrue(unit.getSource().contains("total = 2"));
        assertTrue(unit.getSource().contains("sum(int a, int b)"));
        assertThrows(RuntimeException.class, () -> service.applyPreview(field));
        assertThrows(RuntimeException.class, () -> service.applyPreview(method));
        var undo = org.eclipse.ltk.core.refactoring.RefactoringCore.getUndoManager();
        org.eclipse.ui.PlatformUI.getWorkbench().getDisplay().syncExec(() -> {
            try { undo.performUndo(null, monitor); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        assertEquals(original, unit.getSource());
        assertEquals(callerOriginal, caller.getSource());
    }

    @Test void newReferenceDoesNotExpandFrozenPreview() throws Exception {
        String id = previewId(service.rename(name(), FILE, at("sum(int a, int b)"), "add", IJavaElement.METHOD, true));
        var extra = unit.getParent() instanceof IPackageFragment pkg
                ? pkg.createCompilationUnit("NewCaller.java", """
                    package sample;
                    public class NewCaller { int run() { return new Example().sum(1, 2); } }
                    """, true, monitor) : null;
        assertNotNull(extra);
        String originalExtra = extra.getSource();
        service.applyPreview(id);
        assertTrue(caller.getSource().contains(".add(2, 3)"));
        assertEquals(originalExtra, extra.getSource(), "Applying the preview must never discover and add edits.");
    }

    @Test void renameFieldUpdatesCrossFileReferences() throws Exception {
        service.rename(name(), FILE, at("count ="), "total", IJavaElement.FIELD);
        assertTrue(unit.getSource().contains("total = 2"));
        assertTrue(caller.getSource().contains(".total"));
        assertTrue(unit.getSource().contains("String text, int count"));
        buildWithoutErrors();
    }

    @Test void renameVariablePreservesOtherScopes() throws Exception {
        service.rename(name(), FILE, at("value = a + 1"), "amount", IJavaElement.LOCAL_VARIABLE);
        assertTrue(unit.getSource().contains("return amount * 2"));
        assertTrue(unit.getSource().contains("int value = a + 2; return value"));
        buildWithoutErrors();
    }

    @Test void changeSignatureReordersRenamesAndAddsParametersAtCallers() throws Exception {
        service.changeSignature(name(), FILE, at("join(String"), "label", null, "public", """
            [{"oldIndex":1,"name":"times","type":"int"},
             {"oldIndex":0,"name":"text","type":"String"},
             {"oldIndex":-1,"name":"enabled","type":"boolean","defaultValue":"true"}]
            """);
        assertTrue(unit.getSource().contains("label(int times, String text, boolean enabled)"));
        assertTrue(unit.getSource().contains("text + times"));
        assertTrue(caller.getSource().contains(".label(2, \"x\", true)"));
        buildWithoutErrors();
    }

    @Test void changeSignatureChangesReturnType() throws Exception {
        service.changeSignature(name(), FILE, at("sum(int a, int b)"), null, "long", null, null);
        assertTrue(unit.getSource().contains("long sum(int a, int b)"));
        buildWithoutErrors();
    }

    @Test void changeSignatureRemovesUnusedParameters() throws Exception {
        service.changeSignature(name(), FILE, at("optional(int"), null, null, null, "[]");
        assertTrue(unit.getSource().contains("optional()"));
        assertFalse(unit.getSource().contains("optional(3)"));
        assertTrue(unit.getSource().contains("return optional()"));
        buildWithoutErrors();
    }

    @Test void changeSignatureRejectsRemovalOfUsedParameters() throws Exception {
        String before = unit.getSource();
        assertThrows(RuntimeException.class, () -> service.changeSignature(name(), FILE, at("sum(int a, int b)"), null, null, null, "[]"));
        assertEquals(before, unit.getSource());
    }

    @Test void extractVariableUsesUtf16SelectionAndFinal() throws Exception {
        service.extractVariable(name(), FILE, at("a + 1"), 5, "incremented", false, true);
        assertTrue(unit.getSource().contains("final int incremented = a + 1"));
        assertTrue(unit.getSource().contains("int value = incremented"));
        buildWithoutErrors();
    }

    @Test void extractConstantReplacesAllOccurrences() throws Exception {
        service.extractConstant(name(), FILE, at("42 + 42"), 2, "LIMIT", "private", true);
        assertTrue(unit.getSource().contains("static final int LIMIT = 42"));
        assertTrue(unit.getSource().contains("LIMIT + LIMIT"));
        buildWithoutErrors();
    }

    @Test void extractConstantSupportsPackageVisibilityAndSingleOccurrence() throws Exception {
        service.extractConstant(name(), FILE, at("42 + 42"), 2, "LIMIT", "package", null);
        assertTrue(unit.getSource().contains("static final int LIMIT = 42"));
        assertTrue(unit.getSource().contains("LIMIT + 42"));
        assertFalse(unit.getSource().contains("private static final int LIMIT"));
        buildWithoutErrors();
    }

    @Test void inlineVariableRemovesDeclarationAndKeepsOtherScopes() throws Exception {
        service.inlineVariable(name(), FILE, at("value = a + 1"));
        assertFalse(unit.getSource().contains("int value = a + 1"));
        assertTrue(unit.getSource().contains("(a + 1) * 2"));
        assertTrue(unit.getSource().contains("int value = a + 2"));
        buildWithoutErrors();
    }

    @Test void inlineMethodReplacesCallAndDeletesDeclarationOnRequest() throws Exception {
        service.inlineMethod(name(), FILE, at("twice(a)"), true, true);
        assertFalse(unit.getSource().contains("twice("));
        assertTrue(unit.getSource().contains("a * 2 + count"));
        buildWithoutErrors();
    }

    @Test void inlineMethodDefaultsToSingleInvocationAndKeepsDeclaration() throws Exception {
        service.inlineMethod(name(), FILE, at("twice(a)"), null, null);
        assertTrue(unit.getSource().contains("int twice(int a)"));
        assertFalse(unit.getSource().contains("return twice(a)"));
        buildWithoutErrors();
    }

    @Test void dirtyCallerBufferPreventsCrossFileRename() throws Exception {
        var manager = org.eclipse.core.filebuffers.FileBuffers.getTextFileBufferManager();
        var path = caller.getResource().getFullPath();
        var kind = org.eclipse.core.filebuffers.LocationKind.IFILE;
        manager.connect(path, kind, monitor);
        var buffer = manager.getTextFileBuffer(path, kind);
        String before = unit.getSource();
        try {
            buffer.getDocument().set(caller.getSource() + "\n// unsaved user input\n");
            assertTrue(buffer.isDirty());
            RuntimeException error = assertThrows(RuntimeException.class,
                    () -> service.rename(name(), FILE, at("count ="), "total", IJavaElement.FIELD));
            assertTrue(error.getMessage().contains("unsaved"), error.getMessage());
            assertEquals(before, unit.getSource());
            assertTrue(buffer.getDocument().get().contains("unsaved user input"));
        } finally {
            buffer.revert(monitor);
            manager.disconnect(path, kind, monitor);
        }
    }

    @Test void invalidSelectionAndConflictingNameLeaveSourceUnchanged() throws Exception {
        String before = unit.getSource();
        assertThrows(RuntimeException.class, () -> service.rename(name(), FILE, at("sum(int a)"), "sum", IJavaElement.FIELD));
        assertThrows(RuntimeException.class, () -> service.extractVariable(name(), FILE, before.length(), 5, "invalid", null, null));
        assertThrows(RuntimeException.class, () -> service.rename(name(), FILE, at("value = a + 1"), "a", IJavaElement.LOCAL_VARIABLE));
        assertThrows(RuntimeException.class, () -> service.changeSignature(name(), FILE, at("join(String"), null, null, null,
                "[{\"oldIndex\":4,\"name\":\"x\",\"type\":\"int\"}]"));
        assertEquals(before, unit.getSource());
    }
}
