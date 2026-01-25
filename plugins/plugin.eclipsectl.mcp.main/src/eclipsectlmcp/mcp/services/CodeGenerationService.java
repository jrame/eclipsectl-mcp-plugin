package eclipsectlmcp.mcp.services;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.ui.CodeGeneration;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;

import eclipsectlmcp.tools.ResourceUtilities;
import jakarta.inject.Inject;

/**
 * Service for generating boilerplate code such as getters and setters.
 */
@Creatable
public class CodeGenerationService {

    @Inject
    private ILog logger;

    /**
     * Generates getter and/or setter methods for fields in a Java class.
     *
     * @param projectName The name of the project (optional if file path is unique)
     * @param filePath The path to the Java file relative to the project root
     * @param className The name of the class (optional if file contains only one class)
     * @param fieldNames List of field names to generate methods for (empty = all fields)
     * @param generateGetters Whether to generate getter methods
     * @param generateSetters Whether to generate setter methods
     * @param insertionPoint Where to insert methods: "end" or line number
     * @return Status message with details of generated methods
     */
    public String generateGettersSetters(String projectName, String filePath,
                                          String className, List<String> fieldNames,
                                          boolean generateGetters, boolean generateSetters,
                                          String insertionPoint) {
        Objects.requireNonNull(filePath);

        if (filePath.isEmpty()) {
            return "Error: File path cannot be empty.";
        }

        if (!generateGetters && !generateSetters) {
            return "Error: Must generate at least getters or setters.";
        }

        try {
            // Find the file
            IFile file = ResourceUtilities.findFile(projectName, filePath);
            if (file == null || !file.exists()) {
                return "Error: File not found: " + filePath;
            }

            if (!filePath.endsWith(".java")) {
                return "Error: File must be a Java file: " + filePath;
            }

            // Get compilation unit
            IJavaElement javaElement = JavaCore.create(file);
            if (!(javaElement instanceof ICompilationUnit)) {
                return "Error: Could not resolve Java compilation unit for file: " + filePath;
            }

            ICompilationUnit compilationUnit = (ICompilationUnit) javaElement;

            // Find the type
            IType type = findType(compilationUnit, className);
            if (type == null) {
                return "Error: Could not find class" + (className != null ? " '" + className + "'" : "") + " in file: " + filePath;
            }

            // Get fields
            IField[] allFields = type.getFields();
            if (allFields.length == 0) {
                return "No fields found in class '" + type.getElementName() + "'.";
            }

            // Filter fields if specific names provided
            IField[] fields = filterFields(allFields, fieldNames);
            if (fields.length == 0) {
                return "Error: No matching fields found. Available fields: " + Arrays.toString(Arrays.stream(allFields).map(IField::getElementName).toArray());
            }

            // Generate methods
            List<GeneratedMethodInfo> generated = new ArrayList<>();
            int insertPosition = determineInsertPosition(type, insertionPoint);

            for (IField field : fields) {
                String fieldName = field.getElementName();

                // Generate getter
                if (generateGetters) {
                    if (hasGetter(type, field)) {
                        logger.info("Getter already exists for field: " + fieldName);
                    } else {
                        String getter = generateGetterSource(field, type);
                        if (getter != null) {
                            insertMethod(type, getter, insertPosition);
                            generated.add(new GeneratedMethodInfo("getter", fieldName, getGetterName(field)));
                        }
                    }
                }

                // Generate setter
                if (generateSetters) {
                    if (hasSetter(type, field)) {
                        logger.info("Setter already exists for field: " + fieldName);
                    } else {
                        String setter = generateSetterSource(field, type);
                        if (setter != null) {
                            insertMethod(type, setter, insertPosition);
                            generated.add(new GeneratedMethodInfo("setter", fieldName, getSetterName(field)));
                        }
                    }
                }
            }

            if (generated.isEmpty()) {
                return "No methods generated. All getters/setters already exist for the specified fields.";
            }

            // Save the compilation unit
            compilationUnit.save(new NullProgressMonitor(), true);

            // Refresh the file
            file.refreshLocal(IResource.DEPTH_ZERO, new NullProgressMonitor());

            return formatGenerationResult(type, generated, fields);

        } catch (Exception e) {
            logger.error("Error generating getters/setters", e);
            return "Error generating getters/setters: " + e.getMessage();
        }
    }

    /**
     * Finds the type in the compilation unit.
     */
    private IType findType(ICompilationUnit cu, String className) throws JavaModelException {
        if (className == null || className.isEmpty()) {
            // Return primary type if no class name specified
            return cu.findPrimaryType();
        }

        // Search for the specified class
        IType[] types = cu.getAllTypes();
        for (IType type : types) {
            if (type.getElementName().equals(className)) {
                return type;
            }
        }

        return null;
    }

    /**
     * Filters fields by name if specified.
     */
    private IField[] filterFields(IField[] allFields, List<String> fieldNames) {
        if (fieldNames == null || fieldNames.isEmpty()) {
            return allFields;
        }

        List<IField> filtered = new ArrayList<>();
        for (IField field : allFields) {
            String fieldName = field.getElementName().trim();
            if (fieldNames.stream().anyMatch(name -> name.trim().equals(fieldName))) {
                filtered.add(field);
            }
        }

        return filtered.toArray(new IField[0]);
    }

    /**
     * Checks if a getter already exists for a field.
     */
    private boolean hasGetter(IType type, IField field) throws JavaModelException {
        String getterName = getGetterName(field);
        IMethod[] methods = type.getMethods();

        for (IMethod method : methods) {
            if (method.getElementName().equals(getterName) && method.getNumberOfParameters() == 0) {
                return true;
            }
        }

        return false;
    }

    /**
     * Checks if a setter already exists for a field.
     */
    private boolean hasSetter(IType type, IField field) throws JavaModelException {
        String setterName = getSetterName(field);
        IMethod[] methods = type.getMethods();

        for (IMethod method : methods) {
            if (method.getElementName().equals(setterName) && method.getNumberOfParameters() == 1) {
                return true;
            }
        }

        return false;
    }

    /**
     * Generates getter method name for a field.
     */
    private String getGetterName(IField field) throws JavaModelException {
        String fieldName = field.getElementName();
        String typeSignature = field.getTypeSignature();
        boolean isBoolean = "Z".equals(typeSignature) || "boolean".equals(Signature.toString(typeSignature));

        String prefix = isBoolean ? "is" : "get";
        return prefix + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
    }

    /**
     * Generates setter method name for a field.
     */
    private String getSetterName(IField field) {
        String fieldName = field.getElementName();
        return "set" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
    }

    /**
     * Generates getter method source code.
     */
    private String generateGetterSource(IField field, IType type) throws Exception {
        String fieldName = field.getElementName();
        String getterName = getGetterName(field);
        String typeString = Signature.toString(field.getTypeSignature());

        StringBuilder getter = new StringBuilder();
        getter.append("\n");
        getter.append("    public ").append(typeString).append(" ").append(getterName).append("() {\n");
        getter.append("        return ").append(fieldName).append(";\n");
        getter.append("    }\n");

        return getter.toString();
    }

    /**
     * Generates setter method source code.
     */
    private String generateSetterSource(IField field, IType type) throws Exception {
        String fieldName = field.getElementName();
        String setterName = getSetterName(field);
        String typeString = Signature.toString(field.getTypeSignature());

        StringBuilder setter = new StringBuilder();
        setter.append("\n");
        setter.append("    public void ").append(setterName).append("(").append(typeString).append(" ").append(fieldName).append(") {\n");
        setter.append("        this.").append(fieldName).append(" = ").append(fieldName).append(";\n");
        setter.append("    }\n");

        return setter.toString();
    }

    /**
     * Inserts a method into the type.
     */
    private void insertMethod(IType type, String methodSource, int position) throws JavaModelException {
        if (position >= 0) {
            // Insert at specific position (not fully implemented - would need sibling element)
            type.createMethod(methodSource, null, false, new NullProgressMonitor());
        } else {
            // Insert at end
            type.createMethod(methodSource, null, false, new NullProgressMonitor());
        }
    }

    /**
     * Determines insertion position for new methods.
     */
    private int determineInsertPosition(IType type, String insertionPoint) {
        if (insertionPoint == null || "end".equalsIgnoreCase(insertionPoint)) {
            return -1; // Insert at end
        }

        try {
            return Integer.parseInt(insertionPoint);
        } catch (NumberFormatException e) {
            return -1; // Default to end
        }
    }

    /**
     * Formats the generation result message.
     */
    private String formatGenerationResult(IType type, List<GeneratedMethodInfo> generated, IField[] fields) {
        StringBuilder result = new StringBuilder();

        result.append("Getters/Setters Generated: Success\n\n");
        result.append("Class: ").append(type.getFullyQualifiedName()).append("\n");
        result.append("File: ").append(type.getPath().toString()).append("\n\n");

        result.append("Generated methods (").append(generated.size()).append("):\n");
        for (GeneratedMethodInfo info : generated) {
            result.append("  - ").append(info.methodName).append("() [");
            result.append(info.type).append(" for ").append(info.fieldName).append("]\n");
        }

        result.append("\nFields processed: ");
        result.append(String.join(", ", Arrays.stream(fields).map(IField::getElementName).toArray(String[]::new)));

        return result.toString();
    }

    /**
     * Generates toString, equals, and/or hashCode methods for a Java class.
     *
     * @param projectName The name of the project (optional if file path is unique)
     * @param filePath The path to the Java file relative to the project root
     * @param className The name of the class (optional if file contains only one class)
     * @param fieldNames List of field names to include (empty = all fields)
     * @param generateToString Whether to generate toString method
     * @param generateEquals Whether to generate equals method
     * @param generateHashCode Whether to generate hashCode method
     * @return Status message with details of generated methods
     */
    public String generateToStringEqualsHashCode(String projectName, String filePath,
                                                  String className, List<String> fieldNames,
                                                  boolean generateToString, boolean generateEquals,
                                                  boolean generateHashCode) {
        Objects.requireNonNull(filePath);

        if (filePath.isEmpty()) {
            return "Error: File path cannot be empty.";
        }

        if (!generateToString && !generateEquals && !generateHashCode) {
            return "Error: Must generate at least one method (toString, equals, or hashCode).";
        }

        try {
            // Find the file
            IFile file = ResourceUtilities.findFile(projectName, filePath);
            if (file == null || !file.exists()) {
                return "Error: File not found: " + filePath;
            }

            if (!filePath.endsWith(".java")) {
                return "Error: File must be a Java file: " + filePath;
            }

            // Get compilation unit
            IJavaElement javaElement = JavaCore.create(file);
            if (!(javaElement instanceof ICompilationUnit)) {
                return "Error: Could not resolve Java compilation unit for file: " + filePath;
            }

            ICompilationUnit compilationUnit = (ICompilationUnit) javaElement;

            // Find the type
            IType type = findType(compilationUnit, className);
            if (type == null) {
                return "Error: Could not find class" + (className != null ? " '" + className + "'" : "") + " in file: " + filePath;
            }

            // Get fields
            IField[] allFields = type.getFields();
            if (allFields.length == 0) {
                return "No fields found in class '" + type.getElementName() + "'.";
            }

            // Filter fields if specific names provided
            IField[] fields = filterFields(allFields, fieldNames);
            if (fields.length == 0) {
                return "Error: No matching fields found. Available fields: " + Arrays.toString(Arrays.stream(allFields).map(IField::getElementName).toArray());
            }

            // Generate methods
            List<GeneratedMethodInfo> generated = new ArrayList<>();

            // Generate toString
            if (generateToString) {
                if (hasMethod(type, "toString", 0)) {
                    logger.info("toString method already exists");
                } else {
                    String toStringMethod = generateToStringSource(type, fields);
                    if (toStringMethod != null) {
                        insertMethod(type, toStringMethod, -1);
                        generated.add(new GeneratedMethodInfo("toString", "", "toString"));
                    }
                }
            }

            // Generate equals
            if (generateEquals) {
                if (hasMethod(type, "equals", 1)) {
                    logger.info("equals method already exists");
                } else {
                    String equalsMethod = generateEqualsSource(type, fields);
                    if (equalsMethod != null) {
                        insertMethod(type, equalsMethod, -1);
                        generated.add(new GeneratedMethodInfo("equals", "", "equals"));
                    }
                }
            }

            // Generate hashCode
            if (generateHashCode) {
                if (hasMethod(type, "hashCode", 0)) {
                    logger.info("hashCode method already exists");
                } else {
                    String hashCodeMethod = generateHashCodeSource(type, fields);
                    if (hashCodeMethod != null) {
                        insertMethod(type, hashCodeMethod, -1);
                        generated.add(new GeneratedMethodInfo("hashCode", "", "hashCode"));
                    }
                }
            }

            if (generated.isEmpty()) {
                return "No methods generated. All requested methods already exist.";
            }

            // Save the compilation unit
            compilationUnit.save(new NullProgressMonitor(), true);

            // Refresh the file
            file.refreshLocal(IResource.DEPTH_ZERO, new NullProgressMonitor());

            return formatToStringEqualsHashCodeResult(type, generated, fields);

        } catch (Exception e) {
            logger.error("Error generating toString/equals/hashCode", e);
            return "Error generating toString/equals/hashCode: " + e.getMessage();
        }
    }

    /**
     * Implements or overrides methods from superclass or interfaces.
     *
     * @param projectName The name of the project (optional if file path is unique)
     * @param filePath The path to the Java file relative to the project root
     * @param className The name of the class (optional if file contains only one class)
     * @param methodNames List of method names to implement (empty = all abstract methods)
     * @return Status message with details of implemented methods
     */
    public String implementOverrideMethods(String projectName, String filePath,
                                           String className, List<String> methodNames) {
        Objects.requireNonNull(filePath);

        if (filePath.isEmpty()) {
            return "Error: File path cannot be empty.";
        }

        try {
            // Find the file
            IFile file = ResourceUtilities.findFile(projectName, filePath);
            if (file == null || !file.exists()) {
                return "Error: File not found: " + filePath;
            }

            if (!filePath.endsWith(".java")) {
                return "Error: File must be a Java file: " + filePath;
            }

            // Get compilation unit
            IJavaElement javaElement = JavaCore.create(file);
            if (!(javaElement instanceof ICompilationUnit)) {
                return "Error: Could not resolve Java compilation unit for file: " + filePath;
            }

            ICompilationUnit compilationUnit = (ICompilationUnit) javaElement;

            // Find the type
            IType type = findType(compilationUnit, className);
            if (type == null) {
                return "Error: Could not find class" + (className != null ? " '" + className + "'" : "") + " in file: " + filePath;
            }

            // Get unimplemented methods
            List<IMethod> methodsToImplement = findUnimplementedMethods(type, methodNames);

            if (methodsToImplement.isEmpty()) {
                if (methodNames == null || methodNames.isEmpty()) {
                    return "No unimplemented methods found in class '" + type.getElementName() + "'.";
                } else {
                    return "Error: No matching unimplemented methods found for: " + String.join(", ", methodNames);
                }
            }

            // Generate implementations
            List<GeneratedMethodInfo> generated = new ArrayList<>();

            for (IMethod method : methodsToImplement) {
                String methodImpl = generateMethodImplementation(type, method);
                if (methodImpl != null) {
                    insertMethod(type, methodImpl, -1);
                    generated.add(new GeneratedMethodInfo("implementation", "", method.getElementName()));
                }
            }

            if (generated.isEmpty()) {
                return "No methods implemented.";
            }

            // Save the compilation unit
            compilationUnit.save(new NullProgressMonitor(), true);

            // Refresh the file
            file.refreshLocal(IResource.DEPTH_ZERO, new NullProgressMonitor());

            return formatImplementationResult(type, generated);

        } catch (Exception e) {
            logger.error("Error implementing/overriding methods", e);
            return "Error implementing/overriding methods: " + e.getMessage();
        }
    }

    /**
     * Checks if a method with given name and parameter count exists.
     */
    private boolean hasMethod(IType type, String methodName, int paramCount) throws JavaModelException {
        IMethod[] methods = type.getMethods();
        for (IMethod method : methods) {
            if (method.getElementName().equals(methodName) && method.getNumberOfParameters() == paramCount) {
                return true;
            }
        }
        return false;
    }

    /**
     * Generates toString method source code.
     */
    private String generateToStringSource(IType type, IField[] fields) throws Exception {
        StringBuilder method = new StringBuilder();
        method.append("\n");
        method.append("    @Override\n");
        method.append("    public String toString() {\n");
        method.append("        return \"").append(type.getElementName()).append(" [");

        for (int i = 0; i < fields.length; i++) {
            String fieldName = fields[i].getElementName();
            method.append(fieldName).append("=\" + ").append(fieldName);
            if (i < fields.length - 1) {
                method.append(" + \", ");
            }
        }

        method.append(" + \"]\";\n");
        method.append("    }\n");

        return method.toString();
    }

    /**
     * Generates equals method source code.
     */
    private String generateEqualsSource(IType type, IField[] fields) throws Exception {
        StringBuilder method = new StringBuilder();
        method.append("\n");
        method.append("    @Override\n");
        method.append("    public boolean equals(Object obj) {\n");
        method.append("        if (this == obj) return true;\n");
        method.append("        if (obj == null || getClass() != obj.getClass()) return false;\n");
        method.append("        ").append(type.getElementName()).append(" other = (").append(type.getElementName()).append(") obj;\n");

        for (int i = 0; i < fields.length; i++) {
            String fieldName = fields[i].getElementName();
            String typeSignature = Signature.toString(fields[i].getTypeSignature());

            if (isPrimitiveType(typeSignature)) {
                method.append("        if (").append(fieldName).append(" != other.").append(fieldName).append(") return false;\n");
            } else {
                method.append("        if (!java.util.Objects.equals(").append(fieldName).append(", other.").append(fieldName).append(")) return false;\n");
            }
        }

        method.append("        return true;\n");
        method.append("    }\n");

        return method.toString();
    }

    /**
     * Generates hashCode method source code.
     */
    private String generateHashCodeSource(IType type, IField[] fields) throws Exception {
        StringBuilder method = new StringBuilder();
        method.append("\n");
        method.append("    @Override\n");
        method.append("    public int hashCode() {\n");
        method.append("        return java.util.Objects.hash(");

        for (int i = 0; i < fields.length; i++) {
            method.append(fields[i].getElementName());
            if (i < fields.length - 1) {
                method.append(", ");
            }
        }

        method.append(");\n");
        method.append("    }\n");

        return method.toString();
    }

    /**
     * Finds unimplemented methods from superclass and interfaces.
     */
    private List<IMethod> findUnimplementedMethods(IType type, List<String> methodNames) throws JavaModelException {
        List<IMethod> unimplemented = new ArrayList<>();

        // Get the type hierarchy
        IMethod[] methods = type.getMethods();

        // This is a simplified version - ideally we'd use ITypeHierarchy
        // For now, we'll check for abstract methods in the type itself
        for (IMethod method : methods) {
            int flags = method.getFlags();
            boolean isAbstract = org.eclipse.jdt.core.Flags.isAbstract(flags);

            if (isAbstract) {
                if (methodNames == null || methodNames.isEmpty() || methodNames.contains(method.getElementName())) {
                    unimplemented.add(method);
                }
            }
        }

        return unimplemented;
    }

    /**
     * Generates implementation for a method.
     */
    private String generateMethodImplementation(IType type, IMethod method) throws Exception {
        StringBuilder impl = new StringBuilder();
        impl.append("\n");
        impl.append("    @Override\n");
        impl.append("    public ");

        String returnType = Signature.toString(method.getReturnType());
        impl.append(returnType).append(" ");
        impl.append(method.getElementName()).append("(");

        String[] paramTypes = method.getParameterTypes();
        String[] paramNames = method.getParameterNames();

        for (int i = 0; i < paramTypes.length; i++) {
            String paramType = Signature.toString(paramTypes[i]);
            String paramName = paramNames[i];
            impl.append(paramType).append(" ").append(paramName);
            if (i < paramTypes.length - 1) {
                impl.append(", ");
            }
        }

        impl.append(") {\n");
        impl.append("        // TODO: Implement this method\n");

        // Add appropriate return statement based on return type
        if (!"void".equals(returnType)) {
            impl.append("        ");
            if ("boolean".equals(returnType)) {
                impl.append("return false;\n");
            } else if (isPrimitiveType(returnType)) {
                impl.append("return 0;\n");
            } else {
                impl.append("return null;\n");
            }
        }

        impl.append("    }\n");

        return impl.toString();
    }

    /**
     * Checks if a type is a primitive type.
     */
    private boolean isPrimitiveType(String typeName) {
        return Arrays.asList("byte", "short", "int", "long", "float", "double", "boolean", "char").contains(typeName);
    }

    /**
     * Formats the toString/equals/hashCode generation result.
     */
    private String formatToStringEqualsHashCodeResult(IType type, List<GeneratedMethodInfo> generated, IField[] fields) {
        StringBuilder result = new StringBuilder();

        result.append("toString/equals/hashCode Generated: Success\n\n");
        result.append("Class: ").append(type.getFullyQualifiedName()).append("\n");
        result.append("File: ").append(type.getPath().toString()).append("\n\n");

        result.append("Generated methods (").append(generated.size()).append("):\n");
        for (GeneratedMethodInfo info : generated) {
            result.append("  - ").append(info.methodName).append("()\n");
        }

        result.append("\nFields included: ");
        result.append(String.join(", ", Arrays.stream(fields).map(IField::getElementName).toArray(String[]::new)));

        return result.toString();
    }

    /**
     * Formats the method implementation result.
     */
    private String formatImplementationResult(IType type, List<GeneratedMethodInfo> generated) {
        StringBuilder result = new StringBuilder();

        result.append("Method Implementation: Success\n\n");
        result.append("Class: ").append(type.getFullyQualifiedName()).append("\n");
        result.append("File: ").append(type.getPath().toString()).append("\n\n");

        result.append("Implemented methods (").append(generated.size()).append("):\n");
        for (GeneratedMethodInfo info : generated) {
            result.append("  - ").append(info.methodName).append("()\n");
        }

        return result.toString();
    }

    /**
     * Internal class to track generated method information.
     */
    private static class GeneratedMethodInfo {
        String type; // "getter" or "setter"
        String fieldName;
        String methodName;

        GeneratedMethodInfo(String type, String fieldName, String methodName) {
            this.type = type;
            this.fieldName = fieldName;
            this.methodName = methodName;
        }
    }
}
