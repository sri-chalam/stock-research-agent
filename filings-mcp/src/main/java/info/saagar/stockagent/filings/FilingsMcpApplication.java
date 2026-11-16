package info.saagar.stockagent.filings;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/// Entry point of the filings MCP server.
///
/// Scans its own package and `common`, which sits outside it.
@SpringBootApplication(
    scanBasePackages = {"info.saagar.stockagent.filings", "info.saagar.stockagent.common"})
public class FilingsMcpApplication {

  /// Starts the server.
  ///
  /// @param args command-line arguments
  public static void main(String[] args) {
    SpringApplication.run(FilingsMcpApplication.class, args);
  }
}
