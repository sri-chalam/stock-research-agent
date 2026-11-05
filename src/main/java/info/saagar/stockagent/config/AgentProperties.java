package info.saagar.stockagent.config;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/// Per-agent model settings bound from `stock-agent.agents.<agentName>` in `application.yml`.
///
/// @param agents settings keyed by agent name, e.g. `quote`
@ConfigurationProperties("stock-agent")
public record AgentProperties(Map<String, AgentSettings> agents) {

  /// Model and bounded-autonomy settings for one agent.
  ///
  /// @param model model ID, e.g. `claude-haiku-5-5`
  /// @param maxTokens maximum output tokens per LLM call
  /// @param temperature sampling temperature
  /// @param timeout per-call timeout
  /// @param maxSteps maximum sequential tool invocations per run
  public record AgentSettings(
      String model, int maxTokens, double temperature, Duration timeout, int maxSteps) {}

  /// Returns the settings for an agent.
  ///
  /// @param agentName agent key in `application.yml`
  /// @return the agent's settings
  /// @throws IllegalStateException when the agent is not configured
  public AgentSettings forAgent(String agentName) {
    AgentSettings agentSettings = agents.get(agentName);
    if (agentSettings == null) {
      throw new IllegalStateException("No settings configured for agent " + agentName);
    }
    return agentSettings;
  }
}
