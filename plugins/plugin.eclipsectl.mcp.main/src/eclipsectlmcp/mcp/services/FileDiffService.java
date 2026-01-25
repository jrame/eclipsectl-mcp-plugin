package eclipsectlmcp.mcp.services;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.HistogramDiff;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;

import eclipsectlmcp.tools.ResourceUtilities;

@Creatable
public class FileDiffService extends CodeEditingServiceBase
{
    public String generateCodeDiff(String projectName, String filePath, String proposedCode, Integer contextLines)
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

        if (contextLines == null || contextLines < 0)
        {
            contextLines = 3;
        }
        try
        {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (!project.exists())
            {
                throw new RuntimeException("Error: Project '" + projectName + "' not found.");
            }

            if (!project.isOpen())
            {
                throw new RuntimeException("Error: Project '" + projectName + "' is closed.");
            }

            IResource resource = project.findMember(filePath);
            if (resource == null || !resource.exists())
            {
                throw new RuntimeException("Error: File '" + filePath + "' not found in project '" + projectName + "'.");
            }

            if (!(resource instanceof IFile))
            {
                throw new RuntimeException("Error: Resource '" + filePath + "' is not a file.");
            }

            IFile file = (IFile) resource;

	        sync.syncExec(() ->
	        {
	            safeOpenEditor(file);
	            refreshEditor(file);
	        });

            String originalContent = ResourceUtilities.readFileContent(file);

            Path originalFile = Files.createTempFile("original-", ".tmp");
            Path proposedFile = Files.createTempFile("proposed-", ".tmp");

            try
            {
                Files.writeString(originalFile, originalContent);
                Files.writeString(proposedFile, proposedCode);

                ByteArrayOutputStream diffOutput = new ByteArrayOutputStream();
                DiffFormatter formatter = new DiffFormatter(diffOutput);
                formatter.setContext(contextLines);
                formatter.setDiffComparator(RawTextComparator.DEFAULT);

                RawText rawOriginal = new RawText(originalFile.toFile());
                RawText rawProposed = new RawText(proposedFile.toFile());

                diffOutput.write(("--- /" + filePath + "\n").getBytes());
                diffOutput.write(("+++ /" + filePath + "\n").getBytes());

                EditList edits = new HistogramDiff().diff(RawTextComparator.DEFAULT, rawOriginal, rawProposed);

                formatter.format(edits, rawOriginal, rawProposed);

                String diffResult = diffOutput.toString();
                formatter.close();

                if (diffResult.trim().isEmpty() || !diffResult.contains("@@"))
                {
                    return "";
                }

                return diffResult;
            }
            finally
            {
                Files.deleteIfExists(originalFile);
                Files.deleteIfExists(proposedFile);
            }
        }
        catch (Exception e)
        {
            logger.error(e.getMessage(), e);
            throw new RuntimeException("Error generating diff: " + ExceptionUtils.getRootCauseMessage(e));
        }
    }
}
