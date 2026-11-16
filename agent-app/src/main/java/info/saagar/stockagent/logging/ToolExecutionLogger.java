package info.saagar.stockagent.logging;

import dev.langchain4j.agentic.observability.AfterAgentToolExecution;
import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.service.tool.ToolExecution;
import info.saagar.stockagent.tool.ToolArguments;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/// Logs a `toolExecuted` event after every tool call an agent makes.
///
/// Registered on the Supervisor and inherited by its specialist agents. Agents run on the request
/// thread, so the log line carries the request's MDC fields.
@Component
public class ToolExecutionLogger implements AgentListener {

  private static final Logger log = LoggerFactory.getLogger(ToolExecutionLogger.class);

  private final JsonMapper jsonMapper;

  /// Creates the logger.
  ///
  /// @param jsonMapper mapper used to parse tool-call arguments
  public ToolExecutionLogger(JsonMapper jsonMapper) {
    this.jsonMapper = jsonMapper;
  }

  /// Logs the agent, tool name, arguments, outcome and duration of one tool call.
  ///
  /// @param afterAgentToolExecution the finished tool call and the agent that made it
  @Override
  public void afterAgentToolExecution(AfterAgentToolExecution afterAgentToolExecution) {
    ToolExecution toolExecution = afterAgentToolExecution.toolExecution();
    log.atInfo()
        .addKeyValue("eventName", "toolExecuted")
        .addKeyValue(
            "eventData",
            Map.of(
                "agentName",
                afterAgentToolExecution.agentInstance().name(),
                "toolName",
                toolExecution.request().name(),
                "toolArguments",
                ToolArguments.parse(jsonMapper, toolExecution.request().arguments()),
                "toolFailed",
                toolExecution.hasFailed(),
                "toolExecDurationTimeMs",
                toolExecution.duration().toMillis()))
        .log("Tool executed");
  }

  /// Applies this listener to the specialist agents, which make the tool calls.
  ///
  /// @return always `true`
  @Override
  public boolean inheritedBySubagents() {
    return true;
  }
}
