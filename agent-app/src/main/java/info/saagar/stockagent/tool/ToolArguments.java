package info.saagar.stockagent.tool;

import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/// Parses the JSON arguments the model sends with a tool call.
public final class ToolArguments {

  private static final TypeReference<Map<String, Object>> ARGUMENTS_TYPE = new TypeReference<>() {};

  private ToolArguments() {}

  /// Parses a tool call's raw JSON arguments.
  ///
  /// @param jsonMapper mapper used to read the JSON
  /// @param rawArguments JSON object text from the model; may be null or blank
  /// @return the arguments by name, empty when none were sent
  public static Map<String, Object> parse(JsonMapper jsonMapper, String rawArguments) {
    return rawArguments == null || rawArguments.isBlank()
        ? Map.of()
        : jsonMapper.readValue(rawArguments, ARGUMENTS_TYPE);
  }
}
