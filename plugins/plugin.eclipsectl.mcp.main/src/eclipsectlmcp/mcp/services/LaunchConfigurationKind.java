package eclipsectlmcp.mcp.services;

import java.util.Locale;

/**
 * Supported Java-based Eclipse launch configuration kinds.
 */
enum LaunchConfigurationKind {

	JAVA("java", "Java"),
	JUNIT4("junit4", "JUnit 4"),
	JUNIT5("junit5", "JUnit 5"),
	TESTNG("testng", "TestNG"),
	AUTO("auto", "Auto-detected");

	private final String value;
	private final String displayName;

	LaunchConfigurationKind(String value, String displayName) {
		this.value = value;
		this.displayName = displayName;
	}

	String value() {
		return value;
	}

	String displayName() {
		return displayName;
	}

	static LaunchConfigurationKind parse(String value) {
		if (value == null || value.isBlank()) {
			return JAVA;
		}
		String normalized = value.trim().toLowerCase(Locale.ROOT)
				.replace("-", "")
				.replace("_", "")
				.replace(" ", "");
		for (LaunchConfigurationKind kind : values()) {
			if (kind.value.equals(normalized)) {
				return kind;
			}
		}
		throw new IllegalArgumentException("Invalid launch configuration type '" + value
				+ "'. Expected: java, junit4, junit5, testng, or auto.");
	}
}
