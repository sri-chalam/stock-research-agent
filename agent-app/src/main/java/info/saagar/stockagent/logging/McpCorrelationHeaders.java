package info.saagar.stockagent.logging;

import static info.saagar.stockagent.common.logging.LoggingContextFilter.CORRELATION_ID_HEADER;
import static info.saagar.stockagent.common.logging.LoggingContextFilter.CORRELATION_ID_KEY;

import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.MDC;

/// Supplies the `X-Correlation-Id` header for each MCP request, so one chat request's log lines
/// share a `correlationId` across the agent app and the MCP servers.
///
/// The trace ID is not sent: the OpenTelemetry Java agent propagates the trace in `traceparent`
/// (ADR 0013). The MCP transport calls this on the thread that makes the tool call, which is the
/// request thread, so the value comes from that request's MDC. The header is left out when the MDC
/// value is missing, as for the client's startup initialization and background health checks.
public class McpCorrelationHeaders implements Supplier<Map<String, String>> {

  /// Reads the correlation ID from the current thread's MDC.
  ///
  /// @return the `X-Correlation-Id` header, or no headers when the ID is missing
  @Override
  public Map<String, String> get() {
    String correlationId = MDC.get(CORRELATION_ID_KEY);
    return correlationId == null ? Map.of() : Map.of(CORRELATION_ID_HEADER, correlationId);
  }
}
