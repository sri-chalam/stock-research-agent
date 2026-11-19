package info.saagar.stockagent.filings.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.community.mcp.server.McpServer;
import dev.langchain4j.mcp.protocol.McpCallToolResult;
import dev.langchain4j.mcp.protocol.McpJsonRpcMessage;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
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
///
/// `tools/list` and `tools/call` need a valid bearer token (401 without one) with `filings.read`
/// (403 without it). `initialize`, `ping` and notifications need no token, because the agent app's
/// MCP client sends them outside any chat request. An invalid token is rejected with 401 for every
/// method by the resource-server filter, before this controller.
@RestController
public class McpController {

  private static final Logger log = LoggerFactory.getLogger(McpController.class);

  private static final String MCP_ROUTE = "/mcp";
  private static final Set<String> TOKEN_REQUIRED_METHODS = Set.of("tools/list", "tools/call");
  private static final String FILINGS_READ_SCOPE = "filings.read";
  private static final String FILINGS_READ_AUTHORITY = "SCOPE_" + FILINGS_READ_SCOPE;
  private static final String INSUFFICIENT_SCOPE_CHALLENGE =
      "Bearer error=\"insufficient_scope\", scope=\"" + FILINGS_READ_SCOPE + "\"";
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
  /// @param userToken the caller's validated bearer token, or `null` when none was sent
  /// @return the JSON-RPC reply, `202` with no body for a notification, `400` when the body is not
  ///   JSON, or `401` / `403` for `tools/list` and `tools/call` without a token or without
  ///   `filings.read`
  /// @throws JsonProcessingException if the reply cannot be serialized
  @PostMapping(path = MCP_ROUTE, produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<String> handle(
      @RequestBody String jsonRpcRequestBody, @Nullable JwtAuthenticationToken userToken)
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
    } else if (TOKEN_REQUIRED_METHODS.contains(jsonRpcMethod)) {
      if (userToken == null) {
        return accessDenied(jsonRpcMethod, HttpStatus.UNAUTHORIZED, "Bearer");
      }
      if (!grantsRequiredScope(userToken)) {
        return accessDenied(jsonRpcMethod, HttpStatus.FORBIDDEN, INSUFFICIENT_SCOPE_CHALLENGE);
      }
      jsonRpcReply =
          "tools/call".equals(jsonRpcMethod)
              ? handleToolCall(jsonRpcMessage)
              : mcpServer.handle(jsonRpcMessage);
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

  private static boolean grantsRequiredScope(JwtAuthenticationToken userToken) {
    return userToken.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .anyMatch(FILINGS_READ_AUTHORITY::equals);
  }

  private static ResponseEntity<String> accessDenied(
      String jsonRpcMethod, HttpStatus httpStatus, String bearerChallenge) {
    log.atWarn()
        .addKeyValue("eventName", "mcpAccessDenied")
        .addKeyValue(
            "eventData", Map.of("jsonRpcMethod", jsonRpcMethod, "httpStatus", httpStatus.value()))
        .log("MCP request denied: missing token or scope");
    return ResponseEntity.status(httpStatus)
        .header(HttpHeaders.WWW_AUTHENTICATE, bearerChallenge)
        .build();
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
