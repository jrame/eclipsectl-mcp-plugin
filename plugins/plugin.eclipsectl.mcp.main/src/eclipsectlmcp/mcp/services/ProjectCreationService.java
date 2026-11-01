package eclipsectlmcp.mcp.services;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.*;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jdt.core.*;
import org.eclipse.jdt.junit.JUnitCore;
import org.eclipse.jdt.launching.*;

import eclipsectlmcp.mcp.ToolExecution;

/** Creates new Java projects through Eclipse/JDT, without touching existing directories. */
@Creatable
public class ProjectCreationService {
    record RuntimeConfiguration(IPath container, String compliance, String vmName) {}

    public String createJavaProject(String projectPath, String projectName, Integer javaVersion, String junitVersion) {
        boolean[] reserved = {false};
        IProject[] created = {null};
        Path[] location = {null};
        try {
            if (junitVersion != null && !"5".equals(junitVersion))
                throw new IllegalArgumentException("Only JUnit 5 is supported; omit junitVersion for no JUnit.");
            if (junitVersion != null && JavaCore.getClasspathContainerInitializer(
                    JUnitCore.JUNIT5_CONTAINER_PATH.segment(0)) == null)
                throw new IllegalArgumentException("The Eclipse JUnit classpath container is unavailable.");
            if (junitVersion != null && Platform.getBundle("org.eclipse.jdt.junit5.runtime") == null)
                throw new IllegalArgumentException("Eclipse JUnit 5 runtime is unavailable; install Eclipse JUnit 5 support.");
            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            Path target = validatePath(projectPath);
            location[0] = target;
            String name = projectName == null ? target.getFileName().toString() : projectName;
            IStatus validName = workspace.validateName(name, IResource.PROJECT);
            if (!validName.isOK()) throw new IllegalArgumentException("Invalid project name: " + validName.getMessage());
            IProject project = workspace.getRoot().getProject(name);
            Path defaultLocation = workspace.getRoot().getLocation().toFile().toPath().toRealPath().resolve(name);
            // Eclipse represents the default location as null for both validation and creation.
            URI projectLocation = target.equals(defaultLocation) ? null : target.toUri();
            RuntimeConfiguration runtime = runtime(javaVersion);
            // Serialize conflict checks and creation with other Eclipse workspace writers.
            workspace.run(monitor -> {
                try {
                    validateTarget(workspace, project, target, projectLocation);
                    Files.createDirectory(target); // Atomic reservation; never reuse even an empty directory.
                    reserved[0] = true;
                    created[0] = project;
                    IProjectDescription description = workspace.newProjectDescription(name);
                    description.setLocationURI(projectLocation);
                    project.create(description, monitor);
                    project.open(monitor);
                    configureJava(project, runtime, junitVersion != null, monitor);
                } catch (Exception e) {
                    throw new CoreException(new Status(IStatus.ERROR, "eclipsectlmcp.plugin.main", e.getMessage(), e));
                }
            }, workspace.getRoot(), IWorkspace.AVOID_UPDATE, new NullProgressMonitor());
            return "Created and opened Java project '" + name + "'.\nLocation: " + target
                    + "\nJava: " + runtime.compliance() + "\nJRE: " + runtime.vmName()
                    + "\nEncoding: UTF-8\nSource: src -> bin"
                    + (junitVersion == null ? "\nJUnit: none" : "\nJUnit: 5 (Eclipse container)\nTests: test -> bin-test");
        } catch (Exception e) {
            String partial = reserved[0]
                    ? "\nPartial creation at " + location[0] + "; files retained for inspection. Workspace project: "
                        + (created[0] != null && created[0].exists() ? created[0].getName() : "not registered") + "."
                    : "\nNo project or files were created by this call.";
            return ToolExecution.error("Error creating Java project: " + e.getMessage() + partial);
        }
    }

    private static Path validatePath(String text) throws Exception {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Project path must be absolute and nonblank.");
        String lower = text.replace('/', '\\').toLowerCase(Locale.ROOT);
        if (lower.startsWith("\\\\wsl$\\") || lower.startsWith("\\\\wsl.localhost\\"))
            throw new IllegalArgumentException("Linux filesystem projects are unsupported; use a Windows drive.");
        Path path = Path.of(text);
        if (!path.isAbsolute() || path.getFileName() == null)
            throw new IllegalArgumentException("Project path must be an absolute directory path.");
        path = path.normalize();
        if (path.getParent() == null) throw new IllegalArgumentException("A filesystem root cannot be a project.");
        if (!Files.isDirectory(path.getParent()))
            throw new IllegalArgumentException("Parent directory must already exist: " + path.getParent());
        // Canonicalize existing parents so aliases cannot bypass workspace/location conflicts.
        return path.getParent().toRealPath().resolve(path.getFileName());
    }

    private static void validateTarget(IWorkspace workspace, IProject project, Path target, URI projectLocation) throws Exception {
        for (IProject existing : workspace.getRoot().getProjects()) {
            if (existing.getName().equalsIgnoreCase(project.getName()))
                throw new IllegalArgumentException("Project name already exists in workspace: " + project.getName());
            if (existing.getLocationURI() != null && "file".equalsIgnoreCase(existing.getLocationURI().getScheme())) {
                Path path = Path.of(existing.getLocationURI());
                path = Files.exists(path) ? path.toRealPath() : path.toAbsolutePath().normalize();
                if (target.startsWith(path) || path.startsWith(target))
                    throw new IllegalArgumentException("Target overlaps workspace project: " + existing.getName());
            }
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("Target already exists (nothing overwritten): " + target);
        Path metadata = workspace.getRoot().getLocation().toFile().toPath().toRealPath().resolve(".metadata");
        if (target.startsWith(metadata)) throw new IllegalArgumentException("Cannot create projects in workspace metadata.");
        IStatus valid = workspace.validateProjectLocationURI(project, projectLocation);
        if (!valid.isOK()) throw new IllegalArgumentException("Unsupported project location: " + valid.getMessage());
    }

    private static RuntimeConfiguration runtime(Integer version) {
        IVMInstall vm;
        IPath container;
        String compliance;
        if (version == null) {
            vm = JavaRuntime.getDefaultVMInstall();
            if (!(vm instanceof IVMInstall2 vm2) || vm2.getJavaVersion() == null)
                throw new IllegalArgumentException("No usable default JRE configured in Eclipse Installed JREs.");
            String value = vm2.getJavaVersion();
            int major = Integer.parseInt(value.startsWith("1.") ? value.substring(2).split("[._+\\-]")[0]
                    : value.split("[._+\\-]")[0]);
            compliance = major == 8 ? JavaCore.VERSION_1_8 : Integer.toString(major);
            container = JavaRuntime.newJREContainerPath(vm);
        } else {
            if (version < 8) throw new IllegalArgumentException("Java version must be at least 8.");
            compliance = version == 8 ? JavaCore.VERSION_1_8 : version.toString();
            var environment = JavaRuntime.getExecutionEnvironmentsManager().getEnvironment("JavaSE-" + compliance);
            if (environment == null) throw new IllegalArgumentException("Unsupported execution environment: JavaSE-" + compliance);
            container = JavaRuntime.newJREContainerPath(environment);
            vm = JavaRuntime.getVMInstall(container);
            if (vm == null || !java.util.Arrays.asList(environment.getCompatibleVMs()).contains(vm))
                throw new IllegalArgumentException("No compatible JRE installed for " + environment.getId()
                    + ". Configure Eclipse Installed JREs; no JDK is downloaded.");
        }
        if (!JavaCore.getAllVersions().contains(compliance))
            throw new IllegalArgumentException("Java compiler does not support version " + compliance);
        IStatus valid = vm.getVMInstallType().validateInstallLocation(vm.getInstallLocation());
        if (!valid.isOK() || JavaRuntime.getLibraryLocations(vm).length == 0)
            throw new IllegalArgumentException("Configured JRE is unavailable: " + vm.getName() + ": " + valid.getMessage());
        return new RuntimeConfiguration(container, compliance, vm.getName());
    }

    void configureJava(IProject project, RuntimeConfiguration runtime, boolean junit, IProgressMonitor monitor) throws Exception {
        IProjectDescription description = project.getDescription();
        description.setNatureIds(new String[] {JavaCore.NATURE_ID});
        ICommand builder = description.newCommand();
        builder.setBuilderName(JavaCore.BUILDER_ID);
        description.setBuildSpec(new ICommand[] {builder});
        project.setDescription(description, monitor);
        project.setDefaultCharset("UTF-8", monitor);
        IJavaProject javaProject = JavaCore.create(project);
        var options = new HashMap<String, String>();
        JavaCore.setComplianceOptions(runtime.compliance(), options);
        options.put(JavaCore.COMPILER_RELEASE, JavaCore.ENABLED);
        javaProject.setOptions(options);
        for (String folder : junit ? new String[] {"src", "bin", "test", "bin-test"} : new String[] {"src", "bin"})
            project.getFolder(folder).create(false, true, monitor);
        var entries = new ArrayList<IClasspathEntry>();
        entries.add(JavaCore.newSourceEntry(project.getFolder("src").getFullPath()));
        entries.add(JavaCore.newContainerEntry(runtime.container()));
        if (junit) {
            var testAttribute = new IClasspathAttribute[] {JavaCore.newClasspathAttribute(IClasspathAttribute.TEST, "true")};
            entries.add(JavaCore.newSourceEntry(project.getFolder("test").getFullPath(), new IPath[0], new IPath[0],
                    project.getFolder("bin-test").getFullPath(), testAttribute));
            entries.add(JavaCore.newContainerEntry(JUnitCore.JUNIT5_CONTAINER_PATH, new IAccessRule[0], testAttribute, false));
        }
        javaProject.setRawClasspath(entries.toArray(IClasspathEntry[]::new), project.getFolder("bin").getFullPath(), monitor);
        verifyContainer(runtime.container(), javaProject);
        if (junit) verifyContainer(JUnitCore.JUNIT5_CONTAINER_PATH, javaProject);
        project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
    }

    private static void verifyContainer(IPath path, IJavaProject project) throws Exception {
        IClasspathContainer container = JavaCore.getClasspathContainer(path, project);
        if (container == null || container.getClasspathEntries().length == 0)
            throw new IllegalStateException("Classpath container could not be resolved: " + path);
        for (IClasspathEntry entry : container.getClasspathEntries()) {
            if (entry.getEntryKind() == IClasspathEntry.CPE_LIBRARY && !entry.getPath().toFile().exists())
                throw new IllegalStateException("Classpath library is unavailable: " + entry.getPath());
        }
    }
}
