package info.saagar.stockagent.filings.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import info.saagar.stockagent.filings.provider.EdgarFiling;
import info.saagar.stockagent.filings.provider.FakeEdgarClient;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/// Drives `POST /mcp` with JSON-RPC messages, as the agent's MCP client sends them.
@SpringBootTest(
    properties = {
      "SEC_USER_AGENT=synthetic-agent test@example.com",
      "filings.rag.vector-store=in-memory",
      "filings.rag.ingest-tickers="
    })
@Import(McpControllerTest.FakeEdgarConfig.class)
class McpControllerTest {

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

  private MockMvc mockMvc;

  @BeforeEach
  void setUpMockMvc() {
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
  }

  private JsonNode postForReply(String jsonRpcMessage) throws Exception {
    MvcResult mvcResult =
        mockMvc
            .perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content(jsonRpcMessage))
            .andReturn();
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
    JsonNode reply = postForReply("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");

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
    JsonNode reply =
        postForReply(
            """
            {"jsonrpc":"2.0","id":3,"method":"tools/call",\
            "params":{"name":"listFilings","arguments":{"ticker":"msft","formType":"10-K"}}}""");

    JsonNode result = reply.path("result");
    assertThat(result.path("isError").asBoolean(false)).isFalse();
    JsonNode filings = jsonMapper.readTree(result.path("content").get(0).path("text").asString());
    assertThat(filings.size()).isEqualTo(1);
    assertThat(filings.get(0).propertyNames())
        .containsExactly("form", "filingDate", "reportPeriod", "accessionNumber", "url");
    assertThat(filings.get(0).path("url").asString())
        .isEqualTo("https://www.sec.gov/Archives/edgar/data/789019/000095017026000002/syn-10k.htm");
  }
}
