package info.saagar.stockagent.classifier;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;

/// The prompt classifier's single LLM call: a LangChain4j AI service with no tools and no memory.
/// Its system prompt is set in `PromptClassifierConfig` from the configured prompt ID.
public interface ClassifierAiService {

  /// Classifies a user's prompt.
  ///
  /// @param prompt the user's chat prompt
  /// @return the classification, with the model response that holds its tokens and model name
  Result<Classification> classify(@UserMessage String prompt);
}
