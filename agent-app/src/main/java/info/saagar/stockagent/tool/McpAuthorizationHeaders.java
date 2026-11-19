package info.saagar.stockagent.tool;

import info.saagar.stockagent.logging.McpCorrelationHeaders;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/// Supplies the headers for each MCP request: the correlation headers plus `Authorization: Bearer`
/// with the chat user's access token, forwarded unchanged (Option A, ADR 0001), so `portfolio-mcp`
/// reads the user from the token's `sub`.
///
/// The token travels in code, never in prompt text or the MDC, so it reaches neither the model nor
/// a log line (SEC-4, SEC-5). The MCP transport calls this on the request thread, where Spring
/// Security holds the validated token. Outside a chat request, as for the client's startup
/// `initialize` and its health-check `ping`, there is no token and only the correlation headers are
/// sent.
public class McpAuthorizationHeaders implements Supplier<Map<String, String>> {

  private final McpCorrelationHeaders mcpCorrelationHeaders = new McpCorrelationHeaders();

  /// Reads the correlation and trace IDs from the MDC and the access token from the security
  /// context of the current thread.
  ///
  /// @return the headers to send, without any whose value is missing
  @Override
  public Map<String, String> get() {
    Map<String, String> correlationHeaders = mcpCorrelationHeaders.get();
    if (!(SecurityContextHolder.getContext().getAuthentication()
        instanceof JwtAuthenticationToken userToken)) {
      return correlationHeaders;
    }
    Map<String, String> authorizedHeaders = new HashMap<>(correlationHeaders);
    authorizedHeaders.put(
        HttpHeaders.AUTHORIZATION, "Bearer " + userToken.getToken().getTokenValue());
    return authorizedHeaders;
  }
}
