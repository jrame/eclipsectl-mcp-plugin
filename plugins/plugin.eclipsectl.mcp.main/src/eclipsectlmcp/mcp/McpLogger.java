package eclipsectlmcp.mcp;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.e4.core.di.annotations.Creatable;

import jakarta.inject.Inject;

/**
 * Enhanced logging utility specifically for MCP (Model Context Protocol) operations.
 * Provides detailed logging with optional file output for debugging MCP tool execution.
 */
@Creatable
public class McpLogger
{
    private static final String LOG_FILE_NAME = "mcp-debug.log";
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    
    @Inject
    private ILog eclipseLogger;
    
    private File logFile;
    private boolean fileLoggingEnabled = true;
    
    /**
     * Initializes the MCP logger with file logging enabled by default.
     */
    public McpLogger()
    {
        initializeFileLogging();
    }
    
    /**
     * Initializes file logging by creating log file in workspace metadata folder.
     */
    private void initializeFileLogging()
    {
        try
        {
            // Get Eclipse workspace metadata directory
            String workspaceLocation = Platform.getLocation().toString();
            File metadataDir = new File(workspaceLocation, ".metadata");
            if (!metadataDir.exists())
            {
                metadataDir.mkdirs();
            }
            
            logFile = new File(metadataDir, LOG_FILE_NAME);
            
            // Log initialization
            writeToFile("=== MCP Logger Initialized ===");
            info("MCP debug logging initialized. Log file: " + logFile.getAbsolutePath());
        }
        catch (Exception e)
        {
            fileLoggingEnabled = false;
            warn("Failed to initialize MCP file logging: " + e.getMessage());
        }
    }
    
    /**
     * Logs an info message with detailed MCP context.
     */
    public void info(String message)
    {
        log("INFO", message, null);
    }
    
    /**
     * Logs a warning message.
     */
    public void warn(String message)
    {
        log("WARN", message, null);
    }
    
    /**
     * Logs an error message with optional exception details.
     */
    public void error(String message, Throwable throwable)
    {
        log("ERROR", message, throwable);
    }
    
    /**
     * Logs MCP function call details including arguments and timing.
     */
    public void logFunctionCall(String functionName, Object arguments)
    {
        String message = String.format("MCP Function Call: %s | Arguments: %s", 
                                     functionName, 
                                     Optional.ofNullable(arguments).map(Object::toString).orElse("null"));
        log("MCP_CALL", message, null);
    }
    
    /**
     * Logs MCP function result details.
     */
    public void logFunctionResult(String functionName, Object result, long executionTimeMs)
    {
        String message = String.format("MCP Function Result: %s | Execution Time: %dms | Result: %s", 
                                     functionName, 
                                     executionTimeMs,
                                     Optional.ofNullable(result).map(Object::toString).orElse("null"));
        log("MCP_RESULT", message, null);
    }
    
    /**
     * Logs MCP function execution errors with detailed context.
     */
    public void logFunctionError(String functionName, Object arguments, Throwable error)
    {
        String message = String.format("MCP Function Error: %s | Arguments: %s | Error: %s", 
                                     functionName,
                                     Optional.ofNullable(arguments).map(Object::toString).orElse("null"),
                                     error.getMessage());
        log("MCP_ERROR", message, error);
    }
    
    /**
     * Logs MCP client operations (initialization, connection, etc.).
     */
    public void logClientOperation(String clientName, String operation, String details)
    {
        String message = String.format("MCP Client: %s | Operation: %s | Details: %s", 
                                     clientName, operation, details);
        log("MCP_CLIENT", message, null);
    }
    
    /**
     * Core logging method that writes to both Eclipse log and file.
     */
    private void log(String level, String message, Throwable throwable)
    {
        String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
        String formattedMessage = String.format("[%s] [%s] %s", timestamp, level, message);
        
        // Log to Eclipse logger
        if (eclipseLogger != null)
        {
            switch (level)
            {
                case "ERROR", "MCP_ERROR" -> eclipseLogger.error(formattedMessage, throwable);
                case "WARN" -> eclipseLogger.warn(formattedMessage, throwable);
                default -> eclipseLogger.info(formattedMessage, throwable);
            }
        }
        
        // Log to file
        writeToFile(formattedMessage);
        
        // If there's an exception, log stack trace to file
        if (throwable != null && fileLoggingEnabled)
        {
            writeToFile("Stack trace:");
            writeToFile(getStackTrace(throwable));
        }
    }
    
    /**
     * Writes a message to the log file.
     */
    private void writeToFile(String message)
    {
        if (!fileLoggingEnabled || logFile == null)
        {
            return;
        }
        
        try (FileWriter writer = new FileWriter(logFile, true))
        {
            writer.write(message + System.lineSeparator());
            writer.flush();
        }
        catch (IOException e)
        {
            // Avoid infinite recursion - just disable file logging
            fileLoggingEnabled = false;
        }
    }
    
    /**
     * Converts throwable to string representation.
     */
    private String getStackTrace(Throwable throwable)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(throwable.toString()).append(System.lineSeparator());
        
        for (StackTraceElement element : throwable.getStackTrace())
        {
            sb.append("    at ").append(element.toString()).append(System.lineSeparator());
        }
        
        if (throwable.getCause() != null)
        {
            sb.append("Caused by: ").append(getStackTrace(throwable.getCause()));
        }
        
        return sb.toString();
    }
    
    /**
     * Enables or disables file logging.
     */
    public void setFileLoggingEnabled(boolean enabled)
    {
        this.fileLoggingEnabled = enabled;
        if (enabled && logFile == null)
        {
            initializeFileLogging();
        }
    }
    
    /**
     * Returns the current log file path.
     */
    public Optional<String> getLogFilePath()
    {
        return Optional.ofNullable(logFile).map(File::getAbsolutePath);
    }
    
    /**
     * Clears the log file contents.
     */
    public void clearLogFile()
    {
        if (logFile != null && logFile.exists())
        {
            try (FileWriter writer = new FileWriter(logFile, false))
            {
                writer.write("=== Log Cleared at " + LocalDateTime.now().format(TIME_FORMATTER) + " ===" + System.lineSeparator());
            }
            catch (IOException e)
            {
                warn("Failed to clear log file: " + e.getMessage());
            }
        }
    }
}
