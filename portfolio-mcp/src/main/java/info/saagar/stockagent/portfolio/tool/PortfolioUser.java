package info.saagar.stockagent.portfolio.tool;

import java.util.Optional;
import java.util.regex.Pattern;

/// The user a `tools/call` runs for, bound by the MCP controller from the `X-User-Id` header.
///
/// Tools read the user from here, never from a tool argument, so the model cannot choose whose
/// portfolio it reads (SEC-6). From Iteration 5 the token's `sub` replaces the header.
public final class PortfolioUser {

  /// Request header that identifies the user.
  public static final String USER_ID_HEADER = "X-User-Id";

  /// The user ID for the current tool call; unbound when no valid header was sent. `McpServer` runs
  /// tools on the calling thread, so a binding made around `McpServer.handle` reaches the tool.
  public static final ScopedValue<String> USER_ID = ScopedValue.newInstance();

  private static final Pattern SAFE_USER_ID_PATTERN = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

  private PortfolioUser() {}

  /// Parses the header value. A missing, blank or unsafe value identifies no user, so it never
  /// reaches a query or a log line.
  ///
  /// @param userIdHeaderValue raw `X-User-Id` value, or `null` when the header is absent
  /// @return the user ID, or empty when the value does not identify a user
  public static Optional<String> fromHeader(String userIdHeaderValue) {
    return userIdHeaderValue != null && SAFE_USER_ID_PATTERN.matcher(userIdHeaderValue).matches()
        ? Optional.of(userIdHeaderValue)
        : Optional.empty();
  }
}
