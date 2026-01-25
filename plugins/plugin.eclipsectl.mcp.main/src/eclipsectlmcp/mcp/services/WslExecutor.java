package eclipsectlmcp.mcp.services;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class WslExecutor {

    private static final int MAX_TIMEOUT_MS = 600_000;

    public static String wsl(String command, int timeoutMs, boolean async) {
        if (command == null || command.isBlank()) {
            return "{\"error\": \"Command cannot be null or empty\"}";
        }

        timeoutMs = Math.max(1000, Math.min(timeoutMs, MAX_TIMEOUT_MS));

        try {
            ProcessBuilder pb = new ProcessBuilder("wsl", "bash", "-lc", command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            long pid = process.pid();

            if (async) {
                final int finalTimeout = timeoutMs;
                CompletableFuture.runAsync(() -> {
                    try {
                        if (!process.waitFor(finalTimeout, TimeUnit.MILLISECONDS)) {
                            process.destroyForcibly();
                        }
                    } catch (InterruptedException e) {
                        process.destroyForcibly();
                        Thread.currentThread().interrupt();
                    }
                });
                return "{\"status\": \"started\", \"pid\": " + pid
                        + ", \"command\": \"" + escapeJson(command) + "\"}";
            }

            // Mode synchrone
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);

            if (!finished) {
                process.destroyForcibly();
                return "{\"error\": \"Command timed out after " + timeoutMs + "ms\","
                        + " \"pid\": " + pid
                        + ", \"partial_output\": \"" + escapeJson(output.toString().trim()) + "\"}";
            }

            return "{\"exit_code\": " + process.exitValue()
                    + ", \"pid\": " + pid
                    + ", \"output\": \"" + escapeJson(output.toString().trim()) + "\"}";

        } catch (IOException e) {
            return "{\"error\": \"IOException: " + escapeJson(e.getMessage()) + "\"}";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "{\"error\": \"Command interrupted\"}";
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}