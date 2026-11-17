package info.saagar.stockagent.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/// Prompt classifier settings bound from `stock-agent.classifier` in `application.yml`.
///
/// @param model model ID, e.g. `claude-haiku-5-5`
/// @param promptId ID of the system prompt version; the prompt is read from
///   `prompts/<promptId>.txt`
/// @param maxTokens maximum output tokens of the classifier call
/// @param timeout timeout of the classifier call
@ConfigurationProperties("stock-agent.classifier")
public record ClassifierProperties(
    String model, String promptId, int maxTokens, Duration timeout) {}
