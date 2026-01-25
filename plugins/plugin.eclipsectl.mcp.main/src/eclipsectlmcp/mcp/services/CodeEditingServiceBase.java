package eclipsectlmcp.mcp.services;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.e4.ui.di.UISynchronize;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;

import eclipsectlmcp.mcp.McpLogger;
import jakarta.inject.Inject;


/**
 * Abstract base class for code editing services.
 * Contains shared helpers for project/file resolution, editor management, etc.
 */
public abstract class CodeEditingServiceBase
{
    @Inject
    protected ILog logger;

    @Inject
    protected McpLogger mcpLogger;

    @Inject
    protected UISynchronize sync;

    @Inject
    protected CodeAnalysisService codeAnalysisService;

	/**
	 * Safely opens a file in the editor, handling null cases,
	 * and brings the editor into focus.
	 */
	protected void safeOpenEditor(IFile file)
	{
        Optional.ofNullable( PlatformUI.getWorkbench() )
                .map( IWorkbench::getActiveWorkbenchWindow )
                .map(IWorkbenchWindow::getActivePage)
                .ifPresent( page -> {
					try
					{
						var editor = IDE.openEditor(page, file);
						if (editor != null)
						{
							editor.setFocus();
						}
					}
					catch (PartInitException e)
					{
				        logger.error(e.getMessage(), e);
					}
				});
	}

	/**
	 * Refreshes an editor displaying the given file.
	 */
	protected void refreshEditor(IFile file)
	{
	    try
	    {
	    	file.getParent().refreshLocal(IResource.DEPTH_ONE, null);

	    	Optional.ofNullable(PlatformUI.getWorkbench())
	            .map(IWorkbench::getActiveWorkbenchWindow)
	            .map(IWorkbenchWindow::getActivePage)
	            .ifPresent(page -> {
	                Arrays.stream(page.getEditorReferences())
	                    .map(ref -> ref.getEditor(false))
	                    .filter(Objects::nonNull)
	                    .filter(editor -> {
	                        IEditorInput input = editor.getEditorInput();
	                        return input instanceof IFileEditorInput &&
	                               file.equals(((IFileEditorInput) input).getFile());
	                    })
	                    .findFirst()
	                    .ifPresent(editor -> {
	                        try
	                        {
	                        	IEditorInput input = editor.getEditorInput();
	                        	if (editor instanceof ITextEditor)
	                        	{
	                        		((ITextEditor) editor).getDocumentProvider().resetDocument(input);
	                        	}
	                        }
	                        catch ( Exception e )
	                        {
	                        	throw new RuntimeException( e );
	                        }
	                    });
	            });
	    }
	    catch (Exception e)
	    {
	        logger.error("Error refreshing editor: " + e.getMessage());
	    }
	}

	/**
	 * Resolves a file in a project, supporting relative paths, bare filenames (recursive search),
	 * and paths with /projectName/ prefix.
	 */
	protected IFile resolveFile(IProject project, String filePath) {
		String normalizedPath = filePath;
		if (filePath.startsWith("/")) {
			String[] parts = filePath.substring(1).split("/", 2);
			if (parts.length > 1) {
				normalizedPath = parts[1];
			}
		}

		IResource resource = findResourceInProject(project, normalizedPath);
		if (resource instanceof IFile) {
			return (IFile) resource;
		}
		throw new RuntimeException("Error: File '" + filePath + "' not found in project '" + project.getName() + "'.");
	}

	/**
	 * Find a resource (file or folder) in a project.
	 */
	protected IResource findResourceInProject(IProject project, String resourcePath) {
		if (resourcePath.matches("^[A-Za-z]:[\\\\/].*")) {
			java.net.URI projectLoc = project.getLocationURI();
			if (projectLoc != null) {
				String projectPath = projectLoc.getPath().replace("/", "\\");
				String normalizedInput = resourcePath.replace("/", "\\");
				if (projectPath.startsWith("\\") && !normalizedInput.startsWith("\\")) {
					projectPath = projectPath.substring(1);
				}
				if (normalizedInput.startsWith(projectPath)) {
					String relative = normalizedInput.substring(projectPath.length());
					if (relative.startsWith("\\") || relative.startsWith("/")) {
						relative = relative.substring(1);
					}
					if (relative.isEmpty()) {
						return project;
					}
					resourcePath = relative.replace("\\", "/");
				} else {
					return null;
				}
			}
		}

		IPath path = IPath.fromPath(Path.of(resourcePath));
		IFolder folder = project.getFolder(path);
		if (folder.exists()) return folder;

		IFile file = project.getFile(path);
		if (file.exists()) return file;

		if (!resourcePath.contains("/") && !resourcePath.contains("\\")) {
			return findResourceRecursive(project, resourcePath);
		}
		return null;
	}

	protected IResource findResourceRecursive(IContainer container, String name) {
		try {
			for (IResource member : container.members()) {
				if (member.getName().equals(name)) {
					return member;
				}
				if (member instanceof IContainer) {
					IResource found = findResourceRecursive((IContainer) member, name);
					if (found != null) return found;
				}
			}
		} catch (Exception e) {
			// ignore
		}
		return null;
	}

	/**
	 * Resolves a project from an optional project name and file path.
	 */
	protected IProject resolveProject(String projectName, String filePath) {
		IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();

		if (projectName != null && !projectName.trim().isEmpty()) {
			IProject project = root.getProject(projectName.trim());
			if (!project.exists()) {
				throw new RuntimeException("Error: Project '" + projectName + "' does not exist.");
			}
			return project;
		}

		if (filePath != null && filePath.startsWith("/")) {
			String[] parts = filePath.substring(1).split("/", 2);
			if (parts.length > 0 && !parts[0].isEmpty()) {
				String extractedProjectName = parts[0];
				IProject project = root.getProject(extractedProjectName);
				if (project.exists()) {
					return project;
				}
			}
		}

		if (filePath == null || filePath.trim().isEmpty()) {
			throw new RuntimeException("Error: Both project name and file path are empty. Cannot resolve project.");
		}

		String normalizedPath = filePath;
		while (normalizedPath.startsWith("/") || normalizedPath.startsWith("\\")) {
			normalizedPath = normalizedPath.substring(1);
		}

		if (normalizedPath.isEmpty()) {
			throw new RuntimeException("Error: File path is empty after normalization.");
		}

		IProject[] allProjects = root.getProjects();
		IProject foundProject = null;
		StringBuilder projectList = new StringBuilder();

		for (IProject project : allProjects) {
			if (!project.isOpen()) {
				continue;
			}

			IResource resource = findResourceInProject(project, normalizedPath);
			IFile file = (resource instanceof IFile) ? (IFile) resource : null;
			if (file != null) {
				if (foundProject != null) {
					if (projectList.length() > 0) {
						projectList.append(", ");
					} else {
						projectList.append(foundProject.getName()).append(", ");
					}
					projectList.append(project.getName());
				} else {
					foundProject = project;
				}
			}
		}

		if (foundProject == null) {
			throw new RuntimeException("Error: File '" + filePath + "' not found in any open project.");
		}

		if (projectList.length() > 0) {
			throw new RuntimeException("Error: File '" + filePath + "' exists in multiple projects: " +
									 foundProject.getName() + ", " + projectList.toString() +
									 ". Please specify the project name explicitly.");
		}

		return foundProject;
	}

	/**
	 * Resolves a project from an optional project name and package name.
	 */
	protected IJavaProject resolveProjectByPackage(String projectName, String packageName) {
		IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();

		if (projectName != null && !projectName.trim().isEmpty()) {
			IProject project = root.getProject(projectName.trim());
			if (!project.exists()) {
				throw new RuntimeException("Error: Project '" + projectName + "' does not exist.");
			}
			IJavaProject javaProject = JavaCore.create(project);
			if (javaProject == null || !javaProject.exists()) {
				throw new RuntimeException("Error: Project '" + projectName + "' is not a Java project.");
			}
			return javaProject;
		}

		if (packageName == null || packageName.trim().isEmpty()) {
			throw new RuntimeException("Error: Both project name and package name are empty. Cannot resolve project.");
		}

		IProject[] allProjects = root.getProjects();
		IJavaProject foundJavaProject = null;
		StringBuilder projectListSb = new StringBuilder();

		for (IProject project : allProjects) {
			if (!project.isOpen()) {
				continue;
			}

			IJavaProject javaProject = JavaCore.create(project);
			if (javaProject == null || !javaProject.exists()) {
				continue;
			}

			try {
				IPackageFragment packageFragment = findPackage(javaProject, packageName);
				if (packageFragment != null && packageFragment.exists()) {
					if (foundJavaProject != null) {
						if (projectListSb.length() > 0) {
							projectListSb.append(", ");
						} else {
							projectListSb.append(foundJavaProject.getProject().getName()).append(", ");
						}
						projectListSb.append(project.getName());
					} else {
						foundJavaProject = javaProject;
					}
				}
			} catch (Exception e) {
				// Ignore and continue searching
			}
		}

		if (foundJavaProject == null) {
			throw new RuntimeException("Error: Package '" + packageName + "' not found in any open Java project.");
		}

		if (projectListSb.length() > 0) {
			throw new RuntimeException("Error: Package '" + packageName + "' exists in multiple projects: " +
									 foundJavaProject.getProject().getName() + ", " + projectListSb.toString() +
									 ". Please specify the project name explicitly.");
		}

		return foundJavaProject;
	}

	/**
	 * Finds a package fragment in the Java project.
	 */
	protected IPackageFragment findPackage(IJavaProject javaProject, String packageName) throws JavaModelException
	{
		for (IPackageFragmentRoot root : javaProject.getPackageFragmentRoots())
		{
			if (root.getKind() == IPackageFragmentRoot.K_SOURCE)
			{
				IPackageFragment fragment = root.getPackageFragment(packageName);
				if (fragment != null && fragment.exists())
				{
					return fragment;
				}
			}
		}
		return null;
	}

	/**
	 * Finds or creates a package fragment in the Java project.
	 */
	protected IPackageFragment findOrCreatePackage(IJavaProject javaProject, String packageName) throws CoreException
	{
		IPackageFragment existing = findPackage(javaProject, packageName);
		if (existing != null)
		{
			return existing;
		}

		for (IPackageFragmentRoot root : javaProject.getPackageFragmentRoots())
		{
			if (root.getKind() == IPackageFragmentRoot.K_SOURCE)
			{
				return root.createPackageFragment(packageName, true, new NullProgressMonitor());
			}
		}

		throw new RuntimeException("Error: No source folder found in project to create package '" + packageName + "'.");
	}

	/**
	 * Finds a file across all Java projects.
	 */
	protected IFile findFileInProjects(String filePath) {
		for (IJavaProject project : CodeAnalysisService.getAvailableJavaProjects()) {
			IResource resource = findResourceInProject(project.getProject(), filePath);
			if (resource instanceof IFile) {
				return (IFile) resource;
			}
		}
		return null;
	}

	/**
	 * Creates a backup of the file by triggering Eclipse's local history mechanism.
	 */
	protected void backupFile(IFile file) throws CoreException
	{
		file.refreshLocal(IResource.DEPTH_ZERO, null);
	}
}
