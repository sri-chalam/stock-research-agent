package info.saagar.stockagent.portfolio.tool;

/// The user a `tools/call` runs for: the `sub` of the request's validated bearer token, bound by
/// the MCP controller.
///
/// Tools read the user from here, never from a tool argument, so the model cannot choose whose
/// portfolio it reads (SEC-6).
public final class PortfolioUser {

  /// The user ID (token `sub`) for the current tool call. `McpServer` runs tools on the calling
  /// thread, so a binding made around `McpServer.handle` reaches the tool. The controller binds it
  /// for every `tools/call` it lets through.
  public static final ScopedValue<String> USER_ID = ScopedValue.newInstance();

  private PortfolioUser() {}
}
