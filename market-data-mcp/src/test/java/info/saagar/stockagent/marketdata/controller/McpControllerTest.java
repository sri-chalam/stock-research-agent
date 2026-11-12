package info.saagar.stockagent.marketdata.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import info.saagar.stockagent.marketdata.provider.FakeFinnhubClient;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.stream.Collectors;
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
    FakeFinnhubClient fakeFinnhubClient() {
      return new FakeFinnhubClient();
    }

    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(Instant.parse("2026-05-01T12:00:00Z"), ZoneOffset.UTC);
    }
  }

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private FakeFinnhubClient fakeFinnhubClient;
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

  private JsonNode callTool(String toolName, String argumentsJson) throws Exception {
    return postForReply(
        """
        {"jsonrpc":"2.0","id":4,"method":"tools/call",\
        "params":{"name":"%s","arguments":%s}}"""
            .formatted(toolName, argumentsJson));
  }

  private JsonNode successText(JsonNode reply) {
    JsonNode result = reply.path("result");
    assertThat(result.path("isError").asBoolean(false)).isFalse();
    return jsonMapper.readTree(result.path("content").get(0).path("text").asString());
  }

  private static String errorText(JsonNode reply) {
    assertThat(reply.path("result").path("isError").asBoolean()).isTrue();
    return reply.path("result").path("content").get(0).path("text").asString();
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
  void toolsListListsAllSixToolsWithTheirParameters() throws Exception {
    JsonNode reply = postForReply("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");

    Map<String, JsonNode> inputSchemaByToolName =
        reply
            .path("result")
            .path("tools")
            .valueStream()
            .collect(
                Collectors.toMap(
                    tool -> tool.path("name").asString(), tool -> tool.path("inputSchema")));
    assertThat(inputSchemaByToolName)
        .containsOnlyKeys(
            "getQuote",
            "getCompanyProfile",
            "getCompanyNews",
            "getMarketNews",
            "getEarningsCalendar",
            "getBasicFinancials");
    assertParameters(inputSchemaByToolName.get("getQuote"), new String[] {"symbol"}, "symbol");
    assertParameters(
        inputSchemaByToolName.get("getCompanyProfile"), new String[] {"symbol"}, "symbol");
    assertParameters(
        inputSchemaByToolName.get("getCompanyNews"),
        new String[] {"symbol", "from", "to"},
        "symbol");
    assertParameters(
        inputSchemaByToolName.get("getMarketNews"), new String[] {"category"}, "category");
    assertParameters(
        inputSchemaByToolName.get("getEarningsCalendar"),
        new String[] {"from", "to", "symbol"},
        "from",
        "to");
    assertParameters(
        inputSchemaByToolName.get("getBasicFinancials"), new String[] {"symbol"}, "symbol");
  }

  private static void assertParameters(
      JsonNode inputSchema, String[] parameterNames, String... requiredParameterNames) {
    assertThat(inputSchema.path("properties").propertyNames())
        .containsExactlyInAnyOrder(parameterNames);
    assertThat(inputSchema.path("required").valueStream().map(JsonNode::asString))
        .containsExactlyInAnyOrder(requiredParameterNames);
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
  void getCompanyProfileReturnsTrimmedProfile() throws Exception {
    JsonNode profile = successText(callTool("getCompanyProfile", "{\"symbol\":\"aapl\"}"));

    assertThat(profile.propertyNames())
        .containsExactly(
            "symbol",
            "name",
            "exchange",
            "industry",
            "country",
            "marketCapitalizationMillions",
            "sharesOutstandingMillions",
            "ipoDate",
            "webUrl");
    assertThat(profile.path("name").asString()).isEqualTo("Synthetic Apple Inc");
  }

  @Test
  void getCompanyProfileForUnknownSymbolIsAnError() throws Exception {
    assertThat(errorText(callTool("getCompanyProfile", "{\"symbol\":\"ZZZZ\"}")))
        .isEqualTo(
            "No company profile available for symbol ZZZZ. Only US-listed equities are"
                + " supported.");
  }

  @Test
  void getCompanyNewsReturnsArticles() throws Exception {
    JsonNode articles =
        successText(
            callTool(
                "getCompanyNews",
                "{\"symbol\":\"AAPL\",\"from\":\"2026-04-01\",\"to\":\"2026-04-30\"}"));

    assertThat(articles.size()).isEqualTo(1);
    assertThat(articles.get(0).propertyNames())
        .containsExactly("headline", "source", "publishedAt", "summary", "url");
    assertThat(fakeFinnhubClient.lastCompanyNewsFromDate()).isEqualTo("2026-04-01");
    assertThat(fakeFinnhubClient.lastCompanyNewsToDate()).isEqualTo("2026-04-30");
  }

  @Test
  void getCompanyNewsWithoutDatesCoversTheLastSevenDays() throws Exception {
    successText(callTool("getCompanyNews", "{\"symbol\":\"AAPL\",\"from\":\"\"}"));

    assertThat(fakeFinnhubClient.lastCompanyNewsFromDate())
        .isEqualTo(LocalDate.parse("2026-04-24"));
    assertThat(fakeFinnhubClient.lastCompanyNewsToDate()).isEqualTo(LocalDate.parse("2026-05-01"));
  }

  @Test
  void getCompanyNewsOverThirtyDaysIsAnError() throws Exception {
    assertThat(
            errorText(
                callTool(
                    "getCompanyNews",
                    "{\"symbol\":\"AAPL\",\"from\":\"2026-03-01\",\"to\":\"2026-04-30\"}")))
        .isEqualTo(
            "Date range too long: company news covers at most 30 days between 'from' and 'to'.");
  }

  @Test
  void getCompanyNewsWithMalformedDateIsAnError() throws Exception {
    assertThat(
            errorText(callTool("getCompanyNews", "{\"symbol\":\"AAPL\",\"from\":\"04/01/2026\"}")))
        .isEqualTo("Invalid 'from' date. Use the ISO format YYYY-MM-DD, e.g. 2026-05-01.");
  }

  @Test
  void getMarketNewsReturnsArticles() throws Exception {
    JsonNode articles = successText(callTool("getMarketNews", "{\"category\":\"Merger\"}"));

    assertThat(articles.get(0).path("headline").asString()).isEqualTo("Synthetic headline");
  }

  @Test
  void getMarketNewsWithUnsupportedCategoryIsAnError() throws Exception {
    assertThat(errorText(callTool("getMarketNews", "{\"category\":\"crypto\"}")))
        .isEqualTo("Unsupported news category. Use 'general' or 'merger'.");
  }

  @Test
  void getEarningsCalendarReturnsReleases() throws Exception {
    JsonNode releases =
        successText(
            callTool("getEarningsCalendar", "{\"from\":\"2026-04-27\",\"to\":\"2026-05-01\"}"));

    assertThat(releases.get(0).propertyNames())
        .containsExactly(
            "symbol",
            "date",
            "hour",
            "fiscalYear",
            "fiscalQuarter",
            "epsEstimate",
            "epsActual",
            "revenueEstimate",
            "revenueActual");
  }

  @Test
  void getEarningsCalendarForOneSymbolAllowsLongerRange() throws Exception {
    JsonNode releases =
        successText(
            callTool(
                "getEarningsCalendar",
                "{\"from\":\"2026-01-01\",\"to\":\"2026-06-30\",\"symbol\":\"AAPL\"}"));

    assertThat(releases.get(0).path("symbol").asString()).isEqualTo("AAPL");
  }

  @Test
  void getEarningsCalendarWithoutSymbolOverSevenDaysIsAnError() throws Exception {
    assertThat(
            errorText(
                callTool("getEarningsCalendar", "{\"from\":\"2026-04-20\",\"to\":\"2026-05-01\"}")))
        .isEqualTo(
            "Date range too long: all-company earnings covers at most 7 days between 'from' and"
                + " 'to'.");
  }

  @Test
  void getEarningsCalendarWithFromAfterToIsAnError() throws Exception {
    assertThat(
            errorText(
                callTool(
                    "getEarningsCalendar",
                    "{\"from\":\"2026-05-02\",\"to\":\"2026-05-01\",\"symbol\":\"AAPL\"}")))
        .isEqualTo("'from' must be on or before 'to'.");
  }

  @Test
  void getBasicFinancialsReturnsTheFixedMetrics() throws Exception {
    JsonNode financials = successText(callTool("getBasicFinancials", "{\"symbol\":\"AAPL\"}"));

    assertThat(financials.propertyNames())
        .containsExactly(
            "symbol",
            "week52High",
            "week52Low",
            "peRatioTtm",
            "priceToBook",
            "epsTtm",
            "dividendYieldPercent",
            "beta",
            "grossMarginPercentTtm",
            "operatingMarginPercentTtm",
            "netMarginPercentTtm",
            "returnOnEquityPercentTtm",
            "debtToEquity");
    assertThat(financials.path("peRatioTtm").decimalValue()).isEqualByComparingTo("29.5");
  }

  @Test
  void getBasicFinancialsWithInvalidTickerIsAnError() throws Exception {
    assertThat(errorText(callTool("getBasicFinancials", "{\"symbol\":\"TOOLONGX\"}")))
        .isEqualTo("Invalid ticker symbol. Provide a US ticker such as AAPL.");
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
