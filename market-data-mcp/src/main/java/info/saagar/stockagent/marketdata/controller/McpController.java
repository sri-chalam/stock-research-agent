package info.saagar.stockagent.marketdata.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.community.mcp.server.McpServer;
import dev.langchain4j.mcp.protocol.McpCallToolResult;
import dev.langchain4j.mcp.protocol.McpJsonRpcMessage;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/// Streamable HTTP transport (stateless, JSON responses only) for the LangChain4j [McpServer],
/// which ships only a stdio transport.
///
/// `McpServer` answers `initialize`, `tools/list` and `tools/call`; this controller also answers
/// `ping`, which the agent's MCP client sends as its health check. Library limits: JSON-RPC ids
/// must be numeric (a string id is treated as a notification and gets no reply), and the library
/// uses Jackson 2, so this controller parses and writes JSON-RPC with Jackson 2 rather than the
/// app's Jackson 3. Only `POST` is mapped, so `GET /mcp` (the optional SSE stream) is a 405.
@RestController
public class McpController {

  private static final Logger log = LoggerFactory.getLogger(McpController.class);

  private static final String MCP_ROUTE = "/mcp";
  private static final ObjectMapper JSON_RPC_MAPPER = new ObjectMapper();
  private static final String PARSE_ERROR_REPLY =
      "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":\"Parse error\"}}";

  private final McpServer mcpServer;

  /// Creates the controller.
  ///
  /// @param mcpServer server that dispatches JSON-RPC messages to the tools
  public McpController(McpServer mcpServer) {
    this.mcpServer = mcpServer;
  }

  /// Handles one JSON-RPC message.
  ///
  /// @param jsonRpcRequestBody the JSON-RPC request or notification
  /// @return the JSON-RPC reply, `202` with no body for a notification, or `400` when the body is
  ///   not JSON
  /// @throws JsonProcessingException if the reply cannot be serialized
  @PostMapping(path = MCP_ROUTE, produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<String> handle(@RequestBody String jsonRpcRequestBody)
      throws JsonProcessingException {
    JsonNode jsonRpcMessage;
    try {
      jsonRpcMessage = JSON_RPC_MAPPER.readTree(jsonRpcRequestBody);
    } catch (JsonProcessingException parseException) {
      log.atError()
          .addKeyValue("eventName", "mcpRequestUnparseable")
          .addKeyValue(
              "eventData",
              Map.of(
                  "httpStatus",
                  400,
                  "exceptionType",
                  parseException.getClass().getName(),
                  "exceptionMessageSuppressed",
                  true))
          .log("MCP request body is not valid JSON");
      return ResponseEntity.badRequest().body(PARSE_ERROR_REPLY);
    }
    String jsonRpcMethod = jsonRpcMessage.path("method").asText();
    JsonNode jsonRpcId = jsonRpcMessage.get("id");
    Object jsonRpcReply;
    if ("ping".equals(jsonRpcMethod) && jsonRpcId != null && !jsonRpcId.isNull()) {
      jsonRpcReply = pingReply(jsonRpcId);
    } else if ("tools/call".equals(jsonRpcMethod)) {
      jsonRpcReply = handleToolCall(jsonRpcMessage);
    } else {
      jsonRpcReply = mcpServer.handle(jsonRpcMessage);
    }
    return jsonRpcReply == null
        ? ResponseEntity.accepted().build()
        : ResponseEntity.ok(JSON_RPC_MAPPER.writeValueAsString(jsonRpcReply));
  }

  private static ObjectNode pingReply(JsonNode jsonRpcId) {
    ObjectNode pingReply = JSON_RPC_MAPPER.createObjectNode();
    pingReply.put("jsonrpc", "2.0");
    pingReply.set("id", jsonRpcId);
    pingReply.putObject("result");
    return pingReply;
  }

  /// Runs a `tools/call` and logs it on both sides. Tool failures are logged by the tool's provider
  /// layer, since `McpServer` swallows the exception.
  private McpJsonRpcMessage handleToolCall(JsonNode toolCallRequest) {
    String toolName = toolCallRequest.path("params").path("name").asText();
    try (MDC.MDCCloseable routeContext = MDC.putCloseable("route", MCP_ROUTE)) {
      log.atInfo()
          .addKeyValue("eventName", "toolCallReceived")
          .addKeyValue("eventData", Map.of("toolName", toolName))
          .log("MCP tool call received");
      long startNanos = System.nanoTime();
      McpJsonRpcMessage toolCallReply = mcpServer.handle(toolCallRequest);
      boolean toolFailed =
          !(toolCallReply instanceof McpCallToolResult toolResult)
              || Boolean.TRUE.equals(toolResult.getResult().getIsError());
      log.atInfo()
          .addKeyValue("eventName", "toolCallCompleted")
          .addKeyValue(
              "eventData",
              Map.of(
                  "toolName",
                  toolName,
                  "toolFailed",
                  toolFailed,
                  "durationTimeMs",
                  (System.nanoTime() - startNanos) / 1_000_000))
          .log("MCP tool call completed");
      return toolCallReply;
    }
  }
}
