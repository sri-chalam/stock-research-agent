package info.saagar.stockagent.marketdata.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import info.saagar.stockagent.marketdata.provider.FakeFinnhubClient;
import info.saagar.stockagent.marketdata.provider.FinnhubClient;
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
@SpringBootTest(properties = "FINNHUB_API_KEY=synthetic-finnhub-key")
@Import(McpControllerTest.FakeFinnhubConfig.class)
class McpControllerTest {

  @TestConfiguration
  static class FakeFinnhubConfig {
    @Bean
    @Primary
    FinnhubClient fakeFinnhubClient() {
      return new FakeFinnhubClient();
    }
  }

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private JsonMapper jsonMapper;

  private MockMvc mockMvc;

  @BeforeEach
  void setUpMockMvc() {
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
  }

  private MvcResult postJsonRpc(String jsonRpcMessage) throws Exception {
    return mockMvc
        .perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content(jsonRpcMessage))
        .andReturn();
  }

  private JsonNode postForReply(String jsonRpcMessage) throws Exception {
    MvcResult mvcResult = postJsonRpc(jsonRpcMessage);
    assertThat(mvcResult.getResponse().getStatus()).isEqualTo(200);
    assertThat(mvcResult.getResponse().getContentType()).startsWith("application/json");
    return jsonMapper.readTree(mvcResult.getResponse().getContentAsString());
  }

  private JsonNode callGetQuote(String symbol) throws Exception {
    return postForReply(
        """
        {"jsonrpc":"2.0","id":3,"method":"tools/call",\
        "params":{"name":"getQuote","arguments":{"symbol":"%s"}}}"""
            .formatted(symbol));
  }

  @Test
  void initializeEchoesProtocolVersionAndNamesTheServer() throws Exception {
    JsonNode reply =
        postForReply(
            """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",\
            "capabilities":{},"clientInfo":{"name":"test-client","version":"1.0"}}}""");

    assertThat(reply.path("id").asLong()).isEqualTo(1);
    assertThat(reply.path("result").path("protocolVersion").asString()).isEqualTo("2025-11-25");
    assertThat(reply.path("result").path("serverInfo").path("name").asString())
        .isEqualTo("market-data-mcp");
    assertThat(reply.path("result").path("capabilities").has("tools")).isTrue();
  }

  @Test
  void toolsListListsGetQuoteWithSymbolParameter() throws Exception {
    JsonNode reply = postForReply("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");

    JsonNode tools = reply.path("result").path("tools");
    assertThat(tools.valueStream().map(tool -> tool.path("name").asString()))
        .containsExactly("getQuote");
    assertThat(tools.get(0).path("inputSchema").path("required").get(0).asString())
        .isEqualTo("symbol");
  }

  @Test
  void toolsCallReturnsTrimmedQuoteAsJsonText() throws Exception {
    JsonNode reply = callGetQuote("aapl");

    JsonNode result = reply.path("result");
    assertThat(result.path("isError").asBoolean(false)).isFalse();
    JsonNode quote = jsonMapper.readTree(result.path("content").get(0).path("text").asString());
    assertThat(quote.propertyNames())
        .containsExactly(
            "symbol",
            "currentPrice",
            "change",
            "percentChange",
            "high",
            "low",
            "open",
            "previousClose",
            "asOfTime");
    assertThat(quote.path("symbol").asString()).isEqualTo("AAPL");
    assertThat(quote.path("currentPrice").decimalValue()).isEqualByComparingTo("189.42");
    assertThat(quote.path("asOfTime").asString()).isEqualTo("2026-05-01T14:30:00Z");
  }

  @Test
  void toolsCallForUnknownSymbolIsAnErrorResultWithReadableText() throws Exception {
    JsonNode reply = callGetQuote("ZZZZ");

    assertThat(reply.path("result").path("isError").asBoolean()).isTrue();
    assertThat(reply.path("result").path("content").get(0).path("text").asString())
        .isEqualTo("No quote available for symbol ZZZZ. Only US-listed equities are supported.");
  }

  @Test
  void toolsCallWithInvalidTickerIsAnErrorResultWithReadableText() throws Exception {
    JsonNode reply = callGetQuote("NOT A TICKER");

    assertThat(reply.path("result").path("isError").asBoolean()).isTrue();
    assertThat(reply.path("result").path("content").get(0).path("text").asString())
        .isEqualTo("Invalid ticker symbol. Provide a US ticker such as AAPL.");
  }

  @Test
  void toolsCallWhenFinnhubFailsIsAnErrorResultWithFixedMessage() throws Exception {
    JsonNode reply = callGetQuote(FakeFinnhubClient.RATE_LIMITED_SYMBOL);

    assertThat(reply.path("result").path("isError").asBoolean()).isTrue();
    assertThat(reply.path("result").path("content").get(0).path("text").asString())
        .isEqualTo("Market data rate limit reached; try again in a minute.");
  }

  @Test
  void pingIsAnsweredWithEmptyResult() throws Exception {
    JsonNode reply = postForReply("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\"}");

    assertThat(reply)
        .isEqualTo(jsonMapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{}}"));
  }

  @Test
  void notificationIsAcceptedWithoutBody() throws Exception {
    mockMvc
        .perform(
            post("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
        .andExpect(status().isAccepted())
        .andExpect(content().string(""));
  }

  /// Pins a library limit: `McpServer` only accepts numeric ids and ignores a request with a string
  /// id as if it were a notification.
  @Test
  void requestWithStringIdGetsNoReply() throws Exception {
    mockMvc
        .perform(
            post("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"jsonrpc\":\"2.0\",\"id\":\"abc\",\"method\":\"tools/list\"}"))
        .andExpect(status().isAccepted())
        .andExpect(content().string(""));
  }

  @Test
  void getIsNotAllowed() throws Exception {
    mockMvc.perform(get("/mcp")).andExpect(status().isMethodNotAllowed());
  }
}
