package info.saagar.stockagent.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;
import jakarta.servlet.FilterChain;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LoggingContextFilterTest {

  private static final String UUID_PATTERN =
      "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";
  private static final String SPAN_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

  private final LoggingContextFilter loggingContextFilter = new LoggingContextFilter();
  private final Map<String, String> mdcDuringRequest = new HashMap<>();
  private final FilterChain mdcCapturingChain =
      (servletRequest, servletResponse) -> mdcDuringRequest.putAll(MDC.getCopyOfContextMap());

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void traceIdIsTheCurrentSpansTraceId() throws Exception {
    Span serverSpan =
        Span.wrap(
            SpanContext.create(
                SPAN_TRACE_ID,
                "00f067aa0ba902b7",
                TraceFlags.getSampled(),
                TraceState.getDefault()));

    try (Scope serverSpanScope = serverSpan.makeCurrent()) {
      loggingContextFilter.doFilter(
          new MockHttpServletRequest(), new MockHttpServletResponse(), mdcCapturingChain);
    }

    assertThat(mdcDuringRequest).containsEntry("traceId", SPAN_TRACE_ID);
  }

  @Test
  void traceIdIsARandomUuidWithoutASpan() throws Exception {
    loggingContextFilter.doFilter(
        new MockHttpServletRequest(), new MockHttpServletResponse(), mdcCapturingChain);

    assertThat(mdcDuringRequest.get("traceId")).matches(UUID_PATTERN);
  }

  @Test
  void traceIdHeaderIsIgnored() throws Exception {
    MockHttpServletRequest httpRequest = new MockHttpServletRequest();
    httpRequest.addHeader("X-Trace-Id", "caller-trace-id");

    loggingContextFilter.doFilter(httpRequest, new MockHttpServletResponse(), mdcCapturingChain);

    assertThat(mdcDuringRequest.get("traceId")).matches(UUID_PATTERN);
  }

  @Test
  void safeCorrelationIdHeaderIsKept() throws Exception {
    MockHttpServletRequest httpRequest = new MockHttpServletRequest();
    httpRequest.addHeader("X-Correlation-Id", "corr-123");

    loggingContextFilter.doFilter(httpRequest, new MockHttpServletResponse(), mdcCapturingChain);

    assertThat(mdcDuringRequest).containsEntry("correlationId", "corr-123");
  }

  @Test
  void unsafeCorrelationIdHeaderIsReplacedByAUuid() throws Exception {
    MockHttpServletRequest httpRequest = new MockHttpServletRequest();
    httpRequest.addHeader("X-Correlation-Id", "unsafe<script>");

    loggingContextFilter.doFilter(httpRequest, new MockHttpServletResponse(), mdcCapturingChain);

    assertThat(mdcDuringRequest.get("correlationId")).matches(UUID_PATTERN);
  }

  @Test
  void mdcIsClearedAfterTheRequest() throws Exception {
    loggingContextFilter.doFilter(
        new MockHttpServletRequest(), new MockHttpServletResponse(), mdcCapturingChain);

    assertThat(MDC.get("traceId")).isNull();
    assertThat(MDC.get("correlationId")).isNull();
  }
}
