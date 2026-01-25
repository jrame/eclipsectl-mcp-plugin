package eclipsectlmcp.mcp.services;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import jakarta.inject.Inject;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ILog;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaModel;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import eclipsectlmcp.tools.UISynchronizeCallable;

/**
 * Service providing Eclipse code coverage capabilities via EclEmma/JaCoCo.
 * Handles launching with coverage, reading execution data, and producing
 * annotated source output with line-by-line coverage status.
 */
@Creatable
public class CoverageService {

	@Inject
	private ILog logger;

	@Inject
	private UISynchronizeCallable uiSync;

	@Inject
	private LaunchLogService launchLogService;

	/**
	 * Launch a configuration with EclEmma coverage.
	 *
	 * @param configName Name of the launch configuration
	 * @param waitForCompletion If true, waits for the process to terminate (max 120s)
	 * @return Status message with log path
	 */
	public String runCoverage(String configName, boolean waitForCompletion) {
		try {
			if (!isEclEmmaAvailable()) {
				return "Error: EclEmma plugin is not installed. Please install EclEmma (Eclipse Code Coverage) to use coverage features.";
			}

			return uiSync.syncCall(() -> {
				try {
					ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();

					ILaunchConfiguration targetConfig = null;
					for (ILaunchConfiguration config : launchManager.getLaunchConfigurations()) {
						if (config.getName().equalsIgnoreCase(configName)) {
							targetConfig = config;
							break;
						}
					}

					if (targetConfig == null) {
						return "Error: Launch configuration '" + configName + "' not found. "
								+ "Use listDebugConfigurations to see available configurations.";
					}

					ILaunch launch = targetConfig.launch("coverage", null);

					String logPath = launchLogService.startLogging(launch, configName, "coverage");

					if (waitForCompletion) {
						LaunchLogService.TerminationResult result = launchLogService.waitForTermination(launch, 120_000);
						if (result != null) {
							return "Launch '" + configName + "' completed (coverage mode).\n" +
									"Log: " + logPath + "\n" +
									"Exit code: " + result.getExitCode() + "\n" +
									"Duration: " + result.getDurationFormatted() + "\n" +
									"Use getCoverageReport or getClassCoverage to view results.";
						} else {
							return "Launch '" + configName + "' timed out after 120s (coverage mode).\n" +
									"Log: " + logPath + "\n" +
									"Status: STILL RUNNING";
						}
					} else {
						return "Launch '" + configName + "' started (coverage mode).\n" +
								"Log: " + logPath + "\n" +
								"Status: RUNNING\n" +
								"Use getCoverageReport or getClassCoverage after completion to view results.";
					}

				} catch (Exception e) {
					logger.error("Error launching with coverage", e);
					return "Error: " + e.getMessage();
				}
			});
		} catch (Exception e) {
			logger.error("Error launching with coverage", e);
			return "Error: " + e.getMessage();
		}
	}

	/**
	 * Get overall coverage report for the last coverage session.
	 *
	 * @return Human-readable report with overall coverage statistics
	 */
	public String getCoverageReport() {
		try {
			if (!isEclEmmaAvailable()) {
				return "Error: EclEmma plugin is not installed. Please install EclEmma (Eclipse Code Coverage) to use coverage features.";
			}

			return uiSync.syncCall(() -> {
				try {
					Class<?> coverageToolsClass = Class.forName("org.eclipse.eclemma.core.CoverageTools");
					Object sessionManager = coverageToolsClass.getMethod("getSessionManager").invoke(null);
					Object activeSession = sessionManager.getClass().getMethod("getActiveSession")
							.invoke(sessionManager);

					if (activeSession == null) {
						return "Error: No active coverage session. Run runCoverage first to generate coverage data.";
					}

					String description = (String) activeSession.getClass().getMethod("getDescription")
							.invoke(activeSession);
					Object scope = activeSession.getClass().getMethod("getScope").invoke(activeSession);

					List<String> scopeElements = new ArrayList<>();
					if (scope instanceof java.util.Set) {
						for (Object element : (java.util.Set<?>) scope) {
							String elementName = (String) element.getClass().getMethod("getElementName")
									.invoke(element);
							scopeElements.add(elementName);
						}
					}

					StringBuilder sb = new StringBuilder();
					sb.append("Coverage session active: ").append(description).append("\n\n");
					sb.append("Scope (").append(scopeElements.size()).append(" elements):\n");
					for (String elem : scopeElements) {
						sb.append("  - ").append(elem).append("\n");
					}
					sb.append("\nUse getClassCoverage(className) to see line-by-line coverage for a specific class.");
					return sb.toString();

				} catch (ClassNotFoundException e) {
					return "Error: EclEmma classes not found. Ensure org.eclipse.eclemma.core is installed.";
				} catch (Exception e) {
					logger.error("Error getting coverage report", e);
					return "Error: " + e.getMessage();
				}
			});
		} catch (Exception e) {
			logger.error("Error getting coverage report", e);
			return "Error: " + e.getMessage();
		}
	}

	/**
	 * Get detailed line-by-line coverage for a specific class.
	 * Reads JaCoCo execution data directly from the active EclEmma session
	 * and analyzes the project's .class files to produce annotated source output.
	 *
	 * @param className Fully qualified class name (e.g., "com.example.Calculator")
	 * @return Annotated source with coverage markers
	 */
	public String getClassCoverage(String className) {
		try {
			if (!isEclEmmaAvailable()) {
				return "Error: EclEmma plugin is not installed. Please install EclEmma (Eclipse Code Coverage) to use coverage features.";
			}

			if (className == null || className.trim().isEmpty()) {
				return "Error: className parameter is required";
			}

			// Strip .java extension if present
			String resolvedName = className.trim();
			if (resolvedName.endsWith(".java")) {
				resolvedName = resolvedName.substring(0, resolvedName.length() - 5);
			}
			final String classNameToFind = resolvedName;

			return uiSync.syncCall(() -> {
				try {
					Class<?> coverageToolsClass = Class.forName("org.eclipse.eclemma.core.CoverageTools");

					// Find the IType for the class
					IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
					IJavaModel javaModel = JavaCore.create(root);

					IType type = null;

					// First try as fully qualified name
					for (IJavaProject javaProject : javaModel.getJavaProjects()) {
						type = javaProject.findType(classNameToFind);
						if (type != null && type.exists()) {
							break;
						}
					}

					// If not found and simple name, search all types in workspace
					if (type == null && !classNameToFind.contains(".")) {
						List<IType> matches = new ArrayList<>();
						org.eclipse.jdt.core.search.SearchEngine engine = new org.eclipse.jdt.core.search.SearchEngine();
						engine.searchAllTypeNames(
							null, org.eclipse.jdt.core.search.SearchPattern.R_EXACT_MATCH,
							classNameToFind.toCharArray(),
							org.eclipse.jdt.core.search.SearchPattern.R_EXACT_MATCH | org.eclipse.jdt.core.search.SearchPattern.R_CASE_SENSITIVE,
							org.eclipse.jdt.core.search.IJavaSearchConstants.TYPE,
							org.eclipse.jdt.core.search.SearchEngine.createWorkspaceScope(),
							new org.eclipse.jdt.core.search.TypeNameMatchRequestor() {
								@Override
								public void acceptTypeNameMatch(org.eclipse.jdt.core.search.TypeNameMatch match) {
									if (match.getSimpleTypeName().equals(classNameToFind)) {
										matches.add(match.getType());
									}
								}
							},
							org.eclipse.jdt.core.search.IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH,
							new org.eclipse.core.runtime.NullProgressMonitor()
						);
						if (matches.size() == 1) {
							type = matches.get(0);
						} else if (matches.size() > 1) {
							StringBuilder sb = new StringBuilder();
							sb.append("Multiple classes found for '").append(classNameToFind)
								.append("' (").append(matches.size()).append(" matches). Please specify the fully qualified name:\n");
							int limit = Math.min(matches.size(), 10);
							for (int i = 0; i < limit; i++) {
								sb.append("  - ").append(matches.get(i).getFullyQualifiedName()).append("\n");
							}
							if (matches.size() > 10) {
								sb.append("  ... and ").append(matches.size() - 10).append(" more\n");
							}
							return sb.toString();
						}
					}

					if (type == null) {
						return "Error: Class not found: " + classNameToFind
								+ ". Make sure the class exists and is in the workspace.";
					}

					String resolvedFqn = type.getFullyQualifiedName();

					ICompilationUnit cu = type.getCompilationUnit();
					if (cu == null) {
						return "Error: Compilation unit not found for class: " + resolvedFqn;
					}

					String source = cu.getSource();
					String[] sourceLines = source != null ? source.split("\n") : null;
					int totalSourceLines = sourceLines != null ? sourceLines.length : 0;

					// Wait for running coverage launches to finish BEFORE getting session
					Object sessionManager = coverageToolsClass.getMethod("getSessionManager").invoke(null);
					for (int attempt = 0; attempt < 30; attempt++) {
						Object launches = coverageToolsClass.getMethod("getRunningCoverageLaunches").invoke(null);
						if (launches instanceof java.util.List && ((java.util.List<?>) launches).isEmpty()) {
							break;
						}
						Thread.sleep(500);
					}

					// Now get the active session (created after coverage launch completes)
					Object activeSession = sessionManager.getClass().getMethod("getActiveSession").invoke(sessionManager);

					if (activeSession == null) {
						return "Error: No active coverage session. Run runCoverage first.";
					}

					// Read execution data from session
					Class<?> execDataStoreClass = Class.forName("org.jacoco.core.data.ExecutionDataStore");
					Class<?> sessionInfoStoreClass = Class.forName("org.jacoco.core.data.SessionInfoStore");
					Object execDataStore = execDataStoreClass.getDeclaredConstructor().newInstance();
					Object sessionInfoStore = sessionInfoStoreClass.getDeclaredConstructor().newInstance();

					// ICoverageSession extends IExecutionDataSource which has accept(IExecutionDataVisitor, ISessionInfoVisitor)
					// ExecutionDataStore implements IExecutionDataVisitor, SessionInfoStore implements ISessionInfoVisitor
					java.lang.reflect.Method acceptMethod = null;
					for (java.lang.reflect.Method m : activeSession.getClass().getMethods()) {
						if (m.getName().equals("accept") && m.getParameterCount() == 2) {
							acceptMethod = m;
							break;
						}
					}

					if (acceptMethod == null) {
						return "Error: CoverageSession does not expose accept() method. "
								+ "Session class: " + activeSession.getClass().getName();
					}

					acceptMethod.invoke(activeSession, execDataStore, sessionInfoStore);

					// Analyze .class files with JaCoCo
					Class<?> coverageBuilderClass = Class.forName("org.jacoco.core.analysis.CoverageBuilder");
					Object coverageBuilder = coverageBuilderClass.getDeclaredConstructor().newInstance();

					Class<?> analyzerClass = Class.forName("org.jacoco.core.analysis.Analyzer");
					Object analyzer = null;
					for (java.lang.reflect.Constructor<?> ctor : analyzerClass.getConstructors()) {
						if (ctor.getParameterCount() == 2) {
							analyzer = ctor.newInstance(execDataStore, coverageBuilder);
							break;
						}
					}

					if (analyzer == null) {
						return "Error: Could not create JaCoCo Analyzer.";
					}

					// Find .class files in the project output folder
					IJavaProject javaProject = type.getJavaProject();
					org.eclipse.core.runtime.IPath outputPath = javaProject.getOutputLocation();
					IFolder outputFolder = root.getFolder(outputPath);

					if (outputFolder.exists()) {
						analyzeClassFiles(analyzer, analyzerClass, outputFolder);
					}

					// Get IClassCoverage for our target class
					java.util.Collection<?> classes = (java.util.Collection<?>) coverageBuilderClass
							.getMethod("getClasses").invoke(coverageBuilder);

					String jacocoClassName = resolvedFqn.replace('.', '/');
					Object classCoverage = null;
					for (Object cc : classes) {
						String ccName = (String) cc.getClass().getMethod("getName").invoke(cc);
						if (ccName.equals(jacocoClassName)) {
							classCoverage = cc;
							break;
						}
					}

					if (classCoverage == null) {
						StringBuilder debug = new StringBuilder();
						debug.append("Error: No coverage data for class ").append(resolvedFqn).append(".\n");
						debug.append("Searched for: ").append(jacocoClassName).append("\n");
						debug.append("Available classes (").append(classes.size()).append("):\n");
						for (Object cc : classes) {
							String ccName = (String) cc.getClass().getMethod("getName").invoke(cc);
							debug.append("  - ").append(ccName).append("\n");
						}
						debug.append("Output folder: ").append(outputFolder.getLocation()).append("\n");
						return debug.toString();
					}

					return formatAnnotatedSource(classCoverage, resolvedFqn, sourceLines, totalSourceLines);

				} catch (ClassNotFoundException e) {
					return "Error: EclEmma classes not found. Ensure org.eclipse.eclemma.core is installed.";
				} catch (Exception e) {
					logger.error("Error getting class coverage", e);
					return "Error: " + e.getMessage();
				}
			});
		} catch (Exception e) {
			logger.error("Error getting class coverage", e);
			return "Error: " + e.getMessage();
		}
	}

	/**
	 * Format coverage data as annotated source with markers.
	 */
	private String formatAnnotatedSource(Object classCoverage, String className, String[] sourceLines,
			int totalSourceLines) throws Exception {

		int firstLine = (int) classCoverage.getClass().getMethod("getFirstLine").invoke(classCoverage);
		int lastLine = (int) classCoverage.getClass().getMethod("getLastLine").invoke(classCoverage);

		// JaCoCo ICounter status: EMPTY=0x00, NOT_COVERED=0x01, FULLY_COVERED=0x02, PARTLY_COVERED=0x03
		int[] lineStatuses = new int[totalSourceLines + 1];
		int[][] lineBranches = new int[totalSourceLines + 1][2];
		int coveredCount = 0;
		int missedCount = 0;
		int partlyCount = 0;
		int totalBranchesCovered = 0;
		int totalBranchesTotal = 0;

		java.lang.reflect.Method getLine = classCoverage.getClass().getMethod("getLine", int.class);

		for (int lineNr = firstLine; lineNr <= lastLine && lineNr <= totalSourceLines; lineNr++) {
			Object line = getLine.invoke(classCoverage, lineNr);
			int status = (int) line.getClass().getMethod("getStatus").invoke(line);
			lineStatuses[lineNr] = status;

			if (status != 0) {
				Object branchCounter = line.getClass().getMethod("getBranchCounter").invoke(line);
				int brTotal = (int) branchCounter.getClass().getMethod("getTotalCount").invoke(branchCounter);
				int brCovered = (int) branchCounter.getClass().getMethod("getCoveredCount").invoke(branchCounter);
				lineBranches[lineNr][0] = brCovered;
				lineBranches[lineNr][1] = brTotal;
				totalBranchesCovered += brCovered;
				totalBranchesTotal += brTotal;
			}

			switch (status) {
			case 1:
				missedCount++;
				break;
			case 2:
				coveredCount++;
				break;
			case 3:
				partlyCount++;
				break;
			}
		}

		int totalExecutable = coveredCount + missedCount + partlyCount;
		int totalCovered = coveredCount + partlyCount;
		StringBuilder sb = new StringBuilder();

		// Header
		sb.append("Coverage: ").append(className);
		if (totalExecutable > 0) {
			double linePct = 100.0 * totalCovered / totalExecutable;
			sb.append(String.format(" \u2014 %.1f%% lines (%d/%d)", linePct, totalCovered, totalExecutable));
		}
		if (totalBranchesTotal > 0) {
			double brPct = 100.0 * totalBranchesCovered / totalBranchesTotal;
			sb.append(String.format(", %.1f%% branches (%d/%d)", brPct, totalBranchesCovered, totalBranchesTotal));
		}
		sb.append("\n\n");

		// Line width for line numbers
		int lineWidth = String.valueOf(totalSourceLines).length();
		String lineFormat = "%" + lineWidth + "d";

		// Annotated source lines
		for (int lineNr = 1; lineNr <= totalSourceLines; lineNr++) {
			int status = lineNr < lineStatuses.length ? lineStatuses[lineNr] : 0;
			String marker;
			switch (status) {
			case 1:
				marker = "\u2717"; // ✗ not covered
				break;
			case 2:
				marker = "\u2713"; // ✓ fully covered
				break;
			case 3:
				marker = "\u25D1"; // ◑ partly covered
				break;
			default:
				marker = " "; // no executable code
				break;
			}

			sb.append(marker).append(" ").append(String.format(lineFormat, lineNr)).append("  ");
			sb.append(sourceLines[lineNr - 1]);

			if (lineNr < lineBranches.length && lineBranches[lineNr][1] > 0) {
				sb.append("  // [branches: ").append(lineBranches[lineNr][0]).append("/")
						.append(lineBranches[lineNr][1]).append("]");
			}

			sb.append("\n");
		}

		return sb.toString();
	}

	/**
	 * Recursively analyze .class files in a folder using JaCoCo Analyzer.
	 */
	private void analyzeClassFiles(Object analyzer, Class<?> analyzerClass, IFolder folder) throws Exception {
		java.lang.reflect.Method analyzeAll = null;
		for (java.lang.reflect.Method m : analyzerClass.getMethods()) {
			if (m.getName().equals("analyzeAll") && m.getParameterCount() == 2) {
				Class<?>[] params = m.getParameterTypes();
				if (params[0] == InputStream.class && params[1] == String.class) {
					analyzeAll = m;
					break;
				}
			}
		}

		if (analyzeAll == null) {
			return;
		}

		for (IResource member : folder.members()) {
			if (member instanceof IFolder) {
				analyzeClassFiles(analyzer, analyzerClass, (IFolder) member);
			} else if (member instanceof IFile && member.getName().endsWith(".class")) {
				try (InputStream is = ((IFile) member).getContents()) {
					analyzeAll.invoke(analyzer, is, member.getName());
				}
			}
		}
	}

	/**
	 * Check if EclEmma plugin is available.
	 */
	private boolean isEclEmmaAvailable() {
		try {
			Bundle bundle = FrameworkUtil.getBundle(this.getClass());
			if (bundle != null) {
				bundle.loadClass("org.eclipse.eclemma.core.CoverageTools");
				return true;
			}
		} catch (Exception e) {
			// EclEmma not available
		}
		return false;
	}
}
