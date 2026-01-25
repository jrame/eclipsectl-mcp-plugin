package eclipsectlmcp.mcp.http;

import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jface.preference.IPreferenceStore;

import eclipsectlmcp.Activator;
import jakarta.inject.Singleton;

@Creatable
@Singleton
public class HttpMcpServerPreferencesProvider
{
    public HttpMcpServerPreferences get()
    {
        IPreferenceStore preferenceStore = Activator.getDefault().getPreferenceStore();

        String hostname = preferenceStore.getString(eclipsectlmcp.preferences.PreferenceConstants.ECLIPSECTL_MCP_HTTP_HOSTNAME);
        int port = preferenceStore.getInt(eclipsectlmcp.preferences.PreferenceConstants.ECLIPSECTL_MCP_HTTP_PORT);
        String token = preferenceStore.getString(eclipsectlmcp.preferences.PreferenceConstants.ECLIPSECTL_MCP_HTTP_AUTH_TOKEN);

        return new HttpMcpServerPreferences(port, hostname, token);
    }

    public void save(HttpMcpServerPreferences preferences)
    {
        IPreferenceStore preferenceStore = Activator.getDefault().getPreferenceStore();

        preferenceStore.setValue(eclipsectlmcp.preferences.PreferenceConstants.ECLIPSECTL_MCP_HTTP_HOSTNAME, preferences.hostname());
        preferenceStore.setValue(eclipsectlmcp.preferences.PreferenceConstants.ECLIPSECTL_MCP_HTTP_PORT, preferences.port());
        preferenceStore.setValue(eclipsectlmcp.preferences.PreferenceConstants.ECLIPSECTL_MCP_HTTP_AUTH_TOKEN, preferences.token());
    }

    public boolean isEnabled()
    {
        IPreferenceStore preferenceStore = Activator.getDefault().getPreferenceStore();
        return preferenceStore.getBoolean(eclipsectlmcp.preferences.PreferenceConstants.ECLIPSECTL_MCP_HTTP_ENABLED);
    }

    public void setEnabled(boolean enabled)
    {
        IPreferenceStore preferenceStore = Activator.getDefault().getPreferenceStore();
        preferenceStore.setValue(eclipsectlmcp.preferences.PreferenceConstants.ECLIPSECTL_MCP_HTTP_ENABLED, enabled);
    }

    public void resetToDefaults()
    {
        IPreferenceStore preferenceStore = Activator.getDefault().getPreferenceStore();
        
        preferenceStore.setToDefault(eclipsectlmcp.preferences.PreferenceConstants.ECLIPSECTL_MCP_HTTP_HOSTNAME);
        preferenceStore.setToDefault(eclipsectlmcp.preferences.PreferenceConstants.ECLIPSECTL_MCP_HTTP_PORT);
        preferenceStore.setToDefault(eclipsectlmcp.preferences.PreferenceConstants.ECLIPSECTL_MCP_HTTP_AUTH_TOKEN);
        preferenceStore.setToDefault(eclipsectlmcp.preferences.PreferenceConstants.ECLIPSECTL_MCP_HTTP_ENABLED);
    }

}
