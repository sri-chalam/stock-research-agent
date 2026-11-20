package info.saagar.stockagent.controller;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/// Serves the chat page's public settings, so `index.html` holds no per-deployment values.
@RestController
public class UiConfigController {

  private static final Logger log = LoggerFactory.getLogger(UiConfigController.class);

  private final UiConfig uiConfig;

  /// Creates the controller.
  ///
  /// @param workosClientId the WorkOS application's client ID (`client_...`), which `authkit-js`
  ///   needs to sign the user in; public, not a secret
  public UiConfigController(@Value("${stock-agent.ui.workos-client-id}") String workosClientId) {
    this.uiConfig = new UiConfig(workosClientId);
  }

  /// The chat page's settings.
  ///
  /// @param workosClientId the WorkOS client ID; empty when `WORKOS_CLIENT_ID` is not set
  public record UiConfig(String workosClientId) {}

  /// Returns the chat page's settings; needs no token.
  ///
  /// @return the settings
  @GetMapping("/ui-config")
  public UiConfig uiConfig() {
    log.atDebug()
        .addKeyValue("eventName", "uiConfigServed")
        .addKeyValue(
            "eventData", Map.of("workosClientIdConfigured", !uiConfig.workosClientId().isBlank()))
        .log("UI config served");
    return uiConfig;
  }
}
