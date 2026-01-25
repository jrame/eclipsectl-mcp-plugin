package eclipsectlmcp.preferences.tmux;

import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jface.preference.IPreferenceStore;

import eclipsectlmcp.Activator;
import eclipsectlmcp.preferences.PreferenceConstants;
import jakarta.inject.Singleton;

@Creatable
@Singleton
public class TmuxPreferencesProvider
{
    public TmuxPreferences get()
    {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        String sessionName = store.getString( PreferenceConstants.ECLIPSECTL_TMUX_SESSION_NAME );
        String commandTemplate = store.getString( PreferenceConstants.ECLIPSECTL_TMUX_COMMAND_TEMPLATE );
        boolean copyToClipboard = store.getBoolean( PreferenceConstants.ECLIPSECTL_COPY_SELECTION_TO_CLIPBOARD );
        boolean sendToTmux = store.getBoolean( PreferenceConstants.ECLIPSECTL_SEND_SELECTION_TO_TMUX );
        return new TmuxPreferences( sessionName, commandTemplate, copyToClipboard, sendToTmux );
    }

    public void save( TmuxPreferences preferences )
    {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        store.setValue( PreferenceConstants.ECLIPSECTL_TMUX_SESSION_NAME, preferences.sessionName() );
        store.setValue( PreferenceConstants.ECLIPSECTL_TMUX_COMMAND_TEMPLATE, preferences.commandTemplate() );
        store.setValue( PreferenceConstants.ECLIPSECTL_COPY_SELECTION_TO_CLIPBOARD,
                preferences.copyToClipboard() );
        store.setValue( PreferenceConstants.ECLIPSECTL_SEND_SELECTION_TO_TMUX, preferences.sendToTmux() );
    }

    public void resetToDefaults()
    {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        store.setToDefault( PreferenceConstants.ECLIPSECTL_TMUX_SESSION_NAME );
        store.setToDefault( PreferenceConstants.ECLIPSECTL_TMUX_COMMAND_TEMPLATE );
        store.setToDefault( PreferenceConstants.ECLIPSECTL_COPY_SELECTION_TO_CLIPBOARD );
        store.setToDefault( PreferenceConstants.ECLIPSECTL_SEND_SELECTION_TO_TMUX );
    }
}
