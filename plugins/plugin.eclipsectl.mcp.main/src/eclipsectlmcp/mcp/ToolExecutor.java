package eclipsectlmcp.mcp;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import eclipsectlmcp.mcp.annotations.Tool;
import eclipsectlmcp.mcp.annotations.ToolParam;

public class ToolExecutor
{
    Object functions;
    
    public ToolExecutor( Object functions )
    {
        this.functions = functions;
    }
    
    /**
     * Retrieves an array of {@link Method}s that are declared as a function_call
     * callback with the {@link Tool} annotation.
     * 
     * @return
     */
    public Method[] getFunctions()
    {
        return Arrays.stream( functions.getClass().getDeclaredMethods() )
                .filter( method -> Objects.nonNull( method.getAnnotation( eclipsectlmcp.mcp.annotations.Tool.class ) ) )
                .toArray( Method[]::new );
    }
    
    

    public CompletableFuture<Object> call( String name, Map<String, Object> args )
    {
        Method method = getFunctionCallbackByName( name ).orElseThrow( () -> new RuntimeException("Tool " + name + " not found!" ) ); 
        method.getAnnotationsByType( eclipsectlmcp.mcp.annotations.ToolParam.class );
        Object[] argValues = mapArguments( method, args );
        CompletableFuture<Object> future = CompletableFuture.supplyAsync( () -> {
            try { return ToolExecution.capture( () -> invokeMethod( method, argValues ) ); }
            catch (Exception e) { throw new java.util.concurrent.CompletionException(e); }
        } );
        return future;
    }
    private Object invokeMethod( Method method, Object[] args )
    {
        try
        {
            return method.invoke( functions, args );
        }
        catch ( IllegalAccessException | IllegalArgumentException | InvocationTargetException e )
        {
            throw new RuntimeException( e );
        }
        
    }
    
    public CompletableFuture<Object> call( String name, String[] args )
    {
        return call( name, toMap(args) );
    }
    
    /**
     * Creates an array of parameter values as declared by the callback {@link Method}
     * 
     * @param method
     * @param argMap
     * @return
     */
    public Object[] mapArguments( Method method, Map<String, Object> argMap )
    {
        return Arrays.stream( method.getParameters() )
                    .map( parameter -> mapArgument( parameter, argMap.get( toParamName( parameter ) ) ) )
                    .toArray();
        
    }

    /** Accept native JSON scalars and legacy string clients without truncation. */
    private static Object mapArgument( Parameter parameter, Object value )
    {
        Class<?> type = parameter.getType();
        String name = toParamName(parameter);
        if (value == null)
        {
            if (type.isPrimitive()) throw new IllegalArgumentException("Missing argument '" + name + "'");
            return null;
        }
        if (type == Integer.class || type == int.class)
        {
            if (value instanceof Integer) return value;
            if (value instanceof Number || value instanceof String)
            {
                try { return new BigDecimal(value.toString()).intValueExact(); }
                catch (NumberFormatException | ArithmeticException e) { /* Report no argument values. */ }
            }
            throw new IllegalArgumentException("Argument '" + name + "' must be a 32-bit integer");
        }
        if (type == Boolean.class || type == boolean.class)
        {
            if (value instanceof Boolean) return value;
            if (value instanceof String text && (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false")))
                return Boolean.valueOf(text);
            throw new IllegalArgumentException("Argument '" + name + "' must be a boolean");
        }
        return value;
    }
    /**
     * Converts a String array of key-value pairs into a Map.
     * 
     * @param keyVal the String array of key-value pairs
     * @return the Map representation of the key-value pairs
     * @throws IllegalArgumentException if the input array is not a key-value array
     */
    public Map<String, Object> toMap( String[] keyVal )
    {
        if ( keyVal.length % 2 != 0 )
        {
            throw new IllegalArgumentException("Not a key-val array");
        }
        var map = new HashMap<String, Object>();
        for (int i = 0; i < keyVal.length; i += 2) 
        {
            map.put(keyVal[i], keyVal[i + 1]);
        }
        return map;
    }

    /**
     * Retrieves the function callback method with the specified name.
     *
     * @param name the name of the function
     * @return an Optional containing the function callback method, or an empty Optional if the function is not found
     */
    public Optional<Method> getFunctionCallbackByName( String name )
    {
        return Arrays.stream( getFunctions() )
                     .filter( method -> toFunctionName( method ).equals( name ) )
                     .findFirst();
    }
    /**
     * Converts a Parameter object to its corresponding parameter name.
     *
     * @param parameter the Parameter object
     * @return the parameter name, or the annotated name if present, or the default name if no annotation is found
     */
    public static String toParamName( Parameter parameter )
    {
        return Optional.ofNullable( parameter.getAnnotation( eclipsectlmcp.mcp.annotations.ToolParam.class ) )
                    .map( ToolParam::name )
                    .filter( Predicate.not( String::isBlank ) )
                    .orElse( parameter.getName() );
    }
    /**
     * Retrieves the name of the function based on the provided Method object.
     *
     * @param method the Method object representing the function
     * @return the name of the function, or the annotated name if present, or the default name if no annotation is found
     */
    public static String toFunctionName( Method method )
    {
        return Optional.ofNullable( method.getAnnotation( eclipsectlmcp.mcp.annotations.Tool.class ) )
                .map( Tool::name )
                .filter( Predicate.not(String::isBlank))
                .orElse( method.getName() );
    }
    
}
