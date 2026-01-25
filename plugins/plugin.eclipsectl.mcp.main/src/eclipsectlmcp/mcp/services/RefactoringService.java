package eclipsectlmcp.mcp.services;

import java.nio.file.Path;
import java.util.Objects;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.refactoring.IJavaRefactorings;
import org.eclipse.jdt.core.refactoring.descriptors.MoveDescriptor;
import org.eclipse.jdt.core.refactoring.descriptors.RenameJavaElementDescriptor;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.Refactoring;
import org.eclipse.ltk.core.refactoring.RefactoringContribution;
import org.eclipse.ltk.core.refactoring.RefactoringCore;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.FileEditorInput;

import eclipsectlmcp.tools.ResourceUtilities;


@Creatable
public class RefactoringService extends CodeEditingServiceBase
{
    public String renameFile( String projectName, String filePath, String newFileName )
    {
        Objects.requireNonNull(filePath);
        Objects.requireNonNull(newFileName);

        if (filePath.isEmpty())
        {
            throw new IllegalArgumentException("Error: File path cannot be empty.");
        }
        if (newFileName.isEmpty())
        {
            throw new IllegalArgumentException("Error: New file name cannot be empty.");
        }

        try
        {
            IProject project = resolveProject(projectName, filePath);
            IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();

            if (!project.isOpen())
            {
                throw new RuntimeException("Error: Project '" + project.getName() + "' is closed.");
            }

            IFile file = resolveFile(project, filePath);

            sync.syncExec(() ->
            {
                safeOpenEditor(file);
                refreshEditor(file);
            });

            IContainer parent = file.getParent();
            IPath newPath = parent.getFullPath().append(newFileName);

            IFile newFile = root.getFile(newPath);
            if (newFile.exists())
            {
                throw new RuntimeException("Error: A file named '" + newFileName + "' already exists in the same directory.");
            }

            file.move(newPath, IResource.FORCE, null);

            parent.refreshLocal(IResource.DEPTH_ONE, null);

            sync.asyncExec(() -> {
                safeOpenEditor(newFile);
            });

            return "Success: File '" + filePath + "' renamed to '" + newFileName + "' in project '" + projectName + "'.";
        }
        catch (CoreException e)
        {
            throw new RuntimeException(e);
        }
    }

    public String refactorRenameJavaType(String projectName, String filePath, String newTypeName)
    {
        Objects.requireNonNull(filePath);
        Objects.requireNonNull(newTypeName);

        if (filePath.isEmpty())
        {
            throw new IllegalArgumentException("Error: File path cannot be empty.");
        }
        if (newTypeName.isEmpty())
        {
            throw new IllegalArgumentException("Error: New type name cannot be empty.");
        }

        if (newTypeName.endsWith(".java"))
        {
            newTypeName = newTypeName.substring(0, newTypeName.length() - 5);
        }

        final String finalNewTypeName = newTypeName;

        try
        {
            IProject project = resolveProject(projectName, filePath);

            if (!project.isOpen())
            {
                throw new RuntimeException("Error: Project '" + project.getName() + "' is closed.");
            }

            IJavaProject javaProject = JavaCore.create(project);
            if (javaProject == null || !javaProject.exists())
            {
                throw new RuntimeException("Error: Project '" + project.getName() + "' is not a Java project.");
            }

            IFile file = resolveFile(project, filePath);

            if (!filePath.endsWith(".java"))
            {
                throw new RuntimeException("Error: File '" + filePath + "' is not a Java file. Use renameFile for non-Java files.");
            }

            IJavaElement javaElement = JavaCore.create(file);
            if (!(javaElement instanceof ICompilationUnit))
            {
                throw new RuntimeException("Error: Could not resolve Java compilation unit for file '" + filePath + "'.");
            }

            ICompilationUnit compilationUnit = (ICompilationUnit) javaElement;

            IType primaryType = compilationUnit.findPrimaryType();
            if (primaryType == null)
            {
                throw new RuntimeException("Error: Could not find primary type in file '" + filePath + "'.");
            }

            String oldTypeName = primaryType.getElementName();

            sync.syncExec(() ->
            {
                IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
                if (page != null)
                {
                    IEditorPart editor = page.findEditor(new FileEditorInput(file));
                    if (editor != null)
                    {
                        page.closeEditor(editor, true);
                    }
                }
            });

            RefactoringContribution contribution = RefactoringCore.getRefactoringContribution(IJavaRefactorings.RENAME_TYPE);
            RenameJavaElementDescriptor descriptor = (RenameJavaElementDescriptor) contribution.createDescriptor();

            descriptor.setJavaElement(primaryType);
            descriptor.setNewName(finalNewTypeName);
            descriptor.setUpdateReferences(true);
            descriptor.setUpdateSimilarDeclarations(false);
            descriptor.setUpdateTextualOccurrences(false);

            RefactoringStatus status = new RefactoringStatus();
            Refactoring refactoring = descriptor.createRefactoring(status);

            if (status.hasFatalError())
            {
                throw new RuntimeException("Error creating refactoring: " + status.getMessageMatchingSeverity(RefactoringStatus.FATAL));
            }

            IProgressMonitor monitor = new NullProgressMonitor();
            RefactoringStatus checkStatus = refactoring.checkInitialConditions(monitor);
            if (checkStatus.hasFatalError())
            {
                throw new RuntimeException("Error in initial conditions: " + checkStatus.getMessageMatchingSeverity(RefactoringStatus.FATAL));
            }

            checkStatus = refactoring.checkFinalConditions(monitor);
            if (checkStatus.hasFatalError())
            {
                throw new RuntimeException("Error in final conditions: " + checkStatus.getMessageMatchingSeverity(RefactoringStatus.FATAL));
            }

            Change change = refactoring.createChange(monitor);
            change.perform(monitor);

            project.refreshLocal(IResource.DEPTH_INFINITE, monitor);

            String newFilePath = filePath.replace(oldTypeName + ".java", finalNewTypeName + ".java");
            IFile newFile = project.getFile(IPath.fromPath(Path.of(newFilePath)));

            sync.asyncExec(() -> {
                if (newFile.exists())
                {
                    safeOpenEditor(newFile);
                }
            });

            StringBuilder result = new StringBuilder();
            result.append("Success: Java type '").append(oldTypeName).append("' renamed to '").append(finalNewTypeName).append("'.\n");
            result.append("File renamed from '").append(filePath).append("' to '").append(newFilePath).append("'.\n");
            result.append("All references have been updated.");

            return result.toString();
        }
        catch (CoreException e)
        {
            throw new RuntimeException("Error during refactoring: " + ExceptionUtils.getRootCauseMessage(e), e);
        }
    }

    public String refactorMoveJavaType(String projectName, String filePath, String targetPackage)
    {
        Objects.requireNonNull(filePath);
        Objects.requireNonNull(targetPackage);

        if (filePath.isEmpty())
        {
            throw new IllegalArgumentException("Error: File path cannot be empty.");
        }

        try
        {
            IProject project = resolveProject(projectName, filePath);

            if (!project.isOpen())
            {
                throw new RuntimeException("Error: Project '" + project.getName() + "' is closed.");
            }

            IJavaProject javaProject = JavaCore.create(project);
            if (javaProject == null || !javaProject.exists())
            {
                throw new RuntimeException("Error: Project '" + project.getName() + "' is not a Java project.");
            }

            IFile file = resolveFile(project, filePath);

            if (!filePath.endsWith(".java"))
            {
                throw new RuntimeException("Error: File '" + filePath + "' is not a Java file.");
            }

            IJavaElement javaElement = JavaCore.create(file);
            if (!(javaElement instanceof ICompilationUnit))
            {
                throw new RuntimeException("Error: Could not resolve Java compilation unit for file '" + filePath + "'.");
            }

            ICompilationUnit compilationUnit = (ICompilationUnit) javaElement;
            IType primaryType = compilationUnit.findPrimaryType();

            if (primaryType == null)
            {
                throw new RuntimeException("Error: Could not find primary type in file '" + filePath + "'.");
            }

            String typeName = primaryType.getElementName();
            String oldPackageName = primaryType.getPackageFragment().getElementName();

            IPackageFragment targetPackageFragment = findOrCreatePackage(javaProject, targetPackage);

            sync.syncExec(() ->
            {
                IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
                if (page != null)
                {
                    IEditorPart editor = page.findEditor(new FileEditorInput(file));
                    if (editor != null)
                    {
                        page.closeEditor(editor, true);
                    }
                }
            });

            RefactoringContribution contribution = RefactoringCore.getRefactoringContribution(IJavaRefactorings.MOVE);
            MoveDescriptor descriptor = (MoveDescriptor) contribution.createDescriptor();

            descriptor.setDestination(targetPackageFragment);
            descriptor.setMoveResources(new IFile[0], new IFolder[0], new ICompilationUnit[] { compilationUnit });
            descriptor.setUpdateReferences(true);
            descriptor.setUpdateQualifiedNames(false);

            RefactoringStatus status = new RefactoringStatus();
            Refactoring refactoring = descriptor.createRefactoring(status);

            if (status.hasFatalError())
            {
                throw new RuntimeException("Error creating refactoring: " + status.getMessageMatchingSeverity(RefactoringStatus.FATAL));
            }

            IProgressMonitor monitor = new NullProgressMonitor();
            RefactoringStatus checkStatus = refactoring.checkInitialConditions(monitor);
            if (checkStatus.hasFatalError())
            {
                throw new RuntimeException("Error in initial conditions: " + checkStatus.getMessageMatchingSeverity(RefactoringStatus.FATAL));
            }

            checkStatus = refactoring.checkFinalConditions(monitor);
            if (checkStatus.hasFatalError())
            {
                throw new RuntimeException("Error in final conditions: " + checkStatus.getMessageMatchingSeverity(RefactoringStatus.FATAL));
            }

            Change change = refactoring.createChange(monitor);
            change.perform(monitor);

            project.refreshLocal(IResource.DEPTH_INFINITE, monitor);

            String packagePath = targetPackage.replace('.', '/');
            IPackageFragmentRoot sourceRoot = (IPackageFragmentRoot) compilationUnit.getParent().getParent();
            String sourceRootPath = sourceRoot.getResource().getProjectRelativePath().toString();
            String newFilePath = sourceRootPath + "/" + packagePath + "/" + typeName + ".java";

            IFile newFile = project.getFile(IPath.fromPath(Path.of(newFilePath)));

            sync.asyncExec(() -> {
                if (newFile.exists())
                {
                    safeOpenEditor(newFile);
                }
            });

            StringBuilder result = new StringBuilder();
            result.append("Success: Java type '").append(typeName).append("' moved from package '").append(oldPackageName);
            result.append("' to '").append(targetPackage).append("'.\n");
            result.append("New file location: '").append(newFilePath).append("'.\n");
            result.append("All references have been updated.");

            return result.toString();
        }
        catch (CoreException e)
        {
            throw new RuntimeException("Error during refactoring: " + ExceptionUtils.getRootCauseMessage(e), e);
        }
    }

    public String refactorRenamePackage(String projectName, String packageName, String newPackageName)
    {
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(newPackageName);

        if (packageName.isEmpty())
        {
            throw new IllegalArgumentException("Error: Package name cannot be empty.");
        }
        if (newPackageName.isEmpty())
        {
            throw new IllegalArgumentException("Error: New package name cannot be empty.");
        }

        try
        {
            IJavaProject javaProject = resolveProjectByPackage(projectName, packageName);
            IProject project = javaProject.getProject();

            if (!project.isOpen())
            {
                throw new RuntimeException("Error: Project '" + project.getName() + "' is closed.");
            }

            IPackageFragment packageFragment = findPackage(javaProject, packageName);
            if (packageFragment == null)
            {
                throw new RuntimeException("Error: Package '" + packageName + "' not found in project '" + projectName + "'.");
            }

            sync.syncExec(() ->
            {
                try
                {
                    IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
                    if (page != null)
                    {
                        for (ICompilationUnit cu : packageFragment.getCompilationUnits())
                        {
                            IFile file = (IFile) cu.getResource();
                            IEditorPart editor = page.findEditor(new FileEditorInput(file));
                            if (editor != null)
                            {
                                page.closeEditor(editor, true);
                            }
                        }
                    }
                }
                catch (JavaModelException e)
                {
                    logger.error("Error closing editors: " + e.getMessage());
                }
            });

            RefactoringContribution contribution = RefactoringCore.getRefactoringContribution(IJavaRefactorings.RENAME_PACKAGE);
            RenameJavaElementDescriptor descriptor = (RenameJavaElementDescriptor) contribution.createDescriptor();

            descriptor.setJavaElement(packageFragment);
            descriptor.setNewName(newPackageName);
            descriptor.setUpdateReferences(true);
            descriptor.setUpdateTextualOccurrences(false);
            descriptor.setUpdateHierarchy(true);

            RefactoringStatus status = new RefactoringStatus();
            Refactoring refactoring = descriptor.createRefactoring(status);

            if (status.hasFatalError())
            {
                throw new RuntimeException("Error creating refactoring: " + status.getMessageMatchingSeverity(RefactoringStatus.FATAL));
            }

            IProgressMonitor monitor = new NullProgressMonitor();
            RefactoringStatus checkStatus = refactoring.checkInitialConditions(monitor);
            if (checkStatus.hasFatalError())
            {
                throw new RuntimeException("Error in initial conditions: " + checkStatus.getMessageMatchingSeverity(RefactoringStatus.FATAL));
            }

            checkStatus = refactoring.checkFinalConditions(monitor);
            if (checkStatus.hasFatalError())
            {
                throw new RuntimeException("Error in final conditions: " + checkStatus.getMessageMatchingSeverity(RefactoringStatus.FATAL));
            }

            Change change = refactoring.createChange(monitor);
            change.perform(monitor);

            project.refreshLocal(IResource.DEPTH_INFINITE, monitor);

            StringBuilder result = new StringBuilder();
            result.append("Success: Package '").append(packageName).append("' renamed to '").append(newPackageName).append("'.\n");
            result.append("All package declarations and references have been updated.");

            return result.toString();
        }
        catch (CoreException e)
        {
            throw new RuntimeException("Error during refactoring: " + ExceptionUtils.getRootCauseMessage(e), e);
        }
    }

    public String refactorExtractMethod(String projectName, String filePath,
                                         int startLine, int endLine,
                                         String methodName, String visibility)
    {
        Objects.requireNonNull(filePath);
        Objects.requireNonNull(methodName);

        if (filePath.isEmpty())
        {
            throw new IllegalArgumentException("Error: File path cannot be empty.");
        }
        if (methodName.isEmpty())
        {
            throw new IllegalArgumentException("Error: Method name cannot be empty.");
        }
        if (startLine < 1 || endLine < 1)
        {
            throw new IllegalArgumentException("Error: Line numbers must be >= 1.");
        }
        if (startLine > endLine)
        {
            throw new IllegalArgumentException("Error: Start line must be <= end line.");
        }

        if (visibility == null || visibility.isEmpty())
        {
            visibility = "private";
        }

        final String finalVisibility = visibility;

        try
        {
            IProject project = resolveProject(projectName, filePath);

            if (!project.isOpen())
            {
                throw new RuntimeException("Error: Project '" + project.getName() + "' is closed.");
            }

            IJavaProject javaProject = JavaCore.create(project);
            if (javaProject == null || !javaProject.exists())
            {
                throw new RuntimeException("Error: Project '" + project.getName() + "' is not a Java project.");
            }

            IFile file = resolveFile(project, filePath);

            if (!filePath.endsWith(".java"))
            {
                throw new RuntimeException("Error: File '" + filePath + "' is not a Java file.");
            }

            IJavaElement javaElement = JavaCore.create(file);
            if (!(javaElement instanceof ICompilationUnit))
            {
                throw new RuntimeException("Error: Could not resolve Java compilation unit for file '" + filePath + "'.");
            }

            ICompilationUnit compilationUnit = (ICompilationUnit) javaElement;

            String source = compilationUnit.getSource();
            IDocument document = new Document(source);

            int startOffset;
            int endOffset;
            try
            {
                startOffset = document.getLineOffset(startLine - 1);

                int endLineOffset = document.getLineOffset(endLine - 1);
                int endLineLength = document.getLineLength(endLine - 1);
                endOffset = endLineOffset + endLineLength;

                if (endOffset > 0 && source.charAt(endOffset - 1) == '\n')
                {
                    endOffset--;
                }
                if (endOffset > 0 && source.charAt(endOffset - 1) == '\r')
                {
                    endOffset--;
                }
            }
            catch (BadLocationException e)
            {
                throw new RuntimeException("Error: Invalid line numbers. File has " + document.getNumberOfLines() + " lines.", e);
            }

            int length = endOffset - startOffset;
            if (length <= 0)
            {
                throw new RuntimeException("Error: Selected region is empty or invalid.");
            }

            org.eclipse.jdt.internal.corext.refactoring.code.ExtractMethodRefactoring refactoring =
                new org.eclipse.jdt.internal.corext.refactoring.code.ExtractMethodRefactoring(
                    compilationUnit, startOffset, length);
            refactoring.setMethodName(methodName);
            refactoring.setVisibility(parseVisibilityModifier(finalVisibility));

            IProgressMonitor monitor = new NullProgressMonitor();
            RefactoringStatus checkStatus = refactoring.checkInitialConditions(monitor);
            if (checkStatus.hasFatalError())
            {
                throw new RuntimeException("Error in initial conditions: " + checkStatus.getMessageMatchingSeverity(RefactoringStatus.FATAL));
            }

            checkStatus = refactoring.checkFinalConditions(monitor);
            if (checkStatus.hasFatalError())
            {
                throw new RuntimeException("Error in final conditions: " + checkStatus.getMessageMatchingSeverity(RefactoringStatus.FATAL));
            }

            Change change = refactoring.createChange(monitor);
            change.perform(monitor);

            file.refreshLocal(IResource.DEPTH_ZERO, monitor);

            sync.asyncExec(() -> {
                safeOpenEditor(file);
                refreshEditor(file);
            });

            StringBuilder result = new StringBuilder();
            result.append("Extract Method Refactoring: Success\n\n");
            result.append("Created method: ").append(finalVisibility).append(" ").append(methodName).append("()\n");
            result.append("Location: ").append(filePath).append("\n");
            result.append("Original code (lines ").append(startLine).append("-").append(endLine).append(") replaced with method call\n\n");
            result.append("The refactoring analyzed the code block, determined parameters and return values,\n");
            result.append("created the new method, and replaced the original code with a call to: ").append(methodName).append("();");

            return result.toString();
        }
        catch (JavaModelException e)
        {
            throw new RuntimeException("Error during Extract Method refactoring: " + ExceptionUtils.getRootCauseMessage(e), e);
        }
        catch (CoreException e)
        {
            throw new RuntimeException("Error during refactoring: " + ExceptionUtils.getRootCauseMessage(e), e);
        }
    }

    public String moveResource(String projectName, String sourcePath, String targetPath)
    {
        Objects.requireNonNull(sourcePath);
        Objects.requireNonNull(targetPath);

        if (sourcePath.isEmpty())
        {
            throw new IllegalArgumentException("Error: Source path cannot be empty.");
        }
        if (targetPath.isEmpty())
        {
            throw new IllegalArgumentException("Error: Target path cannot be empty.");
        }

        try
        {
            IProject project = resolveProject(projectName, sourcePath);
            IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();

            if (!project.isOpen())
            {
                throw new RuntimeException("Error: Project '" + project.getName() + "' is closed.");
            }

            String normalizedSource = sourcePath;
            if (sourcePath.startsWith("/")) {
                String[] parts = sourcePath.substring(1).split("/", 2);
                if (parts.length > 1) {
                    normalizedSource = parts[1];
                } else {
                    normalizedSource = "";
                }
            }
            while (normalizedSource.startsWith("/") || normalizedSource.startsWith("\\"))
            {
                normalizedSource = normalizedSource.substring(1);
            }

            if (normalizedSource.isEmpty())
            {
                throw new IllegalArgumentException("Error: Source path cannot be empty after normalization.");
            }

            String normalizedTarget = targetPath;
            if (targetPath.startsWith("/")) {
                String[] parts = targetPath.substring(1).split("/", 2);
                if (parts.length > 1) {
                    normalizedTarget = parts[1];
                } else {
                    normalizedTarget = "";
                }
            }

            while (normalizedTarget.startsWith("/") || normalizedTarget.startsWith("\\"))
            {
                normalizedTarget = normalizedTarget.substring(1);
            }

            if (normalizedTarget.isEmpty())
            {
                throw new IllegalArgumentException("Error: Target path cannot be empty after normalization.");
            }

            IResource sourceResource = project.findMember(normalizedSource);
            if (sourceResource == null || !sourceResource.exists())
            {
                throw new RuntimeException("Error: Resource '" + sourcePath + "' does not exist in project '" + projectName + "'.");
            }

            if (sourceResource instanceof IFile && sourcePath.endsWith(".java"))
            {
                logger.warn("Moving Java file without refactoring - references will not be updated. Consider using refactorMoveJavaType instead.");
            }

            IFolder targetFolder = project.getFolder(normalizedTarget);
            if (!targetFolder.exists())
            {
                ResourceUtilities.createFolderHierarchy(targetFolder);
            }

            if (sourceResource instanceof IFile)
            {
                IFile sourceFile = (IFile) sourceResource;
                sync.syncExec(() ->
                {
                    IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
                    if (page != null)
                    {
                        IEditorPart editor = page.findEditor(new FileEditorInput(sourceFile));
                        if (editor != null)
                        {
                            page.closeEditor(editor, true);
                        }
                    }
                });
            }

            String resourceName = sourceResource.getName();
            IPath destinationPath = targetFolder.getFullPath().append(resourceName);

            IResource existingResource = root.findMember(destinationPath);
            if (existingResource != null && existingResource.exists())
            {
                throw new RuntimeException("Error: A resource named '" + resourceName + "' already exists at the destination.");
            }

            sourceResource.move(destinationPath, IResource.FORCE, new NullProgressMonitor());

            sourceResource.getParent().refreshLocal(IResource.DEPTH_ONE, null);
            targetFolder.refreshLocal(IResource.DEPTH_ONE, null);

            if (sourceResource instanceof IFile)
            {
                IFile newFile = root.getFile(destinationPath);
                sync.asyncExec(() -> {
                    if (newFile.exists())
                    {
                        safeOpenEditor(newFile);
                    }
                });
            }

            String newPath = normalizedTarget + "/" + resourceName;
            return "Success: Resource '" + sourcePath + "' moved to '" + newPath + "' in project '" + projectName + "'.";
        }
        catch (CoreException e)
        {
            throw new RuntimeException("Error during move: " + ExceptionUtils.getRootCauseMessage(e), e);
        }
    }

    private int parseVisibilityModifier(String visibility)
    {
        return switch (visibility.toLowerCase())
        {
            case "public" -> org.eclipse.jdt.core.dom.Modifier.PUBLIC;
            case "protected" -> org.eclipse.jdt.core.dom.Modifier.PROTECTED;
            case "package" -> org.eclipse.jdt.core.dom.Modifier.NONE;
            case "private" -> org.eclipse.jdt.core.dom.Modifier.PRIVATE;
            default -> org.eclipse.jdt.core.dom.Modifier.PRIVATE;
        };
    }
}
