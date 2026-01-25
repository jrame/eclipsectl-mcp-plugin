package eclipsectlmcp.mcp.annotations;


import java.lang.annotation.RetentionPolicy;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

@Retention( RetentionPolicy.RUNTIME )
@Target( ElementType.TYPE )
public @interface McpServer
{

    String name();

    String version() default "1.0.0";

    String description() default "";

}
