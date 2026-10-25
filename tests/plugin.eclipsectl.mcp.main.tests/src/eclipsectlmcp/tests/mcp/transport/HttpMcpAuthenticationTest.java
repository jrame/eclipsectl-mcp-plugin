package eclipsectlmcp.tests.mcp.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;

import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import eclipsectlmcp.mcp.http.HttpMcpServletConfiguration;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Exercises the filter and endpoint mappings installed by the production registry. */
@Timeout(20)
public class HttpMcpAuthenticationTest
{
    @Test
    public void protectsEveryEndpointAndHttpMethod() throws Exception
    {
        withServer("secret", base -> {
            for (String endpoint : new String[] {"/mcp/first", "/mcp/second", "/mcp/first/child"})
            {
                for (String method : new String[] {"GET", "POST", "DELETE"})
                {
                    var missing = send(base + endpoint, method, null);
                    assertEquals(401, missing.statusCode());
                    assertEquals("Bearer realm=\"EclipseCtl MCP\"", missing.headers().firstValue("WWW-Authenticate").orElseThrow());
                    for (String header : new String[] {"Bearer wrong", "Basic secret", "Bearer", "Bearer secret-extra"})
                        assertEquals(401, send(base + endpoint, method, header).statusCode());
                    var valid = send(base + endpoint, method, "Bearer secret");
                    assertEquals(200, valid.statusCode());
                    assertEquals("authenticated", valid.body());
                    assertEquals(200, send(base + endpoint, method, "bearer secret").statusCode());
                }
            }
        });
    }

    @Test
    public void unconfiguredTokensFailClosed() throws Exception
    {
        for (String token : new String[] {null, "", "   "})
        {
            withServer(token, base -> {
                for (String header : new String[] {null, "Bearer secret", "Bearer "})
                {
                    var response = send(base + "/mcp/first", "POST", header);
                    assertEquals(503, response.statusCode());
                    assertTrue(response.body().contains("not configured"));
                }
            });
        }
    }

    private static HttpResponse<String> send(String url, String method, String header) throws Exception
    {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (header != null) request.header("Authorization", header);
        try (var client = HttpClient.newHttpClient())
        {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static void withServer(String token, ServerTest test) throws Exception
    {
        org.apache.catalina.webresources.TomcatURLStreamHandlerFactory.disable();
        var directory = Files.createTempDirectory("mcp-auth-test");
        var tomcat = new Tomcat();
        tomcat.setBaseDir(directory.toString());
        tomcat.setPort(0);
        tomcat.getConnector().setProperty("address", "127.0.0.1");
        var context = tomcat.addContext("", directory.toString());
        HttpMcpServletConfiguration.configureAuthentication(context, token);
        var servlet = new HttpServlet() {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws java.io.IOException
            {
                response.getWriter().write("authenticated");
            }
        };
        HttpMcpServletConfiguration.addServlet(context, "first", servlet);
        HttpMcpServletConfiguration.addServlet(context, "second", new HttpServlet() {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws java.io.IOException
            {
                response.getWriter().write("authenticated");
            }
        });
        try
        {
            tomcat.start();
            test.run("http://127.0.0.1:" + tomcat.getConnector().getLocalPort());
        }
        finally
        {
            tomcat.stop();
            tomcat.destroy();
            try (var paths = Files.walk(directory))
            {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    @FunctionalInterface
    private interface ServerTest
    {
        void run(String base) throws Exception;
    }
}
