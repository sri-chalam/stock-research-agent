package info.saagar.stockagent.filings.config;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/// SEC EDGAR connection settings and the supported tickers, bound from `filings.*` in
/// `application.yml`.
///
/// The User-Agent is not here: it is read from the `SEC_USER_AGENT` environment variable.
///
/// @param baseUrl EDGAR API base URL, e.g. `https://data.sec.gov`
/// @param connectTimeout TCP connect timeout
/// @param readTimeout time to wait for the response
/// @param tickers 10-digit, zero-padded CIK by upper-case ticker, in configured order
@ConfigurationProperties("filings")
public record FilingsProperties(
    String baseUrl, Duration connectTimeout, Duration readTimeout, Map<String, String> tickers) {}
