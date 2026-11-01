package eclipsectlmcp.tests.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import eclipsectlmcp.mcp.McpServerBuiltins;
import eclipsectlmcp.mcp.McpServerFactory;
import eclipsectlmcp.mcp.ToolExecutor;
import eclipsectlmcp.mcp.annotations.Tool;
import eclipsectlmcp.mcp.annotations.ToolParam;
import io.modelcontextprotocol.spec.McpSchema;

@Timeout(15)
public class ToolParameterContractTest
{
    @Test
    public void everyBuiltinSchemaAndMappedArgumentMatchesItsJavaSignature() throws Exception
    {
        var factory = new McpServerFactory();
        Method extract = McpServerFactory.class.getDeclaredMethod("extractAnnotatedTools", Method[].class);
        extract.setAccessible(true);
        int checked = 0;
        for (Class<?> serverType : McpServerBuiltins.BUILT_IN_MCP_SERVERS) {
            var executor = new ToolExecutor(serverType.getConstructor().newInstance());
            for (Method method : executor.getFunctions()) {
                @SuppressWarnings("unchecked")
                var tools = (List<McpSchema.Tool>) extract.invoke(factory, (Object) new Method[] {method});
                var schema = tools.getFirst().inputSchema();
                var nativeArgs = new HashMap<String, Object>();
                var legacyArgs = new HashMap<String, Object>();
                var requiredArgs = new HashMap<String, Object>();
                for (var parameter : method.getParameters()) {
                    String name = ToolExecutor.toParamName(parameter);
                    Class<?> javaType = parameter.getType();
                    var annotation = parameter.getAnnotation(ToolParam.class);
                    String type = (String) ((Map<?, ?>) schema.properties().get(name)).get("type");
                    String expected = javaType == String.class ? "string"
                            : javaType == Integer.class || javaType == int.class ? "integer"
                            : javaType == Boolean.class || javaType == boolean.class ? "boolean"
                            : Map.class.isAssignableFrom(javaType) ? "object"
                            : List.class.isAssignableFrom(javaType) ? "array"
                            : javaType == Object.class ? type : null;
                    assertNotNull(expected, method + ": unhandled Java parameter " + name);
                    assertEquals(expected, type, method + ": " + name);
                    assertEquals(annotation.required(), schema.required().contains(name));
                    assertFalse(javaType.isPrimitive() && !annotation.required(), method + ": optional primitive " + name);
                    Object value = switch (type) {
                        case "integer" -> 7;
                        case "boolean" -> false;
                        case "string" -> "example";
                        case "object" -> Map.of("EXAMPLE", "value");
                        case "array" -> List.of("example");
                        default -> throw new AssertionError("Unhandled schema type " + type);
                    };
                    nativeArgs.put(name, value);
                    legacyArgs.put(name, type.equals("integer") || type.equals("boolean") ? value.toString() : value);
                    if (annotation.required()) requiredArgs.put(name, value);
                }
                Object[] mapped = executor.mapArguments(method, nativeArgs);
                assertArrayEquals(mapped, executor.mapArguments(method, legacyArgs), method.toString());
                Object[] minimal = executor.mapArguments(method, requiredArgs);
                for (int i = 0; i < mapped.length; i++) {
                    Class<?> type = method.getParameterTypes()[i];
                    if (type == int.class) type = Integer.class;
                    if (type == boolean.class) type = Boolean.class;
                    assertTrue(type.isInstance(mapped[i]), method + ": argument " + i);
                    if (!method.getParameters()[i].getAnnotation(ToolParam.class).required()) assertNull(minimal[i]);
                }
                checked++;
            }
        }
        assertEquals(98, checked);
    }

    public static class ScalarTools {
        @Tool(name="scalars", description="Scalar reflection fixture")
        public String scalars(@ToolParam(name="line", description="Line", type="integer") int line,
                @ToolParam(name="enabled", description="Enabled", type="boolean", required=false) Boolean enabled) {
            return line + "/" + enabled;
        }
    }

    @Test
    public void legacyScalarsAreInvocableAndInvalidInputsFailBeforeReflection() throws Exception
    {
        var server = new ScalarTools();
        var executor = new ToolExecutor(server);
        var method = executor.getFunctions()[0];
        assertEquals("7/false", method.invoke(server, executor.mapArguments(method, Map.of("line", "7", "enabled", "false"))));
        assertEquals("7/null", method.invoke(server, executor.mapArguments(method, Map.of("line", 7L))));
        for (Object invalid : List.of("1.5", 2147483648L, true)) {
            assertThrows(IllegalArgumentException.class, () -> executor.mapArguments(method, Map.of("line", invalid)));
        }
        for (Object invalid : List.of("yes", "0", 1)) {
            assertThrows(IllegalArgumentException.class, () -> executor.mapArguments(method, Map.of("line", 7, "enabled", invalid)));
        }
        assertThrows(IllegalArgumentException.class, () -> executor.mapArguments(method, Map.of()));
    }
}
