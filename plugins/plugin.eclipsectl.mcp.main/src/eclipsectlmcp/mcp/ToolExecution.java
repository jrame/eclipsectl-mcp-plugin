package eclipsectlmcp.mcp;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit error status for a single tool invocation, independent of its text. */
public final class ToolExecution
{
    public record Result(Object value, boolean isError) {}

    private static final ThreadLocal<AtomicBoolean> CURRENT = new ThreadLocal<>();

    private ToolExecution() {}

    public static Result capture(Callable<?> action) throws Exception
    {
        AtomicBoolean previous = CURRENT.get();
        AtomicBoolean status = new AtomicBoolean();
        CURRENT.set(status);
        try
        {
            Object value = action.call();
            return new Result(value, status.get());
        }
        finally
        {
            restore(previous);
        }
    }

    /** Marks a business failure while preserving the service's human-readable contract. */
    public static String error(String message)
    {
        AtomicBoolean status = CURRENT.get();
        if (status != null)
            status.set(true);
        return message;
    }

    /** Carries invocation status across the Eclipse UI thread boundary. */
    public static Runnable propagate(Runnable action)
    {
        AtomicBoolean status = CURRENT.get();
        return () -> {
            AtomicBoolean previous = CURRENT.get();
            restore(status);
            try
            {
                action.run();
            }
            finally
            {
                restore(previous);
            }
        };
    }

    private static void restore(AtomicBoolean status)
    {
        if (status == null)
            CURRENT.remove();
        else
            CURRENT.set(status);
    }
}
