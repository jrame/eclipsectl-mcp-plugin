package eclipsectlmcp.mcp.services;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;

import jakarta.inject.Inject;

/**
 * Service for structured editing of Java class members (read, write, remove).
 * Implements a read-gate pattern to ensure files are inspected before modification.
 */
@Creatable
public class JavaMemberEditService extends CodeEditingServiceBase {

    private final Set<String> readFiles = ConcurrentHashMap.newKeySet();

    @Inject
    private OrganizeImportsService organizeImportsService;

    private String fileKey(String projectName, String filePath) {
        String project = projectName != null ? projectName.trim() : "";
        String file = filePath.trim();
        if (file.startsWith("/")) {
            String[] parts = file.substring(1).split("/", 2);
            if (parts.length > 1 && project.isEmpty()) {
                project = parts[0];
                file = parts[1];
            }
        }
        return project + ":" + file;
    }

    private void requireRead(String projectName, String filePath) {
        String key = fileKey(projectName, filePath);
        if (!readFiles.contains(key)) {
            throw new RuntimeException("Error: You must call readJava on this file before modifying it. " +
                    "Call readJava('" + filePath + "') first to inspect the class structure.");
        }
    }

    public String readJava(String projectName, String filePath, String className) {
        Objects.requireNonNull(filePath, "filePath is required");

        if (filePath.isEmpty()) {
            throw new IllegalArgumentException("Error: File path cannot be empty.");
        }

        try {
            IProject project = resolveProject(projectName, filePath);
            IFile file = resolveFile(project, filePath);

            IJavaProject javaProject = JavaCore.create(project);
            if (javaProject == null || !javaProject.exists()) {
                throw new RuntimeException("Error: Project '" + project.getName() + "' is not a Java project.");
            }

            IJavaElement javaElement = JavaCore.create(file);
            if (!(javaElement instanceof ICompilationUnit)) {
                throw new RuntimeException("Error: Could not resolve Java compilation unit for '" + filePath + "'.");
            }

            ICompilationUnit cu = (ICompilationUnit) javaElement;
            String source = cu.getSource();

            // Strip import lines
            StringBuilder result = new StringBuilder();
            for (String line : source.split("\n")) {
                if (!line.matches("^(//\\s*)?import\\s+.*;\\s*$")) {
                    result.append(line).append("\n");
                }
            }

            // Register the file as read
            readFiles.add(fileKey(project.getName(), filePath));

            return result.toString().strip();

        } catch (CoreException e) {
            throw new RuntimeException("Error in readJava: " + ExceptionUtils.getRootCauseMessage(e), e);
        }
    }

    public String writeJava(String projectName, String filePath, String className, String newSource) {
        Objects.requireNonNull(filePath, "filePath is required");
        Objects.requireNonNull(newSource, "newSource is required");

        if (filePath.isEmpty()) {
            throw new IllegalArgumentException("Error: File path cannot be empty.");
        }
        if (newSource.trim().isEmpty()) {
            throw new IllegalArgumentException("Error: newSource cannot be empty.");
        }

        try {
            IProject project = resolveProject(projectName, filePath);
            requireRead(project.getName(), filePath);
            IFile file = resolveFile(project, filePath);

            IJavaProject javaProject = JavaCore.create(project);
            if (javaProject == null || !javaProject.exists()) {
                throw new RuntimeException("Error: Project '" + project.getName() + "' is not a Java project.");
            }

            IJavaElement javaElement = JavaCore.create(file);
            if (!(javaElement instanceof ICompilationUnit)) {
                throw new RuntimeException("Error: Could not resolve Java compilation unit for '" + filePath + "'.");
            }

            ICompilationUnit cu = (ICompilationUnit) javaElement;
            cu.becomeWorkingCopy(new NullProgressMonitor());


            IType targetType = resolveTargetType(cu, className);
            if (targetType == null) {
                throw new RuntimeException("Error: Could not find class" +
                        (className != null ? " '" + className + "'" : "") + " in '" + filePath + "'.");
            }

            List<ParsedMember> parsedMembers = JavaMemberEditHelper.parseNewSource(newSource);

            StringBuilder actions = new StringBuilder();

            // Check if first parsed member is a type matching the target class
            // If so, it means user wrapped source in a class declaration → update header + merge body
            if (!parsedMembers.isEmpty() && "type".equals(parsedMembers.get(0).kind)
                    && parsedMembers.get(0).name.equals(targetType.getElementName())) {
                ParsedMember classDecl = parsedMembers.remove(0);
                // Update the class header (javadoc + declaration)
                String newHeader = JavaMemberEditHelper.extractTypeHeader(classDecl.source);
                String existingHeader = JavaMemberEditHelper.extractTypeHeader(targetType.getSource());
                ISourceRange typeRange = targetType.getSourceRange();
                IDocument doc = new Document(cu.getSource());
                doc.replace(typeRange.getOffset(), existingHeader.length(), newHeader);
                cu.getBuffer().setContents(doc.get());
                cu.save(new NullProgressMonitor(), true);
                actions.append("Updated header of '").append(classDecl.name).append("'\n");

                // Parse body members and prepend them to the list for merging
                String body = JavaMemberEditHelper.extractTypeBody(classDecl.source);
                if (!body.isEmpty()) {
                    List<ParsedMember> bodyMembers = JavaMemberEditHelper.parseNewSource(body);
                    parsedMembers.addAll(0, bodyMembers);
                }
            }

            for (ParsedMember parsed : parsedMembers) {
                // Re-resolve targetType each iteration (source may have changed)
                cu.getResource().refreshLocal(IResource.DEPTH_ZERO, new NullProgressMonitor());
                cu = (ICompilationUnit) JavaCore.create(file);
                IType currentType = resolveTargetType(cu, className);

                IMember existingMember = JavaMemberEditHelper.findExistingMember(currentType, parsed);

                if (existingMember != null && "type".equals(parsed.kind)) {
                    // Inner type: recursive merge
                    IType existingInner = (IType) existingMember;
                    String newHeader = JavaMemberEditHelper.extractTypeHeader(parsed.source);
                    String existingHeader = JavaMemberEditHelper.extractTypeHeader(existingInner.getSource());
                    ISourceRange innerRange = existingInner.getSourceRange();
                    IDocument doc = new Document(cu.getSource());
                    doc.replace(innerRange.getOffset(), existingHeader.length(), newHeader);
                    cu.getBuffer().setContents(doc.get());
                    cu.save(new NullProgressMonitor(), true);
                    actions.append("Updated header of inner type '").append(parsed.name).append("'\n");

                    String innerBody = JavaMemberEditHelper.extractTypeBody(parsed.source);
                    if (!innerBody.isEmpty()) {
                        List<ParsedMember> innerMembers = JavaMemberEditHelper.parseNewSource(innerBody);
                        for (ParsedMember innerParsed : innerMembers) {
                            cu.getResource().refreshLocal(IResource.DEPTH_ZERO, new NullProgressMonitor());
                            cu = (ICompilationUnit) JavaCore.create(file);
                            IType refreshedInner = resolveTargetType(cu,
                                (className != null ? className + "." : currentType.getElementName() + ".") + parsed.name);

                            IMember innerExisting = JavaMemberEditHelper.findExistingMember(refreshedInner, innerParsed);
                            if (innerExisting != null) {
                                ISourceRange r = innerExisting.getSourceRange();
                                doc = new Document(cu.getSource());
                                doc.replace(r.getOffset(), r.getLength(), innerParsed.source);
                                cu.getBuffer().setContents(doc.get());
                                cu.save(new NullProgressMonitor(), true);
                                actions.append("Replaced ").append(innerParsed.kind).append(" '")
                                       .append(parsed.name).append(".").append(innerParsed.name).append("'\n");
                            } else {
                                String innerTypeSource = refreshedInner.getSource();
                                ISourceRange innerTypeRange = refreshedInner.getSourceRange();
                                int lastBrace = innerTypeSource.lastIndexOf('}');
                                int insertOff = innerTypeRange.getOffset() + lastBrace;
                                doc = new Document(cu.getSource());
                                doc.replace(insertOff, 0, "\n" + innerParsed.source + "\n");
                                cu.getBuffer().setContents(doc.get());
                                cu.save(new NullProgressMonitor(), true);
                                actions.append("Added ").append(innerParsed.kind).append(" '")
                                       .append(parsed.name).append(".").append(innerParsed.name).append("'\n");
                            }
                        }
                    }
                } else if (existingMember != null) {
                    ISourceRange range = existingMember.getSourceRange();
                    IDocument doc = new Document(cu.getSource());
                    doc.replace(range.getOffset(), range.getLength(), parsed.source);
                    cu.getBuffer().setContents(doc.get());
                    cu.save(new NullProgressMonitor(), true);
                    actions.append("Replaced ").append(parsed.kind).append(" '").append(parsed.name).append("'\n");
                } else {
                    String typeSource = currentType.getSource();
                    ISourceRange typeRange = currentType.getSourceRange();
                    int lastBrace = typeSource.lastIndexOf('}');
                    if (lastBrace < 0) {
                        throw new RuntimeException("Error: Could not find closing brace of class '" + currentType.getElementName() + "'.");
                    }
                    int insertOffset = typeRange.getOffset() + lastBrace;

                    IDocument doc = new Document(cu.getSource());
                    doc.replace(insertOffset, 0, "\n" + parsed.source + "\n");
                    cu.getBuffer().setContents(doc.get());
                    cu.save(new NullProgressMonitor(), true);
                    actions.append("Added ").append(parsed.kind).append(" '").append(parsed.name).append("'\n");
                }
            }

            cu.commitWorkingCopy(true, new NullProgressMonitor());
            cu.discardWorkingCopy();

            organizeImportsService.organizeImports(projectName, filePath);

            sync.syncExec(() -> {
                safeOpenEditor(file);
                refreshEditor(file);
            });

            cu.getResource().refreshLocal(IResource.DEPTH_ZERO, new NullProgressMonitor());
            cu = (ICompilationUnit) JavaCore.create(file);

            String errors = JavaMemberEditHelper.getCompilationErrors(file);

            StringBuilder result = new StringBuilder();
            result.append("Success in ").append(targetType.getFullyQualifiedName()).append(":\n");
            result.append(actions);

            if (!errors.isEmpty()) {
                result.append("\nCompilation errors:\n").append(errors);
            }

            return result.toString();

        } catch (CoreException | BadLocationException e) {
            throw new RuntimeException("Error in writeJava: " + ExceptionUtils.getRootCauseMessage(e), e);
        }
    }

    public String removeJava(String projectName, String filePath, String className, String members) {
        Objects.requireNonNull(filePath, "filePath is required");
        Objects.requireNonNull(members, "members is required");

        if (filePath.isEmpty()) {
            throw new IllegalArgumentException("Error: File path cannot be empty.");
        }
        if (members.trim().isEmpty()) {
            throw new IllegalArgumentException("Error: members cannot be empty.");
        }

        try {
            IProject project = resolveProject(projectName, filePath);
            requireRead(project.getName(), filePath);
            IFile file = resolveFile(project, filePath);

            IJavaProject javaProject = JavaCore.create(project);
            if (javaProject == null || !javaProject.exists()) {
                throw new RuntimeException("Error: Project '" + project.getName() + "' is not a Java project.");
            }

            IJavaElement javaElement = JavaCore.create(file);
            if (!(javaElement instanceof ICompilationUnit)) {
                throw new RuntimeException("Error: Could not resolve Java compilation unit for '" + filePath + "'.");
            }

            ICompilationUnit cu = (ICompilationUnit) javaElement;
            cu.becomeWorkingCopy(new NullProgressMonitor());

            List<String> removed = new ArrayList<>();

            IType targetType = resolveTargetType(cu, className);
            if (targetType == null) {
                throw new RuntimeException("Error: Could not find class" +
                        (className != null ? " '" + className + "'" : "") + " in '" + filePath + "'.");
            }

            String[] memberNames = members.split(",");
            for (String memberName : memberNames) {
                String name = memberName.trim();
                if (name.isEmpty()) continue;

                IMember found = JavaMemberEditHelper.findMemberByName(targetType, name);
                if (found == null) {
                    throw new RuntimeException("Error: Member '" + name + "' not found in " +
                            targetType.getElementName() + ".");
                }

                ISourceRange range = found.getSourceRange();
                IDocument doc = new Document(cu.getSource());
                doc.replace(range.getOffset(), range.getLength(), "");
                cu.getBuffer().setContents(doc.get());
                cu.save(new NullProgressMonitor(), true);
                removed.add(name);

                targetType = resolveTargetType(cu, className);
            }

            cu.commitWorkingCopy(true, new NullProgressMonitor());
            cu.discardWorkingCopy();

            organizeImportsService.organizeImports(projectName, filePath);

            sync.syncExec(() -> {
                safeOpenEditor(file);
                refreshEditor(file);
            });

            cu.getResource().refreshLocal(IResource.DEPTH_ZERO, new NullProgressMonitor());
            cu = (ICompilationUnit) JavaCore.create(file);

            String errors = JavaMemberEditHelper.getCompilationErrors(file);

            StringBuilder result = new StringBuilder();
            result.append("Success: Removed ").append(String.join(", ", removed)).append(".\n");

            if (!errors.isEmpty()) {
                result.append("\nCompilation errors:\n").append(errors);
            }

            return result.toString();

        } catch (CoreException | BadLocationException e) {
            throw new RuntimeException("Error in removeJava: " + ExceptionUtils.getRootCauseMessage(e), e);
        }
    }

    public String removeLines(String projectName, String filePath, String lines) {
        Objects.requireNonNull(filePath, "filePath is required");
        Objects.requireNonNull(lines, "lines is required");

        if (filePath.isEmpty()) {
            throw new IllegalArgumentException("Error: File path cannot be empty.");
        }
        if (lines.trim().isEmpty()) {
            throw new IllegalArgumentException("Error: lines cannot be empty.");
        }

        try {
            IProject project = resolveProject(projectName, filePath);
            requireRead(project.getName(), filePath);
            IFile file = resolveFile(project, filePath);

            IJavaProject javaProject = JavaCore.create(project);
            if (javaProject == null || !javaProject.exists()) {
                throw new RuntimeException("Error: Project '" + project.getName() + "' is not a Java project.");
            }

            IJavaElement javaElement = JavaCore.create(file);
            if (!(javaElement instanceof ICompilationUnit)) {
                throw new RuntimeException("Error: Could not resolve Java compilation unit for '" + filePath + "'.");
            }

            ICompilationUnit cu = (ICompilationUnit) javaElement;

            List<int[]> ranges = JavaMemberEditHelper.parseLineRanges(lines);
            ranges.sort((a, b) -> b[0] - a[0]);

            IDocument doc = new Document(cu.getSource());
            List<String> removed = new ArrayList<>();

            for (int[] range : ranges) {
                int startLine = range[0];
                int endLine = range[1];
                int totalLines = doc.getNumberOfLines();

                if (startLine > totalLines) continue;
                if (endLine > totalLines) endLine = totalLines;

                int startOffset = doc.getLineOffset(startLine - 1);
                int endOffset;
                if (endLine >= totalLines) {
                    endOffset = doc.getLength();
                } else {
                    endOffset = doc.getLineOffset(endLine);
                }

                doc.replace(startOffset, endOffset - startOffset, "");
                removed.add("lines " + range[0] + "-" + range[1]);
            }

            cu.getBuffer().setContents(doc.get());
            cu.save(new NullProgressMonitor(), true);

            organizeImportsService.organizeImports(projectName, filePath);

            sync.syncExec(() -> {
                safeOpenEditor(file);
                refreshEditor(file);
            });

            cu.getResource().refreshLocal(IResource.DEPTH_ZERO, new NullProgressMonitor());
            String finalSource = cu.getSource();

            String errors = JavaMemberEditHelper.getCompilationErrors(file);

            StringBuilder result = new StringBuilder();
            result.append("Success: Removed ").append(String.join(", ", removed)).append(".\n");

            if (!errors.isEmpty()) {
                result.append("\nCompilation errors:\n").append(errors);
            }

            return result.toString();

        } catch (CoreException | BadLocationException e) {
            throw new RuntimeException("Error in removeLines: " + ExceptionUtils.getRootCauseMessage(e), e);
        }
    }

    public String resetReadGate(String projectName, String filePath) {
        if (filePath != null && !filePath.trim().isEmpty()) {
            String key = fileKey(projectName, filePath);
            readFiles.remove(key);
            return "Gate reset for file: " + filePath;
        }
        int count = readFiles.size();
        readFiles.clear();
        return "Gate reset for all files (" + count + " entries cleared).";
    }

    private IType resolveTargetType(ICompilationUnit cu, String className) throws JavaModelException {
        if (className == null || className.trim().isEmpty()) {
            IType[] types = cu.getTypes();
            return types.length > 0 ? types[0] : null;
        }

        String[] parts = className.split("\\.");
        IType current = null;

        for (IType t : cu.getTypes()) {
            if (t.getElementName().equals(parts[0])) {
                current = t;
                break;
            }
        }
        if (current == null) return null;

        for (int i = 1; i < parts.length; i++) {
            current = current.getType(parts[i]);
            if (current == null || !current.exists()) return null;
        }
        return current;
    }

    private void appendTypeInfo(StringBuilder sb, IType type, IDocument doc, String indent)
            throws JavaModelException, BadLocationException {

        // Class javadoc + declaration (everything up to and including the opening brace)
        String typeSource = type.getSource();
        if (typeSource != null) {
            int braceIdx = typeSource.indexOf('{');
            if (braceIdx >= 0) {
                sb.append(typeSource, 0, braceIdx + 1).append("\n\n");
            }
        }

        // Fields
        for (IField field : type.getFields()) {
            appendMemberSource(sb, field);
        }

        // Methods
        for (IMethod method : type.getMethods()) {
            appendMemberSource(sb, method);
        }

        // Inner types
        for (IType inner : type.getTypes()) {
            sb.append("\n");
            appendTypeInfo(sb, inner, doc, indent + "  ");
        }

        sb.append("}\n");
    }

    private void appendMemberSource(StringBuilder sb, IMember member) throws JavaModelException {
        String src = member.getSource();
        if (src != null) {
            sb.append(src).append("\n\n");
        }
    }

    private int[] getLineRange(IMember member, IDocument doc) throws JavaModelException, BadLocationException {
        ISourceRange range = member.getSourceRange();
        int startLine = doc.getLineOfOffset(range.getOffset()) + 1;
        int endLine = doc.getLineOfOffset(range.getOffset() + range.getLength() - 1) + 1;
        return new int[] { startLine, endLine };
    }
}
