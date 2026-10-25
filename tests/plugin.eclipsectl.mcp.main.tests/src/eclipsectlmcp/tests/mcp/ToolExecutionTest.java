package eclipsectlmcp.tests.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.*;

import org.eclipse.e4.ui.di.UISynchronize;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import eclipsectlmcp.mcp.McpServerFactory;
import eclipsectlmcp.mcp.ToolExecution;
import eclipsectlmcp.mcp.ToolExecutor;
import eclipsectlmcp.mcp.annotations.Tool;
import eclipsectlmcp.mcp.services.CodeGenerationService;
import eclipsectlmcp.tools.UISynchronizeCallable;
import io.modelcontextprotocol.spec.McpSchema;

@Timeout(15)
public class ToolExecutionTest
{
    public static class Tools
    {
        @Tool(name="content", description="Read arbitrary content")
        public String content() { return "Error: this is file or console content"; }

        @Tool(name="business", description="Service validation failure")
        public String business() { return new CodeGenerationService().generateGettersSetters(null, "", null, null, true, true, null); }

        @Tool(name="timeout", description="Timeout status")
        public String timeout() { return ToolExecution.error("Error: Test execution timed out after 0 seconds."); }

        @Tool(name="exception", description="Reflection failure")
        public String exception() { throw new IllegalStateException("root failure"); }

        @Tool(name="batch", description="Aggregate failures")
        public String batch()
        {
            return "Project A: passed\nProject B: " + ToolExecution.error("Error: cannot start") + "\nProject C: passed";
        }
    }

    private McpSchema.CallToolResult invoke(String name) throws Exception
    {
        McpServerFactory factory = new McpServerFactory();
        Method definitions = McpServerFactory.class.getDeclaredMethod("extractAnnotatedTools", Method[].class);
        definitions.setAccessible(true);
        ToolExecutor executor = new ToolExecutor(new Tools());
        @SuppressWarnings("unchecked")
        var tools = (java.util.List<McpSchema.Tool>) definitions.invoke(factory, (Object) executor.getFunctions());
        McpSchema.Tool tool = tools.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
        Method call = McpServerFactory.class.getDeclaredMethod("executeCallTool", ToolExecutor.class, McpSchema.Tool.class, Map.class);
        call.setAccessible(true);
        return (McpSchema.CallToolResult) call.invoke(factory, executor, tool, Map.of());
    }

    private String text(McpSchema.CallToolResult result)
    {
        return ((McpSchema.TextContent) result.content().getFirst()).text();
    }

    @Test
    public void preservesArbitraryErrorTextAsSuccessfulContent() throws Exception
    {
        var result = invoke("content");
        assertFalse(result.isError());
        assertEquals("Error: this is file or console content", text(result));
    }

    @Test
    public void mapsServiceValidationTimeoutAndBatchToMcpErrors() throws Exception
    {
        var business = invoke("business");
        assertTrue(business.isError());
        assertEquals("Error: File path cannot be empty.", text(business));
        var timeout = invoke("timeout");
        assertTrue(timeout.isError());
        assertEquals("Error: Test execution timed out after 0 seconds.", text(timeout));
        var batch = invoke("batch");
        assertTrue(batch.isError());
        assertTrue(text(batch).contains("Project C: passed"));
        assertFalse(invoke("content").isError());
    }

    @Test
    public void unwrapsReflectionExceptionToRootCause() throws Exception
    {
        var result = invoke("exception");
        assertTrue(result.isError());
        assertEquals("Error: IllegalStateException: root failure", text(result));
    }

    @Test
    public void restoresNestedAndExceptionalContexts() throws Exception
    {
        ToolExecution.error("outside invocation");
        var outer = ToolExecution.capture(() -> {
            assertTrue(ToolExecution.capture(() -> ToolExecution.error("inner")).isError());
            assertThrows(IllegalStateException.class, () -> ToolExecution.capture(() -> {
                ToolExecution.error("failed inner");
                throw new IllegalStateException();
            }));
            return "outer success";
        });
        assertFalse(outer.isError());
        assertFalse(ToolExecution.capture(() -> "next invocation").isError());
    }

    @Test
    public void isolatesConcurrentInvocations() throws Exception
    {
        try (ExecutorService pool = Executors.newFixedThreadPool(2))
        {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<ToolExecution.Result> failed = pool.submit(() -> ToolExecution.capture(() -> {
                ToolExecution.error("business failure");
                barrier.await();
                return "failed";
            }));
            Future<ToolExecution.Result> passed = pool.submit(() -> ToolExecution.capture(() -> {
                barrier.await();
                return "Error: arbitrary text";
            }));
            assertTrue(failed.get().isError());
            assertFalse(passed.get().isError());
        }
    }

    @Test
    public void propagatesToReusedUiThreadAndRestoresIt() throws Exception
    {
        try (ExecutorService ui = Executors.newSingleThreadExecutor())
        {
            UISynchronizeCallable sync = new UISynchronizeCallable();
            sync.uiSync = new UISynchronize() {
                @Override public void syncExec(Runnable task) {
                    try { ui.submit(task).get(); }
                    catch (Exception e) { throw new RuntimeException(e); }
                }
                @Override public void asyncExec(Runnable task) { ui.submit(task); }
                @Override public boolean isUIThread(Thread thread) { return false; }
                @Override public void showBusyWhile(Runnable task) { syncExec(task); }
                @Override public boolean dispatchEvents() { return false; }
            };
            assertTrue(ToolExecution.capture(() -> sync.syncCall(() -> ToolExecution.error("UI failure"))).isError());
            assertFalse(ToolExecution.capture(() -> sync.syncCall(() -> "Error: UI content")).isError());
            assertTrue(ToolExecution.capture(() -> sync.asyncCall(() -> ToolExecution.error("async UI failure")).get()).isError());
            assertTrue(ToolExecution.capture(() -> {
                sync.syncExec(() -> ToolExecution.error("sync UI failure"));
                return "aggregate";
            }).isError());
            // A callback submitted outside capture must not retain the preceding tool's status.
            sync.syncExec(() -> ToolExecution.error("outside invocation"));
            assertFalse(ToolExecution.capture(() -> sync.syncCall(() -> "clean")).isError());
        }
    }
}
