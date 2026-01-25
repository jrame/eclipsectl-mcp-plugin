package eclipsectlmcp.mcp.services;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.e4.core.di.annotations.Creatable;

@Creatable
public class FileDeletionService extends CodeEditingServiceBase
{
	public String deleteFile(String projectName, String filePath)
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
	            throw new RuntimeException("Error: Project '" + projectName + "' is closed.");
	        }

	        IFile file = resolveFile(project, filePath);

	        sync.syncExec(() ->
	        {
	            org.eclipse.ui.IWorkbenchPage page = org.eclipse.ui.PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
	            if (page != null)
	            {
	                org.eclipse.ui.IEditorPart editor = page.findEditor(new org.eclipse.ui.part.FileEditorInput(file));
	                if (editor != null)
	                {
	                    page.closeEditor(editor, false);
	                }
	            }
	        });

	        file.delete(true, null);

	        IContainer parent = file.getParent();
	        parent.refreshLocal(IResource.DEPTH_ONE, null);

	        return "Success: File '" + filePath + "' deleted from project '" + projectName + "'.";
	    }
	    catch (CoreException e)
	    {
	        throw new RuntimeException(e);
	    }
	}

	public String deleteLinesInFile(String projectName, String filePath, int startLine, int endLine)
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
	    if (startLine < 1)
	    {
	        throw new IllegalArgumentException("Error: Start line must be at least 1.");
	    }
	    if (endLine < startLine)
	    {
	        throw new IllegalArgumentException("Error: End line must be greater than or equal to start line.");
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
	            throw new RuntimeException("Error: Project '" + projectName + "' is closed.");
	        }

	        IFile file = resolveFile(project, filePath);

	        backupFile(file);

	        String fileContent = new String(file.getContents().readAllBytes(), StandardCharsets.UTF_8);
	        String[] lines = fileContent.split("\r?\n", -1);

	        if (startLine > lines.length)
	        {
	            throw new IllegalArgumentException("Error: Start line " + startLine + " is beyond the file length (" + lines.length + " lines).");
	        }
	        if (endLine > lines.length)
	        {
	            throw new IllegalArgumentException("Error: End line " + endLine + " is beyond the file length (" + lines.length + " lines).");
	        }

	        StringBuilder newContent = new StringBuilder();
	        for (int i = 0; i < lines.length; i++)
	        {
	            int lineNum = i + 1;
	            if (lineNum < startLine || lineNum > endLine)
	            {
	                newContent.append(lines[i]);
	                if (i < lines.length - 1)
	                {
	                    newContent.append("\n");
	                }
	            }
	        }

	        byte[] bytes = newContent.toString().getBytes(StandardCharsets.UTF_8);
	        ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes);
	        file.setContents(inputStream, IResource.FORCE, null);

	        file.refreshLocal(IResource.DEPTH_ZERO, null);

	        sync.asyncExec(() ->
	        {
	            safeOpenEditor(file);
	            refreshEditor(file);
	        });

	        int deletedCount = endLine - startLine + 1;
	        return "Success: Deleted " + deletedCount + " line(s) (lines " + startLine + " to " + endLine + ") from file '" + filePath + "' in project '" + projectName + "'.";
	    }
	    catch (CoreException | IOException e)
	    {
	        throw new RuntimeException(e);
	    }
	}
}
