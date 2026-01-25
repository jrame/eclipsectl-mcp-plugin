package eclipsectlmcp.mcp.services;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.e4.core.di.annotations.Creatable;

import eclipsectlmcp.tools.ResourceUtilities;
import jakarta.inject.Inject;

@Creatable
public class FileTextEditService extends CodeEditingServiceBase
{
	@Inject
	private FileDiffService fileDiffService;
	public String replaceStringInFile(String projectName, String filePath, String oldString, String newString,
	                                 Integer startLine, Integer endLine) {

	    Objects.requireNonNull(projectName);
	    Objects.requireNonNull(filePath);
	    Objects.requireNonNull(oldString);

	    if (projectName.isEmpty())
	    {
	        throw new IllegalArgumentException("Error: Project name cannot be empty.");
	    }

	    if (filePath.isEmpty())
	    {
	        throw new IllegalArgumentException( "Error: File path cannot be empty.");
	    }
	    if (newString == null)
	    {
	        newString = "";
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
	            safeOpenEditor(file);
	            refreshEditor(file);
	        });

	        List<String> lines = ResourceUtilities.readFileLines(file);

	        int totalLines = lines.size();

	        int effectiveStartLine = (startLine != null) ? Math.max(0, startLine - 1) : 0;
	        int effectiveEndLine = (endLine != null) ? Math.min(totalLines - 1, endLine - 1) : totalLines - 1;

	        if (effectiveStartLine >= totalLines)
	        {
	            throw new RuntimeException("Error: Start line " + startLine + " is beyond the end of the file (total lines: " + totalLines + ").");
	        }
	        effectiveEndLine = Math.min(effectiveEndLine, totalLines - 1);

	        if (effectiveStartLine > effectiveEndLine)
	        {
	            throw new RuntimeException("Error: Start line cannot be greater than end line.");
	        }

	        StringBuilder rangeContent = new StringBuilder();
	        for (int i = effectiveStartLine; i <= effectiveEndLine; i++)
	        {
	            rangeContent.append(lines.get(i));
	            if (i < effectiveEndLine)
	            {
	                rangeContent.append("\n");
	            }
	        }

	        String rangeText = rangeContent.toString();

	        if (!rangeText.contains(oldString))
	        {
	            String rangeInfo = "";
	            if (startLine != null || endLine != null)
	            {
	                rangeInfo = " within range (lines " + (startLine != null ? startLine : 1) + " to " + (endLine != null ? endLine : totalLines) + ")";
	            }
	            throw new RuntimeException("Error: The specified string was not found in the file" + rangeInfo + ".");
	        }

	        String replacedRangeText = rangeText.replace(oldString, newString);

	        StringBuilder modifiedContent = new StringBuilder();

	        for (int i = 0; i < effectiveStartLine; i++)
	        {
	            modifiedContent.append(lines.get(i)).append("\n");
	        }

	        modifiedContent.append(replacedRangeText);

	        if (effectiveEndLine < totalLines - 1)
	        {
	            modifiedContent.append("\n");
	        }

	        for (int i = effectiveEndLine + 1; i < totalLines; i++)
	        {
	            modifiedContent.append(lines.get(i));
                modifiedContent.append("\n");
	        }
	        if ( !modifiedContent.toString().endsWith("\n") )
	        {
	        	modifiedContent.append("\n");
	        }

	        var modifiedContentString = modifiedContent.toString();
	        String diff = generateCodeDiff(projectName, filePath, modifiedContentString, 3);

	        try (ByteArrayInputStream source = new ByteArrayInputStream(modifiedContentString.getBytes(Charset.forName( file.getCharset() ))))
	        {
	            file.setContents(source, IResource.FORCE, null);
	        }

	        sync.asyncExec(() -> {
	        	refreshEditor(file);
	        });


	        return "Success: String replaced in file '" + filePath + "' in project '" + projectName + "'. "  + "'.\n" +
	        	   "Changes:\n```diff\n" + diff + "\n```";

	    }
	    catch (CoreException | IOException e)
	    {
	        throw new RuntimeException(e);
	    }
	}

	public String insertIntoFile(String projectName, String filePath, String content, int atLine)
	{
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
	    if ( Objects.isNull(content) )
	    {
	        content = "";
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
	        	safeOpenEditor(file);
	        	refreshEditor(file);
	        });
	        List<String> lines = ResourceUtilities.readFileLines(file);

	        var effectiveAtLine = atLine - 1;
	        if (effectiveAtLine < 0 || effectiveAtLine > lines.size() )
	        {
	            throw new RuntimeException("Error: Invalid line number " + atLine + ". File has " + lines.size() + " lines.");
	        }

	        StringBuilder modifiedContent = new StringBuilder();

	        for (int i = 0; i < effectiveAtLine; i++)
	        {
	            modifiedContent.append(lines.get(i)).append("\n");
	        }

	        modifiedContent.append(content);
	        if (!content.endsWith("\n"))
	        {
	            modifiedContent.append("\n");
	        }

	        for (int i = effectiveAtLine; i < lines.size(); i++)
	        {
	            modifiedContent.append(lines.get(i) );
	            if (i < lines.size() - 1)
	            {
	                modifiedContent.append("\n");
	            }
	        }

	        var modifiedContentString = modifiedContent.toString();
	        String diff = generateCodeDiff(projectName, filePath, modifiedContentString, 3);

	        try (ByteArrayInputStream source = new ByteArrayInputStream(modifiedContentString.getBytes(Charset.forName( file.getCharset() ))))
	        {
	            file.setContents(source, IResource.FORCE, null);
	        }

	        file.getParent().refreshLocal(IResource.DEPTH_ONE, null);

	        sync.syncExec( () -> {
	        	refreshEditor(file);
	        });

	        return "Success: file '" + filePath + "' in project '" + projectName + "' was updated.\n" +
	        	   "Changes:\n```diff\n" + diff + "\n```";

	    }
	    catch (CoreException | IOException e)
	    {
	        throw new RuntimeException(e);
	    }
	}

	public String replaceLines(String projectName, String filePath,  String replacementContent, int startLine, int endLine)
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

	    if (replacementContent == null)
	    {
	        replacementContent = "";
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

	        List<String> lines = ResourceUtilities.readFileLines(file);

	        int totalLines = lines.size();
	        int startLine0 = startLine -1;
	        int endLine0   = endLine - 1;
	        if (startLine0 < 0 || endLine0 < startLine0 ||  startLine0 >= totalLines )
	        {
	            throw new IllegalArgumentException("Error: Invalid line range specified.");
	        }

	        endLine0 = Math.max( Math.min( endLine0, totalLines - 1), 0 );

	        StringBuilder modifiedContent = new StringBuilder();

	        for (int i = 0; i < startLine0; i++)
	        {
	            modifiedContent.append( lines.get(i) );
                modifiedContent.append("\n");
	        }
	        modifiedContent.append(replacementContent);
	        if (!replacementContent.isEmpty() && !replacementContent.endsWith("\n"))
	        {
	            modifiedContent.append("\n");
	        }
	        for (int i = endLine0 + 1; i < totalLines; i++)
	        {
	            modifiedContent.append(lines.get(i));
                modifiedContent.append("\n");
	        }

	        var modifiedContentString = modifiedContent.toString();
	        String diff = generateCodeDiff(projectName, filePath, modifiedContentString, 3);


	        try (ByteArrayInputStream source = new ByteArrayInputStream(
	        		modifiedContentString.getBytes( Charset.forName(file.getCharset()))))
	        {
	            file.setContents(source, IResource.FORCE, null);
	        }

	        file.getParent().refreshLocal(IResource.DEPTH_ONE, null);

	        sync.syncExec(() -> {
	            refreshEditor(file);
	        });


	        return "Success: file '" + filePath + "' in project '" + projectName + "' was updated.\n" +
	        	   "Changes:\n```diff\n" + diff + "\n```";
	    }
	    catch (CoreException | IOException e)
	    {
	        throw new RuntimeException(e);
	    }
	}

	public String replaceFileContent(String projectName, String filePath, String content)
	{
	    Objects.requireNonNull(projectName);
	    Objects.requireNonNull(filePath);
	    Objects.requireNonNull(content);

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

	        backupFile(file);

	        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
	        ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes);
	        file.setContents(inputStream, IResource.FORCE, null);

	        file.refreshLocal(IResource.DEPTH_ZERO, null);

	        sync.asyncExec(() ->
	        {
	            safeOpenEditor(file);
	            refreshEditor(file);
	        });

	        return "Success: Content of file '" + filePath + "' replaced in project '" + projectName + "'.";
	    }
	    catch (CoreException e)
	    {
	        throw new RuntimeException(e);
	    }
	}

	private String generateCodeDiff(String projectName, String filePath, String proposedCode, Integer contextLines)
	{
		return fileDiffService.generateCodeDiff(projectName, filePath, proposedCode, contextLines);
	}
}
