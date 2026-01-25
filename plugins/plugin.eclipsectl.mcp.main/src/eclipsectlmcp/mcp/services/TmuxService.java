package eclipsectlmcp.mcp.services;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;

import eclipsectlmcp.preferences.tmux.TmuxPreferences;
import eclipsectlmcp.preferences.tmux.TmuxPreferencesProvider;
import eclipsectlmcp.tools.UISynchronizeCallable;
import jakarta.inject.Inject;

@Creatable
public class TmuxService
{
    private static final int TMUX_TIMEOUT_SECONDS = 5;
    private static final int MAX_OUTPUT_LENGTH = 16_384;

    @Inject
    private TmuxPreferencesProvider preferencesProvider;

    @Inject
    private UISynchronizeCallable uiSync;

    /**
     * Get the currently selected text from the active text editor, formatted as markdown.
     * Must be called from the UI thread.
     */
    public String getEditorSelectionMarkdown()
    {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if ( window == null )
        {
            return null;
        }
        IEditorPart editor = window.getActivePage().getActiveEditor();
        if ( !( editor instanceof ITextEditor textEditor ) )
        {
            return null;
        }

        ISelection selection = textEditor.getSelectionProvider().getSelection();
        if ( selection.isEmpty() || !( selection instanceof ITextSelection textSelection ) )
        {
            return null;
        }

        IFile file = null;
        if ( textEditor.getEditorInput() instanceof IFileEditorInput fileInput )
        {
            file = fileInput.getFile();
        }

        int length = textSelection.getLength();
        int startLine = textSelection.getStartLine();

        try
        {
            IDocument document = textEditor.getDocumentProvider().getDocument( textEditor.getEditorInput() );
            int offset = textSelection.getOffset();
            int startLineOffset = document.getLineOffset( startLine );
            int startColumn = offset - startLineOffset + 1;
            startLine += 1;

            // No text selected — just return file:line:col
            if ( length == 0 )
            {
                if ( file == null )
                {
                    return null;
                }
                return "`" + file.getFullPath().toString().substring( 1 )
                        + ":" + startLine + ":" + startColumn + "`\n";
            }

            String selectedText = textSelection.getText();
            int endLine = textSelection.getEndLine();
            int endOffset = offset + length;
            int endLineOffset = document.getLineOffset( endLine );
            int endColumn = endOffset - endLineOffset + 1;
            endLine += 1;

            StringBuilder result = new StringBuilder();

            if ( file != null )
            {
                result.append( "`" ).append( file.getFullPath().toString().substring( 1 ) );
                result.append( ":" ).append( startLine ).append( ":" ).append( startColumn );
                result.append( "-" ).append( endLine ).append( ":" ).append( endColumn );
                result.append( "`\n" );
            }

            String language = "";
            if ( file != null )
            {
                String fileName = file.getName();
                int dotIndex = fileName.lastIndexOf( "." );
                if ( dotIndex > 0 )
                {
                    String extension = fileName.substring( dotIndex + 1 ).toLowerCase();
                    language = mapExtensionToLanguage( extension );
                }
            }

            result.append( "```" ).append( language ).append( "\n" );
            result.append( selectedText );
            if ( !selectedText.endsWith( "\n" ) )
            {
                result.append( "\n" );
            }
            result.append( "```\n" );

            return result.toString();
        }
        catch ( Exception e )
        {
            return textSelection.getText();
        }
    }

    private String mapExtensionToLanguage( String extension )
    {
        return switch ( extension )
        {
            case "java" -> "java";
            case "js", "javascript" -> "javascript";
            case "ts", "typescript" -> "typescript";
            case "py", "python" -> "python";
            case "cpp", "cxx", "cc" -> "cpp";
            case "c" -> "c";
            case "cs" -> "csharp";
            case "php" -> "php";
            case "rb", "ruby" -> "ruby";
            case "go" -> "go";
            case "rs", "rust" -> "rust";
            case "kt", "kotlin" -> "kotlin";
            case "scala" -> "scala";
            case "xml" -> "xml";
            case "html", "htm" -> "html";
            case "css" -> "css";
            case "json" -> "json";
            case "yml", "yaml" -> "yaml";
            case "sql" -> "sql";
            case "sh", "bash" -> "bash";
            case "md", "markdown" -> "markdown";
            default -> "";
        };
    }

    /**
     * Copy text to the system clipboard. Must be called from the UI thread.
     */
    public void copyToClipboard( String text )
    {
        Display display = Display.getCurrent();
        Clipboard clipboard = new Clipboard( display );
        try
        {
            clipboard.setContents(
                    new Object[] { text },
                    new Transfer[] { TextTransfer.getInstance() } );
        }
        finally
        {
            clipboard.dispose();
        }
    }

    /**
     * Send text to a WSL tmux session by piping through stdin.
     */
    private String sendToTmuxViaStdin( String text, TmuxPreferences prefs )
    {
        String command = prefs.commandTemplate()
                .replace( "${session}", prefs.sessionName() );

        try
        {
            ProcessBuilder pb = new ProcessBuilder( "wsl", "bash", "-lc", command );
            pb.redirectErrorStream( true );
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            AtomicReference<IOException> outputReadError = new AtomicReference<>();
            Thread outputReader = Thread.ofVirtual()
                    .name( "EclipseCtl tmux output reader" )
                    .start( () -> readProcessOutput( process, output, outputReadError ) );
            AtomicReference<IOException> inputWriteError = new AtomicReference<>();
            Thread inputWriter = Thread.ofVirtual()
                    .name( "EclipseCtl tmux input writer" )
                    .start( () -> writeProcessInput( process, text, inputWriteError ) );

            boolean finished = process.waitFor( TMUX_TIMEOUT_SECONDS, TimeUnit.SECONDS );
            if ( !finished )
            {
                process.destroyForcibly();
                process.waitFor( 1, TimeUnit.SECONDS );
                inputWriter.join( 1_000 );
                outputReader.join( 1_000 );
                return "Command timed out after " + TMUX_TIMEOUT_SECONDS + " seconds";
            }

            inputWriter.join( 1_000 );
            outputReader.join( 1_000 );
            IOException writeError = inputWriteError.get();
            if ( writeError != null && process.exitValue() == 0 )
            {
                return "Could not write command input: " + writeError.getMessage();
            }
            IOException readError = outputReadError.get();
            if ( readError != null )
            {
                return "Could not read command output: " + readError.getMessage();
            }

            int exitCode = process.exitValue();
            if ( exitCode != 0 )
            {
                return "tmux command failed (exit " + exitCode + "): " + output.toString().trim();
            }

            return null;
        }
        catch ( IOException e )
        {
            return "IOException: " + e.getMessage();
        }
        catch ( InterruptedException e )
        {
            Thread.currentThread().interrupt();
            return "Command interrupted";
        }
    }

    private void writeProcessInput( Process process, String text,
            AtomicReference<IOException> inputWriteError )
    {
        try ( OutputStream stdin = process.getOutputStream() )
        {
            stdin.write( text.getBytes( StandardCharsets.UTF_8 ) );
            stdin.flush();
        }
        catch ( IOException e )
        {
            inputWriteError.set( e );
        }
    }

    private void readProcessOutput( Process process, StringBuilder output,
            AtomicReference<IOException> outputReadError )
    {
        try ( BufferedReader reader = new BufferedReader(
                new InputStreamReader( process.getInputStream(), StandardCharsets.UTF_8 ) ) )
        {
            String line;
            while ( ( line = reader.readLine() ) != null )
            {
                if ( output.length() < MAX_OUTPUT_LENGTH )
                {
                    int remaining = MAX_OUTPUT_LENGTH - output.length();
                    output.append( line, 0, Math.min( line.length(), remaining ) ).append( "\n" );
                }
            }
        }
        catch ( IOException e )
        {
            if ( process.isAlive() )
            {
                outputReadError.set( e );
            }
        }
    }

    /**
     * Orchestrates: get selection as markdown, optionally copy it to the clipboard,
     * and send it to tmux via WSL stdin.
     *
     * @return null on success, error message on failure
     */
    public String shareEditorSelection( Consumer<String> asyncErrorHandler )
    {
        String markdown = uiSync.syncCall( this::getEditorSelectionMarkdown );
        if ( markdown == null )
        {
            return "No text selected in the active editor";
        }

        TmuxPreferences prefs = preferencesProvider.get();
        if ( !prefs.copyToClipboard() && !prefs.sendToTmux() )
        {
            return "No sharing destination is enabled. Check Preferences > eclipsectl-mcp > Editor Selection.";
        }

        if ( prefs.copyToClipboard() )
        {
            uiSync.syncExec( () -> copyToClipboard( markdown ) );
        }

        if ( !prefs.sendToTmux() )
        {
            return null;
        }

        if ( prefs.sessionName() == null || prefs.sessionName().isBlank() )
        {
            return "Tmux session name is not configured. Check Preferences > eclipsectl-mcp > Editor Selection.";
        }
        if ( prefs.commandTemplate() == null || prefs.commandTemplate().isBlank() )
        {
            return "Tmux command template is not configured. Check Preferences > eclipsectl-mcp > Editor Selection.";
        }

        Job job = new Job( "Share editor selection with tmux" )
        {
            @Override
            protected IStatus run( IProgressMonitor monitor )
            {
                String error = sendToTmuxViaStdin( markdown, prefs );
                if ( error == null )
                {
                    return Status.OK_STATUS;
                }

                String message = "Failed to send to tmux: " + error;
                if ( asyncErrorHandler != null )
                {
                    uiSync.asyncExec( () -> asyncErrorHandler.accept( message ) );
                    return Status.OK_STATUS;
                }
                return Status.error( message );
            }
        };
        job.setSystem( true );
        job.schedule();

        return null;
    }
}
