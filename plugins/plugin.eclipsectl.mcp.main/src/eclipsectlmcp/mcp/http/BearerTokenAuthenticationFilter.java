package eclipsectlmcp.mcp.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Authenticates every request before it reaches an MCP transport servlet. */
public final class BearerTokenAuthenticationFilter implements Filter
{
    private final byte[] expectedToken;

    public BearerTokenAuthenticationFilter(String token)
    {
        // Empty preferences must never silently expose unauthenticated tools.
        expectedToken = token == null || token.isBlank() ? null : token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException
    {
        var httpRequest = (HttpServletRequest) request;
        var httpResponse = (HttpServletResponse) response;
        if (expectedToken == null)
        {
            httpResponse.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            httpResponse.setContentType("text/plain;charset=UTF-8");
            httpResponse.getWriter().write("HTTP MCP authentication token is not configured.");
            return;
        }

        String header = httpRequest.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)
                || !MessageDigest.isEqual(expectedToken, header.substring(7).getBytes(StandardCharsets.UTF_8)))
        {
            httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            httpResponse.setHeader("WWW-Authenticate", "Bearer realm=\"EclipseCtl MCP\"");
            httpResponse.setContentType("text/plain;charset=UTF-8");
            httpResponse.getWriter().write("A valid Bearer authentication token is required.");
            return;
        }
        chain.doFilter(request, response);
    }
}
