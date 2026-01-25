package eclipsectlmcp.mcp.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jdt.core.IBuffer;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jface.text.Document;
import org.eclipse.ui.editors.text.TextFileDocumentProvider;

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;

import jakarta.inject.Inject;

/**
 * Service for retrieving JavaDoc and source code information from Java projects.
 */
@Creatable
public class JavaDocService {
    
    @Inject
    private ILog logger;
    
    /**
     * Retrieves the attached JavaDoc documentation for a given class within the available Java projects.
     * It searches all projects for the JavaDoc and if found, it returns the JavaDoc content. If no JavaDoc
     * is found, it returns a message stating that JavaDoc is not available for the specified class.
     *
     * @param fullyQualifiedClassName The fully qualified name of the class to find the JavaDoc for.
     * @return The JavaDoc string if available; otherwise, a message indicating it is not available.
     */
    public String getJavaDoc(String fullyQualifiedClassName) {
        if (fullyQualifiedClassName == null || fullyQualifiedClassName.isEmpty()) {
            return "Error: Fully qualified class name cannot be null or empty.";
        }
        return getAvailableJavaProjects().stream()
                .map(project -> getAttachedJavadoc(fullyQualifiedClassName, project))
                .filter(Objects::nonNull)
                .filter(Predicate.not(String::isBlank))
                .findAny()
                .orElse("JavaDoc is not available for " + fullyQualifiedClassName);
    }
    
    /**
     * Retrieves the source code attached to the specified class within the available Java projects.
     * It searches all projects for the source code and if found, returns the source content. If no source code
     * is found or an exception occurs, it returns a message indicating that the source is not available for the specified class.
     *
     * @param fullyQualifiedClassName The fully qualified name of the class for which to find the source code.
     * @return The source code string if available; otherwise, a message indicating it is not available.
     */
    public String getSource(String fullyQualifiedClassName) {
        if (fullyQualifiedClassName == null || fullyQualifiedClassName.isEmpty()) {
            return "Error: Fully qualified class name cannot be null or empty.";
        }
        return getAvailableJavaProjects().stream()
                .map(project -> getAttachedSource(fullyQualifiedClassName, project))
                .filter(Objects::nonNull)
                .filter(Predicate.not(String::isBlank))
                .findAny()
                .orElse("Source is not available for " + fullyQualifiedClassName);
    }
    
    /**
     * Retrieves a list of all available Java projects in the current workspace.
     * It filters out non-Java projects and only includes projects that are open and have the Java nature.
     *
     * @return A list of {@link IJavaProject} representing the available Java projects.
     * @throws RuntimeException if an error occurs while accessing project information.
     */
    public List<IJavaProject> getAvailableJavaProjects() 
    {
        List<IJavaProject> javaProjects = new ArrayList<>();

        try {
            // Get all projects in the workspace
            IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();

            // Filter out the Java projects
            for (IProject project : projects) {
                if (project.isOpen() && project.hasNature(JavaCore.NATURE_ID)) {
                    IJavaProject javaProject = JavaCore.create(project);
                    javaProjects.add(javaProject);
                }
            }
        } catch (CoreException e) {
            throw new RuntimeException(e);
        }

        return javaProjects;
    }
    
    /**
     * Gathers and returns JavaDoc information for a specified class within a given Java project. It retrieves the JavaDoc
     * by looking up the type corresponding to the fully qualified class name and extracting its documentation, as well as
     * the documentation of its children elements.
     *
     * @param fullyQualifiedClassName The fully qualified name of the class for which to retrieve JavaDoc.
     * @param javaProject The Java project within which to search for the class.
     * @return A string containing the JavaDoc for the class and its children, or an empty string if not found.
     */
    private String getAttachedJavadoc(String fullyQualifiedClassName, IJavaProject javaProject) {
        try {
            IType type = javaProject.findType(fullyQualifiedClassName);
            if (type == null) {
                return "";
            }

            StringBuilder sb = new StringBuilder();

            // Class header
            sb.append("# ").append(type.getElementName()).append("\n\n");
            String classDoc = getRawJavadoc((IMember) type);
            if (!classDoc.isEmpty()) {
                sb.append(classDoc).append("\n\n");
            }

            // Collect fields and methods
            StringBuilder fields = new StringBuilder();
            StringBuilder methods = new StringBuilder();

            for (IJavaElement child : type.getChildren()) {
                if (child instanceof IField field) {
                    fields.append("### ").append(formatField(field)).append("\n");
                    String doc = getRawJavadoc(field);
                    if (!doc.isEmpty()) {
                        fields.append(doc).append("\n");
                    }
                    fields.append("\n");
                } else if (child instanceof IMethod method) {
                    methods.append("### ").append(formatMethod(method)).append("\n");
                    String doc = getRawJavadoc(method);
                    if (!doc.isEmpty()) {
                        methods.append(doc).append("\n");
                    }
                    methods.append("\n");
                }
            }

            if (fields.length() > 0) {
                sb.append("## Fields\n\n").append(fields);
            }
            if (methods.length() > 0) {
                sb.append("## Methods\n\n").append(methods);
            }

            return sb.toString();
        } catch (JavaModelException e) {
            logger.error(e.getMessage(), e);
            return "";
        }
    }

    private String getRawJavadoc(IMember member) throws JavaModelException {
        // Try attached javadoc first (for JARs/JDK)
        String attachedJavaDoc = member.getAttachedJavadoc(null);
        if (attachedJavaDoc != null) {
            var converter = FlexmarkHtmlConverter.builder().build();
            return converter.convert(attachedJavaDoc).trim();
        }
        // Fall back to source javadoc
        ISourceRange range = member.getJavadocRange();
        if (range != null) {
            ICompilationUnit unit = member.getCompilationUnit();
            if (unit != null) {
                IBuffer buffer = unit.getBuffer();
                return buffer.getText(range.getOffset(), range.getLength()).trim();
            }
        }
        return "";
    }

    private String formatField(IField field) throws JavaModelException {
        String typeSig = field.getTypeSignature();
        String typeName = Signature.toString(typeSig);
        return typeName + " " + field.getElementName();
    }

    private String formatMethod(IMethod method) throws JavaModelException {
        String returnType = Signature.toString(method.getReturnType());
        StringBuilder sb = new StringBuilder();
        sb.append(returnType).append(" ").append(method.getElementName()).append("(");
        String[] paramTypes = method.getParameterTypes();
        String[] paramNames = method.getParameterNames();
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(Signature.toString(paramTypes[i]));
            if (i < paramNames.length) {
                sb.append(" ").append(paramNames[i]);
            }
        }
        sb.append(")");
        return sb.toString();
    }
    
    /**
     * Extracts the source code for a specified class from the given Java project's associated resources.
     * If the class is found, the source code is retrieved from the corresponding file. If not found,
     * or if any errors occur during retrieval, a message is returned indicating the source is unavailable.
     *
     * @param fullyQualifiedClassName The fully qualified name of the class whose source code is to be retrieved.
     * @param javaProject The Java project to which the class belongs.
     * @return The source code of the class, or a message indicating that the source is not available.
     */
    private String getAttachedSource(String fullyQualifiedClassName, IJavaProject javaProject) {
        try {
            // Find the type for the fully qualified class name
            IType type = javaProject.findType(fullyQualifiedClassName);
            if (type == null) 
            {
                return null;      
            }
            // Get the attached Javadoc
            IResource resource = type.getCorrespondingResource();
            if (resource == null) {
                resource = type.getResource();
            }
            if (resource == null) {
                resource = type.getUnderlyingResource();
            }
            // Check if the resource is a file
            if (resource instanceof IFile) 
            {
                IFile file = (IFile) resource;
                TextFileDocumentProvider provider = new TextFileDocumentProvider();
                provider.connect(file);
                Document document = (Document) provider.getDocument(file);
                String content = document.get();
                provider.disconnect(file);

                return content;
            }
        } 
        catch (Exception e) 
        {
            logger.error(e.getMessage(), e);
            return null;
        }
        return null;
    }
    
}
