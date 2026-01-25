package eclipsectlmcp.mcp;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.e4.core.di.annotations.Creatable;

import eclipsectlmcp.mcp.annotations.McpServer;
import eclipsectlmcp.mcp.servers.EclipseCodeGenerationMcpServer;
import eclipsectlmcp.mcp.servers.EclipseCoverageMcpServer;
import eclipsectlmcp.mcp.servers.EclipseDebugMcpServer;
import eclipsectlmcp.mcp.servers.EclipseIntegrationsMcpServer;
import eclipsectlmcp.mcp.servers.EclipseMcpServer;
import eclipsectlmcp.mcp.servers.EclipseRefactorMcpServer;
import eclipsectlmcp.mcp.servers.EclipseSourceMcpServer;
import jakarta.inject.Singleton;

@Creatable
@Singleton
public class McpServerBuiltins
{
    
    public static final Class<?>[] BUILT_IN_MCP_SERVERS = {
            EclipseMcpServer.class,
            EclipseIntegrationsMcpServer.class,
            EclipseRefactorMcpServer.class,
            EclipseCodeGenerationMcpServer.class,
            EclipseSourceMcpServer.class,
            EclipseDebugMcpServer.class,
            EclipseCoverageMcpServer.class
    };
    
    public List<McpServerDescriptor> listBuiltInImplementations()
    {
        return Stream.of( BUILT_IN_MCP_SERVERS )
                      .map( this::toBuiltInMcpServerDescriptor )
                      .collect( Collectors.toList() );        
    }
    
    private McpServerDescriptor toBuiltInMcpServerDescriptor( Class<?> clazz )
    {
        String serverName = clazz.getAnnotation( McpServer.class ).name();
        return new McpServerDescriptor( serverName, 
                serverName, 
                "",
                Collections.emptyList(),
                Collections.emptyList(),
                true, 
                true );
    }

    public Class<?> findImplementation( String name )
    {
        Objects.requireNonNull( name );
        return Stream.of( BUILT_IN_MCP_SERVERS )
                     .filter( clazz -> clazz.getAnnotation( McpServer.class ).name().equals( name ) )
                     .findAny()
                     .orElseThrow( () -> new IllegalArgumentException( "No implementation for name: " + name  ) );
        
    }
}
