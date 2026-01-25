package eclipsectlmcp.mcp.http;


import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.startup.Tomcat;
import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.e4.ui.workbench.lifecycle.PostWorkbenchClose;

import eclipsectlmcp.mcp.McpServerDescriptor;
import eclipsectlmcp.mcp.McpServerFactory;
import eclipsectlmcp.mcp.McpServerRepository;
import io.modelcontextprotocol.json.McpJsonMapperSupplier;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.servlet.Servlet;

@Creatable
@Singleton
public class HttpMcpServerRegistry
{

    private static String MCP_ENDPOINT = "/mcp";

    @Inject
    private HttpMcpServerPreferencesProvider httpServerPreferncesProvider;

    @Inject
    private McpServerRepository mcpServerRepository;

    @Inject
    private McpServerFactory mcpServerFactory;

    @Inject
    private ILog logger;

    private final List<McpSyncServer> servers = new ArrayList<>();
    private final ArrayList<String> endpoints = new ArrayList<>();

    private Tomcat tomcat;
    private McpJsonMapperSupplier jsonMapperSupplier = new JacksonMcpJsonMapperSupplier();


    public HttpMcpServerRegistry()
    {
    }
    
    /**
     * Handles the shutdown process by closing all MCP clients gracefully.
     */
    @PostWorkbenchClose
    public void handleShutdown()
    {
        servers.forEach( McpSyncServer::closeGracefully );
        if ( tomcat != null )
        {
            try
            {
                tomcat.stop();
            }
            catch ( LifecycleException e )
            {
                logger.error( "Tomcat server failed to stop: " + e.getMessage(), e );
            }
        }
    }

    @PostConstruct
    public void init()
    {
        try
        {
            logger.info( "Initializing MCP Http Server." );
            servers.clear();
            endpoints.clear();
            // Create Tomcat and ONE context upfront
            tomcat = createTomcatServer();

            String baseDir = System.getProperty("java.io.tmpdir");
            Context context = tomcat.addContext("", baseDir);  // Create context once

            var builtin = mcpServerRepository.listBuiltInServers();
            var stored  = mcpServerRepository.listStoredServers();
            initializeBuiltInServers(context, stored, builtin);  // Pass context

            restart();
        }
        catch ( Exception e )
        {
            logger.error( "Failed to initialize MCP Http Server: " + e.getMessage(), e );
            // Don't rethrow - allow the object to be created even if initialization fails
        }
    }
    
    private void initializeBuiltInServers(Context context, List<McpServerDescriptor> stored, List<McpServerDescriptor> builtin )
    {
        for ( McpServerDescriptor builtInServerDescriptor : builtin )
        {
            try
            {
                McpServerDescriptor updated = stored.stream()
                                                    .filter( other -> builtInServerDescriptor.uid().equals( other.uid() ) )
                                                    .findAny()
                                                    .orElse( builtInServerDescriptor );

                if ( updated.enabled() )
                {
                    var implementation = mcpServerRepository.makeImplementation( updated.name() );
                    var transportProvider = createStreamableHttpTransportProvider( updated.name() );
                    var server = mcpServerFactory.createSyncServer( implementation, transportProvider );
                    servers.add( server );
                    addServlet(context, updated.name(), transportProvider);  // Pass context and name
                    logger.info( "Successfully initialized MCP server: " + updated.name() );
                }
            }
            catch ( Exception e )
            {
                logger.error( "Failed to initialize MCP server '" + builtInServerDescriptor.name() + "': " + e.getMessage(), e );
                // Continue with next server
            }
        }
    }
    
    private void addServlet(Context context, String serverName, Servlet servlet)
    {
        // Add transport servlet to the shared context
        var wrapper = context.createWrapper();
        wrapper.setName("mcpServlet_" + serverName);  // Unique name per server
        wrapper.setServlet(servlet);
        wrapper.setLoadOnStartup(1);
        wrapper.setAsyncSupported(true);
        context.addChild(wrapper);
        context.addServletMappingDecoded("/mcp/" + serverName + "/*", "mcpServlet_" + serverName);

        // Track the endpoint
        endpoints.add(serverName);
    }

    private HttpServletStreamableServerTransportProvider createStreamableHttpTransportProvider( String name )
    {
        var transportProvider = HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(jsonMapperSupplier.get())
//                .keepAliveInterval(Duration.ofSeconds(10))
                .mcpEndpoint(MCP_ENDPOINT + "/" + name )
                .build();
        return transportProvider;
    }
    
    public List<String> listEndpoints()
    {
        var config = httpServerPreferncesProvider.get();
        String baseUrl = "http://" + config.hostname() + ":" + config.port();
        
        return endpoints.stream()
                .map(name -> baseUrl + "/mcp/" + name)
                .toList();
    }

    
    private Tomcat createTomcatServer()
    {
        // Disable Tomcat's URL stream handler factory to avoid conflicts with OSGi
        System.setProperty("tomcat.util.buf.StringCache.byte.enabled", "true");
        org.apache.catalina.webresources.TomcatURLStreamHandlerFactory.disable();
        var tomcat = new Tomcat();
        tomcat.setPort(httpServerPreferncesProvider.get().port());
        tomcat.setHostname(httpServerPreferncesProvider.get().hostname());
        
        String baseDir = System.getProperty("java.io.tmpdir");
        tomcat.setBaseDir(baseDir);

        var connector = tomcat.getConnector();
        connector.setAsyncTimeout(3000);

        return tomcat;
    }

    public boolean isRunning()
    {
        return tomcat != null && LifecycleState.STARTED.equals( tomcat.getServer().getState() );
    }

    public void restart()
    {
        if ( tomcat == null )
        {
            logger.warn( "Cannot restart MCP Http Server: Tomcat is not initialized" );
            return;
        }

        try
        {
            if ( isRunning() )
            {
                logger.info( "Stopping MCP Http Server." );
                tomcat.stop();
                logger.info( "MCP Http Server state: " + tomcat.getServer().getState() + " ." );
            }
        }
        catch ( LifecycleException e )
        {
            logger.error( "Error stopping Tomcat server: " + e.getMessage(), e );
            throw new RuntimeException( e );
        }
        if ( httpServerPreferncesProvider.isEnabled() )
        {
            try
            {
                logger.info( "Starting MCP Http Server." );
                tomcat.start();
                logger.info( "MCP Http Server state: " + tomcat.getServer().getState() + " @" + tomcat.getServer().getAddress() + ":" + tomcat.getServer().getPort() );
                logger.info( "MCP Http Server endpoints:\n " + listEndpoints().stream().collect( Collectors.joining("\n") ) );
            }
            catch ( LifecycleException e )
            {
                logger.error( "Error starting MCP Http Server: " + e.getMessage(), e );
            }
        }

    }

    public void rebuild()
    {
        handleShutdown();
        if ( tomcat != null )
        {
            try
            {
                tomcat.destroy();
            }
            catch ( LifecycleException e )
            {
                logger.error( "Tomcat server failed to destroy: " + e.getMessage(), e );
            }
        }
        tomcat = null;
        init();
    }
    
    
}
