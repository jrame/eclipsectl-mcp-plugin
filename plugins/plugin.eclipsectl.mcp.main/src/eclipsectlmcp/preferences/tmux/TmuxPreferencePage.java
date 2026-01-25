package eclipsectlmcp.preferences.tmux;

import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import eclipsectlmcp.Activator;

public class TmuxPreferencePage extends PreferencePage implements IWorkbenchPreferencePage
{
    private TmuxPreferencePresenter presenter;

    private Text sessionNameText;
    private Text commandTemplateText;
    private Button copyToClipboardCheckbox;
    private Button sendToTmuxCheckbox;

    @Override
    public void init( IWorkbench workbench )
    {
        presenter = Activator.getDefault().make( TmuxPreferencePresenter.class );
    }

    @Override
    protected Control createContents( Composite parent )
    {
        Composite container = new Composite( parent, SWT.NONE );
        container.setLayout( new GridLayout( 1, false ) );
        container.setLayoutData( new GridData( GridData.FILL_BOTH ) );

        // Description
        Label descLabel = new Label( container, SWT.WRAP );
        descLabel.setText( "Configure how the F9 shortcut formats and shares the current editor selection." );
        GridData descData = new GridData( GridData.FILL_HORIZONTAL );
        descData.widthHint = 400;
        descLabel.setLayoutData( descData );

        // Clipboard options
        Group clipboardGroup = new Group( container, SWT.NONE );
        clipboardGroup.setText( "Clipboard" );
        clipboardGroup.setLayout( new GridLayout( 1, false ) );
        clipboardGroup.setLayoutData( new GridData( GridData.FILL_HORIZONTAL ) );

        copyToClipboardCheckbox = new Button( clipboardGroup, SWT.CHECK );
        copyToClipboardCheckbox.setText( "Copy generated text to clipboard when using the shortcut" );
        copyToClipboardCheckbox.setToolTipText(
                "Copies the generated Markdown, including the file location and selected code, to the system clipboard" );

        // Session name group
        Group sessionGroup = new Group( container, SWT.NONE );
        sessionGroup.setText( "Tmux Session" );
        sessionGroup.setLayout( new GridLayout( 2, false ) );
        sessionGroup.setLayoutData( new GridData( GridData.FILL_HORIZONTAL ) );

        sendToTmuxCheckbox = new Button( sessionGroup, SWT.CHECK );
        sendToTmuxCheckbox.setText( "Send generated text to tmux when using the shortcut" );
        sendToTmuxCheckbox.setToolTipText(
                "Sends the generated Markdown to the configured WSL tmux session" );
        GridData sendToTmuxData = new GridData( GridData.FILL_HORIZONTAL );
        sendToTmuxData.horizontalSpan = 2;
        sendToTmuxCheckbox.setLayoutData( sendToTmuxData );

        Label sessionLabel = new Label( sessionGroup, SWT.NONE );
        sessionLabel.setText( "Session name:" );

        sessionNameText = new Text( sessionGroup, SWT.BORDER );
        sessionNameText.setLayoutData( new GridData( GridData.FILL_HORIZONTAL ) );
        sessionNameText.setToolTipText( "Name of the tmux session to send text to" );

        // Command template group
        Group commandGroup = new Group( container, SWT.NONE );
        commandGroup.setText( "Command Template" );
        commandGroup.setLayout( new GridLayout( 1, false ) );
        commandGroup.setLayoutData( new GridData( GridData.FILL_BOTH ) );

        commandTemplateText = new Text( commandGroup, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL );
        GridData templateData = new GridData( GridData.FILL_BOTH );
        templateData.heightHint = 80;
        commandTemplateText.setLayoutData( templateData );
        commandTemplateText.setToolTipText( "Command template with placeholders: ${session} and ${text}" );

        // Help text
        Label helpLabel = new Label( commandGroup, SWT.WRAP );
        helpLabel.setText( "Placeholders:\n"
                + "  ${session} - Tmux session name\n\n"
                + "The selected text is piped via stdin (e.g. to tmux load-buffer -)." );
        GridData helpData = new GridData( GridData.FILL_HORIZONTAL );
        helpData.widthHint = 400;
        helpLabel.setLayoutData( helpData );

        // Shortcut info
        Label shortcutLabel = new Label( container, SWT.WRAP );
        shortcutLabel.setText( "Keyboard shortcut: F9 (in text editors)" );
        GridData shortcutData = new GridData( GridData.FILL_HORIZONTAL );
        shortcutData.verticalIndent = 10;
        shortcutLabel.setLayoutData( shortcutData );

        sendToTmuxCheckbox.addListener( SWT.Selection, event -> updateTmuxControlsEnabled() );

        // Register with presenter
        presenter.registerView( this );

        return container;
    }

    public void updatePreferences( TmuxPreferences prefs )
    {
        if ( sessionNameText != null && !sessionNameText.isDisposed() )
        {
            sessionNameText.setText( prefs.sessionName() != null ? prefs.sessionName() : "" );
            commandTemplateText.setText( prefs.commandTemplate() != null ? prefs.commandTemplate() : "" );
            copyToClipboardCheckbox.setSelection( prefs.copyToClipboard() );
            sendToTmuxCheckbox.setSelection( prefs.sendToTmux() );
            updateTmuxControlsEnabled();
        }
    }

    private void updateTmuxControlsEnabled()
    {
        boolean enabled = sendToTmuxCheckbox.getSelection();
        sessionNameText.setEnabled( enabled );
        commandTemplateText.setEnabled( enabled );
    }

    @Override
    protected void performApply()
    {
        presenter.savePreferences( sessionNameText.getText().trim(), commandTemplateText.getText().trim(),
                copyToClipboardCheckbox.getSelection(), sendToTmuxCheckbox.getSelection() );
        super.performApply();
    }

    @Override
    public boolean performOk()
    {
        presenter.savePreferences( sessionNameText.getText().trim(), commandTemplateText.getText().trim(),
                copyToClipboardCheckbox.getSelection(), sendToTmuxCheckbox.getSelection() );
        return super.performOk();
    }

    @Override
    protected void performDefaults()
    {
        super.performDefaults();
        presenter.onPerformDefaults();
    }
}
