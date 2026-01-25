package eclipsectlmcp.preferences.tmux;

import org.eclipse.e4.core.di.annotations.Creatable;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Creatable
@Singleton
public class TmuxPreferencePresenter
{
    @Inject
    private TmuxPreferencesProvider preferencesProvider;

    private TmuxPreferencePage view;

    public void registerView( TmuxPreferencePage view )
    {
        this.view = view;
        TmuxPreferences prefs = preferencesProvider.get();
        view.updatePreferences( prefs );
    }

    public void savePreferences( String sessionName, String commandTemplate, boolean copyToClipboard,
            boolean sendToTmux )
    {
        preferencesProvider.save( new TmuxPreferences(
                sessionName, commandTemplate, copyToClipboard, sendToTmux ) );
        if ( view != null )
        {
            view.updatePreferences( preferencesProvider.get() );
        }
    }

    public void onPerformDefaults()
    {
        preferencesProvider.resetToDefaults();
        if ( view != null )
        {
            view.updatePreferences( preferencesProvider.get() );
        }
    }
}
