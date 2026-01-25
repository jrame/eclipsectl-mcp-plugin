package eclipsectlmcp.mcp.services;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.e4.ui.di.UISynchronize;

import jakarta.inject.Inject;

/**
 * Service for managing Eclipse launch configurations and executions.
 * All launches are async by default with file logging.
 * Use waitForCompletion=true to wait for the process to finish.
 */
@Creatable
public class EclipseRunService {

    @Inject
    private ILog logger;

    @Inject
    private UISynchronize sync;

    @Inject
    private LaunchLogService launchLogService;

    /**
     * Runs a launch configuration or lists all available configurations.
     *
     * @param configuration Name of the configuration to run. If empty or null,
     *                     returns all configuration names.
     * @param waitForCompletion If true, waits for the process to terminate (max 120s)
     * @return Launch status with log path, or list of configuration names
     */
    public String run(String configuration, boolean waitForCompletion) {
        if (configuration == null || configuration.trim().isEmpty()) {
            return listAllConfigurations();
        }
        return executeLaunchConfiguration(configuration.trim(), waitForCompletion);
    }

    /**
     * Lists all available launch configurations in the workspace.
     */
    private String listAllConfigurations() {
        try {
            ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
            ILaunchConfiguration[] configurations = launchManager.getLaunchConfigurations();

            if (configurations.length == 0) {
                return "No launch configurations found in the workspace.";
            }

            List<String> javaConfigurations = new ArrayList<>();
            List<String> testConfigurations = new ArrayList<>();
            List<String> otherConfigurations = new ArrayList<>();

            for (ILaunchConfiguration config : configurations) {
                String name = config.getName();
                String typeName = config.getType().getName();

                if (typeName.contains("Java Application")) {
                    javaConfigurations.add("  " + name);
                } else if (typeName.contains("JUnit")) {
                    testConfigurations.add("  " + name);
                } else {
                    otherConfigurations.add("  " + name + " (" + typeName + ")");
                }
            }

            StringBuilder result = new StringBuilder();
            result.append("Available Launch Configurations:\n");

            if (!javaConfigurations.isEmpty()) {
                result.append("Java Applications:\n");
                javaConfigurations.forEach(config -> result.append(config).append("\n"));
                result.append("\n");
            }

            if (!testConfigurations.isEmpty()) {
                result.append("JUnit Tests:\n");
                testConfigurations.forEach(config -> result.append(config).append("\n"));
                result.append("\n");
            }

            if (!otherConfigurations.isEmpty()) {
                result.append("Other Configurations:\n");
                otherConfigurations.forEach(config -> result.append(config).append("\n"));
            }

            return result.toString().trim();

        } catch (CoreException e) {
            logger.error("Error listing launch configurations", e);
            return "Error listing launch configurations: " + e.getMessage();
        }
    }

    /**
     * Executes a specific launch configuration by name.
     */
    private String executeLaunchConfiguration(String configurationName, boolean waitForCompletion) {
        try {
            ILaunchConfiguration config = findLaunchConfiguration(configurationName);
            if (config == null) {
                return "Launch configuration '" + configurationName + "' not found.\n" +
                       "Use run() without parameters to see available configurations.";
            }

            // Launch in run mode on the UI thread
            ILaunch[] launchHolder = new ILaunch[1];
            sync.syncExec(() -> {
                try {
                    launchHolder[0] = config.launch(ILaunchManager.RUN_MODE, new NullProgressMonitor());
                } catch (CoreException e) {
                    logger.error("Error launching configuration: " + configurationName, e);
                }
            });

            ILaunch launch = launchHolder[0];
            if (launch == null) {
                return "Error: Failed to launch configuration '" + configurationName + "'.";
            }

            // Start file logging
            String logPath = launchLogService.startLogging(launch, configurationName, "run");

            if (waitForCompletion) {
                LaunchLogService.TerminationResult result = launchLogService.waitForTermination(launch, 120_000);
                if (result != null) {
                    return "Launch '" + configurationName + "' completed (run mode).\n" +
                           "Log: " + logPath + "\n" +
                           "Exit code: " + result.getExitCode() + "\n" +
                           "Duration: " + result.getDurationFormatted();
                } else {
                    return "Launch '" + configurationName + "' timed out after 120s (run mode).\n" +
                           "Log: " + logPath + "\n" +
                           "Status: STILL RUNNING";
                }
            } else {
                return "Launch '" + configurationName + "' started (run mode).\n" +
                       "Log: " + logPath + "\n" +
                       "Status: RUNNING\n" +
                       "Use getLaunchStatus to check completion.";
            }

        } catch (Exception e) {
            logger.error("Error executing launch configuration: " + configurationName, e);
            return "Error executing launch configuration '" + configurationName + "': " + e.getMessage();
        }
    }

    /**
     * Finds a launch configuration by name (case-insensitive, then partial match).
     */
    private ILaunchConfiguration findLaunchConfiguration(String configurationName) throws CoreException {
        ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
        ILaunchConfiguration[] configurations = launchManager.getLaunchConfigurations();

        // First try exact match
        for (ILaunchConfiguration config : configurations) {
            if (config.getName().equals(configurationName)) {
                return config;
            }
        }

        // Then try case-insensitive match
        for (ILaunchConfiguration config : configurations) {
            if (config.getName().equalsIgnoreCase(configurationName)) {
                return config;
            }
        }

        // Finally try partial match (contains)
        for (ILaunchConfiguration config : configurations) {
            if (config.getName().toLowerCase().contains(configurationName.toLowerCase())) {
                return config;
            }
        }

        return null;
    }
}
