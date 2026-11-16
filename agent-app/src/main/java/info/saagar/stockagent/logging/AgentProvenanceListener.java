package info.saagar.stockagent.logging;

import dev.langchain4j.agentic.observability.AfterAgentToolExecution;
import dev.langchain4j.agentic.observability.AgentInvocationError;
import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.agentic.observability.AgentRequest;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.service.tool.ToolExecution;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/// Records, per Supervisor run, the specialist agents called in order and the tools each one
/// called.
///
/// Registered on the Supervisor and inherited by its specialist agents. A run is keyed by its
/// agentic scope's memory ID, which is a fresh UUID for each request because the Supervisor has no
/// memory. The caller takes the run with [#takeAgentCalls(AgenticScope)] when the Supervisor
/// returns; a run that fails is dropped.
@Component
public class AgentProvenanceListener implements AgentListener {

  private final Map<Object, List<AgentCall>> agentCallsByRunId = new ConcurrentHashMap<>();

  /// One specialist agent invocation and the tools it called.
  ///
  /// @param agentName the specialist's name, e.g. `filings`
  /// @param toolExecutions the tools it called, in order
  public record AgentCall(String agentName, List<ToolExecution> toolExecutions) {}

  /// Records a specialist invocation. The Supervisor's own invocation is not recorded.
  ///
  /// @param agentRequest the agent about to run
  @Override
  public void beforeAgentInvocation(AgentRequest agentRequest) {
    if (agentRequest.agent().parent() == null) {
      return;
    }
    agentCallsByRunId
        .computeIfAbsent(agentRequest.agenticScope().memoryId(), runId -> new ArrayList<>())
        .add(new AgentCall(agentRequest.agentName(), new ArrayList<>()));
  }

  /// Adds a tool call to the latest invocation of the agent that made it.
  ///
  /// @param afterAgentToolExecution the finished tool call and the agent that made it
  @Override
  public void afterAgentToolExecution(AfterAgentToolExecution afterAgentToolExecution) {
    List<AgentCall> agentCalls =
        agentCallsByRunId.get(afterAgentToolExecution.agenticScope().memoryId());
    String agentName = afterAgentToolExecution.agentInstance().name();
    agentCalls.reversed().stream()
        .filter(agentCall -> agentCall.agentName().equals(agentName))
        .findFirst()
        .orElseThrow()
        .toolExecutions()
        .add(afterAgentToolExecution.toolExecution());
  }

  /// Drops a failed run, so its record does not outlive the request.
  ///
  /// @param agentInvocationError the failed agent invocation
  @Override
  public void onAgentInvocationError(AgentInvocationError agentInvocationError) {
    if (agentInvocationError.agent().parent() == null) {
      agentCallsByRunId.remove(agentInvocationError.agenticScope().memoryId());
    }
  }

  /// Applies this listener to the specialist agents.
  ///
  /// @return always `true`
  @Override
  public boolean inheritedBySubagents() {
    return true;
  }

  /// Removes and returns a finished run's specialist calls.
  ///
  /// @param agenticScope the scope of the Supervisor run
  /// @return the specialist calls in order; empty when the Supervisor called no specialist
  public List<AgentCall> takeAgentCalls(AgenticScope agenticScope) {
    List<AgentCall> agentCalls = agentCallsByRunId.remove(agenticScope.memoryId());
    return agentCalls == null ? List.of() : List.copyOf(agentCalls);
  }
}
