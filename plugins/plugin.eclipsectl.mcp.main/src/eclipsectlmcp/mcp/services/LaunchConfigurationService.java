package eclipsectlmcp.mcp.services;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;

import eclipsectlmcp.tools.UISynchronizeCallable;
import jakarta.inject.Inject;

/**
 * Creates and updates Eclipse launch configurations.
 */
@Creatable
public class LaunchConfigurationService {

	private static final String CONFLICT_ERROR = "error";
	private static final String CONFLICT_GENERATE = "generate";
	private static final String UPDATE_MERGE = "merge";
	private static final String UPDATE_REPLACE = "replace";

	@Inject
	private ILog logger;

	@Inject
	private UISynchronizeCallable uiSync;

	/**
	 * Creates a local Java Application launch configuration.
	 */
	public String createJavaLaunchConfiguration(String mainClass, String configName, String projectName,
			String programArguments, String vmArguments, String workingDirectory,
			Map<String, String> environmentVariables, Boolean appendSystemEnvironment,
			String nameConflict) {
		try {
			return uiSync.syncCall(() -> createJavaLaunchConfigurationInternal(mainClass, configName, projectName,
					programArguments, vmArguments, workingDirectory, environmentVariables,
					appendSystemEnvironment, nameConflict));
		} catch (Exception e) {
			logger.log(Status.error("Error creating Java launch configuration", e));
			return "Error: " + errorMessage(e);
		}
	}

	/**
	 * Returns the useful attributes of a launch configuration.
	 */
	public String getLaunchConfiguration(String configName, Boolean includeEnvironmentValues) {
		try {
			return uiSync.syncCall(() -> {
				ILaunchConfiguration config = findConfiguration(configName);
				if (config == null) {
					return "Error: Launch configuration '" + safe(configName) + "' not found. "
							+ "Use listRunConfigurations to see available configurations.";
				}
				return formatConfiguration(config, Boolean.TRUE.equals(includeEnvironmentValues));
			});
		} catch (Exception e) {
			logger.log(Status.error("Error reading launch configuration", e));
			return "Error: " + errorMessage(e);
		}
	}

	/**
	 * Updates only the environment-related attributes of a launch configuration.
	 */
	public String updateLaunchEnvironment(String configName, Map<String, String> variables,
			List<String> removeVariables, String updateMode, Boolean appendSystemEnvironment) {
		try {
			return uiSync.syncCall(() -> updateLaunchEnvironmentInternal(configName, variables,
					removeVariables, updateMode, appendSystemEnvironment));
		} catch (Exception e) {
			logger.log(Status.error("Error updating launch environment", e));
			return "Error: " + errorMessage(e);
		}
	}

	private String createJavaLaunchConfigurationInternal(String mainClass, String configName, String projectName,
			String programArguments, String vmArguments, String workingDirectory,
			Map<String, String> environmentVariables, Boolean appendSystemEnvironment,
			String nameConflict) throws CoreException {
		String requestedMainClass = requireText(mainClass, "mainClass");
		MainTypeMatch mainType = resolveMainType(requestedMainClass, projectName);
		validateEnvironmentVariables(environmentVariables);

		ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
		String conflictPolicy = normalizeChoice(nameConflict, CONFLICT_ERROR, CONFLICT_ERROR, CONFLICT_GENERATE);
		boolean derivedName = isBlank(configName);
		String requestedName = derivedName ? mainType.type().getElementName() : configName.trim();
		String effectiveName;

		if (derivedName || CONFLICT_GENERATE.equals(conflictPolicy)) {
			effectiveName = launchManager.generateLaunchConfigurationName(requestedName);
		} else {
			launchManager.isValidLaunchConfigurationName(requestedName);
			if (launchManager.isExistingLaunchConfigurationName(requestedName)) {
				return "Error: Launch configuration '" + requestedName + "' already exists. "
						+ "Use nameConflict='generate' to create a uniquely named configuration.";
			}
			effectiveName = requestedName;
		}

		ILaunchConfigurationType type = launchManager
				.getLaunchConfigurationType(IJavaLaunchConfigurationConstants.ID_JAVA_APPLICATION);
		if (type == null) {
			return "Error: Java Application launch configuration type is not available.";
		}

		ILaunchConfigurationWorkingCopy workingCopy = type.newInstance(null, effectiveName);
		workingCopy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME,
				mainType.project().getElementName());
		workingCopy.setAttribute(IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME,
				mainType.type().getFullyQualifiedName('.'));
		setOptionalAttribute(workingCopy, IJavaLaunchConfigurationConstants.ATTR_PROGRAM_ARGUMENTS,
				programArguments);
		setOptionalAttribute(workingCopy, IJavaLaunchConfigurationConstants.ATTR_VM_ARGUMENTS, vmArguments);
		setOptionalAttribute(workingCopy, IJavaLaunchConfigurationConstants.ATTR_WORKING_DIRECTORY,
				workingDirectory);
		if (environmentVariables != null) {
			workingCopy.setAttribute(ILaunchManager.ATTR_ENVIRONMENT_VARIABLES,
					new LinkedHashMap<>(environmentVariables));
		}
		workingCopy.setAttribute(ILaunchManager.ATTR_APPEND_ENVIRONMENT_VARIABLES,
				appendSystemEnvironment == null || appendSystemEnvironment);
		workingCopy.setMappedResources(new IResource[] { mainType.project().getProject() });

		ILaunchConfiguration saved = workingCopy.doSave();
		StringBuilder result = new StringBuilder();
		result.append("Created Java launch configuration '").append(saved.getName()).append("'.\n\n")
				.append("- Project: ").append(mainType.project().getElementName()).append("\n")
				.append("- Main class: ").append(mainType.type().getFullyQualifiedName('.')).append("\n")
				.append("- Environment variables: ")
				.append(environmentVariables == null ? 0 : environmentVariables.size()).append("\n")
				.append("- Append system environment: ")
				.append(appendSystemEnvironment == null || appendSystemEnvironment);
		if (!effectiveName.equals(requestedName)) {
			result.append("\n- Requested name: ").append(requestedName);
		}
		return result.toString();
	}

	private String updateLaunchEnvironmentInternal(String configName, Map<String, String> variables,
			List<String> removeVariables, String updateMode, Boolean appendSystemEnvironment) throws CoreException {
		ILaunchConfiguration config = findConfiguration(configName);
		if (config == null) {
			return "Error: Launch configuration '" + safe(configName) + "' not found. "
					+ "Use listRunConfigurations to see available configurations.";
		}

		String mode = normalizeChoice(updateMode, UPDATE_MERGE, UPDATE_MERGE, UPDATE_REPLACE);
		validateEnvironmentVariables(variables);
		List<String> removals = normalizeRemovals(removeVariables);
		if (UPDATE_REPLACE.equals(mode) && !removals.isEmpty()) {
			return "Error: removeVariables cannot be combined with updateMode='replace'. "
					+ "The replacement map already defines the complete environment.";
		}
		if (variables == null && removals.isEmpty() && appendSystemEnvironment == null) {
			return "Error: No environment changes specified.";
		}
		if (UPDATE_REPLACE.equals(mode) && variables == null) {
			return "Error: variables is required when updateMode='replace'. Use an empty object to clear all variables.";
		}

		Map<String, String> previous = config.getAttribute(ILaunchManager.ATTR_ENVIRONMENT_VARIABLES,
				Collections.emptyMap());
		Map<String, String> updated = UPDATE_REPLACE.equals(mode)
				? new LinkedHashMap<>(variables)
				: new LinkedHashMap<>(previous);
		int changed = 0;
		if (UPDATE_MERGE.equals(mode) && variables != null) {
			for (Map.Entry<String, String> entry : variables.entrySet()) {
				String oldValue = updated.put(entry.getKey(), entry.getValue());
				if (!entry.getValue().equals(oldValue)) {
					changed++;
				}
			}
		} else if (UPDATE_REPLACE.equals(mode)) {
			changed = updated.size();
		}
		int removed = 0;
		for (String name : removals) {
			if (updated.remove(name) != null) {
				removed++;
			}
		}

		ILaunchConfigurationWorkingCopy workingCopy = config.getWorkingCopy();
		workingCopy.setAttribute(ILaunchManager.ATTR_ENVIRONMENT_VARIABLES, updated);
		if (appendSystemEnvironment != null) {
			workingCopy.setAttribute(ILaunchManager.ATTR_APPEND_ENVIRONMENT_VARIABLES,
					appendSystemEnvironment.booleanValue());
		}
		ILaunchConfiguration saved = workingCopy.doSave();
		boolean append = saved.getAttribute(ILaunchManager.ATTR_APPEND_ENVIRONMENT_VARIABLES, true);

		return "Updated environment for launch configuration '" + saved.getName() + "'.\n\n"
				+ "- Update mode: " + mode + "\n"
				+ "- Variables changed: " + changed + "\n"
				+ "- Variables removed: " + removed + "\n"
				+ "- Configured variables: " + updated.size() + "\n"
				+ "- Append system environment: " + append;
	}

	private MainTypeMatch resolveMainType(String mainClass, String projectName) throws CoreException {
		String normalizedClass = mainClass.endsWith(".java")
				? mainClass.substring(0, mainClass.length() - ".java".length())
				: mainClass;
		List<IJavaProject> projects = candidateProjects(projectName);
		Map<String, MainTypeMatch> matches = new LinkedHashMap<>();
		for (IJavaProject javaProject : projects) {
			IType directMatch = javaProject.findType(normalizedClass);
			if (directMatch != null && hasMainMethod(directMatch)) {
				matches.put(directMatch.getHandleIdentifier(), new MainTypeMatch(javaProject, directMatch));
			}
			for (IPackageFragment fragment : javaProject.getPackageFragments()) {
				if (fragment.getKind() != IPackageFragmentRoot.K_SOURCE) {
					continue;
				}
				for (ICompilationUnit unit : fragment.getCompilationUnits()) {
					for (IType type : unit.getAllTypes()) {
						if (matchesTypeName(type, normalizedClass) && hasMainMethod(type)) {
							matches.put(type.getHandleIdentifier(), new MainTypeMatch(javaProject, type));
						}
					}
				}
			}
		}

		if (matches.isEmpty()) {
			String scope = isBlank(projectName) ? "the workspace" : "project '" + projectName.trim() + "'";
			throw new IllegalArgumentException("No runnable main class '" + mainClass + "' found in " + scope + ".");
		}
		if (matches.size() > 1) {
			List<MainTypeMatch> sortedMatches = new ArrayList<>(matches.values());
			sortedMatches.sort(Comparator.comparing((MainTypeMatch match) -> match.type().getFullyQualifiedName('.'))
					.thenComparing(match -> match.project().getElementName()));
			StringBuilder message = new StringBuilder("Main class '").append(mainClass)
					.append("' is ambiguous. Matching runnable classes:\n");
			for (MainTypeMatch match : sortedMatches) {
				message.append("- ").append(match.type().getFullyQualifiedName('.'))
						.append(" (project: ").append(match.project().getElementName()).append(")\n");
			}
			message.append("Specify projectName or a fully qualified mainClass.");
			throw new IllegalArgumentException(message.toString());
		}
		return matches.values().iterator().next();
	}

	private List<IJavaProject> candidateProjects(String projectName) throws CoreException {
		if (!isBlank(projectName)) {
			IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName.trim());
			if (!project.isAccessible()) {
				throw new IllegalArgumentException("Project '" + projectName.trim() + "' does not exist or is closed.");
			}
			if (!project.hasNature(JavaCore.NATURE_ID)) {
				throw new IllegalArgumentException("Project '" + projectName.trim() + "' is not a Java project.");
			}
			return List.of(JavaCore.create(project));
		}

		List<IJavaProject> projects = new ArrayList<>();
		for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
			if (project.isAccessible() && project.hasNature(JavaCore.NATURE_ID)) {
				projects.add(JavaCore.create(project));
			}
		}
		return projects;
	}

	private boolean matchesTypeName(IType type, String requestedName) {
		return requestedName.equals(type.getElementName())
				|| requestedName.equals(type.getFullyQualifiedName('.'));
	}

	private boolean hasMainMethod(IType type) throws CoreException {
		for (IMethod method : type.getMethods()) {
			if (method.isMainMethod()) {
				return true;
			}
		}
		return false;
	}

	private ILaunchConfiguration findConfiguration(String configName) throws CoreException {
		if (isBlank(configName)) {
			return null;
		}
		ILaunchConfiguration caseInsensitiveMatch = null;
		for (ILaunchConfiguration config : DebugPlugin.getDefault().getLaunchManager().getLaunchConfigurations()) {
			if (config.getName().equals(configName.trim())) {
				return config;
			}
			if (config.getName().equalsIgnoreCase(configName.trim())) {
				caseInsensitiveMatch = config;
			}
		}
		return caseInsensitiveMatch;
	}

	private String formatConfiguration(ILaunchConfiguration config, boolean includeEnvironmentValues)
			throws CoreException {
		Map<String, Object> attributes = config.getAttributes();
		StringBuilder result = new StringBuilder();
		result.append("Launch configuration '").append(config.getName()).append("':\n\n")
				.append("- Type: ").append(config.getType().getName()).append("\n")
				.append("- Modes: ").append(formatModes(config)).append("\n")
				.append("- Project: ").append(attributeOrDefault(attributes,
						IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, "(not set)")).append("\n")
				.append("- Main class: ").append(attributeOrDefault(attributes,
						IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME, "(not set)")).append("\n")
				.append("- Program arguments: ").append(attributeOrDefault(attributes,
						IJavaLaunchConfigurationConstants.ATTR_PROGRAM_ARGUMENTS, "(none)")).append("\n")
				.append("- VM arguments: ").append(attributeOrDefault(attributes,
						IJavaLaunchConfigurationConstants.ATTR_VM_ARGUMENTS, "(none)")).append("\n")
				.append("- Working directory: ").append(attributeOrDefault(attributes,
						IJavaLaunchConfigurationConstants.ATTR_WORKING_DIRECTORY, "(project default)")).append("\n")
				.append("- Append system environment: ")
				.append(config.getAttribute(ILaunchManager.ATTR_APPEND_ENVIRONMENT_VARIABLES, true)).append("\n");

		Map<String, String> environment = config.getAttribute(ILaunchManager.ATTR_ENVIRONMENT_VARIABLES,
				Collections.emptyMap());
		result.append("- Environment variables: ").append(environment.size());
		if (!environment.isEmpty()) {
			List<Map.Entry<String, String>> entries = new ArrayList<>(environment.entrySet());
			entries.sort(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER));
			for (Map.Entry<String, String> entry : entries) {
				result.append("\n  ").append(entry.getKey()).append(" = ")
						.append(includeEnvironmentValues ? entry.getValue() : "<hidden>");
			}
		}
		return result.toString();
	}

	private String formatModes(ILaunchConfiguration config) throws CoreException {
		List<String> modes = new ArrayList<>();
		if (config.supportsMode(ILaunchManager.RUN_MODE)) {
			modes.add(ILaunchManager.RUN_MODE);
		}
		if (config.supportsMode(ILaunchManager.DEBUG_MODE)) {
			modes.add(ILaunchManager.DEBUG_MODE);
		}
		if (config.supportsMode("coverage")) {
			modes.add("coverage");
		}
		return modes.isEmpty() ? "(none)" : String.join(", ", modes);
	}

	private void validateEnvironmentVariables(Map<String, String> variables) {
		if (variables == null) {
			return;
		}
		for (Map.Entry<String, String> entry : variables.entrySet()) {
			if (isBlank(entry.getKey())) {
				throw new IllegalArgumentException("Environment variable names must not be blank.");
			}
			if (entry.getValue() == null) {
				throw new IllegalArgumentException("Environment variable '" + entry.getKey()
						+ "' has a null value. Use removeVariables to delete it.");
			}
		}
	}

	private List<String> normalizeRemovals(List<String> removeVariables) {
		if (removeVariables == null || removeVariables.isEmpty()) {
			return List.of();
		}
		List<String> normalized = new ArrayList<>();
		for (String name : removeVariables) {
			if (isBlank(name)) {
				throw new IllegalArgumentException("removeVariables must not contain blank names.");
			}
			normalized.add(name.trim());
		}
		return normalized;
	}

	private String normalizeChoice(String value, String defaultValue, String... allowedValues) {
		String normalized = isBlank(value) ? defaultValue : value.trim().toLowerCase(Locale.ROOT);
		for (String allowed : allowedValues) {
			if (allowed.equals(normalized)) {
				return normalized;
			}
		}
		throw new IllegalArgumentException("Invalid value '" + value + "'. Expected: "
				+ String.join(" or ", allowedValues) + ".");
	}

	private void setOptionalAttribute(ILaunchConfigurationWorkingCopy workingCopy, String key, String value) {
		if (!isBlank(value)) {
			workingCopy.setAttribute(key, value);
		}
	}

	private String attributeOrDefault(Map<String, Object> attributes, String key, String defaultValue) {
		Object value = attributes.get(key);
		return value instanceof String stringValue && !stringValue.isEmpty() ? stringValue : defaultValue;
	}

	private String requireText(String value, String name) {
		if (isBlank(value)) {
			throw new IllegalArgumentException(name + " is required.");
		}
		return value.trim();
	}

	private boolean isBlank(String value) {
		return value == null || value.trim().isEmpty();
	}

	private String safe(String value) {
		return value == null ? "" : value.trim();
	}

	private String errorMessage(Exception exception) {
		Throwable cause = exception;
		while (cause.getCause() != null && cause.getMessage() != null
				&& cause.getMessage().equals(cause.getCause().getMessage())) {
			cause = cause.getCause();
		}
		return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
	}

	private record MainTypeMatch(IJavaProject project, IType type) {
	}
}
