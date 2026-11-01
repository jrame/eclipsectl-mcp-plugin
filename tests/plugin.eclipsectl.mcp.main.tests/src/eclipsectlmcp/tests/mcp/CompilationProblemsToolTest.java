package eclipsectlmcp.tests.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import eclipsectlmcp.mcp.McpServerFactory;
import eclipsectlmcp.mcp.ToolExecutor;
import eclipsectlmcp.mcp.servers.EclipseIntegrationsMcpServer;
import eclipsectlmcp.mcp.services.CodeAnalysisService;
import io.modelcontextprotocol.spec.McpSchema;

@Timeout(15)
public class CompilationProblemsToolTest
{
    private McpSchema.CallToolResult invoke(Map<String, Object> args) throws Exception
    {
        var server = new EclipseIntegrationsMcpServer();
        var service = EclipseIntegrationsMcpServer.class.getDeclaredField("codeAnalysisService");
        service.setAccessible(true);
        service.set(server, new CodeAnalysisService() {
            @Override
            public String getCompilationErrors(String project, String severity, Integer maxResults) {
                return "Project: " + project + "; severity: " + severity + "; max: " + maxResults;
            }
        });
        var executor = new ToolExecutor(server);
        var factory = new McpServerFactory();
        Method extract = McpServerFactory.class.getDeclaredMethod("extractAnnotatedTools", Method[].class);
        extract.setAccessible(true);
        @SuppressWarnings("unchecked")
        var tools = (List<McpSchema.Tool>) extract.invoke(factory, (Object) executor.getFunctions());
        var tool = tools.stream().filter(t -> t.name().equals("getCompilationErrors")).findFirst().orElseThrow();
        assertEquals("integer", ((Map<?, ?>) tool.inputSchema().properties().get("maxResults")).get("type"));
        Method call = McpServerFactory.class.getDeclaredMethod("executeCallTool", ToolExecutor.class, McpSchema.Tool.class, Map.class);
        call.setAccessible(true);
        return (McpSchema.CallToolResult) call.invoke(factory, executor, tool, args);
    }

    private String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().getFirst()).text();
    }

    @Test
    public void invokesCompilationProblemsWithJsonIntegersAndLegacyStrings() throws Exception
    {
        for (Object limit : List.of(10, 10L, new BigDecimal("10"), "10")) {
            var result = invoke(Map.of("projectName", "app", "severity", "error", "maxResults", limit));
            assertFalse(result.isError(), text(result));
            assertEquals("Project: app; severity: error; max: 10", text(result));
        }
        var omitted = invoke(Map.of());
        assertFalse(omitted.isError(), text(omitted));
        assertEquals("Project: null; severity: null; max: null", text(omitted));
    }

    @Test
    public void rejectsInvalidIntegerArgumentsWithoutReflectionMismatch() throws Exception
    {
        for (Object limit : List.of(2147483648L, new BigDecimal("10.5"), "invalid", true)) {
            var result = invoke(Map.of("maxResults", limit));
            assertTrue(result.isError());
            assertTrue(text(result).contains("Argument 'maxResults' must be a 32-bit integer"), text(result));
        }
    }
}
