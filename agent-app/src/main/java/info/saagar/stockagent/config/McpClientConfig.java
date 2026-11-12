package info.saagar.stockagent.config;

import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import info.saagar.stockagent.logging.McpCorrelationHeaders;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/// MCP clients for our MCP servers.
@Configuration
@EnableConfigurationProperties(McpServerProperties.class)
public class McpClientConfig {

  /// Key of the market-data MCP server's settings in `application.yml`.
  public static final String MARKET_DATA_SERVER_NAME = "market-data";

  /// Builds the market-data MCP client.
  ///
  /// The client initializes in its constructor, so the app fails to start when `market-data-mcp` is
  /// not reachable. Each request carries the caller's correlation and trace IDs.
  ///
  /// @param mcpServerProperties MCP server settings
  /// @return the connected client; Spring closes it on shutdown
  @Bean
  public McpClient marketDataMcpClient(McpServerProperties mcpServerProperties) {
    McpServerProperties.McpServerSettings serverSettings =
        mcpServerProperties.forServer(MARKET_DATA_SERVER_NAME);
    StreamableHttpMcpTransport transport =
        StreamableHttpMcpTransport.builder()
            .url(serverSettings.url())
            .customHeaders(new McpCorrelationHeaders())
            .build();
    return DefaultMcpClient.builder()
        .key(MARKET_DATA_SERVER_NAME)
        .transport(transport)
        .protocolVersion(serverSettings.protocolVersion())
        .toolExecutionTimeout(serverSettings.toolExecutionTimeout())
        .build();
  }
}
