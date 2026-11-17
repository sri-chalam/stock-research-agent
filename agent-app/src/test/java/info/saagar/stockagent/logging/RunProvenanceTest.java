package info.saagar.stockagent.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.tool.ToolExecution;
import dev.langchain4j.service.tool.ToolExecutionResult;
import info.saagar.stockagent.logging.RunProvenance.AgentCall;
import info.saagar.stockagent.logging.RunProvenance.TokenCount;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RunProvenanceTest {

  private static ToolExecution toolExecution(String toolName) {
    return ToolExecution.builder()
        .request(ToolExecutionRequest.builder().id("call-" + toolName).name(toolName).build())
        .result(ToolExecutionResult.builder().resultText("result of " + toolName).build())
        .invocationContext(InvocationContext.builder().build())
        .build();
  }

  private static RunProvenance filingsThenMarketDataRun() {
    RunProvenance runProvenance = new RunProvenance();
    runProvenance.tokensUsed("supervisor", new TokenUsage(1000, 50));
    runProvenance.agentInvoked("filings");
    runProvenance.tokensUsed("filings", new TokenUsage(700, 40));
    runProvenance.toolExecuted("filings", toolExecution("getCompanyFacts"));
    runProvenance.tokensUsed("filings", new TokenUsage(900, 120));
    runProvenance.tokensUsed("supervisor", new TokenUsage(1300, 60));
    runProvenance.agentInvoked("market-data");
    runProvenance.tokensUsed("market-data", new TokenUsage(600, 30));
    runProvenance.toolExecuted("market-data", toolExecution("getQuote"));
    runProvenance.tokensUsed("market-data", new TokenUsage(650, 45));
    runProvenance.tokensUsed("supervisor", new TokenUsage(1500, 300));
    return runProvenance;
  }

  @Test
  void agentCallsListSpecialistsInOrderWithTheirTools() {
    RunProvenance runProvenance = filingsThenMarketDataRun();

    assertThat(runProvenance.agentCalls())
        .extracting(
            AgentCall::agentName,
            agentCall ->
                agentCall.toolExecutions().stream()
                    .map(toolExecution -> toolExecution.request().name())
                    .toList())
        .containsExactly(
            tuple("filings", List.of("getCompanyFacts")),
            tuple("market-data", List.of("getQuote")));
  }

  @Test
  void toolIsAddedToTheLatestCallOfTheAgentThatMadeIt() {
    RunProvenance runProvenance = new RunProvenance();
    runProvenance.agentInvoked("market-data");
    runProvenance.toolExecuted("market-data", toolExecution("getQuote"));
    runProvenance.agentInvoked("filings");
    runProvenance.agentInvoked("market-data");
    runProvenance.toolExecuted("market-data", toolExecution("getCompanyProfile"));

    assertThat(runProvenance.agentCalls())
        .extracting(
            AgentCall::agentName,
            agentCall ->
                agentCall.toolExecutions().stream()
                    .map(toolExecution -> toolExecution.request().name())
                    .toList())
        .containsExactly(
            tuple("market-data", List.of("getQuote")),
            tuple("filings", List.of()),
            tuple("market-data", List.of("getCompanyProfile")));
  }

  @Test
  void tokensAreSummedPerAgentInFirstUseOrder() {
    RunProvenance runProvenance = filingsThenMarketDataRun();

    assertThat(runProvenance.tokensByAgent())
        .containsExactly(
            Map.entry("supervisor", new TokenCount(3800, 410)),
            Map.entry("filings", new TokenCount(1600, 160)),
            Map.entry("market-data", new TokenCount(1250, 75)));
  }

  @Test
  void totalTokensAreTheSumOfEveryAgent() {
    RunProvenance runProvenance = filingsThenMarketDataRun();

    assertThat(runProvenance.totalTokens()).isEqualTo(new TokenCount(6650, 645));
  }

  @Test
  void supervisorOnlyRunHasNoAgentCallsButCountsSupervisorTokens() {
    RunProvenance runProvenance = new RunProvenance();
    runProvenance.tokensUsed("supervisor", new TokenUsage(800, 90));

    assertThat(runProvenance.agentCalls()).isEmpty();
    assertThat(runProvenance.tokensByAgent())
        .containsExactly(Map.entry("supervisor", new TokenCount(800, 90)));
    assertThat(runProvenance.totalTokens()).isEqualTo(new TokenCount(800, 90));
  }

  @Test
  void missingTokenCountsAreTreatedAsZero() {
    RunProvenance runProvenance = new RunProvenance();
    runProvenance.tokensUsed("supervisor", null);
    runProvenance.tokensUsed("supervisor", new TokenUsage(null, null));

    assertThat(runProvenance.tokensByAgent())
        .containsExactly(Map.entry("supervisor", new TokenCount(0, 0)));
    assertThat(runProvenance.totalTokens()).isEqualTo(new TokenCount(0, 0));
  }

  @Test
  void emptyRunHasZeroTotals() {
    RunProvenance runProvenance = new RunProvenance();

    assertThat(runProvenance.agentCalls()).isEmpty();
    assertThat(runProvenance.tokensByAgent()).isEmpty();
    assertThat(runProvenance.totalTokens()).isEqualTo(new TokenCount(0, 0));
  }

  private static ChatResponse modelResponse(String modelName, TokenUsage tokenUsage) {
    return ChatResponse.builder()
        .aiMessage(AiMessage.from("answer"))
        .modelName(modelName)
        .tokenUsage(tokenUsage)
        .build();
  }

  @Test
  void modelResponseRecordsItsModelAndAddsItsTokensPerAgent() {
    RunProvenance runProvenance = new RunProvenance();
    runProvenance.modelResponded(
        "supervisor", modelResponse("claude-haiku-5-5", new TokenUsage(1000, 50)));
    runProvenance.modelResponded(
        "filings", modelResponse("claude-sonnet-5-5", new TokenUsage(700, 40)));
    runProvenance.modelResponded(
        "supervisor", modelResponse("claude-haiku-5-5", new TokenUsage(300, 20)));
    runProvenance.modelResponded("classifier", modelResponse(null, new TokenUsage(120, 15)));

    assertThat(runProvenance.modelByAgent())
        .containsExactly(
            Map.entry("supervisor", "claude-haiku-5-5"), Map.entry("filings", "claude-sonnet-5-5"));
    assertThat(runProvenance.tokensByAgent())
        .containsEntry("supervisor", new TokenCount(1300, 70))
        .containsEntry("classifier", new TokenCount(120, 15));
  }
}
