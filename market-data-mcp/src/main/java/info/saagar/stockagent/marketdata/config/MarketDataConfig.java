package info.saagar.stockagent.marketdata.config;

import dev.langchain4j.community.mcp.server.McpServer;
import dev.langchain4j.mcp.protocol.McpImplementation;
import info.saagar.stockagent.marketdata.provider.FinnhubClient;
import info.saagar.stockagent.marketdata.provider.RestClientFinnhubClient;
import info.saagar.stockagent.marketdata.tool.MarketDataTools;
import java.net.http.HttpClient;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/// Wires the Finnhub client and the MCP server that serves the market data tools.
@Configuration
@EnableConfigurationProperties(FinnhubProperties.class)
public class MarketDataConfig {

  /// Builds the Finnhub client on the JDK HTTP client with the configured timeouts.
  ///
  /// @param finnhubProperties base URL and timeouts
  /// @param finnhubApiKey API key from the `FINNHUB_API_KEY` environment variable; startup fails
  ///   when it is missing or blank
  /// @return the Finnhub client
  @Bean
  public FinnhubClient finnhubClient(
      FinnhubProperties finnhubProperties, @Value("${FINNHUB_API_KEY}") String finnhubApiKey) {
    if (finnhubApiKey.isBlank()) {
      throw new IllegalStateException("FINNHUB_API_KEY must not be blank");
    }
    HttpClient finnhubHttpClient =
        HttpClient.newBuilder().connectTimeout(finnhubProperties.connectTimeout()).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(finnhubHttpClient);
    requestFactory.setReadTimeout(finnhubProperties.readTimeout());
    RestClient finnhubRestClient =
        RestClient.builder()
            .baseUrl(finnhubProperties.baseUrl())
            .requestFactory(requestFactory)
            .build();
    return new RestClientFinnhubClient(finnhubRestClient, finnhubApiKey);
  }

  /// Builds the transport-agnostic MCP server over the market data tools.
  ///
  /// @param marketDataTools the `@Tool` methods to expose
  /// @param serverName name reported in `initialize`, from `spring.application.name`
  /// @param serverVersion version reported in `initialize`; `dev` when not run from a built jar
  /// @return the MCP server
  @Bean
  public McpServer mcpServer(
      MarketDataTools marketDataTools,
      @Value("${spring.application.name}") String serverName,
      @Value("${spring.application.version:dev}") String serverVersion) {
    McpImplementation serverInfo = new McpImplementation();
    serverInfo.setName(serverName);
    serverInfo.setVersion(serverVersion);
    return new McpServer(List.of(marketDataTools), serverInfo);
  }
}
