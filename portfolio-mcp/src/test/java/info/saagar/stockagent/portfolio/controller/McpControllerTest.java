package info.saagar.stockagent.portfolio.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import info.saagar.stockagent.common.testing.TestJwts;
import info.saagar.stockagent.portfolio.PortfolioTestDatabase;
import info.saagar.stockagent.portfolio.PortfolioTestUsers;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/// Drives `POST /mcp` through the security filters with JSON-RPC messages, as the agent's MCP
/// client sends them, against the seeded store (`schema.sql` and the demo users on in-memory H2).
/// Tokens are test JWTs signed for [TestJwts#ISSUER].
@SpringBootTest(
    properties = {
      PortfolioTestDatabase.URL_PROPERTY,
      PortfolioTestDatabase.USERNAME_PROPERTY,
      PortfolioTestDatabase.PASSWORD_PROPERTY,
      PortfolioTestUsers.USER_1_SUB_PROPERTY,
      PortfolioTestUsers.USER_2_SUB_PROPERTY,
      PortfolioTestUsers.NO_CLAUDE_TEST_USER_1_PROPERTY,
      PortfolioTestUsers.NO_CLAUDE_TEST_USER_2_PROPERTY
    })
@ExtendWith(OutputCaptureExtension.class)
class McpControllerTest {

  @DynamicPropertySource
  static void trustTheTestIssuer(DynamicPropertyRegistry propertyRegistry) {
    TestJwts.registerResourceServerProperties(propertyRegistry);
  }

  private static final String USER_1_TOKEN =
      TestJwts.token(PortfolioTestUsers.USER_1_SUB, "portfolio.read");
  private static final String USER_2_TOKEN =
      TestJwts.token(PortfolioTestUsers.USER_2_SUB, "portfolio.read");

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private JsonMapper jsonMapper;

  @Autowired
  @Qualifier("springSecurityFilterChain")
  private Filter springSecurityFilterChain;

  private MockMvc mockMvc;

  @BeforeEach
  void setUpMockMvc() {
    mockMvc =
        MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .addFilters(springSecurityFilterChain)
            .build();
  }

  private JsonNode postForReply(MockHttpServletRequestBuilder mcpRequest) throws Exception {
    MvcResult mvcResult = mockMvc.perform(mcpRequest).andReturn();
    assertThat(mvcResult.getResponse().getStatus()).isEqualTo(200);
    return jsonMapper.readTree(mvcResult.getResponse().getContentAsString());
  }

  private int statusOf(MockHttpServletRequestBuilder mcpRequest) throws Exception {
    return mockMvc.perform(mcpRequest).andReturn().getResponse().getStatus();
  }

  private static MockHttpServletRequestBuilder jsonRpc(String jsonRpcMessage) {
    return post("/mcp").contentType(MediaType.APPLICATION_JSON).content(jsonRpcMessage);
  }

  private static MockHttpServletRequestBuilder toolCall(String toolName) {
    return jsonRpc(
        """
        {"jsonrpc":"2.0","id":3,"method":"tools/call",\
        "params":{"name":"%s","arguments":{}}}"""
            .formatted(toolName));
  }

  private static MockHttpServletRequestBuilder toolCallAs(String toolName, String bearerToken) {
    return toolCall(toolName).header("Authorization", "Bearer " + bearerToken);
  }

  private JsonNode successfulToolResult(MockHttpServletRequestBuilder toolCallRequest)
      throws Exception {
    JsonNode result = postForReply(toolCallRequest).path("result");
    assertThat(result.path("isError").asBoolean(false)).isFalse();
    return jsonMapper.readTree(result.path("content").get(0).path("text").asString());
  }

  @Test
  void toolsListListsExactlyTheTwoReadToolsWithoutArguments() throws Exception {
    JsonNode reply =
        postForReply(
            jsonRpc("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}")
                .header("Authorization", "Bearer " + USER_1_TOKEN));

    JsonNode tools = reply.path("result").path("tools");
    assertThat(tools.valueStream().map(tool -> tool.path("name").asString()))
        .containsExactlyInAnyOrder("getHoldings", "getWatchlist");
    assertThat(tools.valueStream().map(tool -> tool.path("inputSchema").path("properties")))
        .allMatch(JsonNode::isEmpty);
  }

  @Test
  void getHoldingsReturnsOnlyTheTokenSubjectsHoldings(CapturedOutput capturedOutput)
      throws Exception {
    JsonNode user1Holdings = successfulToolResult(toolCallAs("getHoldings", USER_1_TOKEN));
    JsonNode user2Holdings = successfulToolResult(toolCallAs("getHoldings", USER_2_TOKEN));

    assertThat(user1Holdings.valueStream().map(holding -> holding.path("ticker").asString()))
        .containsExactly("AAPL", "MSFT", "NVDA");
    assertThat(user1Holdings.get(0).path("shares").asInt()).isEqualTo(50);
    assertThat(user1Holdings.get(0).propertyNames()).containsExactly("ticker", "shares");
    assertThat(user2Holdings.valueStream().map(holding -> holding.path("ticker").asString()))
        .containsExactly("JPM", "XOM");
    assertThat(capturedOutput.getOut()).doesNotContain(USER_1_TOKEN).doesNotContain(USER_2_TOKEN);
  }

  @Test
  void getWatchlistReturnsOnlyTheTokenSubjectsTickers() throws Exception {
    JsonNode user1Watchlist = successfulToolResult(toolCallAs("getWatchlist", USER_1_TOKEN));
    JsonNode user2Watchlist = successfulToolResult(toolCallAs("getWatchlist", USER_2_TOKEN));

    assertThat(user1Watchlist.valueStream().map(JsonNode::asString))
        .containsExactly("AMZN", "GOOGL");
    assertThat(user2Watchlist.valueStream().map(JsonNode::asString))
        .containsExactly("META", "NFLX", "TSLA");
  }

  @Test
  void unknownSubjectHasAnEmptyPortfolio() throws Exception {
    JsonNode unknownUserHoldings =
        successfulToolResult(
            toolCallAs("getHoldings", TestJwts.token("user_unknown", "portfolio.read")));

    assertThat(unknownUserHoldings.isEmpty()).isTrue();
  }

  @Test
  void toolCallsWithoutAValidTokenAreRejectedWith401() throws Exception {
    assertThat(statusOf(toolCall("getHoldings"))).isEqualTo(401);
    assertThat(statusOf(jsonRpc("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}")))
        .isEqualTo(401);
    assertThat(
            statusOf(
                toolCallAs(
                    "getHoldings",
                    TestJwts.expiredToken(PortfolioTestUsers.USER_1_SUB, "portfolio.read"))))
        .isEqualTo(401);
    assertThat(
            statusOf(
                toolCallAs(
                    "getHoldings",
                    TestJwts.tokenFromIssuer(
                        "https://evil.test.invalid",
                        PortfolioTestUsers.USER_1_SUB,
                        "portfolio.read"))))
        .isEqualTo(401);
  }

  @Test
  void toolCallWithoutPortfolioReadIsRejectedWith403() throws Exception {
    String chatOnlyToken = TestJwts.token(PortfolioTestUsers.USER_1_SUB, "agent.chat");

    assertThat(statusOf(toolCallAs("getHoldings", chatOnlyToken))).isEqualTo(403);
    assertThat(statusOf(toolCallAs("getWatchlist", chatOnlyToken))).isEqualTo(403);
  }

  @Test
  void initializeAndPingNeedNoTokenBecauseTheAgentAppSendsThemOutsideAChatRequest()
      throws Exception {
    JsonNode initializeReply =
        postForReply(
            jsonRpc(
                """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":\
                "2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}"""));
    JsonNode pingReply =
        postForReply(jsonRpc("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"ping\"}"));

    assertThat(initializeReply.path("result").path("serverInfo").path("name").asString())
        .isEqualTo("portfolio-mcp");
    assertThat(pingReply.path("result").isObject()).isTrue();
  }

  @Test
  void invalidTokenIsRejectedWith401EvenOnPing() throws Exception {
    assertThat(
            statusOf(
                jsonRpc("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"ping\"}")
                    .header(
                        "Authorization",
                        "Bearer "
                            + TestJwts.expiredToken(
                                PortfolioTestUsers.USER_1_SUB, "portfolio.read"))))
        .isEqualTo(401);
  }
}
