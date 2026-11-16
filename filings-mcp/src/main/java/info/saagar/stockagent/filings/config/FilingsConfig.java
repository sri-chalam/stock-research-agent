package info.saagar.stockagent.filings.config;

import dev.langchain4j.community.mcp.server.McpServer;
import dev.langchain4j.mcp.protocol.McpImplementation;
import info.saagar.stockagent.filings.provider.EdgarClient;
import info.saagar.stockagent.filings.provider.RestClientEdgarClient;
import info.saagar.stockagent.filings.tool.FilingsTools;
import java.net.http.HttpClient;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/// Wires the SEC EDGAR client and the MCP server that serves the filings tools.
@Configuration
@EnableConfigurationProperties(FilingsProperties.class)
public class FilingsConfig {

  /// Builds the EDGAR client on the JDK HTTP client with the configured timeouts.
  ///
  /// @param filingsProperties base URL and timeouts
  /// @param secUserAgent User-Agent from the `SEC_USER_AGENT` environment variable; startup fails
  ///   when it is missing or blank
  /// @return the EDGAR client
  @Bean
  public EdgarClient edgarClient(
      FilingsProperties filingsProperties, @Value("${SEC_USER_AGENT}") String secUserAgent) {
    if (secUserAgent.isBlank()) {
      throw new IllegalStateException("SEC_USER_AGENT must not be blank");
    }
    HttpClient edgarHttpClient =
        HttpClient.newBuilder().connectTimeout(filingsProperties.connectTimeout()).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(edgarHttpClient);
    requestFactory.setReadTimeout(filingsProperties.readTimeout());
    RestClient edgarRestClient =
        RestClient.builder()
            .baseUrl(filingsProperties.baseUrl())
            .requestFactory(requestFactory)
            .build();
    return new RestClientEdgarClient(edgarRestClient, secUserAgent);
  }

  /// Builds the transport-agnostic MCP server over the filings tools.
  ///
  /// @param filingsTools the `@Tool` methods to expose
  /// @param serverName name reported in `initialize`, from `spring.application.name`
  /// @param serverVersion version reported in `initialize`; `dev` when not run from a built jar
  /// @return the MCP server
  @Bean
  public McpServer mcpServer(
      FilingsTools filingsTools,
      @Value("${spring.application.name}") String serverName,
      @Value("${spring.application.version:dev}") String serverVersion) {
    McpImplementation serverInfo = new McpImplementation();
    serverInfo.setName(serverName);
    serverInfo.setVersion(serverVersion);
    return new McpServer(List.of(filingsTools), serverInfo);
  }
}
