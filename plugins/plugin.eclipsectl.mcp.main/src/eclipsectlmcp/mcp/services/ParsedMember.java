package eclipsectlmcp.mcp.services;

import java.util.List;

/**
 * Represents a parsed Java member (method, field, or inner type).
 */
public class ParsedMember {
    public String kind;
    public String name;
    public String source;
    public List<String> paramTypes;
}
