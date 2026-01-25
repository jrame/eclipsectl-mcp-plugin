package eclipsectlmcp.preferences.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.eclipse.e4.core.di.annotations.Creatable;

import eclipsectlmcp.mcp.McpServerDescriptor;
import eclipsectlmcp.mcp.McpServerDescriptor.McpServerDescriptorWithStatus;
import eclipsectlmcp.mcp.McpServerDescriptor.Status;
import eclipsectlmcp.mcp.McpServerRepository;
import eclipsectlmcp.mcp.http.HttpMcpServerRegistry;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Presenter for the built-in MCP server preferences.
 */
@Creatable
@Singleton
public class McpServerPreferencePresenter
{
    @Inject
    private McpServerRepository mcpServerRepository;

    @Inject
    private HttpMcpServerRegistry httpServerRegistry;

    private McpServerPreferencePage view;

    public List<McpServerDescriptorWithStatus> getServersWithStatus()
    {
        var endpoints = httpServerRegistry.listEndpoints();
        return getServers().stream()
                .map(server -> new McpServerDescriptorWithStatus(
                        server,
                        server.enabled() && endpoints.stream().anyMatch(endpoint -> endpoint.endsWith("/" + server.name()))
                                ? Status.RUNNING
                                : Status.NOT_CONNECTED))
                .toList();
    }

    public Optional<McpServerDescriptor> getServerAt(int index)
    {
        var servers = getServers();
        return index >= 0 && index < servers.size() ? Optional.of(servers.get(index)) : Optional.empty();
    }

    public void addServer()
    {
        view.showError("External MCP servers are not supported in standalone mode.");
    }

    public void toggleServerEnabled(int serverIndex, boolean enabled)
    {
        var servers = new ArrayList<>(getServers());
        if (serverIndex < 0 || serverIndex >= servers.size())
        {
            return;
        }

        McpServerDescriptor server = servers.get(serverIndex);
        servers.set(serverIndex, new McpServerDescriptor(
                server.uid(),
                server.name(),
                server.command(),
                server.args(),
                server.environmentVariables(),
                enabled,
                true));

        mcpServerRepository.save(servers);
        httpServerRegistry.rebuild();
        view.showServers(getServersWithStatus());
    }

    public void removeServer(int selectedIndex)
    {
        view.showError("Built-in MCP servers cannot be removed.");
    }

    public void saveServer(int selectedIndex, McpServerDescriptor updatedServerStub)
    {
        view.showError("Built-in MCP server details are read-only in standalone mode.");
    }

    public void setSelectedServer(int selectedIndex)
    {
        getServerAt(selectedIndex).ifPresentOrElse(server -> {
            view.showServerDetails(server);
            view.setDetailsEditable(false);
            view.setRemoveEditable(false);
        }, () -> {
            view.clearServerDetails();
            view.setDetailsEditable(false);
            view.setRemoveEditable(false);
        });
    }

    public void registerView(McpServerPreferencePage mcpServerPreferencePage)
    {
        view = mcpServerPreferencePage;
        view.showServers(getServersWithStatus());
        view.setDetailsEditable(false);
        view.setRemoveEditable(false);
    }

    public void onPerformDefaults()
    {
        mcpServerRepository.setToDefault();
        httpServerRegistry.rebuild();
        view.showServers(getServersWithStatus());
        view.clearServerDetails();
        view.setDetailsEditable(false);
        view.setRemoveEditable(false);
    }

    private List<McpServerDescriptor> getServers()
    {
        var stored = mcpServerRepository.listStoredServers();
        return mcpServerRepository.listBuiltInServers().stream()
                .map(builtin -> stored.stream()
                        .filter(server -> builtin.uid().equals(server.uid()))
                        .findFirst()
                        .orElse(builtin))
                .toList();
    }
}
