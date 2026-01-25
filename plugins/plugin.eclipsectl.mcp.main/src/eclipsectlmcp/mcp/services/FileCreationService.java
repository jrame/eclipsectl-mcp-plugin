package eclipsectlmcp.mcp.services;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.util.Objects;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.e4.core.di.annotations.Creatable;

import eclipsectlmcp.tools.ResourceUtilities;

@Creatable
public class FileCreationService extends CodeEditingServiceBase
{
	/**
	 * Creates a directory structure (recursively) in the specified project.
	 */
	public String createDirectories(String projectName, String directoryPath) {
	    Objects.requireNonNull(projectName);
	    Objects.requireNonNull(directoryPath);

	    if (projectName.isEmpty())
	    {
	        throw new IllegalArgumentException("Error: Project name cannot be empty.");
	    }
	    if (directoryPath.isEmpty())
	    {
	        throw new IllegalArgumentException("Error: Directory path cannot be empty.");
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

	        String normalizedPath = directoryPath;
	        while (normalizedPath.startsWith("/") || normalizedPath.startsWith("\\"))
	        {
	            normalizedPath = normalizedPath.substring(1);
	        }

	        if (normalizedPath.isEmpty())
	        {
	            throw new RuntimeException("Error: Invalid directory path. Path cannot be empty after normalization.");
	        }

	        IFolder folder = project.getFolder(normalizedPath);

	        if (folder.exists())
	        {
	            return "Directory '" + normalizedPath + "' already exists in project '" + projectName + "'.";
	        }

	        ResourceUtilities.createFolderHierarchy(folder);
	        folder.getParent().refreshLocal(IResource.DEPTH_INFINITE, null);

	        return "Success: Directory structure '" + normalizedPath + "' created in project '" + projectName + "'.";
	    }
	    catch (CoreException e)
	    {
	        throw new RuntimeException(e);
	    }
	}

	public String createFileAndOpen(String projectName, String filePath, String content)
	{
        mcpLogger.info("=== CREATE FILE REQUEST ===");
        mcpLogger.info("Project: " + projectName);
        mcpLogger.info("File path: " + filePath);
        mcpLogger.info("Content length: " + (content != null ? content.length() : 0));

		Objects.requireNonNull(projectName);
		Objects.requireNonNull(filePath);

		if (projectName.isEmpty())
	    {
	        throw new IllegalArgumentException("Error: Project name cannot be empty.");
	    }
	    if (filePath.isEmpty())
	    {
	    	throw new IllegalArgumentException( "Error: File path cannot be empty.");
	    }

	    if (content == null)
	    {
	        content = "";
	    }

	    try
	    {
	        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
	        IProject project = root.getProject(projectName);

	        if (!project.exists())
	        {
	            throw new RuntimeException( "Error: Project '" + projectName + "' does not exist.");
	        }

	        if (!project.isOpen())
	        {
	        	throw new RuntimeException("Error: Project '" + projectName + "' is closed.");
	        }

	        String normalizedPath = filePath;
	        while (normalizedPath.startsWith("/") || normalizedPath.startsWith("\\"))
	        {
	            normalizedPath = normalizedPath.substring(1);
	        }

	        if (normalizedPath.isEmpty())
	        {
	        	throw new RuntimeException("Error: Invalid file path. Path cannot be empty after normalization.");
	        }

	        final IFile file = project.getFile(normalizedPath);

	        if (file.exists())
	        {
	        	throw new RuntimeException("Error: File '" + normalizedPath + "' already exists in project '" + projectName + "'.");
	        }

	        IContainer parent = file.getParent();
	        if (parent instanceof IFolder && !parent.exists())
	        {
	            ResourceUtilities.createFolderHierarchy((IFolder) parent);
	        }
	        ByteArrayInputStream source = new ByteArrayInputStream(content.getBytes(Charset.forName( project.getDefaultCharset() )));
			file.create(source, true, null);
	        file.getParent().refreshLocal(IResource.DEPTH_ONE, null);

	        sync.syncExec(() -> {
	        	safeOpenEditor(file);
	        	refreshEditor(file);
	        });

        mcpLogger.info("File creation completed successfully");
        mcpLogger.info("=== CREATE FILE REQUEST COMPLETED ===");
	        return "Success: File '" + normalizedPath + "' created in project '" + projectName + "'.";
	    }
	    catch ( CoreException e){
	        throw new RuntimeException( e );
	    }
	}
}
