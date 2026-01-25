package eclipsectlmcp.preferences;

import java.util.UUID;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jface.preference.IPreferenceStore;

import eclipsectlmcp.Activator;
import eclipsectlmcp.mcp.McpServerRepository;
import eclipsectlmcp.preferences.mcp.McpServerDescriptorUtilities;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Class used to initialize MCP server preference values.
 */
@Creatable
@Singleton
public class PreferenceInitializer extends AbstractPreferenceInitializer
{
    @Inject
    private McpServerRepository mcpServerRepository;
    
    public void init()
    {
        mcpServerRepository = Activator.getDefault().make( McpServerRepository.class );
    }

    public void initializeDefaultPreferences()
    {
        init();

        IPreferenceStore store = Activator.getDefault().getPreferenceStore();

        // Initialize MCP Server defaults
        var descriptors = mcpServerRepository.listBuiltInServers();
        String mcpServersJson = McpServerDescriptorUtilities.toJson( descriptors );
        store.setDefault(PreferenceConstants.ECLIPSECTL_DEFINED_MCP_SERVERS, mcpServersJson);

        // Initialize Tmux defaults
        store.setDefault(PreferenceConstants.ECLIPSECTL_TMUX_SESSION_NAME, "main");
        store.setDefault(PreferenceConstants.ECLIPSECTL_TMUX_COMMAND_TEMPLATE,
                "tmux load-buffer - && tmux paste-buffer -t ${session}");
        store.setDefault(PreferenceConstants.ECLIPSECTL_COPY_SELECTION_TO_CLIPBOARD, true);
        store.setDefault(PreferenceConstants.ECLIPSECTL_SEND_SELECTION_TO_TMUX, true);

        // Initialize HTTP MCP Server defaults
        store.setDefault(PreferenceConstants.ECLIPSECTL_MCP_HTTP_HOSTNAME, "localhost");
        store.setDefault(PreferenceConstants.ECLIPSECTL_MCP_HTTP_PORT, 8124);
        store.setDefault(PreferenceConstants.ECLIPSECTL_MCP_HTTP_ENABLED, false);

        // Generate auth token only if it doesn't exist
        String existingToken = store.getString(PreferenceConstants.ECLIPSECTL_MCP_HTTP_AUTH_TOKEN);
        if (existingToken == null || existingToken.isEmpty()) {
            store.setValue(PreferenceConstants.ECLIPSECTL_MCP_HTTP_AUTH_TOKEN, UUID.randomUUID().toString());
        }
    }
}
