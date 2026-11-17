package info.saagar.stockagent.tool;

import info.saagar.stockagent.logging.McpCorrelationHeaders;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.MDC;

/// Supplies the headers for each MCP request to `portfolio-mcp`: the correlation headers plus
/// `X-User-Id`, so the portfolio tools read the chat user's portfolio.
///
/// The user ID travels in code, never in prompt text (SEC-4). `ChatController` puts it in the MDC
/// under [#USER_ID_MDC_KEY], and the MCP transport calls this on the request thread. Only the
/// `portfolio-mcp` client uses this supplier, so the user ID never reaches the other MCP servers.
public class McpUserHeaders implements Supplier<Map<String, String>> {

  /// Header that identifies the user, on `POST /api/chat` and on MCP calls to `portfolio-mcp`.
  public static final String USER_ID_HEADER = "X-User-Id";

  /// MDC key of the chat request's user ID.
  public static final String USER_ID_MDC_KEY = "userId";

  private final McpCorrelationHeaders mcpCorrelationHeaders = new McpCorrelationHeaders();

  /// Reads the correlation, trace and user IDs from the current thread's MDC.
  ///
  /// @return the headers to send, without any whose ID is missing
  @Override
  public Map<String, String> get() {
    Map<String, String> correlationHeaders = mcpCorrelationHeaders.get();
    String userId = MDC.get(USER_ID_MDC_KEY);
    if (userId == null) {
      return correlationHeaders;
    }
    Map<String, String> userHeaders = new HashMap<>(correlationHeaders);
    userHeaders.put(USER_ID_HEADER, userId);
    return userHeaders;
  }
}
