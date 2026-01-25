package eclipsectlmcp.preferences.tmux;

public record TmuxPreferences(
        String sessionName,
        String commandTemplate,
        boolean copyToClipboard,
        boolean sendToTmux) {
}
