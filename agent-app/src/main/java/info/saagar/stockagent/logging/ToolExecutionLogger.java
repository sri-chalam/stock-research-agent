package info.saagar.stockagent.logging;

import dev.langchain4j.service.tool.ToolExecution;
import info.saagar.stockagent.tool.ToolArguments;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/// Logs a `toolExecuted` event after every tool call an agent makes.
///
/// Registered with `AiServices.afterToolExecution`, so it runs on the request thread and the log
/// line carries the request's MDC fields.
@Component
public class ToolExecutionLogger implements Consumer<ToolExecution> {

  private static final Logger log = LoggerFactory.getLogger(ToolExecutionLogger.class);

  private final JsonMapper jsonMapper;

  /// Creates the logger.
  ///
  /// @param jsonMapper mapper used to parse tool-call arguments
  public ToolExecutionLogger(JsonMapper jsonMapper) {
    this.jsonMapper = jsonMapper;
  }

  /// Logs the tool name, arguments, outcome and duration of one tool call.
  ///
  /// @param toolExecution the finished tool call
  @Override
  public void accept(ToolExecution toolExecution) {
    log.atInfo()
        .addKeyValue("eventName", "toolExecuted")
        .addKeyValue(
            "eventData",
            Map.of(
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
}
