package eclipsectlmcp.tests.mcp.transport;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.time.Duration;
import java.util.Map;

import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import eclipsectlmcp.mcp.McpServerFactory;
import eclipsectlmcp.mcp.annotations.McpServer;
import eclipsectlmcp.mcp.annotations.Tool;
import eclipsectlmcp.mcp.annotations.ToolParam;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

@Timeout(30)
public class TypedToolHttpTest
{
    @McpServer(name="typed")
    public static class TypedTools {
        @Tool(name="inspect", description="Scalar HTTP fixture")
        public String inspect(@ToolParam(name="line", description="Line", type="integer") int line,
                @ToolParam(name="limit", description="Limit", type="integer", required=false) Integer limit,
                @ToolParam(name="enabled", description="Enabled", type="boolean", required=false) Boolean enabled) {
            return "Line: " + line + "; limit: " + limit + "; enabled: " + enabled;
        }
    }

    @Test
    public void jsonScalarsReachAnnotatedJavaMethodsThroughTheRealHttpTransport() throws Exception
    {
        var temp = Files.createTempDirectory("eclipsectl-typed-http-");
        var tomcat = new Tomcat();
        tomcat.setBaseDir(temp.toString());
        tomcat.setPort(0);
        tomcat.getConnector().setProperty("address", "127.0.0.1");
        var context = tomcat.addContext("", temp.toString());
        var provider = HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapperSupplier().get())
                .mcpEndpoint("/mcp/typed").build();
        var server = new McpServerFactory().createSyncServer(new TypedTools(), provider);
        Tomcat.addServlet(context, "mcp", provider);
        context.addServletMappingDecoded("/mcp/typed", "mcp");
        try {
            tomcat.start();
            var transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + tomcat.getConnector().getLocalPort())
                    .endpoint("/mcp/typed").build();
            var client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10)).build();
            try {
                client.initialize();
                var schema = client.listTools().tools().getFirst().inputSchema();
                assertEquals("integer", ((Map<?, ?>) schema.properties().get("line")).get("type"));
                assertEquals("boolean", ((Map<?, ?>) schema.properties().get("enabled")).get("type"));
                for (var args : java.util.List.of(Map.<String, Object>of("line", 7, "limit", 10, "enabled", false),
                        Map.<String, Object>of("line", "7", "limit", "10", "enabled", "false"))) {
                    var result = client.callTool(McpSchema.CallToolRequest.builder().name("inspect").arguments(args).build());
                    assertFalse(result.isError());
                    assertEquals("Line: 7; limit: 10; enabled: false", ((McpSchema.TextContent) result.content().getFirst()).text());
                }
                var omitted = client.callTool(McpSchema.CallToolRequest.builder().name("inspect").arguments(Map.of("line", 7)).build());
                assertFalse(omitted.isError());
                assertEquals("Line: 7; limit: null; enabled: null", ((McpSchema.TextContent) omitted.content().getFirst()).text());
                var invalid = client.callTool(McpSchema.CallToolRequest.builder().name("inspect").arguments(Map.of("line", 2147483648L)).build());
                assertTrue(invalid.isError());
                assertTrue(((McpSchema.TextContent) invalid.content().getFirst()).text().contains("32-bit integer"));
            } finally { client.close(); }
        } finally {
            server.close();
            tomcat.stop();
            tomcat.destroy();
            // This fresh temporary directory contains only this test's embedded Tomcat files.
            try (var paths = Files.walk(temp)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
