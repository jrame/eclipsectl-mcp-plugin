package eclipsectlmcp.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;

import eclipsectlmcp.Activator;
import eclipsectlmcp.mcp.services.TmuxService;

public class SendSelectionToTmuxHandler extends AbstractHandler
{
    @Override
    public Object execute( ExecutionEvent event ) throws ExecutionException
    {
        try
        {
            TmuxService service = Activator.getDefault().make( TmuxService.class );
            Shell shell = HandlerUtil.getActiveShell( event );
            String error = service.shareEditorSelection( asyncError -> {
                if ( shell != null && !shell.isDisposed() )
                {
                    MessageDialog.openError( shell, "Share Editor Selection", asyncError );
                }
            } );
            if ( error != null )
            {
                MessageDialog.openError(
                        shell,
                        "Share Editor Selection",
                        error );
            }
        }
        catch ( Exception e )
        {
            MessageDialog.openError(
                    HandlerUtil.getActiveShell( event ),
                    "Share Editor Selection",
                    "Unexpected error: " + e.getMessage() );
        }
        return null;
    }
}
