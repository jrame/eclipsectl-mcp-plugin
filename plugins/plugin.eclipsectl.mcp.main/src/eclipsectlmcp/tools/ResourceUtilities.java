package eclipsectlmcp.tools;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import org.apache.tika.Tika;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.TypeNameMatch;
import org.eclipse.jdt.core.search.TypeNameMatchRequestor;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.PackageDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;

public class ResourceUtilities
{
    /**
     * Finds an IFile in the workspace given an optional project name and a file path.
     *
     * Supported input formats (returns null if not unique):
     * 1. Workspace-relative path: "src/main/java/com/example/User.java"
     * 2. Workspace absolute path: "/ProjectName/src/main/java/User.java"
     * 3. Absolute filesystem path: "/mnt/c/workspace/User.java"
     * 4. Fully qualified class name: "com.example.User"
     * 5. Filename: "User.java"
     * 6. Simple class name: "User"
     *
     * @param projectName Optional project name (can be null or empty)
     * @param filePath Path, filename, or class name to resolve
     * @return The IFile if found uniquely, null otherwise
     */
    public static IFile findFile(String projectName, String filePath) {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();

        // Normalize file path
        String normalizedPath = filePath;
        if (normalizedPath == null || normalizedPath.trim().isEmpty()) {
            return null;
        }
        normalizedPath = normalizedPath.trim();

        // Strategy 1: Use provided project name
        if (projectName != null && !projectName.trim().isEmpty()) {
            IProject project = root.getProject(projectName.trim());
            if (project.exists() && project.isOpen()) {
                // Strip leading project name from path if present
                String path = normalizedPath;
                if (path.startsWith("/" + projectName + "/")) {
                    path = path.substring(projectName.length() + 2);
                }
                while (path.startsWith("/")) path = path.substring(1);
                IFile file = project.getFile(path);
                if (file.exists()) return file;
            }
        }

        // Strategy 2: Extract project from workspace absolute path (/projectName/...)
        if (normalizedPath.startsWith("/")) {
            String[] parts = normalizedPath.substring(1).split("/", 2);
            if (parts.length > 1 && !parts[0].isEmpty()) {
                IProject project = root.getProject(parts[0]);
                if (project.exists() && project.isOpen()) {
                    IFile file = project.getFile(parts[1]);
                    if (file.exists()) return file;
                }
            }
        }

        // Strategy 3: Absolute filesystem path → map to workspace via location
        if (normalizedPath.startsWith("/") || (normalizedPath.length() > 2 && normalizedPath.charAt(1) == ':')) {
            org.eclipse.core.runtime.IPath location = org.eclipse.core.runtime.Path.fromOSString(normalizedPath);
            IFile[] files = root.findFilesForLocationURI(location.toFile().toURI());
            if (files.length == 1 && files[0].exists()) {
                return files[0];
            }
        }

        // Strategy 4: Search all open projects for relative path
        String stripped = normalizedPath;
        while (stripped.startsWith("/") || stripped.startsWith("\\")) {
            stripped = stripped.substring(1);
        }
        if (!stripped.isEmpty() && stripped.contains("/")) {
            // 4a: Try path as-is in each project
            for (IProject project : root.getProjects()) {
                if (!project.isOpen()) continue;
                IFile file = project.getFile(stripped);
                if (file.exists()) return file;
            }
            // 4b: First segment may be a project name (e.g. "HelloWorld/src/Main.java")
            String[] parts = stripped.split("/", 2);
            if (parts.length == 2 && !parts[0].isEmpty()) {
                IProject project = root.getProject(parts[0]);
                if (project.exists() && project.isOpen()) {
                    IFile file = project.getFile(parts[1]);
                    if (file.exists()) return file;
                }
            }
        }

        // Strategy 5: Fully qualified class name (e.g. "com.example.User") → JDT type search
        if (looksLikeQualifiedClassName(normalizedPath)) {
            IFile found = findFileByTypeName(normalizedPath);
            if (found != null) return found;
        }

        // Strategy 6: Simple filename (e.g. "User.java") → search all projects by name
        if (!normalizedPath.contains("/") && !normalizedPath.contains("\\")) {
            String fileName = normalizedPath;
            // Strategy 7: Simple name without extension (e.g. "User") → try as .java, then JDT
            if (!fileName.contains(".")) {
                // Try JDT type search first for simple name
                IFile found = findFileByTypeName(fileName);
                if (found != null) return found;
                // Fallback: try as .java filename
                fileName = fileName + ".java";
            }
            IFile found = findFileByName(fileName);
            if (found != null) return found;
        }

        return null;
    }

    /**
     * Checks if the input looks like a fully qualified Java class name (e.g. "com.example.User").
     */
    private static boolean looksLikeQualifiedClassName(String input) {
        if (input == null || input.isEmpty()) return false;
        // Must contain at least one dot, no slashes, no file extension with common suffixes
        if (!input.contains(".")) return false;
        if (input.contains("/") || input.contains("\\")) return false;
        // If it ends with a known file extension, it's a filename not a class name
        if (input.endsWith(".java") || input.endsWith(".class") || input.endsWith(".xml")
                || input.endsWith(".properties") || input.endsWith(".json") || input.endsWith(".txt")) {
            return false;
        }
        // All segments must be valid Java identifiers
        String[] parts = input.split("\\.");
        for (String part : parts) {
            if (part.isEmpty() || !Character.isJavaIdentifierStart(part.charAt(0))) return false;
            for (int i = 1; i < part.length(); i++) {
                if (!Character.isJavaIdentifierPart(part.charAt(i))) return false;
            }
        }
        return true;
    }

    /**
     * Finds a unique IFile by Java type name using JDT SearchEngine.
     * Works with both simple names ("User") and qualified names ("com.example.User").
     *
     * @return The IFile if exactly one source type matches, null otherwise
     */
    private static IFile findFileByTypeName(String typeName) {
        try {
            List<IFile> results = new ArrayList<>();
            IJavaSearchScope scope = SearchEngine.createWorkspaceScope();
            SearchEngine engine = new SearchEngine();

            // Split qualified name into package and simple name
            String packageName = null;
            String simpleName = typeName;
            int lastDot = typeName.lastIndexOf('.');
            if (lastDot > 0) {
                packageName = typeName.substring(0, lastDot);
                simpleName = typeName.substring(lastDot + 1);
            }

            engine.searchAllTypeNames(
                packageName != null ? packageName.toCharArray() : null,
                packageName != null ? SearchPattern.R_EXACT_MATCH : SearchPattern.R_PATTERN_MATCH,
                simpleName.toCharArray(),
                SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE,
                IJavaSearchConstants.TYPE,
                scope,
                new TypeNameMatchRequestor() {
                    @Override
                    public void acceptTypeNameMatch(TypeNameMatch match) {
                        try {
                            IType type = match.getType();
                            IResource resource = type.getResource();
                            if (resource instanceof IFile) {
                                results.add((IFile) resource);
                            }
                        } catch (Exception e) {
                            // skip
                        }
                    }
                },
                IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH,
                new NullProgressMonitor()
            );

            // Return only if unique
            if (results.size() == 1) {
                return results.get(0);
            }
        } catch (Exception e) {
            // JDT not available or search failed — fall through
        }
        return null;
    }

    /**
     * Finds a unique IFile by filename across all open projects.
     *
     * @return The IFile if exactly one file with that name exists, null otherwise
     */
    private static IFile findFileByName(String fileName) {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        List<IFile> results = new ArrayList<>();
        for (IProject project : root.getProjects()) {
            if (!project.isOpen()) continue;
            try {
                project.accept(new IResourceVisitor() {
                    @Override
                    public boolean visit(IResource resource) {
                        if (results.size() > 1) return false; // early exit
                        if (resource instanceof IFile && resource.getName().equals(fileName)) {
                            results.add((IFile) resource);
                        }
                        return true;
                    }
                });
            } catch (CoreException e) {
                // skip project
            }
            if (results.size() > 1) break;
        }
        return results.size() == 1 ? results.get(0) : null;
    }

    public static List<String> readFileLines(IFile file) throws IOException, CoreException
    {
        Objects.requireNonNull(file);
        List<String> lines = new ArrayList<>();
        try (InputStream is = file.getContents();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(is, Charset.forName(file.getCharset()))))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                lines.add(line);
            }
        }
        return lines;
    }

    /**
     * Recursively creates a folder hierarchy.
     * 
     * @param folder The folder to create
     * @throws CoreException If there is an error creating the folder
     */
    public static void createFolderHierarchy(IFolder folder) throws CoreException
    {
        Objects.requireNonNull(folder);
        if (!folder.exists())
        {
            IContainer parent = folder.getParent();
            if (parent instanceof IFolder && !parent.exists())
            {
                createFolderHierarchy((IFolder) parent);
            }
            folder.create(true, true, null);
        }
    }

    public static byte[] readInputStream(InputStream inputStream) throws IOException
    {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int length;
        while ((length = inputStream.read(buffer)) != -1)
        {
            result.write(buffer, 0, length);
        }
        return result.toByteArray();
    }

    public static String readFileContent(IFile file) throws IOException, CoreException
    {
        Objects.requireNonNull(file);
        // Read file content
        try (InputStream is = file.getContents())
        {
            ByteArrayOutputStream content = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int length;
            while ((length = is.read(buffer)) != -1)
            {
                content.write(buffer, 0, length);
            }
            return content.toString(file.getCharset());
        }
    }

    public static String getSuggestedFileName(String lang, String codeBlock)
    {
        // Fall back to the original implementation if parsing fails or for other languages
        return switch (lang)
        {
            case "java" -> suggestedJavaFile(codeBlock);
            case "python" -> "new_script.py";
            case "javascript" -> "script.js";
            case "typescript" -> "script.ts";
            case "html" -> "index.html";
            case "xml" -> "config.xml";
            case "json" -> "data.json";
            case "markdown" -> "README.md";
            case "cpp" -> "main.cpp";
            case "bash" -> "script.sh";
            case "yaml" -> "config.yaml";
            case "properties" -> "config.properties";
            default -> "NewFile." + getFileExtensionForLang(lang);
        };
    }

    public static IPath suggestedJavaPackage(String codeBlock)
    {
        String pathString = "";
        // Extract package name from Java code
        if (codeBlock != null && !codeBlock.isBlank())
        {
            try
            {
                // Use Eclipse JDT to parse the Java code
                ASTParser parser = ASTParser.newParser(AST.JLS23);
                parser.setSource(codeBlock.toCharArray());
                parser.setKind(ASTParser.K_COMPILATION_UNIT);

                CompilationUnit cu = (CompilationUnit) parser.createAST(null);

                PackageDeclaration packageDecl = cu.getPackage();
                if (packageDecl != null)
                {
                    pathString = packageDecl.getName().getFullyQualifiedName().replace(".", "/");
                }
            }
            catch (Exception e)
            {
                // ignore
            }
        }
        return IPath.fromPath(Paths.get(pathString));
    }

    public static String suggestedJavaFile(String codeBlock)
    {
        // Extract class name from Java code
        if (codeBlock != null && !codeBlock.isBlank())
        {
            try
            {
                // Use Eclipse JDT to parse the Java code
                ASTParser parser = ASTParser.newParser(AST.JLS23);
                parser.setSource(codeBlock.toCharArray());
                parser.setKind(ASTParser.K_COMPILATION_UNIT);

                CompilationUnit cu = (CompilationUnit) parser.createAST(null);

                // Find the first type declaration (class, interface, enum)
                for (Object type : cu.types())
                {
                    if (type instanceof TypeDeclaration)
                    {
                        TypeDeclaration typeDecl = (TypeDeclaration) type;
                        return typeDecl.getName().getIdentifier() + ".java";
                    }
                    else if (type instanceof EnumDeclaration)
                    {
                        EnumDeclaration enumDecl = (EnumDeclaration) type;
                        return enumDecl.getName().getIdentifier() + ".java";
                    }
                }
            }
            catch (Exception e)
            {
                // ignore
            }
        }
        return "NewClass.java";

    }

    public static IPath getSuggestedPath(IProject project, String lang, String codeBlock)
    {
        return switch (lang)
        {
            case "java" -> suggestedJavaPath(project).append(suggestedJavaPackage(codeBlock));
            case "python" -> suggestedPythonPath(project);
            case "javascript", "typescript", "html", "css" -> suggestedWebPath(project);
            default -> project.getFullPath();
        };
    }

    public static IPath suggestedWebPath(IProject project)
    {
        // Check for web folders
        if (project.getFolder("webapp").exists())
        {
            return project.getFolder("webapp").getFullPath();
        }
        else if (project.getFolder("WebContent").exists())
        {
            return project.getFolder("WebContent").getFullPath();
        }
        else if (project.getFolder("public").exists())
        {
            return project.getFolder("public").getFullPath();
        }
        else if (project.getFolder("web").exists())
        {
            return project.getFolder("web").getFullPath();
        }
        else
        {
            return project.getFullPath();
        }
    }

    public static IPath suggestedPythonPath(IProject project)
    {
        if (project.getFolder("src").exists())
        {
            return project.getFolder("src").getFullPath();
        }
        else
        {
            return project.getFullPath();
        }
    }

    public static IPath suggestedJavaPath(IProject project)
    {
        // Try to find src/main/java or src folder
        IPath srcMainJava = project.getFolder("src/main/java").getFullPath();
        if (project.getFolder("src/main/java").exists())
        {
            return srcMainJava;
        }
        else if (project.getFolder("src").exists())
        {
            return project.getFolder("src").getFullPath();
        }
        else
        {
            return project.getFullPath();
        }
    }

    public static String getFileExtensionForLang(String lang)
    {
        return switch (lang)
        {
            case "java" -> "java";
            case "python" -> "py";
            case "javascript" -> "js";
            case "typescript" -> "ts";
            case "html" -> "html";
            case "xml" -> "xml";
            case "json" -> "json";
            case "markdown" -> "md";
            case "cpp" -> "cpp";
            case "bash" -> "sh";
            case "yaml" -> "yaml";
            case "properties" -> "properties";
            case "text" -> "txt";
            default -> "txt";
        };
    }

    public static String getResourceFileType(IFile file) throws IOException
    {
        Objects.requireNonNull(file);

        int MAX_FILE_SIZE_KB = 1024;
        // Check file size to avoid loading extremely large files
        long fileSizeInKB = file.getLocation().toFile().length() / 1024;
        if (fileSizeInKB > MAX_FILE_SIZE_KB)
        {
            // Limit to 1MB
            throw new IOException("Error: File '" + file.getFullPath().toFile() + "' is too large (" + fileSizeInKB
                    + " KB). Maximum size is " + MAX_FILE_SIZE_KB + " KB.");
        }

        // Use Apache Tika to detect content type
        Tika tika = new Tika();
        try
        {
            String mimeType = tika.detect(file.getLocation().toFile());

            // Check if this is a text file
            if (!isTextMimeType(mimeType))
            {

                throw new IOException(
                        "Cannot read binary file '" + file.getFullPath().toFile() + "' with MIME type '" + mimeType
                                + "'. Only text files are supported.");
            }
            String lang = getLanguageForMimeType(mimeType);
            if (lang.isBlank())
            {
                lang = getLanguageForFile(file);
            }
            return lang;
        }
        catch (Exception e)
        {
            throw new IOException("Error: Cannot detect the file content type. Reason: " + e.getMessage());
        }
    }

    private static boolean isTextMimeType(String mimeType)
    {
        return mimeType.startsWith("text/") || mimeType.equals("application/json") || mimeType.equals("application/xml")
                || mimeType.equals("application/javascript") || mimeType.contains("+xml") || mimeType.contains("+json");
    }

    /**
     * Determines the language for syntax highlighting based on file extension.
     * 
     * @param fileExtension The file extension
     * @return The language identifier for syntax highlighting
     */
    public static String getLanguageForFile(IFile file)
    {
        return Optional.ofNullable(file)
                .map(IFile::getFileExtension)
                .map(ResourceUtilities::getLanguageForExtension)
                .orElse("");
    }

    public static String getLanguageForMimeType(String mimeType)
    {
        return switch (mimeType)
        {
            case String s when s.contains("java") -> "java";
            case String s when s.contains("python") -> "python";
            case String s when s.contains("javascript") -> "javascript";
            case String s when s.contains("html") -> "html";
            case String s when s.contains("xml") -> "xml";
            case String s when s.contains("json") -> "json";
            case String s when s.contains("markdown") -> "markdown";
            case String s when s.contains("x-c") -> "cpp";
            case String s when s.contains("x-sh") -> "bash";
            default -> "";
        };
    }

    /**
     * Determines the language for syntax highlighting based on file extension.
     * 
     * @param fileExtension The file extension
     * @return The language identifier for syntax highlighting
     */
    public static String getLanguageForExtension(String fileExtension)
    {

        return switch (Optional.ofNullable(fileExtension).map(String::toLowerCase).orElse(""))
        {
            case "java" -> "java";
            case "py" -> "python";
            case "js" -> "javascript";
            case "ts" -> "typescript";
            case "html" -> "html";
            case "xml" -> "xml";
            case "json" -> "json";
            case "md" -> "markdown";
            case "c" -> "cpp";
            case "cpp" -> "cpp";
            case "h" -> "cpp";
            case "hpp" -> "cpp";
            case "sh" -> "bash";
            case "properties" -> "properties";
            case "yaml" -> "yaml";
            case "yml" -> "yaml";
            case "txt" -> "text";
            default -> "";

        };
    }

    /**
     * Normalizes glob patterns. If no patterns are provided, returns an array containing "*".
     */
    public static String[] normalizeGlobPatterns(String... fileNamePatterns)
    {
        if (fileNamePatterns == null || fileNamePatterns.length == 0)
        {
            return new String[] { "*" };
        }

        List<String> patterns = new ArrayList<>();
        for (String p : fileNamePatterns)
        {
            if (p != null && !p.isBlank())
            {
                patterns.add(p.trim());
            }
        }
        return patterns.isEmpty() ? new String[] { "*" } : patterns.toArray(String[]::new);
    }

    /**
     * Converts one or more glob patterns (supports '*' and '?') into a single regex {@link Pattern}.
     * The resulting pattern is anchored to match the full file name.
     */
    public static Pattern globPatternsToRegex(String... globs)
    {
        String[] normalized = normalizeGlobPatterns(globs);

        StringBuilder regex = new StringBuilder();
        regex.append("^(?:");
        for (int i = 0; i < normalized.length; i++)
        {
            if (i > 0)
            {
                regex.append("|");
            }
            regex.append(globToRegex(normalized[i]));
        }
        regex.append(")$");

        return Pattern.compile(regex.toString());
    }

    /**
     * Very small glob implementation: supports '*' and '?' only.
     */
    public static String globToRegex(String glob)
    {
        String g = glob == null ? "*" : glob.trim();
        if (g.isEmpty())
        {
            g = "*";
        }

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < g.length(); i++)
        {
            char c = g.charAt(i);
            switch (c)
            {
                case '*':
                    out.append(".*");
                    break;
                case '?':
                    out.append('.');
                    break;
                // Escape regex metacharacters
                case '\\':
                case '.':
                case '^':
                case '$':
                case '+':
                case '{':
                case '}':
                case '[':
                case ']':
                case '(':
                case ')':
                case '|':
                    out.append('\\').append(c);
                    break;
                default:
                    out.append(c);
            }
        }
        return out.toString();
    }
}
