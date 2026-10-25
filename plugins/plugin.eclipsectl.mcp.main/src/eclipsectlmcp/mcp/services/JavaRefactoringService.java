package eclipsectlmcp.mcp.services;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jdt.core.*;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.refactoring.IJavaRefactorings;
import org.eclipse.jdt.core.refactoring.descriptors.RenameJavaElementDescriptor;
import org.eclipse.jdt.internal.corext.refactoring.ParameterInfo;
import org.eclipse.jdt.internal.corext.refactoring.code.*;
import org.eclipse.jdt.internal.corext.refactoring.structure.ChangeSignatureProcessor;
import org.eclipse.jdt.internal.corext.util.JdtFlags;
import org.eclipse.ltk.core.refactoring.*;
import org.eclipse.ltk.core.refactoring.participants.ProcessorBasedRefactoring;
import eclipsectlmcp.tools.UISynchronizeCallable;
import jakarta.inject.Inject;
import jakarta.annotation.PreDestroy;

/** Position-based JDT refactorings. Offsets and lengths count UTF-16 code units. */
@Creatable
@SuppressWarnings("restriction")
public class JavaRefactoringService extends CodeEditingServiceBase {
    private final UISynchronizeCallable ui;
    private final RefactoringPreviewStore previews = new RefactoringPreviewStore();

    @Inject
    public JavaRefactoringService(UISynchronizeCallable ui) {
        this.ui = ui;
    }

    @FunctionalInterface
    private interface Factory {
        Refactoring create(ICompilationUnit unit, NullProgressMonitor monitor) throws Exception;
    }

    private String execute(String project, String path, int offset, int length, boolean preview, Factory factory) {
        return execute(project, path, offset, length, preview, false, factory);
    }

    private String execute(String project, String path, int offset, int length, boolean preview, boolean namedTarget, Factory factory) {
        if (path == null || path.isBlank() || offset < 0 || length < 0)
            throw new IllegalArgumentException("A Java file and a nonnegative UTF-16 selection are required.");
        return ui.syncCall(() -> {
            var workspace = ResourcesPlugin.getWorkspace();
            String[] result = new String[1];
            // Reference updates can affect other projects. Lock the workspace through validation and application.
            workspace.run(monitor -> {
                try {
                    IFile file = namedTarget && !path.contains("/") && !path.contains("\\")
                            ? VariableRenameTarget.file(project, path)
                            : resolveFile(resolveProject(project, path), path);
                    requireCleanEditors(file);
                    file.refreshLocal(IResource.DEPTH_ZERO, monitor);
                    var selectedSource = RefactoringSourceSnapshot.capture(file, this::requireCleanEditors);
                    if (!(JavaCore.create(file) instanceof ICompilationUnit unit))
                        throw new IllegalArgumentException("The selected file is not a Java compilation unit.");
                    if ((long) offset + length > unit.getSource().length())
                        throw new IllegalArgumentException("Selection exceeds the saved Java source.");
                    var progress = new NullProgressMonitor();
                    Refactoring refactoring = factory.create(unit, progress);
                    RefactoringStatus conditions = refactoring.checkFinalConditions(progress);
                    requireValid(conditions);
                    Change change = refactoring.createChange(progress);
                    boolean retained = false;
                    try {
                        requireCleanChanges(change);
                        change.initializeValidationData(progress);
                        requireValid(change.isValid(progress));
                        var description = RefactoringChangeReport.describe(change, progress);
                        String report = warnings(conditions) + description.text();
                        var sources = new java.util.LinkedHashMap<IFile, RefactoringSourceSnapshot>();
                        sources.put(file, selectedSource);
                        for (var edit : description.edits()) {
                            var source = RefactoringSourceSnapshot.capture(edit.file(), this::requireCleanEditors);
                            if (!source.source().equals(edit.before()))
                                throw new IllegalStateException("Source changed while preparing the refactoring: " + edit.file().getFullPath());
                            sources.putIfAbsent(edit.file(), source);
                        }
                        for (var source : sources.values()) source.validate(this::requireCleanEditors);
                        if (preview) {
                            String id = previews.add(change, refactoring.getName(), report, new ArrayList<>(sources.values()), description);
                            retained = true;
                            result[0] = "Preview: " + refactoring.getName() + ". No files were changed.\n"
                                    + "Apply this exact change with refactorApplyPreview(previewId). One use; expires in 15 minutes.\n"
                                    + previews.read(id, 0);
                            return;
                        }
                        perform(change, refactoring.getName(), progress);
                        result[0] = "Success: " + refactoring.getName() + " applied to " + file.getFullPath()
                                + ".\nDirect apply against current source (not bound to a preview).\n" + RefactoringChangeReport.bounded(report);
                    } finally {
                        if (!retained) change.dispose();
                    }
                } catch (CoreException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalArgumentException(e.getMessage(), e);
                }
            }, workspace.getRoot(), 0, new NullProgressMonitor());
            return result[0];
        });
    }

    /** Apply the retained change itself; never repeat selection, reference search, or change creation. */
    public String applyPreview(String previewId) {
        return ui.syncCall(() -> {
            var workspace = ResourcesPlugin.getWorkspace();
            String[] result = new String[1];
            workspace.run(monitor -> {
                var preview = previews.consume(previewId);
                try {
                    for (var source : preview.sources) source.validate(this::requireCleanEditors);
                    var progress = new NullProgressMonitor();
                    requireCleanChanges(preview.change);
                    requireValid(preview.change.isValid(progress));
                    for (var edit : preview.edits.edits()) {
                        if (!edit.before().equals(edit.change().getCurrentContent(progress))
                                || !edit.after().equals(edit.change().getPreviewContent(progress)))
                            throw new IllegalStateException("Preview is stale for " + edit.file().getFullPath()
                                    + ". No files were changed; create a new preview.");
                    }
                    // Complete all preflight checks for all files before performing any child change.
                    for (var source : preview.sources) source.validate(this::requireCleanEditors);
                    perform(preview.change, preview.name, progress);
                    result[0] = "Success: " + preview.name + " applied exactly as previewed.\nPreview ID: " + previewId
                            + " (consumed).\n" + RefactoringChangeReport.bounded(preview.report);
                } catch (CoreException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalArgumentException(e.getMessage(), e);
                } finally {
                    preview.change.dispose();
                }
            }, workspace.getRoot(), 0, new NullProgressMonitor());
            return result[0];
        });
    }

    public String getPreview(String previewId, Integer offset) {
        return ui.syncCall(() -> previews.read(previewId, offset == null ? 0 : offset));
    }

    @PreDestroy
    public void disposePreviews() {
        ui.syncCall(() -> { previews.clear(); return null; });
    }

    private static void perform(Change change, String name, NullProgressMonitor progress) throws CoreException {
        var operation = new PerformChangeOperation(change);
        operation.setUndoManager(RefactoringCore.getUndoManager(), name);
        operation.run(progress);
        requireValid(operation.getValidationStatus());
        if (!operation.changeExecuted() || operation.changeExecutionFailed())
            throw new IllegalStateException("Eclipse could not apply the refactoring.");
    }

    private void requireCleanChanges(Change change) {
        Object modified = change.getModifiedElement();
        if (modified instanceof ICompilationUnit unit) modified = unit.getResource();
        if (modified instanceof IFile file) requireCleanEditors(file);
        if (change instanceof CompositeChange composite)
            for (Change child : composite.getChildren()) requireCleanChanges(child);
    }

    private static void requireValid(RefactoringStatus status) {
        if (status != null && status.hasError())
            throw new IllegalArgumentException(status.getEntryWithHighestSeverity().getMessage());
    }

    private static String warnings(RefactoringStatus status) {
        StringBuilder text = new StringBuilder();
        for (RefactoringStatusEntry entry : status.getEntries())
            if (entry.isWarning()) text.append("Warning: ").append(entry.getMessage()).append("\n");
        return text.toString();
    }

    private static <T extends Refactoring> T initialize(T refactoring, NullProgressMonitor monitor) throws CoreException {
        requireValid(refactoring.checkInitialConditions(monitor));
        return refactoring;
    }

    private static IJavaElement selected(ICompilationUnit unit, int offset, int kind) throws JavaModelException {
        IJavaElement[] elements = unit.codeSelect(offset, 0);
        if (elements.length != 1 || elements[0].getElementType() != kind)
            throw new IllegalArgumentException("Select exactly one " + switch (kind) {
                case IJavaElement.METHOD -> "method name";
                case IJavaElement.FIELD -> "field name";
                default -> "local variable or parameter name";
            } + " using its UTF-16 offset.");
        return elements[0];
    }

    private static void requireName(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("A nonempty Java name is required.");
    }

    public String rename(String project, String file, int offset, String name, int kind) {
        return rename(project, file, offset, name, kind, false);
    }

    public String rename(String project, String file, int offset, String name, int kind, boolean preview) {
        requireName(name);
        return execute(project, file, offset, 0, preview, (unit, monitor) -> {
            IJavaElement element = selected(unit, offset, kind);
            if (element instanceof IMethod method && method.isConstructor())
                throw new IllegalArgumentException("Use Rename Java Type for constructors.");
            return renameElement(element, name, kind, monitor);
        });
    }

    public String renameVariable(String project, String file, Integer offset, String oldName, String newName,
            Integer line, String method, boolean preview) {
        requireName(newName);
        if (offset != null) {
            if (oldName != null || line != null || method != null)
                throw new IllegalArgumentException("offset cannot be combined with oldName, lineNumber or methodName.");
            return rename(project, file, offset, newName, IJavaElement.LOCAL_VARIABLE, preview);
        }
        requireName(oldName);
        if (line == null && method == null)
            throw new IllegalArgumentException("Select the variable with lineNumber and/or methodName, or use offset.");
        if (line != null && line < 1)
            throw new IllegalArgumentException("lineNumber must be positive (1-based).");
        if (method != null) requireName(method);
        return execute(project, file, 0, 0, preview, true, (unit, monitor) ->
                renameElement(VariableRenameTarget.select(unit, oldName, line, method), newName,
                        IJavaElement.LOCAL_VARIABLE, monitor));
    }

    private static Refactoring renameElement(IJavaElement element, String name, int kind, NullProgressMonitor monitor)
            throws CoreException {
        String id = switch (kind) {
            case IJavaElement.METHOD -> IJavaRefactorings.RENAME_METHOD;
            case IJavaElement.FIELD -> IJavaRefactorings.RENAME_FIELD;
            default -> IJavaRefactorings.RENAME_LOCAL_VARIABLE;
        };
        var descriptor = (RenameJavaElementDescriptor) RefactoringCore.getRefactoringContribution(id).createDescriptor();
        descriptor.setJavaElement(element);
        descriptor.setNewName(name);
        descriptor.setUpdateReferences(true);
        var status = new RefactoringStatus();
        Refactoring refactoring = descriptor.createRefactoring(status);
        requireValid(status);
        if (refactoring == null) throw new IllegalArgumentException("Eclipse cannot rename this element.");
        return initialize(refactoring, monitor);
    }

    public String extractVariable(String project, String file, int offset, int length, String name,
            Boolean replaceAll, Boolean declareFinal) {
        return extractVariable(project, file, offset, length, name, replaceAll, declareFinal, false);
    }

    public String extractVariable(String project, String file, int offset, int length, String name,
            Boolean replaceAll, Boolean declareFinal, boolean preview) {
        requireName(name);
        requireLength(length);
        return execute(project, file, offset, length, preview, (unit, monitor) -> {
            var refactoring = initialize(new ExtractTempRefactoring(unit, offset, length), monitor);
            refactoring.setTempName(name);
            refactoring.setReplaceAllOccurrences(Boolean.TRUE.equals(replaceAll));
            refactoring.setDeclareFinal(Boolean.TRUE.equals(declareFinal));
            return refactoring;
        });
    }

    public String extractConstant(String project, String file, int offset, int length, String name,
            String visibility, Boolean replaceAll) {
        return extractConstant(project, file, offset, length, name, visibility, replaceAll, false);
    }

    public String extractConstant(String project, String file, int offset, int length, String name,
            String visibility, Boolean replaceAll, boolean preview) {
        requireName(name);
        requireLength(length);
        int modifier = visibility(visibility == null ? "private" : visibility);
        return execute(project, file, offset, length, preview, (unit, monitor) -> {
            var refactoring = initialize(new ExtractConstantRefactoring(unit, offset, length), monitor);
            refactoring.setConstantName(name);
            refactoring.setVisibility(switch (modifier) {
                case Modifier.PRIVATE -> JdtFlags.VISIBILITY_STRING_PRIVATE;
                case Modifier.PROTECTED -> JdtFlags.VISIBILITY_STRING_PROTECTED;
                case Modifier.PUBLIC -> JdtFlags.VISIBILITY_STRING_PUBLIC;
                default -> JdtFlags.VISIBILITY_STRING_PACKAGE;
            });
            refactoring.setReplaceAllOccurrences(Boolean.TRUE.equals(replaceAll));
            return refactoring;
        });
    }

    private static void requireLength(int length) {
        if (length <= 0) throw new IllegalArgumentException("Expression selection length must be positive.");
    }

    public String inlineVariable(String project, String file, int offset) {
        return inlineVariable(project, file, offset, false);
    }

    public String inlineVariable(String project, String file, int offset, boolean preview) {
        return execute(project, file, offset, 0, preview, (unit, monitor) -> {
            selected(unit, offset, IJavaElement.LOCAL_VARIABLE);
            return initialize(new InlineTempRefactoring(unit, offset, 0), monitor);
        });
    }

    public String inlineMethod(String project, String file, int offset, Boolean all, Boolean deleteSource) {
        return inlineMethod(project, file, offset, all, deleteSource, false);
    }

    public String inlineMethod(String project, String file, int offset, Boolean all, Boolean deleteSource, boolean preview) {
        if (Boolean.TRUE.equals(deleteSource) && !Boolean.TRUE.equals(all))
            throw new IllegalArgumentException("Deleting the method requires inlineAll=true.");
        return execute(project, file, offset, 0, preview, (unit, monitor) -> {
            selected(unit, offset, IJavaElement.METHOD);
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setSource(unit);
            parser.setResolveBindings(true);
            var ast = (CompilationUnit) parser.createAST(monitor);
            var refactoring = InlineMethodRefactoring.create(unit, ast, offset, 0);
            if (refactoring == null) throw new IllegalArgumentException("Select a method declaration or invocation.");
            requireValid(refactoring.checkInitialConditions(monitor));
            var mode = all == null ? refactoring.getInitialMode() : Boolean.TRUE.equals(all)
                    ? InlineMethodRefactoring.Mode.INLINE_ALL : InlineMethodRefactoring.Mode.INLINE_SINGLE;
            if (refactoring.getInitialMode() == InlineMethodRefactoring.Mode.INLINE_ALL
                    && mode == InlineMethodRefactoring.Mode.INLINE_SINGLE)
                throw new IllegalArgumentException("Select a method invocation to inline a single call.");
            requireValid(refactoring.setCurrentMode(mode));
            if (Boolean.TRUE.equals(deleteSource) && !refactoring.canEnableDeleteSource())
                throw new IllegalArgumentException("Eclipse cannot delete this method declaration.");
            refactoring.setDeleteSource(Boolean.TRUE.equals(deleteSource));
            return refactoring;
        });
    }

    public String changeSignature(String project, String file, int offset, String name, String returnType,
            String visibility, String parameters) {
        return changeSignature(project, file, offset, name, returnType, visibility, parameters, false);
    }

    public String changeSignature(String project, String file, int offset, String name, String returnType,
            String visibility, String parameters, boolean preview) {
        if (name == null && returnType == null && visibility == null && parameters == null)
            throw new IllegalArgumentException("Specify a new name, return type, visibility or parameter list.");
        if (name != null) requireName(name);
        if (returnType != null) requireName(returnType);
        return execute(project, file, offset, 0, preview, (unit, monitor) -> {
            var method = (IMethod) selected(unit, offset, IJavaElement.METHOD);
            var processor = new ChangeSignatureProcessor(method);
            requireValid(processor.checkInitialConditions(monitor));
            if (name != null) processor.setNewMethodName(name);
            if (returnType != null) processor.setNewReturnTypeName(returnType);
            if (visibility != null) processor.setVisibility(visibility(visibility));
            if (parameters != null) configureParameters(processor.getParameterInfos(), parameters);
            requireValid(processor.checkSignature());
            return new ProcessorBasedRefactoring(processor);
        });
    }

    private static int visibility(String visibility) {
        return switch (visibility) {
            case "private" -> Modifier.PRIVATE;
            case "protected" -> Modifier.PROTECTED;
            case "public" -> Modifier.PUBLIC;
            case "package" -> Modifier.NONE;
            default -> throw new IllegalArgumentException("Visibility must be private, protected, public or package.");
        };
    }

    static void configureParameters(List<ParameterInfo> parameters, String input) throws Exception {
        JsonNode array = new ObjectMapper().readTree(input);
        if (array == null || !array.isArray()) throw new IllegalArgumentException("parameters must be a JSON array.");
        List<ParameterInfo> original = new ArrayList<>(parameters);
        List<ParameterInfo> ordered = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        for (JsonNode parameter : array) {
            if (!parameter.isObject()) throw new IllegalArgumentException("Each parameter must be an object.");
            var names = parameter.fieldNames();
            while (names.hasNext()) {
                if (!Set.of("oldIndex", "name", "type", "defaultValue").contains(names.next()))
                    throw new IllegalArgumentException("Unknown parameter field.");
            }
            JsonNode indexNode = parameter.get("oldIndex");
            if (indexNode == null || !indexNode.isIntegralNumber() || !indexNode.canConvertToInt())
                throw new IllegalArgumentException("Each parameter needs an integer oldIndex (-1 for new parameters).");
            int index = indexNode.intValue();
            String name = requiredText(parameter, "name");
            String type = requiredText(parameter, "type");
            if (parameter.has("defaultValue") && !parameter.get("defaultValue").isTextual())
                throw new IllegalArgumentException("defaultValue must be a Java expression string.");
            ParameterInfo info;
            if (index == -1) {
                info = ParameterInfo.createInfoForAddedParameter(type, name, requiredText(parameter, "defaultValue"));
            } else {
                if (index < 0 || index >= original.size() || !used.add(index))
                    throw new IllegalArgumentException("Invalid or duplicate oldIndex in parameters.");
                info = original.get(index);
                info.setNewName(name);
                info.setNewTypeName(type);
            }
            ordered.add(info);
        }
        for (int i = 0; i < original.size(); i++) {
            if (!used.contains(i)) {
                original.get(i).markAsDeleted();
                ordered.add(original.get(i));
            }
        }
        parameters.clear();
        parameters.addAll(ordered);
    }

    private static String requiredText(JsonNode parameter, String field) {
        JsonNode node = parameter.get(field);
        if (node == null || !node.isTextual() || node.textValue().isBlank())
            throw new IllegalArgumentException("Each parameter needs a nonempty " + field + ".");
        return node.textValue();
    }
}
