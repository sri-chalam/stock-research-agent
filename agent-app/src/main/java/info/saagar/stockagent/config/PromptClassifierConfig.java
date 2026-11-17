package info.saagar.stockagent.config;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import info.saagar.stockagent.classifier.ClassifierAiService;
import info.saagar.stockagent.classifier.PromptClassifier;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

/// Wires the prompt classifier: one LLM call with structured output, no tools and no memory.
@Configuration
@EnableConfigurationProperties(ClassifierProperties.class)
public class PromptClassifierConfig {

  private static final Logger log = LoggerFactory.getLogger(PromptClassifierConfig.class);

  /// Name the classifier's tokens and model are logged under in `answerProvenance`.
  public static final String CLASSIFIER_NAME = "classifier";

  /// Builds the Claude chat model for the classifier from its `application.yml` settings.
  ///
  /// It declares JSON-schema response format, so the classification comes back as structured
  /// output. It has no token listener: the classifier runs before the Supervisor's run exists, so
  /// its tokens are taken from its response instead.
  ///
  /// @param classifierProperties the classifier's settings
  /// @param anthropicApiKey API key from the `ANTHROPIC_API_KEY` environment variable
  /// @return the chat model
  @Bean
  public ChatModel classifierChatModel(
      ClassifierProperties classifierProperties,
      @Value("${ANTHROPIC_API_KEY}") String anthropicApiKey) {
    return AnthropicChatModel.builder()
        .apiKey(anthropicApiKey)
        .modelName(classifierProperties.model())
        .maxTokens(classifierProperties.maxTokens())
        .timeout(classifierProperties.timeout())
        .supportedCapabilities(Capability.RESPONSE_FORMAT_JSON_SCHEMA)
        .build();
  }

  /// Builds the prompt classifier around the chat model and the configured prompt version.
  ///
  /// @param classifierChatModel chat model used by the classifier
  /// @param classifierProperties the classifier's settings, holding the prompt ID
  /// @return the prompt classifier
  /// @throws IOException when the prompt resource cannot be read
  @Bean
  public PromptClassifier promptClassifier(
      ChatModel classifierChatModel, ClassifierProperties classifierProperties) throws IOException {
    String classifierPrompt =
        new ClassPathResource("prompts/" + classifierProperties.promptId() + ".txt")
            .getContentAsString(StandardCharsets.UTF_8);
    log.atDebug()
        .addKeyValue("eventName", "classifierPromptLoaded")
        .addKeyValue(
            "eventData",
            Map.of(
                "promptId",
                classifierProperties.promptId(),
                "promptLengthChars",
                classifierPrompt.length()))
        .log("Classifier prompt loaded");
    ClassifierAiService classifierAiService =
        AiServices.builder(ClassifierAiService.class)
            .chatModel(classifierChatModel)
            .systemMessage(classifierPrompt)
            .build();
    return new PromptClassifier(classifierAiService);
  }
}
