
package eclipsectlmcp.mcp.services;

import java.util.Optional;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;

import eclipsectlmcp.tools.ResourceFormatter;
import eclipsectlmcp.tools.ResourceUtilities;
import eclipsectlmcp.tools.UISynchronizeCallable;
import jakarta.inject.Inject;



/**
 * Service interface for editor-related operations including
 * retrieving the current file and selection.
 */
@Creatable
public class EditorService 
{
    @Inject
    ILog logger;
    
    @Inject
    UISynchronizeCallable uiSync;
    
    public Optional<IFile> getCurrentlyOpenedFile()
    {
        return getActiveEditor().map( IEditorPart::getEditorInput )
                         .filter( editorInput -> editorInput instanceof IFileEditorInput )
                         .map( IFileEditorInput.class::cast )
                         .map( IFileEditorInput::getFile );
    }
    
    
    
    /**
     * Gets information about the currently active file in the Eclipse editor.
     * 
     * @return A formatted string containing file information and content
     */
    public String getCurrentlyOpenedFileContent()
    {
        return uiSync.syncCall( () -> {
	        try 
	        {
	            IFile file = getCurrentlyOpenedFile().orElseThrow( () ->  new RuntimeException("No active editor found or editor input not available.") );
	            
	            final StringBuilder result = new StringBuilder();
	            result.append("# Currently Opened File:\n\n");
                ResourceFormatter resourceFormatter = new ResourceFormatter(file);
                result.append(resourceFormatter.formatFile());
                
                return result.toString();
	        } 
	        catch (Exception e)
	        {
                return "Error: " + e.getMessage();
	        }
        });
    }

    /**
     * Gets the currently selected text or lines in the active editor.
     * 
     * @return A formatted string containing the selected text
     */
    public  String getEditorSelection()
    {
        return uiSync.syncCall( () ->{
            final StringBuilder result = new StringBuilder();
            try 
            {
                IEditorPart editor = getActiveEditor().orElseThrow( () ->  new Exception("No active editor found. Please open a file.") );
                
                // Get the selection from the editor
                if (!(editor instanceof ITextEditor textEditor)) 
                {
                    throw new RuntimeException("The current selection is not a text selection.");
                }
                ISelection selection = textEditor.getSelectionProvider().getSelection();
                
                if (selection.isEmpty()) 
                {
                    result.append("No text is currently selected in the editor.");
                    return result.toString();
                }
                if (!(selection instanceof ITextSelection textSelection)) 
                {
                    throw new RuntimeException("The current selection is not a text selection.");
                }
                
                // Get the selected text
                String selectedText = textSelection.getText();
                
                result.append("# Selected Text in Editor\n\n");
                
                // Selection details
                int startLine = textSelection.getStartLine() + 1; // 1-based
                int endLine   = textSelection.getEndLine() + 1; // 1-based
                int length    = textSelection.getLength();

                IFile file = getCurrentlyOpenedFile().orElseThrow( () ->  new RuntimeException("No active editor found or editor input not available.") );

        		result.append( "Selection from line: " + startLine );
        		result.append(" to: " + endLine );
        		result.append(" length: " + length );
        		result.append("\n");
        		result.append( "=== BEGIN selected ===\n");
        		result.append( selectedText );
        		result.append( selectedText.endsWith("\n") ? "" : "\n" );
        		result.append( "=== END selected text ===");
        		result.append("\n");
            	ResourceFormatter resourceFormatter = new ResourceFormatter(file);
            	result.append(resourceFormatter.format(startLine, endLine));
            }
            catch (Exception e)
            {
            	throw new RuntimeException(e);
            }
            return result.toString();
        } );
    }
    
/**
     * Gets the currently selected text or lines in the active editor.
     * 
     * @return A formatted string containing the selected text
     */
    /**
     * Gets the currently selected text or lines in the active editor.
     * 
     * @return A formatted string containing the selected text in markdown format
     */
    public String getEditorSelectionMarkdown()
    {
        return uiSync.syncCall( () ->{
            final StringBuilder result = new StringBuilder();
            try 
            {
                IEditorPart editor = getActiveEditor().orElseThrow( () ->  new Exception("No active editor found. Please open a file.") );
    
                // Get the selection from the editor
                if (!(editor instanceof ITextEditor textEditor)) 
                {
                    throw new RuntimeException("The current selection is not a text selection.");
                }
                ISelection selection = textEditor.getSelectionProvider().getSelection();
                
                if (selection.isEmpty()) 
                {
                    result.append("No text is currently selected in the editor.");
                    return result.toString();
                }
                if (!(selection instanceof ITextSelection textSelection)) 
                {
                    throw new RuntimeException("The current selection is not a text selection.");
                }
                
                IFile file = getCurrentlyOpenedFile().orElseThrow( () ->  new RuntimeException("No active editor found or editor input not available.") );
                
//                {
//                	logger.info("rp "+file.getProjectRelativePath());
//                	logger.info("fp "+file.getFullPath()); make relativize unless disk different with ws
//                	logger.info("fl "+file.getLocation());
//                	logger.info("ud "+System.getProperty("user.dir"));
//                	logger.info("wl "+ResourcesPlugin.getWorkspace().getRoot().getLocation());
//                	
//                 	{
//                 		IPath absoluteFilePath = file.getLocation();
//                 		Path filePath = Paths.get(absoluteFilePath.toString());
//
//                 		// Obtenir le r�pertoire du workspace
//                 		IPath workspaceLocation = ResourcesPlugin.getWorkspace().getRoot().getLocation();
//                 		Path workspacePath = Paths.get(workspaceLocation.toString());
//
//                 		// Calculer le chemin relatif
//                 		Path relativePath = workspacePath.relativize(filePath);
//						logger.info(""+relativePath);
//                	}
//                }
                
                int length = textSelection.getLength();
                if(length==0) {
                	result.append("`").append(file.getFullPath()).append("`\n");
                	return result.toString();
                }
                                
                // Get the selected text
                String selectedText = textSelection.getText();
                
                // Selection details
                int startLine = textSelection.getStartLine();
                int endLine   = textSelection.getEndLine();
                

                IDocument document = textEditor.getDocumentProvider().getDocument(textEditor.getEditorInput());
                int offset = textSelection.getOffset();
                int endOffset = offset + length;
                int startLineOffset = document.getLineOffset(startLine);
				int endLineOffset = document.getLineOffset(endLine);
				int startColumn = offset - startLineOffset + 1;// 1-based
				int endColumn = endOffset - endLineOffset +1;// 1-based
                startLine += 1; // 1-based
                endLine   += 1; // 1-based
                
                // Get file extension to determine language for syntax highlighting
                String fileName = file.getName();
                String extension = "";
                int dotIndex = fileName.lastIndexOf(".");
                if (dotIndex > 0) {
                    extension = fileName.substring(dotIndex + 1).toLowerCase();
                }
                
                // Map extension to markdown language identifier
                String language = switch (extension) {
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
                
                // Format in clean markdown style
                result.append("`").append(file.getProjectRelativePath());
                result.append(":").append(startLine).append(":").append(startColumn).append("-").append(endLine).append(":").append(endColumn);

                result.append("`\n");
                result.append("```").append(language).append("\n");
                result.append(selectedText);
                if (!selectedText.endsWith("\n")) {
                    result.append("\n");
                }
                result.append("```\n");
            }
            catch (Exception e)
            {
            	throw new RuntimeException(e);
            }
            return result.toString();
        } );
    }
    
    public Optional<IEditorPart> getActiveEditor()
    {
        return Optional.ofNullable( PlatformUI.getWorkbench() )
                       .map( IWorkbench::getActiveWorkbenchWindow )
                       .map( IWorkbenchWindow::getActivePage)
                       .map( IWorkbenchPage::getActiveEditor);
    }
    
    /**
     * Gets the code before the cursor in the currently active editor.
     * 
     * @return The code before the cursor, or empty string if not available
     */
    public String getCodeBeforeCursor()
    {
        return getActiveTextEditor()
                .map(editor -> {
                    IDocument document = editor.getDocumentProvider().getDocument(editor.getEditorInput());
                    ISelection selection = editor.getSelectionProvider().getSelection();
                    
                    if (document != null && selection instanceof ITextSelection)
                    {
                        ITextSelection textSelection = (ITextSelection) selection;
                        int offset = textSelection.getOffset();
                        
                        try
                        {
                            return document.get(0, offset);
                        }
                        catch (Exception e)
                        {
                            logger.error("Error getting code before cursor", e);
                        }
                    }
                    return "";
                })
                .orElse("");
    }
    
    /**
     * Gets the code after the cursor in the currently active editor.
     * 
     * @return The code after the cursor, or empty string if not available
     */
    public String getCodeAfterCursor()
    {
        return getActiveTextEditor()
                .map(editor -> {
                    IDocument document = editor.getDocumentProvider().getDocument(editor.getEditorInput());
                    ISelection selection = editor.getSelectionProvider().getSelection();
                    
                    if (document != null && selection instanceof ITextSelection)
                    {
                        ITextSelection textSelection = (ITextSelection) selection;
                        int offset = textSelection.getOffset();
                        int length = document.getLength();
                        
                        try
                        {
                            return document.get(offset, length - offset);
                        }
                        catch (Exception e)
                        {
                            logger.error("Error getting code after cursor", e);
                        }
                    }
                    return "";
                })
                .orElse("");
    }
    
    /**
     * Gets the current cursor line number (1-based).
     * 
     * @return The line number as a string, or empty string if not available
     */
    public String getCursorLine()
    {
        return getActiveTextEditor()
                .map(editor -> {
                    IDocument document = editor.getDocumentProvider().getDocument(editor.getEditorInput());
                    ISelection selection = editor.getSelectionProvider().getSelection();
                    
                    if (document != null && selection instanceof ITextSelection)
                    {
                        ITextSelection textSelection = (ITextSelection) selection;
                        try
                        {
                            // getLine() returns 0-based line number, so add 1
                            int line = document.getLineOfOffset(textSelection.getOffset()) + 1;
                            return String.valueOf(line);
                        }
                        catch (Exception e)
                        {
                            logger.error("Error getting cursor line", e);
                        }
                    }
                    return "";
                })
                .orElse("");
    }
    
    /**
     * Gets the current cursor column number (1-based).
     * 
     * @return The column number as a string, or empty string if not available
     */
    public String getCursorColumn()
    {
        return getActiveTextEditor()
                .map(editor -> {
                    IDocument document = editor.getDocumentProvider().getDocument(editor.getEditorInput());
                    ISelection selection = editor.getSelectionProvider().getSelection();
                    
                    if (document != null && selection instanceof ITextSelection)
                    {
                        ITextSelection textSelection = (ITextSelection) selection;
                        try
                        {
                            int offset = textSelection.getOffset();
                            int lineOffset = document.getLineInformationOfOffset(offset).getOffset();
                            // Column is 1-based
                            int column = offset - lineOffset + 1;
                            return String.valueOf(column);
                        }
                        catch (Exception e)
                        {
                            logger.error("Error getting cursor column", e);
                        }
                    }
                    return "";
                })
                .orElse("");
    }
    
    /**
     * Opens a file in the Eclipse editor, optionally at a specific line.
     *
     * @param filePath    Project-relative or absolute (/ProjectName/path) file path
     * @param lineNumber  Optional line number (1-based) to navigate to
     * @param projectName Optional project name (helps resolve relative paths)
     * @return Confirmation message
     */
    public String openFile(String filePath, Integer lineNumber, String projectName)
    {
        return uiSync.syncCall(() -> {
            try
            {
                IFile file = ResourceUtilities.findFile(projectName, filePath);
                if (file == null || !file.exists())
                {
                    return "Error: File not found: " + filePath;
                }

                IWorkbenchPage page = PlatformUI.getWorkbench()
                        .getActiveWorkbenchWindow()
                        .getActivePage();

                IEditorPart editor = IDE.openEditor(page, file);

                if (lineNumber != null && lineNumber > 0 && editor instanceof ITextEditor textEditor)
                {
                    IDocument document = textEditor.getDocumentProvider()
                            .getDocument(textEditor.getEditorInput());
                    if (document != null)
                    {
                        int line = Math.min(lineNumber - 1, document.getNumberOfLines() - 1);
                        int offset = document.getLineOffset(line);
                        int length = document.getLineLength(line);
                        textEditor.selectAndReveal(offset, length);
                    }
                }

                String displayPath = file.getFullPath().toString();
                if (lineNumber != null && lineNumber > 0)
                {
                    return "Opened " + displayPath + " at line " + lineNumber;
                }
                return "Opened " + displayPath;
            }
            catch (Exception e)
            {
                return "Error opening file: " + e.getMessage();
            }
        });
    }

    /**
     * Lists all open editors in Eclipse, marking the active editor.
     *
     * @return Formatted list of open editors
     */
    public String getOpenEditors()
    {
        return uiSync.syncCall(() -> {
            try
            {
                IWorkbenchPage page = PlatformUI.getWorkbench()
                        .getActiveWorkbenchWindow()
                        .getActivePage();

                if (page == null)
                {
                    return "No active workbench page.";
                }

                IEditorReference[] editorRefs = page.getEditorReferences();
                if (editorRefs.length == 0)
                {
                    return "No editors are open.";
                }

                IEditorPart activeEditor = page.getActiveEditor();
                StringBuilder result = new StringBuilder();
                result.append("Open editors (").append(editorRefs.length).append("):\n\n");

                for (IEditorReference ref : editorRefs)
                {
                    boolean isActive = (activeEditor != null && ref.getEditor(false) == activeEditor);
                    boolean isDirty = ref.isDirty();

                    String prefix = isActive ? "* " : "  ";
                    result.append(prefix);

                    // Try to get file path
                    if (ref.getEditorInput() instanceof IFileEditorInput fileInput)
                    {
                        result.append(fileInput.getFile().getFullPath().toString());
                    }
                    else
                    {
                        result.append(ref.getName());
                    }

                    if (isDirty)
                    {
                        result.append(" [modified]");
                    }
                    result.append("\n");
                }

                return result.toString();
            }
            catch (Exception e)
            {
                return "Error listing editors: " + e.getMessage();
            }
        });
    }

    /**
     * Gets the active text editor.
     *
     * @return Optional containing the active ITextEditor, or empty if not available
     */
    private Optional<ITextEditor> getActiveTextEditor()
    {
        return getActiveEditor()
                .filter(editor -> editor instanceof ITextEditor)
                .map(editor -> (ITextEditor) editor);
    }

}
