package info.saagar.stockagent.filings.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import info.saagar.stockagent.common.testing.TestJwts;
import info.saagar.stockagent.filings.provider.EdgarFiling;
import info.saagar.stockagent.filings.provider.FakeEdgarClient;
import jakarta.servlet.Filter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
/// client sends them. `tools/list` and `tools/call` carry a test JWT with `filings.read`, signed
/// for [TestJwts#ISSUER].
@SpringBootTest(
    properties = {
      "SEC_USER_AGENT=synthetic-agent test@example.com",
      "filings.rag.vector-store=in-memory",
      "filings.rag.ingest-tickers="
    })
@Import(McpControllerTest.FakeEdgarConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class McpControllerTest {

  @DynamicPropertySource
  static void trustTheTestIssuer(DynamicPropertyRegistry propertyRegistry) {
    TestJwts.registerResourceServerProperties(propertyRegistry);
  }

  private static final String TEST_USER_SUB = "user_filings_test";
  private static final String FILINGS_TOKEN = TestJwts.token(TEST_USER_SUB, "filings.read");
  private static final String LIST_FILINGS_CALL =
      """
      {"jsonrpc":"2.0","id":3,"method":"tools/call",\
      "params":{"name":"listFilings","arguments":{"ticker":"msft","formType":"10-K"}}}""";
  private static final String TOOLS_LIST =
      "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}";

  @TestConfiguration
  static class FakeEdgarConfig {
    @Bean
    @Primary
    FakeEdgarClient fakeEdgarClient() {
      return new FakeEdgarClient(
          List.of(
              new EdgarFiling(
                  "10-K", "2026-07-30", "2026-06-30", "0000950170-26-000002", "syn-10k.htm"),
              new EdgarFiling(
                  "8-K", "2026-04-25", "2026-04-25", "0000950170-26-000001", "syn-8k.htm")));
    }
  }

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

  private static MockHttpServletRequestBuilder jsonRpc(String jsonRpcMessage) {
    return post("/mcp").contentType(MediaType.APPLICATION_JSON).content(jsonRpcMessage);
  }

  private static MockHttpServletRequestBuilder jsonRpcAs(
      String jsonRpcMessage, String bearerToken) {
    return jsonRpc(jsonRpcMessage).header("Authorization", "Bearer " + bearerToken);
  }

  private int statusOf(MockHttpServletRequestBuilder mcpRequest) throws Exception {
    return mockMvc.perform(mcpRequest).andReturn().getResponse().getStatus();
  }

  private JsonNode postForReply(String jsonRpcMessage) throws Exception {
    MvcResult mvcResult = mockMvc.perform(jsonRpcAs(jsonRpcMessage, FILINGS_TOKEN)).andReturn();
    assertThat(mvcResult.getResponse().getStatus()).isEqualTo(200);
    return jsonMapper.readTree(mvcResult.getResponse().getContentAsString());
  }

  private static List<String> requiredParameters(JsonNode tools, String toolName) {
    return tools
        .valueStream()
        .filter(tool -> toolName.equals(tool.path("name").asString()))
        .findFirst()
        .orElseThrow()
        .path("inputSchema")
        .path("required")
        .valueStream()
        .map(JsonNode::asString)
        .toList();
  }

  @Test
  void toolsListListsEveryToolWithItsRequiredParameters() throws Exception {
    JsonNode reply = postForReply(TOOLS_LIST);

    JsonNode tools = reply.path("result").path("tools");
    assertThat(tools.valueStream().map(tool -> tool.path("name").asString()))
        .containsExactlyInAnyOrder("listFilings", "getCompanyFacts", "searchFilings");
    assertThat(requiredParameters(tools, "listFilings"))
        .containsExactlyInAnyOrder("ticker", "formType");
    assertThat(requiredParameters(tools, "getCompanyFacts")).containsExactly("ticker");
    assertThat(requiredParameters(tools, "searchFilings"))
        .containsExactlyInAnyOrder("ticker", "query");
  }

  @Test
  void toolsCallReturnsFilingRowsWithSecGovLinks() throws Exception {
    JsonNode reply = postForReply(LIST_FILINGS_CALL);

    JsonNode result = reply.path("result");
    assertThat(result.path("isError").asBoolean(false)).isFalse();
    JsonNode filings = jsonMapper.readTree(result.path("content").get(0).path("text").asString());
    assertThat(filings.size()).isEqualTo(1);
    assertThat(filings.get(0).propertyNames())
        .containsExactly("form", "filingDate", "reportPeriod", "accessionNumber", "url");
    assertThat(filings.get(0).path("url").asString())
        .isEqualTo("https://www.sec.gov/Archives/edgar/data/789019/000095017026000002/syn-10k.htm");
  }

  @Test
  void toolCallsWithoutAValidTokenAreRejectedWith401() throws Exception {
    assertThat(statusOf(jsonRpc(LIST_FILINGS_CALL))).isEqualTo(401);
    assertThat(statusOf(jsonRpc(TOOLS_LIST))).isEqualTo(401);
    assertThat(
            statusOf(
                jsonRpcAs(LIST_FILINGS_CALL, TestJwts.expiredToken(TEST_USER_SUB, "filings.read"))))
        .isEqualTo(401);
    assertThat(
            statusOf(
                jsonRpcAs(
                    LIST_FILINGS_CALL,
                    TestJwts.tokenFromIssuer(
                        "https://evil.test.invalid", TEST_USER_SUB, "filings.read"))))
        .isEqualTo(401);
  }

  @Test
  void toolCallsWithoutFilingsReadAreRejectedWith403() throws Exception {
    String otherScopesToken =
        TestJwts.token(TEST_USER_SUB, "agent.chat", "marketdata.read", "portfolio.read");

    assertThat(statusOf(jsonRpcAs(LIST_FILINGS_CALL, otherScopesToken))).isEqualTo(403);
    assertThat(statusOf(jsonRpcAs(TOOLS_LIST, otherScopesToken))).isEqualTo(403);
  }

  @Test
  void initializeAndPingNeedNoTokenBecauseTheAgentAppSendsThemOutsideAChatRequest()
      throws Exception {
    assertThat(
            statusOf(
                jsonRpc(
                    """
                    {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":\
                    "2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""")))
        .isEqualTo(200);
    assertThat(statusOf(jsonRpc("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"ping\"}")))
        .isEqualTo(200);
  }

  @Test
  void invalidTokenIsRejectedWith401EvenOnPing() throws Exception {
    assertThat(
            statusOf(
                jsonRpcAs(
                    "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"ping\"}",
                    TestJwts.expiredToken(TEST_USER_SUB, "filings.read"))))
        .isEqualTo(401);
  }

  @Test
  void toolCallLogsNeitherTheTokenNorItsSubject(CapturedOutput capturedOutput) throws Exception {
    postForReply(LIST_FILINGS_CALL);

    assertThat(capturedOutput.getOut())
        .contains("toolCallCompleted")
        .doesNotContain(FILINGS_TOKEN)
        .doesNotContain(TEST_USER_SUB);
  }
}
