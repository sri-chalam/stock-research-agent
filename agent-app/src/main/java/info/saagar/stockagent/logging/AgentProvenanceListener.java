package info.saagar.stockagent.logging;

import dev.langchain4j.agentic.observability.AfterAgentToolExecution;
import dev.langchain4j.agentic.observability.AgentInvocationError;
import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.agentic.observability.AgentRequest;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.invocation.LangChain4jManaged;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/// Records a [RunProvenance] per Supervisor run: the specialist agents called in order, the tools
/// each one called, and the tokens and model each agent used.
///
/// Registered on the Supervisor and inherited by its specialist agents. A run is keyed by its
/// agentic scope's memory ID, which is a fresh UUID for each request because the Supervisor has no
/// memory. Token counts and model names come from the [ChatModelListener] that
/// [#tokenListener(String)] returns, one per agent's chat model. The caller takes the run with
/// [#takeRunProvenance(AgenticScope)] when the Supervisor returns; a run that fails is dropped.
@Component
public class AgentProvenanceListener implements AgentListener {

  private final Map<Object, RunProvenance> runProvenanceByRunId = new ConcurrentHashMap<>();

  /// Records a specialist invocation. The Supervisor's own invocation is not recorded.
  ///
  /// @param agentRequest the agent about to run
  @Override
  public void beforeAgentInvocation(AgentRequest agentRequest) {
    if (agentRequest.agent().parent() == null) {
      return;
    }
    runProvenance(agentRequest.agenticScope()).agentInvoked(agentRequest.agentName());
  }

  /// Adds a tool call to the latest invocation of the agent that made it.
  ///
  /// @param afterAgentToolExecution the finished tool call and the agent that made it
  @Override
  public void afterAgentToolExecution(AfterAgentToolExecution afterAgentToolExecution) {
    runProvenance(afterAgentToolExecution.agenticScope())
        .toolExecuted(
            afterAgentToolExecution.agentInstance().name(),
            afterAgentToolExecution.toolExecution());
  }

  /// Drops a failed run, so its record does not outlive the request.
  ///
  /// @param agentInvocationError the failed agent invocation
  @Override
  public void onAgentInvocationError(AgentInvocationError agentInvocationError) {
    if (agentInvocationError.agent().parent() == null) {
      runProvenanceByRunId.remove(agentInvocationError.agenticScope().memoryId());
    }
  }

  /// Applies this listener to the specialist agents.
  ///
  /// @return always `true`
  @Override
  public boolean inheritedBySubagents() {
    return true;
  }

  /// Returns a listener for one agent's chat model that adds each response's tokens and model name
  /// to the current run under that agent's name.
  ///
  /// The run is the agentic scope that `langchain4j-agentic` binds to the calling thread around
  /// every planner and specialist LLM call (`LangChain4jManaged`, an internal API of the beta
  /// module). Chat model listeners run on that same thread. A call made outside an agentic run is
  /// not counted.
  ///
  /// @param agentName the agent that owns the chat model, e.g. `supervisor`
  /// @return the chat model listener
  public ChatModelListener tokenListener(String agentName) {
    return new ChatModelListener() {
      @Override
      public void onResponse(ChatModelResponseContext responseContext) {
        AgenticScope agenticScope = LangChain4jManaged.current(AgenticScope.class);
        if (agenticScope != null) {
          runProvenance(agenticScope).modelResponded(agentName, responseContext.chatResponse());
        }
      }
    };
  }

  /// Removes and returns a finished run's provenance.
  ///
  /// @param agenticScope the scope of the Supervisor run
  /// @return the run's provenance; empty when nothing was recorded
  public RunProvenance takeRunProvenance(AgenticScope agenticScope) {
    RunProvenance runProvenance = runProvenanceByRunId.remove(agenticScope.memoryId());
    return runProvenance == null ? new RunProvenance() : runProvenance;
  }

  private RunProvenance runProvenance(AgenticScope agenticScope) {
    return runProvenanceByRunId.computeIfAbsent(
        agenticScope.memoryId(), runId -> new RunProvenance());
  }
}
