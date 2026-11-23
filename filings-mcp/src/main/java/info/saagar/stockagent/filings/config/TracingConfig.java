package info.saagar.stockagent.filings.config;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/// Wires the tracer for filings-mcp's manual spans (ADR 0013).
@Configuration
public class TracingConfig {

  /// Returns the tracer of the OpenTelemetry Java agent, which registers itself as the global
  /// instance before the app starts. Without the agent (`bootRun`, tests) it is a no-op tracer.
  ///
  /// @return the tracer
  @Bean
  public Tracer tracer() {
    return GlobalOpenTelemetry.getTracer("info.saagar.stockagent.filings");
  }
}
