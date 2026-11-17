package info.saagar.stockagent.controller;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import info.saagar.stockagent.config.FilingsAgentConfig;
import info.saagar.stockagent.config.MarketDataAgentConfig;
import info.saagar.stockagent.config.PortfolioAgentConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/// Fake model that plays both the Supervisor's planner and the specialist agents.
///
/// As the planner, it sends the user's request to each specialist in its script, one per planning
/// call, then replies `done` with a merged response holding every specialist's answer. This depends
/// on the beta `SupervisorPlanner` prompt format, so it may need updating when
/// `langchain4j-agentic` changes.
///
/// As a specialist, it first calls one tool (`listFilings` for the Filings analyst, `getHoldings`
/// with no arguments for the Portfolio agent, `getQuote` for the Market data agent) for the symbol,
/// then answers with the tool result text.
class ScriptedAgentsChatModel implements ChatModel {

  private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();
  private static final Pattern USER_REQUEST = Pattern.compile("The user request is: '(.*?)'\\.");
  private static final Pattern LAST_RESPONSE =
      Pattern.compile("The last received response is: '(.*?)'\\.", Pattern.DOTALL);

  private final List<String> specialistsToCall;
  private final String symbolToRequest;

  /// @param specialistsToCall specialist agent names the planner calls, in order
  /// @param symbolToRequest symbol each specialist passes to its tool
  ScriptedAgentsChatModel(List<String> specialistsToCall, String symbolToRequest) {
    this.specialistsToCall = specialistsToCall;
    this.symbolToRequest = symbolToRequest;
  }

  /// A planner that sends the request to the Market data agent only.
  static ScriptedAgentsChatModel marketDataOnly(String symbolToRequest) {
    return new ScriptedAgentsChatModel(
        List.of(MarketDataAgentConfig.MARKET_DATA_AGENT_NAME), symbolToRequest);
  }

  /// A planner that sends the request to the Filings analyst, then the Market data agent.
  static ScriptedAgentsChatModel filingsThenMarketData(String symbolToRequest) {
    return new ScriptedAgentsChatModel(
        List.of(
            FilingsAgentConfig.FILINGS_AGENT_NAME, MarketDataAgentConfig.MARKET_DATA_AGENT_NAME),
        symbolToRequest);
  }

  /// A planner that sends the request to the Portfolio agent only.
  static ScriptedAgentsChatModel portfolioOnly() {
    return new ScriptedAgentsChatModel(List.of(PortfolioAgentConfig.PORTFOLIO_AGENT_NAME), "");
  }

  /// A planner that sends the request to the Portfolio agent, then the Market data agent.
  static ScriptedAgentsChatModel portfolioThenMarketData(String symbolToRequest) {
    return new ScriptedAgentsChatModel(
        List.of(
            PortfolioAgentConfig.PORTFOLIO_AGENT_NAME,
            MarketDataAgentConfig.MARKET_DATA_AGENT_NAME),
        symbolToRequest);
  }

  @Override
  public ChatResponse doChat(ChatRequest chatRequest) {
    String systemText = systemText(chatRequest.messages());
    if (systemText.contains("planner")) {
      return plan(chatRequest.messages());
    }
    if (systemText.contains("Portfolio agent")) {
      return answerAsSpecialist(chatRequest.messages(), "getHoldings", "{}");
    }
    String toolName = systemText.contains("Filings analyst") ? "listFilings" : "getQuote";
    return answerAsSpecialist(
        chatRequest.messages(), toolName, "{\"symbol\":\"" + symbolToRequest + "\"}");
  }

  private ChatResponse plan(List<ChatMessage> messages) {
    List<String> lastResponses = new ArrayList<>();
    String userRequest = "";
    for (ChatMessage message : messages) {
      if (message instanceof UserMessage userMessage) {
        String plannerInput = userMessage.singleText();
        Matcher userRequestMatcher = USER_REQUEST.matcher(plannerInput);
        if (userRequestMatcher.find()) {
          userRequest = userRequestMatcher.group(1);
        }
        Matcher lastResponseMatcher = LAST_RESPONSE.matcher(plannerInput);
        if (lastResponseMatcher.find() && !lastResponseMatcher.group(1).isEmpty()) {
          lastResponses.add(lastResponseMatcher.group(1));
        }
      }
    }
    int planningCallCount = lastResponses.size();
    Map<String, Object> agentInvocation =
        planningCallCount < specialistsToCall.size()
            ? Map.of(
                "agentName",
                specialistsToCall.get(planningCallCount),
                "arguments",
                Map.of("request", userRequest))
            : Map.of(
                "agentName",
                "done",
                "arguments",
                Map.of("response", "Merged: " + String.join(" | ", lastResponses)));
    return ChatResponse.builder()
        .aiMessage(AiMessage.from(JSON_MAPPER.writeValueAsString(agentInvocation)))
        .build();
  }

  private static ChatResponse answerAsSpecialist(
      List<ChatMessage> messages, String toolName, String toolArguments) {
    if (messages.getLast() instanceof ToolExecutionResultMessage toolResult) {
      return ChatResponse.builder()
          .aiMessage(AiMessage.from("Result: " + toolResult.text()))
          .build();
    }
    ToolExecutionRequest toolRequest =
        ToolExecutionRequest.builder().id("call-1").name(toolName).arguments(toolArguments).build();
    return ChatResponse.builder().aiMessage(AiMessage.from(toolRequest)).build();
  }

  private static String systemText(List<ChatMessage> messages) {
    return messages.stream()
        .filter(SystemMessage.class::isInstance)
        .map(message -> ((SystemMessage) message).text())
        .findFirst()
        .orElse("");
  }
}
