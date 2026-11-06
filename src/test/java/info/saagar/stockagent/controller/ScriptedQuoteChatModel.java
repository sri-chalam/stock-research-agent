package info.saagar.stockagent.controller;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;

/// Fake model: first asks for `getQuote(symbol)`, then answers with the tool result text.
class ScriptedQuoteChatModel implements ChatModel {

  private final String symbolToRequest;

  ScriptedQuoteChatModel(String symbolToRequest) {
    this.symbolToRequest = symbolToRequest;
  }

  @Override
  public ChatResponse doChat(ChatRequest chatRequest) {
    List<ChatMessage> messages = chatRequest.messages();
    ChatMessage lastMessage = messages.get(messages.size() - 1);
    if (lastMessage instanceof ToolExecutionResultMessage toolResult) {
      return ChatResponse.builder()
          .aiMessage(AiMessage.from("Result: " + toolResult.text()))
          .build();
    }
    ToolExecutionRequest getQuoteRequest =
        ToolExecutionRequest.builder()
            .id("call-1")
            .name("getQuote")
            .arguments("{\"symbol\":\"" + symbolToRequest + "\"}")
            .build();
    return ChatResponse.builder().aiMessage(AiMessage.from(getQuoteRequest)).build();
  }
}
