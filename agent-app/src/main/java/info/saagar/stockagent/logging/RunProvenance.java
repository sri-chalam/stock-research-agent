package info.saagar.stockagent.logging;

import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.tool.ToolExecution;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// What one Supervisor run did: the specialist agents it called in order, the tools each call made,
/// and the tokens each agent used.
///
/// Fed by [AgentProvenanceListener] while the run executes. A run's events arrive one at a time,
/// because the Supervisor calls one specialist at a time on the request thread.
public final class RunProvenance {

  private final List<AgentCall> agentCalls = new ArrayList<>();
  private final Map<String, TokenCount> tokensByAgent = new LinkedHashMap<>();

  /// One specialist agent invocation and the tools it called.
  ///
  /// @param agentName the specialist's name, e.g. `filings`
  /// @param toolExecutions the tools it called, in order
  public record AgentCall(String agentName, List<ToolExecution> toolExecutions) {}

  /// Input and output tokens of one or more LLM calls.
  ///
  /// @param inputTokens tokens sent to the model
  /// @param outputTokens tokens the model generated
  public record TokenCount(long inputTokens, long outputTokens) {

    /// Zero tokens.
    public static final TokenCount ZERO = new TokenCount(0, 0);

    /// Adds two token counts.
    ///
    /// @param other the count to add
    /// @return the sum
    public TokenCount plus(TokenCount other) {
      return new TokenCount(inputTokens + other.inputTokens, outputTokens + other.outputTokens);
    }
  }

  /// Records a specialist invocation.
  ///
  /// @param agentName the specialist about to run
  public void agentInvoked(String agentName) {
    agentCalls.add(new AgentCall(agentName, new ArrayList<>()));
  }

  /// Adds a tool call to the latest invocation of the agent that made it.
  ///
  /// @param agentName the specialist that called the tool
  /// @param toolExecution the finished tool call
  public void toolExecuted(String agentName, ToolExecution toolExecution) {
    agentCalls.reversed().stream()
        .filter(agentCall -> agentCall.agentName().equals(agentName))
        .findFirst()
        .orElseThrow()
        .toolExecutions()
        .add(toolExecution);
  }

  /// Adds one LLM call's tokens to the agent that made it. Missing counts count as zero.
  ///
  /// @param agentName the agent whose chat model was called, e.g. `supervisor`
  /// @param tokenUsage the token usage the model reported; may be `null`
  public void tokensUsed(String agentName, TokenUsage tokenUsage) {
    TokenCount callTokens =
        tokenUsage == null
            ? TokenCount.ZERO
            : new TokenCount(
                Objects.requireNonNullElse(tokenUsage.inputTokenCount(), 0),
                Objects.requireNonNullElse(tokenUsage.outputTokenCount(), 0));
    tokensByAgent.merge(agentName, callTokens, TokenCount::plus);
  }

  /// Returns the specialist calls in order.
  ///
  /// @return the specialist calls; empty when the Supervisor called no specialist
  public List<AgentCall> agentCalls() {
    return List.copyOf(agentCalls);
  }

  /// Returns the tokens each agent used, in the order the agents first called their model.
  ///
  /// @return tokens per agent name
  public Map<String, TokenCount> tokensByAgent() {
    return new LinkedHashMap<>(tokensByAgent);
  }

  /// Returns the tokens all agents used together.
  ///
  /// @return the sum of [#tokensByAgent()]
  public TokenCount totalTokens() {
    return tokensByAgent.values().stream().reduce(TokenCount.ZERO, TokenCount::plus);
  }
}
