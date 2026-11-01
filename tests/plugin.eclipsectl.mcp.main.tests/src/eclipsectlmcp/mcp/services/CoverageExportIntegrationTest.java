package eclipsectlmcp.mcp.services;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.jacoco.core.tools.ExecFileLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import eclipsectlmcp.mcp.ToolExecution;
import eclipsectlmcp.tools.UISynchronizeCallable;

public class CoverageExportIntegrationTest {
	@Test @Timeout(180)
	public void completedJavaLaunchExportsItsActualProbes() throws Exception {
		String name = "CoverageExport_" + UUID.randomUUID().toString().substring(0, 8);
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
		ILaunchConfiguration configuration = null;
		Path exported = null;
		try {
			project.create(null);
			project.open(null);
			IProjectDescription description = project.getDescription();
			description.setNatureIds(new String[] { JavaCore.NATURE_ID });
			project.setDescription(description, null);
			var javaProject = JavaCore.create(project);
			var src = project.getFolder("src");
			src.create(true, true, null);
			var bin = project.getFolder("bin");
			bin.create(true, true, null);
			javaProject.setOutputLocation(bin.getFullPath(), null);
			javaProject.setRawClasspath(new IClasspathEntry[] {
					JavaCore.newSourceEntry(src.getFullPath()),
					JavaCore.newContainerEntry(JavaRuntime.newDefaultJREContainerPath()) }, null);
			IFile file = src.getFile("Main.java");
			file.create(new ByteArrayInputStream(("public class Main { public static void main(String[] args) { "
					+ "System.out.println(42); } }").getBytes(StandardCharsets.UTF_8)), true, null);
			project.build(IncrementalProjectBuilder.FULL_BUILD, null);
			assertTrue(bin.getFile("Main.class").exists(), "Disposable program must compile");
			var manager = DebugPlugin.getDefault().getLaunchManager();
			var working = manager.getLaunchConfigurationType(IJavaLaunchConfigurationConstants.ID_JAVA_APPLICATION)
					.newInstance(null, name);
			working.setAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, name);
			working.setAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, "Main");
			configuration = working.doSave();

			CoverageService service = new CoverageService();
			service.uiSync = new UISynchronizeCallable() {
				@Override public <T> T syncCall(Callable<T> callable) {
					AtomicReference<T> value = new AtomicReference<>();
					AtomicReference<Exception> failure = new AtomicReference<>();
					PlatformUI.getWorkbench().getDisplay().syncExec(() -> {
						try { value.set(callable.call()); }
						catch (Exception e) { failure.set(e); }
					});
					if (failure.get() != null) throw new RuntimeException(failure.get());
					return value.get();
				}
			};
			service.launchLogService = new LaunchLogService();
			setLogger(service);
			setLogger(service.launchLogService);
			assertNull(Display.getCurrent());
			ToolExecution.Result result = ToolExecution.capture(() -> service.runCoverage(name, true));
			String output = (String) result.value();
			assertFalse(result.isError(), output);
			assertTrue(output.contains("completed (coverage mode)"), output);
			assertTrue(output.contains("Exit code: 0"), output);
			exported = Path.of(output.lines().filter(line -> line.startsWith("Coverage session: "))
					.findFirst().orElseThrow(() -> new AssertionError(output)).substring("Coverage session: ".length()));
			assertTrue(Files.isRegularFile(exported), output);
			assertEquals(ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toPath()
					.resolve(".logs/eclipsectl").toAbsolutePath(), exported.getParent());
			assertTrue(exported.getFileName().toString().endsWith("_" + name + ".exec"));
			ExecFileLoader reader = new ExecFileLoader();
			reader.load(exported.toFile());
			assertFalse(reader.getSessionInfoStore().getInfos().isEmpty());
			var main = reader.getExecutionDataStore().getContents().stream()
					.filter(data -> data.getName().equals("Main")).findFirst().orElseThrow();
			boolean covered = false;
			for (boolean probe : main.getProbes()) covered |= probe;
			assertTrue(covered, "Export must contain actual executed Main probes");
		} finally {
			if (exported != null) Files.deleteIfExists(exported);
			if (configuration != null) configuration.delete();
			if (project.exists()) project.delete(true, true, null);
		}
	}

	private static void setLogger(Object service) throws Exception {
		Field logger = service.getClass().getDeclaredField("logger");
		logger.setAccessible(true);
		logger.set(service, Platform.getLog(CoverageExportIntegrationTest.class));
	}
}
