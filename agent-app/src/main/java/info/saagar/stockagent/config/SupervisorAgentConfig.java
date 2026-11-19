package info.saagar.stockagent.config;

import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.supervisor.SupervisorAgent;
import dev.langchain4j.agentic.supervisor.SupervisorResponseStrategy;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import info.saagar.stockagent.agent.FilingsAgent;
import info.saagar.stockagent.agent.MarketDataAgent;
import info.saagar.stockagent.agent.PortfolioAgent;
import info.saagar.stockagent.guardrail.ResearchAnswerGuardrail;
import info.saagar.stockagent.logging.AgentProvenanceListener;
import info.saagar.stockagent.logging.ToolExecutionLogger;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

/// Wires the Supervisor, which plans each request, delegates sub-questions to the specialist agents
/// and merges their answers.
@Configuration
@EnableConfigurationProperties(AgentProperties.class)
public class SupervisorAgentConfig {

  private static final Logger log = LoggerFactory.getLogger(SupervisorAgentConfig.class);

  /// Key of the Supervisor's settings in `application.yml`; also logged as its agent name.
  public static final String SUPERVISOR_AGENT_NAME = "supervisor";

  /// ID of the Supervisor's prompt version, taken from its filename and logged with every answer.
  public static final String SUPERVISOR_PROMPT_ID = "supervisor-v2";

  /// Agentic-scope key of the planner's summary, before the output guardrail rewrites it.
  private static final String SUPERVISOR_ANSWER_KEY = "supervisorAnswer";

  /// Builds the output guardrail applied to every Supervisor answer.
  ///
  /// @return the guardrail
  @Bean
  public ResearchAnswerGuardrail researchAnswerGuardrail() {
    return new ResearchAnswerGuardrail();
  }

  /// Builds the Claude chat model for the Supervisor's planner from its `application.yml` settings.
  ///
  /// @param agentProperties per-agent settings
  /// @param anthropicApiKey API key from the `ANTHROPIC_API_KEY` environment variable
  /// @param agentProvenanceListener counts the model's tokens per run
  /// @return the chat model
  @Bean
  public ChatModel supervisorChatModel(
      AgentProperties agentProperties,
      @Value("${ANTHROPIC_API_KEY}") String anthropicApiKey,
      AgentProvenanceListener agentProvenanceListener) {
    AgentProperties.AgentSettings supervisorSettings =
        agentProperties.forAgent(SUPERVISOR_AGENT_NAME);
    return AnthropicChatModel.builder()
        .apiKey(anthropicApiKey)
        .modelName(supervisorSettings.model())
        .maxTokens(supervisorSettings.maxTokens())
        .timeout(supervisorSettings.timeout())
        .listeners(List.of(agentProvenanceListener.tokenListener(SUPERVISOR_AGENT_NAME)))
        .build();
  }

  /// Builds the Supervisor over the Filings analyst, the Market data agent and the Portfolio agent.
  ///
  /// It returns the planner's summary of the specialists' answers (`SUMMARY`), and `max-steps` caps
  /// how many specialist invocations one run may make. Without chat memory, each request is a fresh
  /// run.
  ///
  /// The summary goes through [ResearchAnswerGuardrail] before it is returned. The supervisor
  /// builder has no `outputGuardrails` option, and the planner's own model calls return routing
  /// JSON, so the guardrail is applied in the builder's `output` function, which every finished run
  /// passes through.
  ///
  /// @param supervisorChatModel chat model the planner uses
  /// @param filingsAgent the Filings analyst
  /// @param marketDataAgent the Market data agent
  /// @param portfolioAgent the Portfolio agent
  /// @param agentProperties per-agent settings
  /// @param agentProvenanceListener records which specialists and tools each run used
  /// @param toolExecutionLogger logs each tool call the specialists make
  /// @param researchAnswerGuardrail rewrites the final answer (links and disclaimer)
  /// @return the Supervisor
  /// @throws IOException when the prompt resource cannot be read
  @Bean
  public SupervisorAgent supervisorAgent(
      ChatModel supervisorChatModel,
      FilingsAgent filingsAgent,
      MarketDataAgent marketDataAgent,
      PortfolioAgent portfolioAgent,
      AgentProperties agentProperties,
      AgentProvenanceListener agentProvenanceListener,
      ToolExecutionLogger toolExecutionLogger,
      ResearchAnswerGuardrail researchAnswerGuardrail)
      throws IOException {
    String supervisorPrompt =
        new ClassPathResource("prompts/" + SUPERVISOR_PROMPT_ID + ".txt")
            .getContentAsString(StandardCharsets.UTF_8);
    log.atDebug()
        .addKeyValue("eventName", "supervisorPromptLoaded")
        .addKeyValue(
            "eventData",
            Map.of(
                "promptId", SUPERVISOR_PROMPT_ID, "promptLengthChars", supervisorPrompt.length()))
        .log("Supervisor prompt loaded");
    return AgenticServices.supervisorBuilder()
        .name(SUPERVISOR_AGENT_NAME)
        .chatModel(supervisorChatModel)
        .subAgents(filingsAgent, marketDataAgent, portfolioAgent)
        .responseStrategy(SupervisorResponseStrategy.SUMMARY)
        .outputKey(SUPERVISOR_ANSWER_KEY)
        .output(
            agenticScope ->
                researchAnswerGuardrail
                    .validate(
                        AiMessage.from((String) agenticScope.readState(SUPERVISOR_ANSWER_KEY)))
                    .successfulText())
        .supervisorContext(supervisorPrompt)
        .maxAgentsInvocations(agentProperties.forAgent(SUPERVISOR_AGENT_NAME).maxSteps())
        .listener(agentProvenanceListener)
        .listener(toolExecutionLogger)
        .build();
  }
}
