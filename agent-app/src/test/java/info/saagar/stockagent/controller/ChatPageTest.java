package info.saagar.stockagent.controller;

import static org.assertj.core.api.Assertions.assertThat;

import info.saagar.stockagent.common.testing.TestJwts;
import info.saagar.stockagent.controller.UiConfigController.UiConfig;
import info.saagar.stockagent.testing.FakeMcpServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/// The chat page is public; the API behind it still needs a token.
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"ANTHROPIC_API_KEY=test-key", "stock-agent.semantic-cache.store=in-memory"})
class ChatPageTest {

  private static final FakeMcpServer FAKE_MARKET_DATA_MCP = FakeMcpServer.start("getQuote");
  private static final FakeMcpServer FAKE_FILINGS_MCP = FakeMcpServer.start("listFilings");
  private static final FakeMcpServer FAKE_PORTFOLIO_MCP =
      FakeMcpServer.start("getHoldings", "getWatchlist");

  @DynamicPropertySource
  static void pointAgentAtFakeMcpServer(DynamicPropertyRegistry propertyRegistry) {
    propertyRegistry.add("stock-agent.mcp-servers.market-data.url", FAKE_MARKET_DATA_MCP::url);
    propertyRegistry.add("stock-agent.mcp-servers.filings.url", FAKE_FILINGS_MCP::url);
    propertyRegistry.add("stock-agent.mcp-servers.portfolio.url", FAKE_PORTFOLIO_MCP::url);
    propertyRegistry.add("stock-agent.ui.workos-client-id", () -> "client_page_test");
    TestJwts.registerResourceServerProperties(propertyRegistry);
  }

  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

  @LocalServerPort private int port;
  @Autowired private JsonMapper jsonMapper;

  private HttpResponse<String> get(String path) throws Exception {
    return HTTP_CLIENT.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void rootServesChatPageWithoutToken() throws Exception {
    HttpResponse<String> pageResponse = get("/");

    assertThat(pageResponse.statusCode()).isEqualTo(200);
    assertThat(pageResponse.headers().firstValue("Content-Type"))
        .hasValueSatisfying(contentType -> assertThat(contentType).startsWith("text/html"));
    assertThat(pageResponse.body()).contains("<title>Equity Research</title>");
  }

  @Test
  void uiConfigReturnsWorkosClientIdWithoutToken() throws Exception {
    HttpResponse<String> configResponse = get("/ui-config");

    assertThat(configResponse.statusCode()).isEqualTo(200);
    assertThat(jsonMapper.readValue(configResponse.body(), UiConfig.class).workosClientId())
        .isEqualTo("client_page_test");
  }

  @Test
  void chatApiStillRequiresToken() throws Exception {
    HttpResponse<String> chatResponse =
        HTTP_CLIENT.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/chat"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString("{\"message\":\"What stocks do I hold?\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertThat(chatResponse.statusCode()).isEqualTo(401);
  }
}
