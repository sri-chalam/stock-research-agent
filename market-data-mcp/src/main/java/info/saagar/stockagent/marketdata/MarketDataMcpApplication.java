package info.saagar.stockagent.marketdata;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/// Entry point of the market-data MCP server.
///
/// Scans its own package and `common`, which sits outside it.
@SpringBootApplication(
    scanBasePackages = {"info.saagar.stockagent.marketdata", "info.saagar.stockagent.common"})
public class MarketDataMcpApplication {

  /// Starts the server.
  ///
  /// @param args command-line arguments
  public static void main(String[] args) {
    SpringApplication.run(MarketDataMcpApplication.class, args);
  }
}
