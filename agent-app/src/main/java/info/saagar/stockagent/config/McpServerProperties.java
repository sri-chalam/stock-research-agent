package info.saagar.stockagent.config;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/// MCP server connection settings bound from `stock-agent.mcp-servers.<serverName>`.
///
/// @param mcpServers settings keyed by server name, e.g. `market-data`
@ConfigurationProperties("stock-agent")
public record McpServerProperties(Map<String, McpServerSettings> mcpServers) {

  /// Connection settings for one MCP server.
  ///
  /// @param url Streamable HTTP endpoint, e.g. `http://localhost:8081/mcp`
  /// @param protocolVersion MCP protocol version to initialize with; pinning it skips the
  ///   `server/discover` probe that our servers do not support
  /// @param toolExecutionTimeout how long one `tools/call` may take
  public record McpServerSettings(
      String url, String protocolVersion, Duration toolExecutionTimeout) {}

  /// Returns the settings for an MCP server.
  ///
  /// @param serverName server key in `application.yml`
  /// @return the server's settings
  /// @throws IllegalStateException when the server is not configured
  public McpServerSettings forServer(String serverName) {
    McpServerSettings serverSettings = mcpServers == null ? null : mcpServers.get(serverName);
    if (serverSettings == null) {
      throw new IllegalStateException("No settings configured for MCP server " + serverName);
    }
    return serverSettings;
  }
}
