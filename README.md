# EclipseCtl MCP - Model Context Protocol Server for Eclipse IDE

EclipseCtl MCP is an Eclipse IDE plugin that exposes Eclipse functionality as a Model Context Protocol (MCP) server. This allows AI assistants like Claude Desktop, Claude Code, or Cursor to interact directly with Eclipse projects through standardized MCP tools.

## Origin

This project is a fork of [AssistAI](https://github.com/gradusnikov/eclipse-chatgpt-plugin) by Wojciech Gradkowski. The original project provided both an AI chat interface within Eclipse and MCP server capabilities. This fork has removed the AI chat interface and focuses exclusively on providing a standalone MCP server that exposes Eclipse IDE functionality to external AI clients.

## Features

EclipseCtl MCP exposes **85 MCP tools** across 7 main servers, allowing AI assistants to:

- **Manage Projects**: List projects, import new projects, read project properties and structure
- **Code Analysis**: Search types, find references, get type hierarchies, analyze call hierarchies
- **Code Generation**: Generate getters/setters, toString/equals/hashCode, implement interfaces
- **Refactoring**: Rename types/packages, move classes, extract methods
- **Testing**: Run JUnit tests (all, package, class, or specific methods), find test classes
- **Debugging**: Create and inspect Java, JUnit 4/5, and TestNG launch configurations, manage launch environments and breakpoints, control debug sessions, inspect variables, evaluate expressions
- **Code Coverage**: Launch tests with coverage analysis, get coverage reports (requires EclEmma)
- **Maven**: Run Maven builds, get effective POM, list dependencies
- **Source Operations**: Organize imports, format code
- **Search**: Full-text search, regex search, file pattern matching
- **Quick Fixes**: Get and apply Eclipse Quick Fixes for compilation errors
- **Editor Access**: Get current file, selection, console output
- **Editor Selection Sharing**: Use the "Share Editor Selection" command (F9) to optionally copy formatted selections to the clipboard, send them to tmux, or both

## MCP Servers

### Core Servers

#### eclipse-ide (29 tools)
Project management, testing, Maven, search, file operations, editor access, console output, directory creation, search and replace

#### eclipse (13 tools)
Type search, call hierarchy, references, type hierarchy, implementations, problems, markers, Quick Fixes, refresh

#### eclipse-codegen (3 tools)
Generate getters/setters, toString/equals/hashCode, implement/override methods

#### eclipse-refactor (6 tools)
Rename files/types/packages, move types/resources, extract methods

#### eclipse-source (8 tools)
Organize imports, format code

#### eclipse-debug (23 tools)
Breakpoint management, thread/process management, debug session control, runtime inspection, and Java/JUnit/TestNG launch configuration creation, inspection, and environment updates. `createJavaLaunchConfiguration` accepts `type=java|junit4|junit5|testng|auto`; the default remains `java`, and TestNG requires the TestNG Eclipse plugin.

#### eclipse-coverage (3 tools)
Coverage analysis with EclEmma (requires EclEmma plugin)

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

#### Building the Plugin

1. Clone the repository:
   ```bash
   git clone https://github.com/jrame/eclipsectl-mcp-plugin.git
   cd eclipsectl-mcp-plugin
   ```

2. Build using the Eclipse update site:
   - Open Eclipse IDE
   - Import the project as an existing project
   - Open `site/site.xml`
   - Click **Build All** to build the plugin JARs

3. Install the plugin using the `dropins` folder:
   - Locate the generated JAR files in:
     - `plugins/plugin.eclipsectl.mcp.main/` (main plugin JAR)
     - `plugins/plugin.eclipsectl.mcp.dependencies/` (dependencies JAR)
     - `features/feature.eclipsectl.mcp/` (feature JAR)
   - Copy all JARs to your Eclipse installation's `dropins` folder:
     - **Windows**: `C:\eclipse\dropins\`
     - **macOS**: `/Applications/Eclipse.app/Contents/Eclipse/dropins/`
     - **Linux**: `/opt/eclipse/dropins/` (or your Eclipse install location)
   - Restart Eclipse to load the plugin

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
- **Refactor safely**: Rename types/packages, move classes with reference updates
- **Fix bugs**: Read compilation errors, apply Quick Fixes, run tests
- **Debug issues**: Set breakpoints, inspect variables, evaluate expressions
- **Analyze coverage**: Run tests with coverage, identify untested code
- **Build projects**: Run Maven goals, check dependencies, get effective POM

All within your existing Eclipse environment, preserving your project history, configurations, and workspace state.

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
