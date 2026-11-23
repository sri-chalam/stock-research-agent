package info.saagar.stockagent.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class McpCorrelationHeadersTest {

  private final McpCorrelationHeaders mcpCorrelationHeaders = new McpCorrelationHeaders();

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void sendsOnlyTheCorrelationIdFromTheMdc() {
    MDC.put("correlationId", "corr-123");
    MDC.put("traceId", "trace-456");

    assertThat(mcpCorrelationHeaders.get())
        .containsExactlyEntriesOf(Map.of("X-Correlation-Id", "corr-123"));
  }

  @Test
  void sendsNoHeadersOutsideARequest() {
    assertThat(mcpCorrelationHeaders.get()).isEmpty();
  }
}
