package info.saagar.stockagent.common.logging;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/// Puts `correlationId` and `traceId` into the MDC for the duration of each request.
///
/// `traceId` is the OpenTelemetry trace ID of the current span, so a log line links to its trace in
/// Tempo (ADR 0013). With no valid span, as under `bootRun` without the Java agent, it is a random
/// UUID. The trace travels between apps in the agent's `traceparent` header, not in a header of
/// ours.
///
/// The `X-Correlation-Id` value is used only when it matches `^[A-Za-z0-9-]{1,64}$`; a missing or
/// unsafe value is replaced by a random UUID so log lines never carry caller-controlled content.
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LoggingContextFilter extends OncePerRequestFilter {

  /// Request header that carries the correlation ID between services.
  public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

  /// MDC key of the correlation ID.
  public static final String CORRELATION_ID_KEY = "correlationId";

  /// MDC key of the trace ID.
  public static final String TRACE_ID_KEY = "traceId";

  private static final Pattern SAFE_ID_PATTERN = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

  /// Sets the request's correlation and trace IDs in the MDC, runs the chain, then clears them.
  ///
  /// @param httpRequest incoming request
  /// @param httpResponse outgoing response
  /// @param filterChain remaining filters
  /// @throws ServletException if a later filter fails
  /// @throws IOException if a later filter fails on I/O
  @Override
  protected void doFilterInternal(
      HttpServletRequest httpRequest, HttpServletResponse httpResponse, FilterChain filterChain)
      throws ServletException, IOException {
    MDC.put(CORRELATION_ID_KEY, safeIdOrNew(httpRequest.getHeader(CORRELATION_ID_HEADER)));
    MDC.put(TRACE_ID_KEY, currentTraceIdOrNew());
    try {
      filterChain.doFilter(httpRequest, httpResponse);
    } finally {
      MDC.remove(CORRELATION_ID_KEY);
      MDC.remove(TRACE_ID_KEY);
    }
  }

  private static String currentTraceIdOrNew() {
    SpanContext currentSpanContext = Span.current().getSpanContext();
    return currentSpanContext.isValid()
        ? currentSpanContext.getTraceId()
        : UUID.randomUUID().toString();
  }

  private static String safeIdOrNew(String headerValue) {
    return headerValue != null && SAFE_ID_PATTERN.matcher(headerValue).matches()
        ? headerValue
        : UUID.randomUUID().toString();
  }
}
