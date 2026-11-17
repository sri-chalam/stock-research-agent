package info.saagar.stockagent.config;

import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import info.saagar.stockagent.logging.McpCorrelationHeaders;
import info.saagar.stockagent.tool.McpUserHeaders;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/// MCP clients for our MCP servers.
///
/// Each client initializes in its constructor, so the app fails to start when one of the servers is
/// not reachable. Each request carries the caller's correlation and trace IDs; only requests to
/// `portfolio-mcp` also carry the chat user's `X-User-Id`.
@Configuration
@EnableConfigurationProperties(McpServerProperties.class)
public class McpClientConfig {

  /// Key of the market-data MCP server's settings in `application.yml`.
  public static final String MARKET_DATA_SERVER_NAME = "market-data";

  /// Key of the filings MCP server's settings in `application.yml`.
  public static final String FILINGS_SERVER_NAME = "filings";

  /// Key of the portfolio MCP server's settings in `application.yml`.
  public static final String PORTFOLIO_SERVER_NAME = "portfolio";

  /// Builds the market-data MCP client.
  ///
  /// @param mcpServerProperties MCP server settings
  /// @return the connected client; Spring closes it on shutdown
  @Bean
  public McpClient marketDataMcpClient(McpServerProperties mcpServerProperties) {
    return connect(MARKET_DATA_SERVER_NAME, mcpServerProperties, new McpCorrelationHeaders());
  }

  /// Builds the filings MCP client.
  ///
  /// @param mcpServerProperties MCP server settings
  /// @return the connected client; Spring closes it on shutdown
  @Bean
  public McpClient filingsMcpClient(McpServerProperties mcpServerProperties) {
    return connect(FILINGS_SERVER_NAME, mcpServerProperties, new McpCorrelationHeaders());
  }

  /// Builds the portfolio MCP client, the only one that forwards the chat user's `X-User-Id`.
  ///
  /// @param mcpServerProperties MCP server settings
  /// @return the connected client; Spring closes it on shutdown
  @Bean
  public McpClient portfolioMcpClient(McpServerProperties mcpServerProperties) {
    return connect(PORTFOLIO_SERVER_NAME, mcpServerProperties, new McpUserHeaders());
  }

  private static McpClient connect(
      String serverName,
      McpServerProperties mcpServerProperties,
      Supplier<Map<String, String>> requestHeaders) {
    McpServerProperties.McpServerSettings serverSettings =
        mcpServerProperties.forServer(serverName);
    StreamableHttpMcpTransport transport =
        StreamableHttpMcpTransport.builder()
            .url(serverSettings.url())
            .customHeaders(requestHeaders)
            .build();
    return DefaultMcpClient.builder()
        .key(serverName)
        .transport(transport)
        .protocolVersion(serverSettings.protocolVersion())
        .toolExecutionTimeout(serverSettings.toolExecutionTimeout())
        .build();
  }
}
