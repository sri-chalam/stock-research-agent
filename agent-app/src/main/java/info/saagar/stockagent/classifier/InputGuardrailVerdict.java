package info.saagar.stockagent.classifier;

/// The input guardrail's decision on a prompt, taken from the prompt classifier's flags.
public enum InputGuardrailVerdict {
  /// Neither flag was set: the prompt goes on to the cache and the agents.
  PASSED,
  /// The classifier failed or returned no flags: the guardrail fails open and the agents run.
  SKIPPED,
  /// The prompt seems to try to override the assistant's instructions: it is refused.
  INJECTION,
  /// The prompt asks for personalized buy or sell advice: it is refused.
  ADVICE;

  /// Returns whether the prompt is refused before the cache and the agents.
  ///
  /// @return `true` for `INJECTION` and `ADVICE`
  public boolean blocked() {
    return this == INJECTION || this == ADVICE;
  }
}
