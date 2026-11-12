package info.saagar.stockagent.marketdata.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/// Finnhub connection settings bound from `finnhub.*` in `application.yml`.
///
/// The API key is not here: it is read from the `FINNHUB_API_KEY` environment variable.
///
/// @param baseUrl Finnhub REST API base URL, e.g. `https://finnhub.io/api/v1`
/// @param connectTimeout TCP connect timeout
/// @param readTimeout time to wait for the response
@ConfigurationProperties("finnhub")
public record FinnhubProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {}
