package info.saagar.stockagent.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/// Per-agent model settings bound from `stock-agent.agents.<agentName>` in `application.yml`.
///
/// @param agents settings keyed by agent name, e.g. `quote`
@ConfigurationProperties("stock-agent")
public record AgentProperties(Map<String, AgentSettings> agents) {

  /// Model, bounded-autonomy and tool settings for one agent.
  ///
  /// @param model model ID, e.g. `claude-haiku-5-5`
  /// @param maxTokens maximum output tokens per LLM call
  /// @param timeout per-call timeout
  /// @param maxSteps maximum sequential tool invocations per run
  /// @param allowedTools names of the MCP tools the agent may see and call; empty means none
  public record AgentSettings(
      String model, int maxTokens, Duration timeout, int maxSteps, List<String> allowedTools) {

    /// Treats a missing `allowed-tools` as an empty allowlist, never as "all tools".
    public AgentSettings {
      allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
    }
  }

  /// Returns the settings for an agent.
  ///
  /// @param agentName agent key in `application.yml`
  /// @return the agent's settings
  /// @throws IllegalStateException when the agent is not configured
  public AgentSettings forAgent(String agentName) {
    AgentSettings agentSettings = agents == null ? null : agents.get(agentName);
    if (agentSettings == null) {
      throw new IllegalStateException("No settings configured for agent " + agentName);
    }
    return agentSettings;
  }
}
