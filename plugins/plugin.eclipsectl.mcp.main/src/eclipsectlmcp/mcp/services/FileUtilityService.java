package eclipsectlmcp.mcp.services;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Objects;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFileState;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jdt.core.IJavaProject;

import eclipsectlmcp.tools.ResourceUtilities;

@Creatable
public class FileUtilityService extends CodeEditingServiceBase
{
	public String undoEdit(String projectName, String filePath)
	{
	    Objects.requireNonNull(projectName);
	    Objects.requireNonNull(filePath);

	    if (projectName.isEmpty())
	    {
	        throw new IllegalArgumentException("Error: Project name cannot be empty.");
	    }
	    if (filePath.isEmpty())
	    {
	        throw new IllegalArgumentException("Error: File path cannot be empty.");
	    }

	    try
	    {
	        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
	        IProject project = root.getProject(projectName);

	        if (!project.exists())
	        {
	            throw new RuntimeException("Error: Project '" + projectName + "' does not exist.");
	        }
	        if (!project.isOpen())
	        {
	            project.open(null);
	        }

	        IFile file = resolveFile(project, filePath);
	        sync.syncExec(() ->
	        {
	            safeOpenEditor(file);
	            refreshEditor(file);
	        });

	        IFileState[] history = file.getHistory(null);
	        if (history == null || history.length == 0)
	        {
	            throw new RuntimeException("Error: No edit history found for file '" + filePath + "'.");
	        }

	        IFileState previousState = history[0];

	        var previousContentString = new String( ResourceUtilities.readInputStream(previousState.getContents()), Charset.forName( file.getCharset() ));

	        try (ByteArrayInputStream source = new ByteArrayInputStream(previousContentString.getBytes(Charset.forName( file.getCharset() ))))
	        {
	            file.setContents(source, IResource.FORCE, null);
	        }

	        file.getParent().refreshLocal(IResource.DEPTH_ONE, null);

	        sync.asyncExec(() ->
	        {
	            refreshEditor(file);
	        });

	        return "Success: Undid last edit in file '" + filePath + "' in project '" + projectName + "'." +
	        	   "Updated file content:\n```" + ResourceUtilities.readFileContent(file) + "\n```";
	    }
	    catch (CoreException | IOException e)
	    {
	        throw new RuntimeException(e);
	    }
	}

	public String refresh(String paths) {
		StringBuilder result = new StringBuilder();
		int refreshedCount = 0;

		for (String filePath : paths.split(",")) {
			filePath = filePath.trim();
			if (filePath.isEmpty()) continue;

			boolean found = false;
			for (IJavaProject project : CodeAnalysisService.getAvailableJavaProjects()) {
				try {
					IResource resource = findResourceInProject(project.getProject(), filePath);
					if (resource == null || !resource.exists()) continue;

					found = true;
					int depth = (resource instanceof IContainer) ? IResource.DEPTH_INFINITE : IResource.DEPTH_ZERO;
					resource.refreshLocal(depth, null);

					if (resource instanceof IFile) {
						IContainer parent = resource.getParent();
						while (parent != null && !(parent instanceof IWorkspaceRoot)) {
							parent.refreshLocal(IResource.DEPTH_ONE, null);
							parent = parent.getParent();
						}
						if (ResourcesPlugin.getWorkspace().isAutoBuilding()) {
							resource.touch(null);
						}
					}

					refreshedCount++;
					String type = (resource instanceof IContainer) ? "folder" : "file";
					result.append("Refreshed ").append(type).append(": ").append(project.getProject().getName())
						.append("/").append(resource.getProjectRelativePath()).append("\n");
				} catch (Exception e) {
					result.append("Error refreshing ").append(filePath)
						.append(" in ").append(project.getProject().getName())
						.append(": ").append(e.getMessage()).append("\n");
				}
			}
			if (!found) {
				result.append("Not found: ").append(filePath).append("\n");
			}
		}

		if (refreshedCount == 0) {
			return "No files/folders found to refresh for: " + paths;
		}
		return "Refreshed " + refreshedCount + " resource(s):\n" + result.toString().trim();
	}

    public String refreshAndSave(String paths) {
        StringBuilder result = new StringBuilder();
        for (String filePath : paths.split(",")) {
            filePath = filePath.trim();
            if (filePath.isEmpty()) continue;
            try {
                IFile file = findFileInProjects(filePath);
                if (file == null || !file.exists()) {
                    result.append("File not found: ").append(filePath).append("\n");
                    continue;
                }

                file.refreshLocal(IResource.DEPTH_ZERO, null);

                final IFile theFile = file;
                final String thePath = filePath;
                sync.syncExec(() -> {
                    try {
                        org.eclipse.ui.IWorkbenchPage page = org.eclipse.ui.PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
                        if (page == null) return;

                        org.eclipse.ui.IEditorPart editor = org.eclipse.ui.ide.IDE.openEditor(page, theFile);
                        if (editor != null) {
                            if (editor instanceof org.eclipse.ui.texteditor.ITextEditor textEditor) {
                                textEditor.getDocumentProvider().resetDocument(editor.getEditorInput());
                            }
                            editor.doSave(new NullProgressMonitor());
                            result.append("OK: ").append(thePath).append("\n");
                        }
                    } catch (Exception e) {
                        result.append("Error saving ").append(thePath).append(": ").append(e.getMessage()).append("\n");
                    }
                });
            } catch (Exception e) {
                result.append("Error: ").append(filePath).append(": ").append(e.getMessage()).append("\n");
            }
        }
        return result.length() == 0 ? "No files processed" : result.toString();
    }
}
