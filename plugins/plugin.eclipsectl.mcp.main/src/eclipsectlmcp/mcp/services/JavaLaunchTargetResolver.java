package eclipsectlmcp.mcp.services;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IAnnotation;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IImportDeclaration;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;

/**
 * Resolves Java types and detects the launch kind from test annotations/imports.
 */
final class JavaLaunchTargetResolver {

	LaunchTarget resolve(String className, String projectName, boolean requireMain) throws CoreException {
		String normalizedClass = className.endsWith(".java")
				? className.substring(0, className.length() - ".java".length())
				: className;
		Map<String, LaunchTarget> matches = new LinkedHashMap<>();
		for (IJavaProject javaProject : candidateProjects(projectName)) {
			IType directMatch = javaProject.findType(normalizedClass);
			if (isUsableMatch(directMatch, requireMain)) {
				matches.put(directMatch.getHandleIdentifier(), new LaunchTarget(javaProject, directMatch));
			}
			for (IPackageFragment fragment : javaProject.getPackageFragments()) {
				if (fragment.getKind() != IPackageFragmentRoot.K_SOURCE) {
					continue;
				}
				for (ICompilationUnit unit : fragment.getCompilationUnits()) {
					for (IType type : unit.getAllTypes()) {
						if (matchesTypeName(type, normalizedClass) && isUsableMatch(type, requireMain)) {
							matches.put(type.getHandleIdentifier(), new LaunchTarget(javaProject, type));
						}
					}
				}
			}
		}

		if (matches.isEmpty()) {
			String scope = isBlank(projectName) ? "the workspace" : "project '" + projectName.trim() + "'";
			String targetDescription = requireMain ? "runnable main class" : "Java class";
			throw new IllegalArgumentException("No " + targetDescription + " '" + className + "' found in "
					+ scope + ".");
		}
		if (matches.size() > 1) {
			List<LaunchTarget> sortedMatches = new ArrayList<>(matches.values());
			sortedMatches.sort(Comparator.comparing((LaunchTarget match) -> match.type().getFullyQualifiedName('.'))
					.thenComparing(match -> match.project().getElementName()));
			StringBuilder message = new StringBuilder("Class '").append(className)
					.append("' is ambiguous. Matching classes:\n");
			for (LaunchTarget match : sortedMatches) {
				message.append("- ").append(match.type().getFullyQualifiedName('.'))
						.append(" (project: ").append(match.project().getElementName()).append(")\n");
			}
			message.append("Specify projectName or a fully qualified className.");
			throw new IllegalArgumentException(message.toString());
		}
		return matches.values().iterator().next();
	}

	LaunchConfigurationKind detectKind(IType type) throws CoreException {
		ICompilationUnit unit = type.getCompilationUnit();
		List<String> imports = new ArrayList<>();
		if (unit != null) {
			for (IImportDeclaration declaration : unit.getImports()) {
				if (!Flags.isStatic(declaration.getFlags())) {
					imports.add(declaration.getElementName());
				}
			}
		}

		Set<LaunchConfigurationKind> annotationKinds = new LinkedHashSet<>();
		for (IAnnotation annotation : type.getAnnotations()) {
			addAnnotationKind(type, annotation, imports, annotationKinds);
		}
		for (IMethod method : type.getMethods()) {
			for (IAnnotation annotation : method.getAnnotations()) {
				addAnnotationKind(type, annotation, imports, annotationKinds);
			}
		}
		if (annotationKinds.size() == 1) {
			return annotationKinds.iterator().next();
		}
		if (annotationKinds.size() > 1) {
			throw ambiguousFrameworks(type, annotationKinds);
		}

		Set<LaunchConfigurationKind> importKinds = new LinkedHashSet<>();
		for (String importedType : imports) {
			LaunchConfigurationKind kind = frameworkKind(importedType);
			if (kind != null) {
				importKinds.add(kind);
			}
		}
		if (looksLikeTest(type)) {
			if (importKinds.size() == 1) {
				return importKinds.iterator().next();
			}
			if (importKinds.size() > 1) {
				throw ambiguousFrameworks(type, importKinds);
			}
		}
		if (hasMainMethod(type)) {
			return LaunchConfigurationKind.JAVA;
		}
		throw new IllegalArgumentException("Cannot auto-detect the launch configuration type for class '"
				+ type.getFullyQualifiedName('.')
				+ "'. Specify type explicitly: java, junit4, junit5, or testng.");
	}

	boolean hasMainMethod(IType type) throws CoreException {
		for (IMethod method : type.getMethods()) {
			if (method.isMainMethod()) {
				return true;
			}
		}
		return false;
	}

	private void addAnnotationKind(IType type, IAnnotation annotation, List<String> imports,
			Set<LaunchConfigurationKind> kinds) throws CoreException {
		String annotationName = annotation.getElementName();
		LaunchConfigurationKind directKind = frameworkKind(annotationName);
		if (directKind != null) {
			kinds.add(directKind);
			return;
		}
		String simpleName = simpleName(annotationName);
		for (String importedType : imports) {
			String qualifiedCandidate = importedType.endsWith(".*")
					? importedType.substring(0, importedType.length() - 1) + simpleName
					: importedType;
			if (simpleName(qualifiedCandidate).equals(simpleName)) {
				LaunchConfigurationKind importedKind = frameworkKind(qualifiedCandidate);
				if (importedKind != null) {
					kinds.add(importedKind);
				}
			}
		}
		String[][] resolvedTypes = type.resolveType(annotationName);
		if (resolvedTypes != null) {
			for (String[] resolvedType : resolvedTypes) {
				LaunchConfigurationKind resolvedKind = frameworkKind(resolvedType[0] + "." + resolvedType[1]);
				if (resolvedKind != null) {
					kinds.add(resolvedKind);
				}
			}
		}
	}

	private LaunchConfigurationKind frameworkKind(String qualifiedName) {
		if (qualifiedName.startsWith("org.junit.jupiter.")) {
			return LaunchConfigurationKind.JUNIT5;
		}
		if (qualifiedName.startsWith("org.junit.") || qualifiedName.startsWith("junit.framework.")) {
			return LaunchConfigurationKind.JUNIT4;
		}
		if (qualifiedName.startsWith("org.testng.")) {
			return LaunchConfigurationKind.TESTNG;
		}
		return null;
	}

	private boolean looksLikeTest(IType type) throws CoreException {
		String typeName = type.getElementName();
		if (typeName.endsWith("Test") || typeName.endsWith("Tests")) {
			return true;
		}
		for (IMethod method : type.getMethods()) {
			if (method.getElementName().startsWith("test")) {
				return true;
			}
			for (IAnnotation annotation : method.getAnnotations()) {
				if (simpleName(annotation.getElementName()).contains("Test")) {
					return true;
				}
			}
		}
		return false;
	}

	private IllegalArgumentException ambiguousFrameworks(IType type, Set<LaunchConfigurationKind> kinds) {
		return new IllegalArgumentException("Multiple test frameworks were detected for class '"
				+ type.getFullyQualifiedName('.') + "': "
				+ kinds.stream().map(LaunchConfigurationKind::displayName).sorted().toList()
				+ ". Specify type explicitly.");
	}

	private List<IJavaProject> candidateProjects(String projectName) throws CoreException {
		if (!isBlank(projectName)) {
			IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName.trim());
			if (!project.isAccessible()) {
				throw new IllegalArgumentException("Project '" + projectName.trim()
						+ "' does not exist or is closed.");
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

	private boolean isUsableMatch(IType type, boolean requireMain) throws CoreException {
		return type != null && (!requireMain || hasMainMethod(type));
	}

	private boolean matchesTypeName(IType type, String requestedName) {
		return requestedName.equals(type.getElementName())
				|| requestedName.equals(type.getFullyQualifiedName('.'));
	}

	private String simpleName(String qualifiedName) {
		int separator = qualifiedName.lastIndexOf('.');
		return separator < 0 ? qualifiedName : qualifiedName.substring(separator + 1);
	}

	private boolean isBlank(String value) {
		return value == null || value.trim().isEmpty();
	}

	record LaunchTarget(IJavaProject project, IType type) {
	}
}
