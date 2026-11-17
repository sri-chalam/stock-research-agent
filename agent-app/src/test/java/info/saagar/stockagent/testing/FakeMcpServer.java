package info.saagar.stockagent.testing;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/// In-process MCP server over Streamable HTTP (JSON responses), standing in for our MCP servers.
///
/// It lists the tool names it was started with. `getHoldings` returns a synthetic holding when the
/// call carries `X-User-Id` and an `isError` result without it, as `portfolio-mcp` does. Every
/// other tool answers like `getQuote`: a synthetic quote for `AAPL` and an `isError` result for any
/// other symbol, as `market-data-mcp` does. It records the correlation and user headers of each
/// `tools/call`.
public final class FakeMcpServer implements AutoCloseable {

  /// The synthetic quote text `getQuote` returns for `AAPL`.
  public static final String AAPL_QUOTE_TEXT =
      "{\"symbol\":\"AAPL\",\"currentPrice\":189.42,\"change\":-0.7,\"percentChange\":-0.37,"
          + "\"high\":191.05,\"low\":188.1,\"open\":190.2,\"previousClose\":190.12,"
          + "\"asOfTime\":\"2026-05-01T14:30:00Z\"}";

  /// The synthetic holdings text `getHoldings` returns for any identified user.
  public static final String HOLDINGS_TEXT = "[{\"ticker\":\"AAPL\",\"shares\":50}]";

  /// The error text `getHoldings` returns when the call carries no `X-User-Id`.
  public static final String NO_USER_TEXT = "No user identified; send X-User-Id";

  private static final List<String> RECORDED_HEADER_NAMES =
      List.of("X-Correlation-Id", "X-Trace-Id", "X-User-Id");
  private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

  private final HttpServer httpServer;
  private final List<String> toolNames;
  private final List<Map<String, String>> toolCallHeaders = new CopyOnWriteArrayList<>();

  private FakeMcpServer(List<String> toolNames) throws IOException {
    this.toolNames = toolNames;
    this.httpServer =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    httpServer.createContext("/mcp", this::handle);
    httpServer.start();
  }

  /// Starts a server on a free loopback port.
  ///
  /// @param toolNames names returned by `tools/list`
  /// @return the running server
  public static FakeMcpServer start(String... toolNames) {
    try {
      return new FakeMcpServer(List.of(toolNames));
    } catch (IOException startFailure) {
      throw new IllegalStateException("Could not start the fake MCP server", startFailure);
    }
  }

  /// Returns the MCP endpoint URL.
  ///
  /// @return the endpoint, e.g. `http://127.0.0.1:54321/mcp`
  public String url() {
    return "http://127.0.0.1:" + httpServer.getAddress().getPort() + "/mcp";
  }

  /// Returns the `X-Correlation-Id`, `X-Trace-Id` and `X-User-Id` headers of each `tools/call`, in
  /// order.
  ///
  /// @return headers by name; a header that was not sent is absent
  public List<Map<String, String>> toolCallHeaders() {
    return toolCallHeaders;
  }

  @Override
  public void close() {
    httpServer.stop(0);
  }

  private void handle(HttpExchange httpExchange) throws IOException {
    try (httpExchange) {
      if (!"POST".equals(httpExchange.getRequestMethod())) {
        httpExchange.sendResponseHeaders(405, -1);
        return;
      }
      JsonNode jsonRpcMessage = JSON_MAPPER.readTree(httpExchange.getRequestBody().readAllBytes());
      if (!jsonRpcMessage.has("id")) {
        httpExchange.sendResponseHeaders(202, -1);
        return;
      }
      Map<String, Object> jsonRpcReply = new HashMap<>();
      jsonRpcReply.put("jsonrpc", "2.0");
      jsonRpcReply.put("id", jsonRpcMessage.get("id").asLong());
      String method = jsonRpcMessage.path("method").asString();
      switch (method) {
        case "initialize" -> jsonRpcReply.put("result", initializeResult(jsonRpcMessage));
        case "tools/list" -> jsonRpcReply.put("result", Map.of("tools", toolDefinitions()));
        case "tools/call" -> {
          Map<String, String> recordedHeaders = recordToolCallHeaders(httpExchange);
          jsonRpcReply.put(
              "result",
              "getHoldings".equals(jsonRpcMessage.path("params").path("name").asString())
                  ? getHoldingsResult(recordedHeaders.containsKey("X-User-Id"))
                  : getQuoteResult(jsonRpcMessage));
        }
        case "ping" -> jsonRpcReply.put("result", Map.of());
        default ->
            jsonRpcReply.put(
                "error", Map.of("code", -32601, "message", "Method not found: " + method));
      }
      byte[] replyBytes = JSON_MAPPER.writeValueAsBytes(jsonRpcReply);
      httpExchange.getResponseHeaders().set("Content-Type", "application/json");
      httpExchange.sendResponseHeaders(200, replyBytes.length);
      try (OutputStream responseBody = httpExchange.getResponseBody()) {
        responseBody.write(replyBytes);
      }
    }
  }

  private static Map<String, Object> initializeResult(JsonNode initializeRequest) {
    return Map.of(
        "protocolVersion",
        initializeRequest.path("params").path("protocolVersion").asString(),
        "capabilities",
        Map.of("tools", Map.of()),
        "serverInfo",
        Map.of("name", "fake-market-data-mcp", "version", "test"));
  }

  private List<Map<String, Object>> toolDefinitions() {
    return toolNames.stream()
        .map(
            toolName ->
                Map.<String, Object>of(
                    "name",
                    toolName,
                    "description",
                    "Synthetic " + toolName + " tool.",
                    "inputSchema",
                    Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of("symbol", Map.of("type", "string")),
                        "required",
                        List.of("symbol"))))
        .toList();
  }

  private static Map<String, Object> getQuoteResult(JsonNode toolCallRequest) {
    String symbol = toolCallRequest.path("params").path("arguments").path("symbol").asString();
    if ("AAPL".equals(symbol)) {
      return Map.of("content", List.of(Map.of("type", "text", "text", AAPL_QUOTE_TEXT)));
    }
    String noQuoteText =
        "No quote available for symbol " + symbol + ". Only US-listed equities are supported.";
    return Map.of("content", List.of(Map.of("type", "text", "text", noQuoteText)), "isError", true);
  }

  private static Map<String, Object> getHoldingsResult(boolean userIdentified) {
    return userIdentified
        ? Map.of("content", List.of(Map.of("type", "text", "text", HOLDINGS_TEXT)))
        : Map.of("content", List.of(Map.of("type", "text", "text", NO_USER_TEXT)), "isError", true);
  }

  private Map<String, String> recordToolCallHeaders(HttpExchange httpExchange) {
    Map<String, String> recordedHeaders = new HashMap<>();
    for (String headerName : RECORDED_HEADER_NAMES) {
      String headerValue = httpExchange.getRequestHeaders().getFirst(headerName);
      if (headerValue != null) {
        recordedHeaders.put(headerName, headerValue);
      }
    }
    Map<String, String> toolCallHeaderValues = Map.copyOf(recordedHeaders);
    toolCallHeaders.add(toolCallHeaderValues);
    return toolCallHeaderValues;
  }
}
