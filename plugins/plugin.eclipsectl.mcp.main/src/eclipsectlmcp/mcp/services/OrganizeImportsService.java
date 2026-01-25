package eclipsectlmcp.mcp.services;

import java.util.Objects;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.manipulation.OrganizeImportsOperation;
import org.eclipse.jdt.core.manipulation.OrganizeImportsOperation.IChooseImportQuery;
import org.eclipse.jdt.core.search.TypeNameMatch;

/**
 * Service for organizing imports in Java files.
 */
@Creatable
public class OrganizeImportsService extends CodeEditingServiceBase {

    public String organizeImports(String projectName, String filePath) {
        Objects.requireNonNull(filePath);

        if (filePath.isEmpty()) {
            throw new IllegalArgumentException("Error: File path cannot be empty.");
        }

        try {
            IProject project = resolveProject(projectName, filePath);

            if (!project.isOpen()) {
                throw new RuntimeException("Error: Project '" + project.getName() + "' is closed.");
            }

            IJavaProject javaProject = JavaCore.create(project);
            if (javaProject == null || !javaProject.exists()) {
                throw new RuntimeException("Error: Project '" + project.getName() + "' is not a Java project.");
            }

            IFile file = resolveFile(project, filePath);

            if (!filePath.endsWith(".java")) {
                throw new RuntimeException("Error: File '" + filePath + "' is not a Java file.");
            }

            IJavaElement javaElement = JavaCore.create(file);
            if (!(javaElement instanceof ICompilationUnit)) {
                throw new RuntimeException("Error: Could not resolve Java compilation unit for file '" + filePath + "'.");
            }

            ICompilationUnit compilationUnit = (ICompilationUnit) javaElement;

            sync.syncExec(() -> {
                safeOpenEditor(file);
                refreshEditor(file);
            });

            String originalSource = compilationUnit.getSource();
            String originalImports = extractImportSection(originalSource);

            IChooseImportQuery chooseImportQuery = new IChooseImportQuery() {
                @Override
                public TypeNameMatch[] chooseImports(TypeNameMatch[][] openChoices, ISourceRange[] ranges) {
                    TypeNameMatch[] result = new TypeNameMatch[openChoices.length];
                    for (int i = 0; i < openChoices.length; i++) {
                        if (openChoices[i].length > 0) {
                            result[i] = openChoices[i][0];
                        }
                    }
                    return result;
                }
            };

            org.eclipse.core.runtime.IProgressMonitor monitor = new NullProgressMonitor();
            OrganizeImportsOperation operation = new OrganizeImportsOperation(
                compilationUnit,
                null,
                true,
                true,
                true,
                chooseImportQuery
            );

            operation.run(monitor);

            compilationUnit.getResource().refreshLocal(IResource.DEPTH_ZERO, monitor);

            String newSource = compilationUnit.getSource();
            String newImports = extractImportSection(newSource);

            sync.asyncExec(() -> {
                refreshEditor(file);
            });

            StringBuilder result = new StringBuilder();
            result.append("Success: Imports organized in file '").append(filePath).append("'.\n");

            if (originalImports.equals(newImports)) {
                result.append("No changes were necessary - imports were already organized.");
            } else {
                result.append("\nUpdated imports:\n```java\n").append(newImports).append("\n```");
            }

            return result.toString();
        } catch (CoreException e) {
            throw new RuntimeException("Error during organize imports: " + ExceptionUtils.getRootCauseMessage(e), e);
        }
    }

    public String organizeImportsInPackage(String projectName, String packageName) {
        Objects.requireNonNull(packageName);

        if (packageName.isEmpty()) {
            throw new IllegalArgumentException("Error: Package name cannot be empty.");
        }

        try {
            IJavaProject javaProject = resolveProjectByPackage(projectName, packageName);
            IProject project = javaProject.getProject();

            if (!project.isOpen()) {
                throw new RuntimeException("Error: Project '" + project.getName() + "' is closed.");
            }

            IPackageFragment packageFragment = findPackage(javaProject, packageName);
            if (packageFragment == null) {
                throw new RuntimeException("Error: Package '" + packageName + "' not found in project '" + projectName + "'.");
            }

            ICompilationUnit[] compilationUnits = packageFragment.getCompilationUnits();

            if (compilationUnits.length == 0) {
                return "No Java files found in package '" + packageName + "'.";
            }

            org.eclipse.core.runtime.IProgressMonitor monitor = new NullProgressMonitor();
            int processedCount = 0;
            int changedCount = 0;

            IChooseImportQuery chooseImportQuery = new IChooseImportQuery() {
                @Override
                public TypeNameMatch[] chooseImports(TypeNameMatch[][] openChoices, ISourceRange[] ranges) {
                    TypeNameMatch[] result = new TypeNameMatch[openChoices.length];
                    for (int i = 0; i < openChoices.length; i++) {
                        if (openChoices[i].length > 0) {
                            result[i] = openChoices[i][0];
                        }
                    }
                    return result;
                }
            };

            for (ICompilationUnit cu : compilationUnits) {
                try {
                    String originalSource = cu.getSource();

                    OrganizeImportsOperation operation = new OrganizeImportsOperation(
                        cu,
                        null,
                        true,
                        true,
                        true,
                        chooseImportQuery
                    );

                    operation.run(monitor);
                    cu.getResource().refreshLocal(IResource.DEPTH_ZERO, monitor);

                    String newSource = cu.getSource();
                    if (!originalSource.equals(newSource)) {
                        changedCount++;
                    }
                    processedCount++;
                } catch (Exception e) {
                    logger.warn("Failed to organize imports in " + cu.getElementName() + ": " + e.getMessage());
                }
            }

            StringBuilder result = new StringBuilder();
            result.append("Success: Organized imports in package '").append(packageName).append("'.\n");
            result.append("Processed ").append(processedCount).append(" file(s), ");
            result.append(changedCount).append(" file(s) were modified.");

            return result.toString();
        } catch (CoreException e) {
            throw new RuntimeException("Error during organize imports: " + ExceptionUtils.getRootCauseMessage(e), e);
        }
    }

    private String extractImportSection(String source) {
        StringBuilder imports = new StringBuilder();
        String[] lines = source.split("\n");
        boolean inImports = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("import ")) {
                inImports = true;
                imports.append(line).append("\n");
            } else if (inImports && !trimmed.isEmpty() && !trimmed.startsWith("import ")) {
                break;
            }
        }

        return imports.toString().trim();
    }
}
