package eclipsectlmcp.mcp.services;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.*;
import org.eclipse.jdt.core.dom.*;

/** Resolves named variable selections against JDT bindings inside the refactoring workspace lock. */
final class VariableRenameTarget {
    private VariableRenameTarget() {}

    static IFile file(String projectName, String target) throws CoreException {
        String filename = target.endsWith(".java") ? target : target + ".java";
        var root = ResourcesPlugin.getWorkspace().getRoot();
        List<IProject> projects;
        if (projectName == null || projectName.isBlank()) {
            projects = List.of(root.getProjects());
        } else {
            IProject project = root.getProject(projectName.trim());
            if (!project.exists() || !project.isOpen())
                throw new IllegalArgumentException("Project does not exist or is closed: " + projectName);
            projects = List.of(project);
        }
        var files = new ArrayList<IFile>();
        for (IProject project : projects) {
            if (!project.isOpen() || !project.hasNature(JavaCore.NATURE_ID)) continue;
            project.accept(resource -> {
                if (resource instanceof IFile file && file.getName().equals(filename)
                        && JavaCore.create(file) instanceof ICompilationUnit) files.add(file);
                return !(resource instanceof IFile);
            });
        }
        if (files.size() != 1)
            throw new IllegalArgumentException(files.isEmpty()
                    ? "No Java source found for " + target + ". Supply a Java file path."
                    : "Multiple Java files match " + target + ": "
                        + String.join(", ", files.stream().map(f -> f.getFullPath().toString()).toList())
                        + ". Supply a project and/or an explicit Java file path.");
        return files.getFirst();
    }

    static IJavaElement select(ICompilationUnit unit, String name, Integer line, String method) {
        var parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(unit);
        parser.setResolveBindings(true);
        var ast = (CompilationUnit) parser.createAST(new NullProgressMonitor());
        var candidates = new LinkedHashMap<String, IJavaElement>();
        ast.accept(new ASTVisitor() {
            @Override public boolean visit(SimpleName node) {
                if (!name.equals(node.getIdentifier())) return true;
                if (line != null && ast.getLineNumber(node.getStartPosition()) != line) return true;
                if (method != null) {
                    ASTNode parent = node.getParent();
                    while (parent != null && !(parent instanceof MethodDeclaration)) parent = parent.getParent();
                    if (!(parent instanceof MethodDeclaration declaration)
                            || !method.equals(declaration.getName().getIdentifier())) return true;
                }
                if (node.resolveBinding() instanceof IVariableBinding binding && !binding.isField()
                        && !binding.isEnumConstant() && !binding.isRecovered()) {
                    var declaration = binding.getVariableDeclaration();
                    IJavaElement element = declaration.getJavaElement();
                    if (element instanceof ILocalVariable)
                        candidates.putIfAbsent(declaration.getKey(), element);
                }
                return true;
            }
        });
        if (candidates.size() != 1)
            throw new IllegalArgumentException(candidates.isEmpty()
                    ? "No local variable or parameter named " + name + " matches the selected line/method."
                    : "Multiple variables named " + name + " match. Narrow the selection with lineNumber/methodName or use offset.");
        return candidates.values().iterator().next();
    }
}
