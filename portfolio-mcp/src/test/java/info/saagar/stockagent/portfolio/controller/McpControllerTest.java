package info.saagar.stockagent.portfolio.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import info.saagar.stockagent.portfolio.PortfolioTestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/// Drives `POST /mcp` with JSON-RPC messages, as the agent's MCP client sends them, against the
/// seeded store (`schema.sql` and `data.sql` on in-memory H2).
@SpringBootTest(
    properties = {
      PortfolioTestDatabase.URL_PROPERTY,
      PortfolioTestDatabase.USERNAME_PROPERTY,
      PortfolioTestDatabase.PASSWORD_PROPERTY
    })
class McpControllerTest {

  private static final String NO_USER_MESSAGE = "No user identified; send X-User-Id";

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private JsonMapper jsonMapper;

  private MockMvc mockMvc;

  @BeforeEach
  void setUpMockMvc() {
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
  }

  private JsonNode postForReply(MockHttpServletRequestBuilder mcpRequest) throws Exception {
    MvcResult mvcResult = mockMvc.perform(mcpRequest).andReturn();
    assertThat(mvcResult.getResponse().getStatus()).isEqualTo(200);
    return jsonMapper.readTree(mvcResult.getResponse().getContentAsString());
  }

  private static MockHttpServletRequestBuilder toolCall(String toolName) {
    return post("/mcp")
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            """
            {"jsonrpc":"2.0","id":3,"method":"tools/call",\
            "params":{"name":"%s","arguments":{}}}"""
                .formatted(toolName));
  }

  private JsonNode successfulToolResult(MockHttpServletRequestBuilder toolCallRequest)
      throws Exception {
    JsonNode result = postForReply(toolCallRequest).path("result");
    assertThat(result.path("isError").asBoolean(false)).isFalse();
    return jsonMapper.readTree(result.path("content").get(0).path("text").asString());
  }

  private JsonNode failedToolResult(MockHttpServletRequestBuilder toolCallRequest)
      throws Exception {
    JsonNode result = postForReply(toolCallRequest).path("result");
    assertThat(result.path("isError").asBoolean(false)).isTrue();
    return result.path("content").get(0).path("text");
  }

  @Test
  void toolsListListsExactlyTheTwoReadToolsWithoutArguments() throws Exception {
    JsonNode reply =
        postForReply(
            post("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"));

    JsonNode tools = reply.path("result").path("tools");
    assertThat(tools.valueStream().map(tool -> tool.path("name").asString()))
        .containsExactlyInAnyOrder("getHoldings", "getWatchlist");
    assertThat(tools.valueStream().map(tool -> tool.path("inputSchema").path("properties")))
        .allMatch(JsonNode::isEmpty);
  }

  @Test
  void getHoldingsReturnsOnlyTheIdentifiedUsersHoldings() throws Exception {
    JsonNode aliceHoldings =
        successfulToolResult(toolCall("getHoldings").header("X-User-Id", "alice"));
    JsonNode bobHoldings = successfulToolResult(toolCall("getHoldings").header("X-User-Id", "bob"));

    assertThat(aliceHoldings.valueStream().map(holding -> holding.path("ticker").asString()))
        .containsExactly("AAPL", "MSFT", "NVDA");
    assertThat(aliceHoldings.get(0).path("shares").asInt()).isEqualTo(50);
    assertThat(aliceHoldings.get(0).propertyNames()).containsExactly("ticker", "shares");
    assertThat(bobHoldings.valueStream().map(holding -> holding.path("ticker").asString()))
        .containsExactly("JPM", "XOM");
  }

  @Test
  void getWatchlistReturnsOnlyTheIdentifiedUsersTickers() throws Exception {
    JsonNode aliceWatchlist =
        successfulToolResult(toolCall("getWatchlist").header("X-User-Id", "alice"));
    JsonNode bobWatchlist =
        successfulToolResult(toolCall("getWatchlist").header("X-User-Id", "bob"));

    assertThat(aliceWatchlist.valueStream().map(JsonNode::asString))
        .containsExactly("AMZN", "GOOGL");
    assertThat(bobWatchlist.valueStream().map(JsonNode::asString))
        .containsExactly("META", "NFLX", "TSLA");
  }

  @Test
  void unknownUserHasAnEmptyPortfolio() throws Exception {
    JsonNode carolHoldings =
        successfulToolResult(toolCall("getHoldings").header("X-User-Id", "carol"));

    assertThat(carolHoldings.isEmpty()).isTrue();
  }

  @Test
  void bothToolsReturnTheNoUserErrorWithoutTheHeader() throws Exception {
    assertThat(failedToolResult(toolCall("getHoldings")).asString()).isEqualTo(NO_USER_MESSAGE);
    assertThat(failedToolResult(toolCall("getWatchlist")).asString()).isEqualTo(NO_USER_MESSAGE);
  }

  @Test
  void blankOrUnsafeHeaderIsTreatedAsNoUser() throws Exception {
    assertThat(failedToolResult(toolCall("getHoldings").header("X-User-Id", " ")).asString())
        .isEqualTo(NO_USER_MESSAGE);
    assertThat(
            failedToolResult(toolCall("getHoldings").header("X-User-Id", "alice' OR '1'='1"))
                .asString())
        .isEqualTo(NO_USER_MESSAGE);
  }
}
