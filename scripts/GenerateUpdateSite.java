import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Creates the legacy homepage index from the feature that Tycho actually built. */
class GenerateUpdateSite {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: java GenerateUpdateSite.java <p2 repository>");
        }
        Path repository = Path.of(args[0]);
        List<Path> features;
        try (var files = Files.list(repository.resolve("features"))) {
            features = files.filter(p -> p.getFileName().toString()
                    .matches("eclipsectlmcp_[0-9A-Za-z.]+\\.jar")).toList();
        }
        if (features.size() != 1) {
            throw new IllegalStateException("Expected exactly one built EclipseCtl feature; found "
                    + features.size());
        }
        String filename = features.getFirst().getFileName().toString();
        String version = filename.substring("eclipsectlmcp_".length(), filename.length() - 4);
        String site = """
                <?xml version="1.0" encoding="UTF-8"?>
                <site>
                  <description name="EclipseCtl MCP">Model Context Protocol server for Eclipse IDE</description>
                  <feature url="features/%s" id="eclipsectlmcp" version="%s"/>
                </site>
                """.formatted(filename, version);
        Files.writeString(repository.resolve("site.xml"), site, StandardCharsets.UTF_8);
        System.out.println("Homepage index generated for EclipseCtl " + version);
    }
}
