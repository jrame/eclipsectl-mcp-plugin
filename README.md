# EclipseCtl MCP - Model Context Protocol Server for Eclipse IDE

EclipseCtl MCP is an Eclipse IDE plugin that exposes Eclipse functionality as a Model Context Protocol (MCP) server. This allows AI assistants like Claude Desktop, Claude Code, or Cursor to interact directly with Eclipse projects through standardized MCP tools.

## Origin

This project is a fork of [AssistAI](https://github.com/gradusnikov/eclipse-chatgpt-plugin) by Wojciech Gradkowski. The original project provided both an AI chat interface within Eclipse and MCP server capabilities. This fork has removed the AI chat interface and focuses exclusively on providing a standalone MCP server that exposes Eclipse IDE functionality to external AI clients.

## Features

<!-- BEGIN GENERATED MCP COUNT -->
EclipseCtl MCP exposes **97 MCP tools** across 7 main servers, allowing AI assistants to:
<!-- END GENERATED MCP COUNT -->

- **Workspace State**: `getWorkspaceState` reports `Workspace status: idle|busy` and `Build status: idle|busy` for bounded client polling. Build state covers Eclipse auto/manual builds (including delayed builds); unrelated sleeping maintenance jobs do not prevent idle. This is a snapshot and does not start a build or track external Maven processes.
- **Eclipse Builds**: `buildProjects` runs an incremental or full Eclipse build, waits for completion (default: 120 seconds), and reports error/warning totals and problem locations per project. It includes dependencies and, by default, transitive dependent projects. Timeouts request cancellation and return an incomplete-result error.
- **Manage Projects**: List projects, import new projects, read project properties and structure
- **Code Analysis**: Search types, find references, get type hierarchies, analyze call hierarchies
- **Code Generation**: Generate getters/setters, toString/equals/hashCode, implement interfaces
- **Refactoring**: Rename types/packages/methods/fields/local variables, change signatures, move classes, extract methods/variables/constants, inline methods/variables
- **Testing**: Run JUnit tests (all, package, class, or specific methods), find test classes
- **Debugging**: Create and inspect Java, JUnit 4/5, and TestNG launch configurations, manage launch environments and breakpoints, control debug sessions, inspect variables, evaluate expressions
- **Code Coverage**: Launch tests with coverage analysis, get coverage reports (requires EclEmma)
- **Maven**: Run Maven builds, get effective POM, list dependencies
- **Source Operations**: Organize imports, format code
- **Search**: Full-text search, regex search, file pattern matching
- **Quick Fixes**: Get and apply Eclipse Quick Fixes for compilation errors
- **Editor Access**: Get current file, selection, console output
- **Editor Selection Sharing**: Use the "Share Editor Selection" command (F9) to optionally copy formatted selections to the clipboard, send them to tmux, or both

Tool calls preserve human-readable text and set MCP `isError=true` for reported execution or validation failures, including test timeouts and partial failures in batch operations. File and console content beginning with `Error:` remains successful content.

## MCP Servers

<!-- BEGIN GENERATED MCP SERVERS -->
| Server | Tools |
| --- | ---: |
| [eclipse](docs/mcp-tools/eclipse.md) | 13 |
| [eclipse-codegen](docs/mcp-tools/eclipse-codegen.md) | 3 |
| [eclipse-coverage](docs/mcp-tools/eclipse-coverage.md) | 3 |
| [eclipse-debug](docs/mcp-tools/eclipse-debug.md) | 23 |
| [eclipse-ide](docs/mcp-tools/eclipse-ide.md) | 31 |
| [eclipse-refactor](docs/mcp-tools/eclipse-refactor.md) | 16 |
| [eclipse-source](docs/mcp-tools/eclipse-source.md) | 8 |
<!-- END GENERATED MCP SERVERS -->

[Full tool catalogue](docs/mcp-tools.md) includes descriptions, schema types, Java types, and required parameters.

Regenerate or verify documentation using JDK 21 from the repository root:

```bash
java scripts/GenerateToolCatalog.java
java scripts/GenerateToolCatalog.java --check
java scripts/TestToolCatalog.java
```

The catalogue reads the builtin registry and annotations directly from Java syntax trees, including annotation defaults. CI checks for documentation drift before downloading dependencies.

Java/JUnit/TestNG launch configuration creation, inspection, and environment updates are available in `eclipse-debug`. `createJavaLaunchConfiguration` accepts `type=java|junit4|junit5|testng|auto`; the default remains `java`, and TestNG requires the TestNG Eclipse plugin.

## Installation

### From Update Site

1. In Eclipse IDE, open *Help > Install new software*
2. Click *Add* button
3. Enter:
   - Name: `EclipseCtl MCP`
   - Location: `https://jrame.github.io/eclipsectl-mcp-plugin/`
4. Click *Add*
5. Select "EclipseCtl MCP" from the plugin list
6. Click *Next* and follow the installation wizard
7. Accept certificate warnings if prompted
8. Restart Eclipse when prompted

### From Source

#### Building in Eclipse

The existing Eclipse PDE workflow remains available. Import the projects into an
Eclipse installation with PDE and JDK 21, open `site/site.xml`, then click
**Build All** in the update-site editor. This builds the update site under `site/`.
Keep `site/site.xml` in Git; its generated plugin JARs and p2 metadata are ignored.

On a fresh clone, first download the embedded dependencies using the bootstrap
command below, then refresh the projects in Eclipse. The Maven/Tycho build is an
additional option for automated builds and tests; it is not required for each
manual PDE build once the dependencies are available.

#### Automated build and tests from a fresh clone

Install JDK 21. The checked-in Maven Wrapper downloads Maven 3.9.9.

Windows PowerShell, from the repository root:

```powershell
.\mvnw.cmd -B -f build/dependencies/pom.xml validate
.\mvnw.cmd -B clean verify
```

Linux GTK x86_64, with Xvfb installed:

```bash
bash ./mvnw -B -f build/dependencies/pom.xml validate
xvfb-run -a bash ./mvnw -B clean verify
```

The bootstrap downloads the explicitly versioned published libraries listed in
`build/dependencies/pom.xml` before Tycho reads the bundle classpath.
The reactor compiles both bundles, runs the entire JUnit 5 PDE suite, and builds
the feature and p2 update site in `repository/target/repository/`.
Install this directory with Eclipse **Help > Install New Software > Add > Local**.
The target is the dated Eclipse 2025-09 repository. Supported build and test
platforms are Windows x86_64 and Linux GTK x86_64; other platforms are unverified.

The build uses source and fixed dependency versions; it does not use precompiled
`bin/`, `site/plugins/`, or an existing Eclipse installation.
Generated p2 qualifiers contain build time so subsequent installations can update.
The PDE workbench uses Tycho's isolated workspace under
`tests/plugin.eclipsectl.mcp.main.tests/target/work/data/`.
Never point the test runtime at your development workspace. Test reports are under
the test module's `target/surefire-reports/`.
EclEmma is optional and absent from this target: the coverage tests exercise
the unavailable-plugin response, not actual instrumentation.

GitHub Actions runs the same bootstrap and `clean verify` for pull requests
and main pushes. Publishing waits for success and uploads the generated p2
repository and static homepage. Pull requests have read-only repository
permission and cannot publish Pages.

## Configuration

### Enabling the HTTP MCP Server

1. **Open Preferences**
   Navigate to *Window > Preferences > EclipseCtl MCP > HTTP MCP Server*

2. **Configure Server**
   - Check **Enable HTTP MCP Server**
   - Set **Hostname** (default: `localhost`)
   - Set **Port** (default: `8124`)
   - Click **Generate** to create an authentication token
   - Click **Apply** to start the server

Every request to `/mcp/*` requires `Authorization: Bearer <token>`, including
session creation, streaming, and session deletion. Missing or incorrect credentials
receive HTTP 401 with a Bearer challenge. An empty or whitespace-only token disables
access to MCP endpoints (HTTP 503); generate a token and apply the preferences to
restore access. Leaving the token empty never enables anonymous access.

3. **Verify Status**
   The status section will show "HTTP Server is running" when active

4. **Available Endpoints**
   - `http://localhost:8124/mcp/eclipse-ide`
   - `http://localhost:8124/mcp/eclipse`
   - `http://localhost:8124/mcp/eclipse-codegen`
   - `http://localhost:8124/mcp/eclipse-refactor`
   - `http://localhost:8124/mcp/eclipse-source`
   - `http://localhost:8124/mcp/eclipse-debug`
   - `http://localhost:8124/mcp/eclipse-coverage`


## Integration with AI Clients

### Claude Desktop

Edit your Claude Desktop configuration file:

**Windows**: `%APPDATA%\Roaming\Claude\claude_desktop_config.json`
**macOS**: `~/Library/Application Support/Claude/claude_desktop_config.json`
**Linux**: `~/.config/Claude/claude_desktop_config.json`

Add MCP server entries:

```json
{
  "mcpServers": {
    "eclipse-ide": {
      "command": "npx",
      "args": [
        "-y", "mcp-remote",
        "http://localhost:8124/mcp/eclipse-ide",
        "--allow-http",
        "--header", "Authorization: Bearer YOUR_TOKEN_HERE"
      ]
    },
    "eclipse-codegen": {
      "command": "npx",
      "args": [
        "-y", "mcp-remote",
        "http://localhost:8124/mcp/eclipse-codegen",
        "--allow-http",
        "--header", "Authorization: Bearer YOUR_TOKEN_HERE"
      ]
    }
  }
}
```

**Windows WSL Users**: Use `"command": "wsl"` and add `"npx"` as the first arg.

Replace `YOUR_TOKEN_HERE` with the token from Eclipse preferences.

Restart Claude Desktop after making changes.

### Claude Code (CLI)

Create or edit `~/.config/claude-code/mcp_settings.json`:

```json
{
  "mcpServers": {
    "eclipse-ide": {
      "command": "npx",
      "args": [
        "-y", "mcp-remote",
        "http://localhost:8124/mcp/eclipse-ide",
        "--allow-http",
        "--header", "Authorization: Bearer YOUR_TOKEN_HERE"
      ]
    }
  }
}
```

### Cursor

Cursor can connect to MCP servers via the same mcp-remote approach. Configure in Cursor's settings.

## Use Cases

With EclipseCtl MCP, AI assistants can:

- **Understand your codebase**: Search for types, find references, analyze call hierarchies
- **Write code**: Generate boilerplate, implement interfaces, create tests
- **Refactor safely**: Use Eclipse JDT to rename symbols, change signatures, extract or inline code, and update references. The additional position-based refactorings use zero-based UTF-16 offsets in saved Java source; save pending changes and recompute positions after each edit. See [the refactoring tool schemas](docs/mcp-tools/eclipse-refactor.md).
- **Fix bugs**: Read compilation errors, apply Quick Fixes, run tests
- **Debug issues**: Set breakpoints, inspect variables, evaluate expressions
- **Analyze coverage**: Run tests with coverage, identify untested code
- **Build projects**: Run Eclipse builds with completion and problem reports, or Maven goals

All within your existing Eclipse environment, preserving your project history, configurations, and workspace state.

### Preview, refactor, and verify compilation

`refactorRenameVariable` accepts `oldName` with a 1-based `lineNumber` and/or an
enclosing `methodName`, instead of an `offset`. JDT resolves declarations and
references to a single local variable or parameter; comments and string literals
are ignored. Repeated uses of the same variable count as one target, while
different bindings (including overloaded methods or separate blocks) are refused
until the selection is narrowed. Named selection accepts a Java file path or a
unique simple class name such as `FooTest`; duplicate files require an explicit
path/project. `offset` and `lineNumber` use JSON integers. Offset selection is
exclusive with all named selectors. Selection, validation and change creation
share the workspace lock and support the same frozen preview/apply lifecycle.

The position-based tools (`refactorRenameMethod`, `refactorRenameField`,
`refactorRenameVariable`, `refactorChangeSignature`, `refactorExtractVariable`,
`refactorExtractConstant`, `refactorInlineMethod`, `refactorInlineVariable`) accept
`preview=true`. This validates saved source and returns affected projects, files,
a unified diff, and a `Preview ID` without applying the change. Call
`refactorApplyPreview` with that `previewId` to apply the retained Eclipse change
exactly as previewed, including its reference edits across files and projects.
All affected files and the selected source are checked before any writes: changed
disk contents (including external edits), unsaved editors/buffers, and unsaved Java
working copies reject the entire apply attempt. Eclipse refactoring undo is preserved.
The token identifies the operation and its arguments; no selection or reference
search is repeated during apply. References introduced later in other files are
not added to the frozen change, so build afterward and create a new preview when
the workspace changes.

Tokens are local to this running service, expire after 15 minutes, and are consumed
on an apply attempt, including a stale-source failure. At most 20 previews are
retained; older previews are evicted. Unknown, expired, or used tokens require a
new preview. Reports larger than 100,000 characters are paged without losing diff
content. Follow `refactorGetPreview(previewId, offset)` using each returned next
offset; applying is refused until all report pages have been returned in order.
Diff line numbers are 1-based; report character offsets are zero-based.

For compatibility, omitting `preview` or setting it to `false` still applies a newly
calculated refactoring directly against current source. Its output explicitly says
that it is not bound to a preview. Use `refactorApplyPreview` for preview protection.

For example, to rename a method, inspect its references, and verify compilation:

1. Call `eclipse-refactor.refactorRenameMethod` with `projectName="MyApp"`,
   `filePath="src/example/Service.java"`, `offset=<UTF-16 name offset>`,
   `newName="calculate"`, and `preview=true`.
2. Inspect the returned diff (follow any remaining report pages), then call
   `eclipse-refactor.refactorApplyPreview` with the returned `previewId`.
3. Call `eclipse-ide.buildProjects` with `projectNames="MyApp"` (or the comma-separated
   affected project names from the report), `buildKind="incremental"`, and
   `timeoutSeconds=120`. Dependencies and dependent projects are included by default;
   use `includeDependents=false` to omit dependents. Omit `projectNames` to build all
   open projects. Use `buildKind="full"` to force full builds.

Builds refresh files from disk and compile saved resources through Eclipse builders;
they do not save unsaved editors or run Maven goals. The timeout includes waiting
for the workspace scheduling rule. A completed build with error markers returns
MCP `isError=true`; warnings alone do not. `maxProblems` limits displayed details
(default: 100, maximum: 500), while totals include all error and warning markers.

## Security Considerations

- **Local Network Only**: By default, binds to `localhost`. Only expose externally if necessary.
- **Authentication Required**: Always use an authentication token when exposing beyond localhost.
- **Firewall Protection**: Ensure firewall allows connections only from trusted sources.
- **Full Access**: AI clients have full access to all exposed MCP tools - they can read, modify, and delete files.
- **No Undo**: Some operations (refactoring, file deletion) cannot be easily reversed - use version control.
- **HTTPS**: Consider using a reverse proxy with HTTPS for production use.

## Development

See [CLAUDE.md](CLAUDE.md) for development guidelines and architecture documentation.

## License

MIT License

Copyright (c) 2026 Metherlance
Based on Copyright (c) 2023 Wojciech Gradkowski (original AssistAI project, kept only MCP structure + HTTP)

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## Credits

This project is based on [AssistAI](https://github.com/gradusnikov/eclipse-chatgpt-plugin) by Wojciech Gradkowski, which originally provided both an AI chat interface and MCP server capabilities for Eclipse. This fork focuses exclusively on the MCP server functionality.

## Contributing

Contributions are welcome! Please feel free to submit issues or pull requests.

## Resources

- [Model Context Protocol](https://modelcontextprotocol.io/)
- [Original AssistAI Project](https://github.com/gradusnikov/eclipse-chatgpt-plugin)
- [Eclipse JDT](https://www.eclipse.org/jdt/)

### Text edit safety

Text replacements preserve untouched CRLF, LF and mixed line endings and do not add a final newline. Line replacement and insertion use the adjacent delimiter when a separator is needed; supplied content keeps its own delimiters. Line ranges are 1-based and inclusive and must be within the file; insertion accepts lines 1 through line-count + 1 (append). An empty file has one editable line.

Disk edits refuse files with unsaved editor or connected text-buffer changes. Save or discard those changes before retrying. Files with external changes or changes since the edit snapshot are refused; refresh and review them before retrying. Writes retain Eclipse local history and do not force overwrites. Editor reloads skip unsaved documents.
