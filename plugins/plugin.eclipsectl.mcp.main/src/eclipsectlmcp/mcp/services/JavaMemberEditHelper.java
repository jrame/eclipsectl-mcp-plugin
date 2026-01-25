package eclipsectlmcp.mcp.services;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.HistogramDiff;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;

/**
 * Helper class for parsing Java members and computing diffs.
 */
public class JavaMemberEditHelper {

    @SuppressWarnings("unchecked")
    public static List<ParsedMember> parseNewSource(String newSource) {
        String wrapped = "class __Temp {\n" + newSource + "\n}";
        ASTParser parser = ASTParser.newParser(AST.JLS_Latest);
        parser.setSource(wrapped.toCharArray());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        CompilationUnit astCu = (CompilationUnit) parser.createAST(null);

        List<?> types = astCu.types();
        if (types.isEmpty()) {
            throw new RuntimeException("Error: Could not parse newSource.");
        }

        AbstractTypeDeclaration tempType = (AbstractTypeDeclaration) types.get(0);
        List<BodyDeclaration> members = tempType.bodyDeclarations();
        if (members.isEmpty()) {
            throw new RuntimeException("Error: newSource does not contain any class member.");
        }

        int wrapperPrefixLen = "class __Temp {\n".length();
        List<ParsedMember> results = new ArrayList<>();

        for (BodyDeclaration member : members) {
            ParsedMember result = new ParsedMember();
            int start = member.getStartPosition() - wrapperPrefixLen;
            int end = start + member.getLength();
            result.source = newSource.substring(Math.max(0, start), Math.min(newSource.length(), end)).trim();

            if (member instanceof MethodDeclaration method) {
                result.name = method.getName().getIdentifier();
                result.kind = method.isConstructor() ? "constructor" : "method";
                result.paramTypes = new ArrayList<>();
                for (Object param : method.parameters()) {
                    SingleVariableDeclaration svd = (SingleVariableDeclaration) param;
                    Type paramType = svd.getType();
                    String typeName = paramType.toString();
                    if (svd.isVarargs()) {
                        typeName += "[]";
                    }
                    for (int i = 0; i < svd.getExtraDimensions(); i++) {
                        typeName += "[]";
                    }
                    result.paramTypes.add(typeName);
                }
            } else if (member instanceof FieldDeclaration field) {
                List<VariableDeclarationFragment> fragments = field.fragments();
                result.kind = "field";
                result.name = fragments.get(0).getName().getIdentifier();
                result.paramTypes = null;
            } else if (member instanceof AbstractTypeDeclaration typeDecl) {
                result.kind = "type";
                result.name = typeDecl.getName().getIdentifier();
                result.paramTypes = null;
            } else {
                throw new RuntimeException("Error: Unsupported member type in newSource: " + member.getClass().getSimpleName());
            }

            results.add(result);
        }

        return results;
    }

    /**
     * Extract the header of a type declaration (javadoc + annotations + declaration up to and including '{').
     */
    public static String extractTypeHeader(String typeSource) {
        int openBrace = typeSource.indexOf('{');
        if (openBrace < 0) return typeSource;
        return typeSource.substring(0, openBrace + 1);
    }

    /**
     * Extract the body of a type declaration from source (content between first '{' and last '}').
     */
    public static String extractTypeBody(String typeSource) {
        int openBrace = typeSource.indexOf('{');
        int closeBrace = typeSource.lastIndexOf('}');
        if (openBrace < 0 || closeBrace < 0 || closeBrace <= openBrace) {
            return "";
        }
        return typeSource.substring(openBrace + 1, closeBrace).trim();
    }

    public static IMember findExistingMember(IType targetType, ParsedMember parsed) throws JavaModelException {
        switch (parsed.kind) {
            case "method", "constructor" -> {
                String[] paramSigs = parsed.paramTypes.stream()
                        .map(JavaMemberEditHelper::toSimpleTypeSignature)
                        .toArray(String[]::new);

                IMethod method = targetType.getMethod(parsed.name, paramSigs);
                if (method.exists()) return method;

                for (IMethod m : targetType.getMethods()) {
                    if (m.getElementName().equals(parsed.name) &&
                        m.getParameterTypes().length == parsed.paramTypes.size()) {
                        return m;
                    }
                }
                return null;
            }
            case "field" -> {
                IField field = targetType.getField(parsed.name);
                return field.exists() ? field : null;
            }
            case "type" -> {
                IType inner = targetType.getType(parsed.name);
                return inner.exists() ? inner : null;
            }
            default -> { return null; }
        }
    }

    public static String toSimpleTypeSignature(String typeName) {
        int arrayDim = 0;
        String base = typeName;
        while (base.endsWith("[]")) {
            arrayDim++;
            base = base.substring(0, base.length() - 2);
        }

        String sig;
        switch (base) {
            case "int" -> sig = "I";
            case "long" -> sig = "J";
            case "short" -> sig = "S";
            case "byte" -> sig = "B";
            case "float" -> sig = "F";
            case "double" -> sig = "D";
            case "boolean" -> sig = "Z";
            case "char" -> sig = "C";
            case "void" -> sig = "V";
            default -> sig = "Q" + base + ";";
        }

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < arrayDim; i++) {
            result.append("[");
        }
        result.append(sig);
        return result.toString();
    }

    public static String computeUnifiedDiff(String oldSource, String newSource) {
        try {
            RawText oldText = new RawText(oldSource.getBytes(StandardCharsets.UTF_8));
            RawText newText = new RawText(newSource.getBytes(StandardCharsets.UTF_8));
            EditList edits = new HistogramDiff().diff(RawTextComparator.DEFAULT, oldText, newText);

            if (edits.isEmpty()) return "";

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (DiffFormatter formatter = new DiffFormatter(out)) {
                formatter.format(edits, oldText, newText);
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "(diff unavailable: " + e.getMessage() + ")";
        }
    }

    public static String getCompilationErrors(IFile file) {
        try {
            file.getProject().build(org.eclipse.core.resources.IncrementalProjectBuilder.INCREMENTAL_BUILD,
                    new NullProgressMonitor());

            IMarker[] markers = file.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_ZERO);
            StringBuilder sb = new StringBuilder();
            for (IMarker marker : markers) {
                int severity = marker.getAttribute(IMarker.SEVERITY, -1);
                if (severity == IMarker.SEVERITY_ERROR) {
                    int line = marker.getAttribute(IMarker.LINE_NUMBER, -1);
                    String msg = marker.getAttribute(IMarker.MESSAGE, "");
                    sb.append("  Line ").append(line).append(": ").append(msg).append("\n");
                }
            }
            return sb.toString();
        } catch (CoreException e) {
            return "(could not retrieve compilation errors: " + e.getMessage() + ")";
        }
    }

    public static IMember findMemberByName(IType type, String name) throws JavaModelException {
        IField field = type.getField(name);
        if (field.exists()) return field;

        for (IMethod method : type.getMethods()) {
            if (method.getElementName().equals(name)) return method;
        }

        IType inner = type.getType(name);
        if (inner.exists()) return inner;

        return null;
    }

    public static List<int[]> parseLineRanges(String spec) {
        List<int[]> ranges = new ArrayList<>();
        for (String part : spec.split(",")) {
            part = part.trim();
            if (part.contains("-")) {
                String[] bounds = part.split("-", 2);
                int start = Integer.parseInt(bounds[0].trim());
                int end = Integer.parseInt(bounds[1].trim());
                if (start < 1 || end < start) {
                    throw new IllegalArgumentException("Error: Invalid line range '" + part + "'.");
                }
                ranges.add(new int[] { start, end });
            } else {
                int line = Integer.parseInt(part);
                if (line < 1) {
                    throw new IllegalArgumentException("Error: Invalid line number '" + part + "'.");
                }
                ranges.add(new int[] { line, line });
            }
        }
        return ranges;
    }
}
