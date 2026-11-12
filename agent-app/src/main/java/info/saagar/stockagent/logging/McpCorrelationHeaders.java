package info.saagar.stockagent.logging;

import static info.saagar.stockagent.common.logging.LoggingContextFilter.CORRELATION_ID_HEADER;
import static info.saagar.stockagent.common.logging.LoggingContextFilter.CORRELATION_ID_KEY;
import static info.saagar.stockagent.common.logging.LoggingContextFilter.TRACE_ID_HEADER;
import static info.saagar.stockagent.common.logging.LoggingContextFilter.TRACE_ID_KEY;

import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.MDC;

/// Supplies the `X-Correlation-Id` and `X-Trace-Id` headers for each MCP request, so one chat
/// request's log lines share a `correlationId` across the agent app and the MCP servers.
///
/// The MCP transport calls this on the thread that makes the tool call, which is the request
/// thread, so the values come from that request's MDC. A header is left out when its MDC value is
/// missing, as for the client's startup initialization and background health checks.
public class McpCorrelationHeaders implements Supplier<Map<String, String>> {

  /// Reads the correlation and trace IDs from the current thread's MDC.
  ///
  /// @return the headers to send, without any whose ID is missing
  @Override
  public Map<String, String> get() {
    String correlationId = MDC.get(CORRELATION_ID_KEY);
    String traceId = MDC.get(TRACE_ID_KEY);
    if (correlationId == null) {
      return traceId == null ? Map.of() : Map.of(TRACE_ID_HEADER, traceId);
    }
    return traceId == null
        ? Map.of(CORRELATION_ID_HEADER, correlationId)
        : Map.of(CORRELATION_ID_HEADER, correlationId, TRACE_ID_HEADER, traceId);
  }
}
