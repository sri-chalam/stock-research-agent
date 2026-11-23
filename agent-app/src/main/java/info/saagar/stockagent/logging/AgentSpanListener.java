package info.saagar.stockagent.logging;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agentic.observability.AfterAgentToolExecution;
import dev.langchain4j.agentic.observability.AgentInvocationError;
import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.agentic.observability.AgentRequest;
import dev.langchain4j.agentic.observability.AgentResponse;
import dev.langchain4j.agentic.observability.BeforeAgentToolExecution;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/// Opens one span per Supervisor or specialist run, named after the agent, and one per tool call,
/// named after the tool, and makes each span current while it is open. The spans started under it
/// nest in it: an agent's LLM calls, tool calls and delegated specialists, and a tool call's MCP
/// request. Spans carry names and durations only (ADR 0013).
///
/// `langchain4j-agentic` reports an agent's start and end on the thread that runs the agent, and a
/// tool call's start and end on the thread that runs the tool, which for the sequential Supervisor
/// and its specialists is the request thread. A span is therefore opened, made current and ended on
/// one thread. The open spans of a thread form a stack: an agent's span lies below the spans of the
/// tools and specialists it calls. Ending a span first ends any span still open above it, such as a
/// tool call whose end was never reported because the agent failed.
///
/// Registered on the Supervisor and inherited by its specialist agents.
@Component
public class AgentSpanListener implements AgentListener {

  private static final Logger log = LoggerFactory.getLogger(AgentSpanListener.class);

  private final Tracer tracer;
  private final ThreadLocal<Deque<OpenSpan>> openSpansOfThread = new ThreadLocal<>();

  /// Creates the listener.
  ///
  /// @param tracer creates the spans; a no-op without the OpenTelemetry Java agent
  public AgentSpanListener(Tracer tracer) {
    this.tracer = tracer;
  }

  /// Opens the agent's span.
  ///
  /// @param agentRequest the agent about to run
  @Override
  public void beforeAgentInvocation(AgentRequest agentRequest) {
    openSpan(agentRequest.agent(), agentRequest.agentName());
  }

  /// Ends the agent's span.
  ///
  /// @param agentResponse the finished agent run
  @Override
  public void afterAgentInvocation(AgentResponse agentResponse) {
    endSpan(agentResponse.agent());
  }

  /// Ends the failed agent's span.
  ///
  /// @param agentInvocationError the failed agent run
  @Override
  public void onAgentInvocationError(AgentInvocationError agentInvocationError) {
    endSpan(agentInvocationError.agent());
  }

  /// Opens the tool call's span.
  ///
  /// @param beforeAgentToolExecution the tool call about to run and the agent making it
  @Override
  public void beforeAgentToolExecution(BeforeAgentToolExecution beforeAgentToolExecution) {
    ToolExecutionRequest toolRequest = beforeAgentToolExecution.toolExecution().request();
    openSpan(toolRequest, toolRequest.name());
  }

  /// Ends the tool call's span.
  ///
  /// @param afterAgentToolExecution the finished tool call and the agent that made it
  @Override
  public void afterAgentToolExecution(AfterAgentToolExecution afterAgentToolExecution) {
    endSpan(afterAgentToolExecution.toolExecution().request());
  }

  /// Applies this listener to the specialist agents.
  ///
  /// @return always `true`
  @Override
  public boolean inheritedBySubagents() {
    return true;
  }

  /// Starts a span under the current one, makes it current and pushes it onto this thread's stack.
  private void openSpan(Object spanOwner, String spanName) {
    Span span = tracer.spanBuilder(spanName).startSpan();
    Deque<OpenSpan> openSpans = openSpansOfThread.get();
    if (openSpans == null) {
      openSpans = new ArrayDeque<>();
      openSpansOfThread.set(openSpans);
    }
    openSpans.push(new OpenSpan(spanOwner, span, span.makeCurrent()));
  }

  /// Ends the owner's span and every span opened above it, restoring the context below it, and logs
  /// `unendedSpansClosed` when spans above it were left open. Does nothing when this thread holds
  /// no span for the owner.
  private void endSpan(Object spanOwner) {
    Deque<OpenSpan> openSpans = openSpansOfThread.get();
    if (openSpans == null || !holdsSpanOf(openSpans, spanOwner)) {
      return;
    }
    int unendedSpanCount = 0;
    OpenSpan endedSpan;
    while (true) {
      endedSpan = openSpans.pop();
      endedSpan.scope().close();
      endedSpan.span().end();
      if (endedSpan.owns(spanOwner)) {
        break;
      }
      unendedSpanCount++;
    }
    if (openSpans.isEmpty()) {
      openSpansOfThread.remove();
    }
    if (unendedSpanCount > 0) {
      log.atWarn()
          .addKeyValue("eventName", "unendedSpansClosed")
          .addKeyValue("eventData", Map.of("unendedSpanCount", unendedSpanCount))
          .log("Spans whose end was never reported were closed with their parent");
    }
  }

  private static boolean holdsSpanOf(Deque<OpenSpan> openSpans, Object spanOwner) {
    for (OpenSpan openSpan : openSpans) {
      if (openSpan.owns(spanOwner)) {
        return true;
      }
    }
    return false;
  }

  /// An open span, the scope that made it current, and the agent or tool request it belongs to.
  ///
  /// @param spanOwner the agent instance or tool execution request, compared by identity
  /// @param span the open span
  /// @param scope closes to restore the context that was current before the span
  private record OpenSpan(Object spanOwner, Span span, Scope scope) {

    boolean owns(Object candidateOwner) {
      return spanOwner == candidateOwner;
    }
  }
}
