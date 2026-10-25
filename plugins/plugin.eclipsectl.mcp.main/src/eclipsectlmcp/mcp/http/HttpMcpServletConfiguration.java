package eclipsectlmcp.mcp.http;

import org.apache.catalina.Context;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;

import jakarta.servlet.Servlet;

/** Shared production servlet configuration, also used by transport integration tests. */
public final class HttpMcpServletConfiguration
{
    private HttpMcpServletConfiguration() { }

    public static void configureAuthentication(Context context, String token)
    {
        var definition = new FilterDef();
        definition.setFilterName("mcpBearerAuthentication");
        definition.setFilter(new BearerTokenAuthenticationFilter(token));
        definition.setAsyncSupported("true");
        context.addFilterDef(definition);

        var mapping = new FilterMap();
        mapping.setFilterName(definition.getFilterName());
        mapping.addURLPattern("/mcp/*");
        context.addFilterMapBefore(mapping);
    }

    public static void addServlet(Context context, String serverName, Servlet servlet)
    {
        var wrapper = context.createWrapper();
        wrapper.setName("mcpServlet_" + serverName);
        wrapper.setServlet(servlet);
        wrapper.setLoadOnStartup(1);
        wrapper.setAsyncSupported(true);
        context.addChild(wrapper);
        context.addServletMappingDecoded("/mcp/" + serverName + "/*", wrapper.getName());
    }
}
