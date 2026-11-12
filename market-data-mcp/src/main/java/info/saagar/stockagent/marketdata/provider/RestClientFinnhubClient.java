package info.saagar.stockagent.marketdata.provider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/// [FinnhubClient] over Spring's `RestClient`.
///
/// The API key is sent in the `X-Finnhub-Token` header, never as a query parameter, so it stays out
/// of URLs and access logs. Each failure is logged once here as `finnhubCallFailed` and rethrown as
/// a [MarketDataException] with a fixed message. The MCP library turns that exception into a tool
/// error without logging it, so this is the only place it is logged.
public class RestClientFinnhubClient implements FinnhubClient {

  private static final Logger log = LoggerFactory.getLogger(RestClientFinnhubClient.class);

  private static final String TOKEN_HEADER = "X-Finnhub-Token";
  private static final String QUOTE_ENDPOINT = "/quote";

  static final String AUTHORIZATION_MESSAGE =
      "Market data provider rejected the request (authorization).";
  static final String RATE_LIMIT_MESSAGE = "Market data rate limit reached; try again in a minute.";
  static final String REJECTED_MESSAGE = "Market data provider rejected the request.";
  static final String UNAVAILABLE_MESSAGE = "Market data provider is unavailable.";
  static final String TIMED_OUT_MESSAGE = "Market data provider timed out.";

  private final RestClient finnhubRestClient;
  private final String finnhubApiKey;

  /// Creates the client.
  ///
  /// @param finnhubRestClient client with the Finnhub base URL and timeouts already set
  /// @param finnhubApiKey Finnhub API key
  public RestClientFinnhubClient(RestClient finnhubRestClient, String finnhubApiKey) {
    this.finnhubRestClient = finnhubRestClient;
    this.finnhubApiKey = finnhubApiKey;
  }

  /// {@inheritDoc}
  @Override
  public Optional<Quote> fetchQuote(String tickerSymbol) {
    long startNanos = System.nanoTime();
    FinnhubQuoteResponse quoteResponse;
    try {
      quoteResponse =
          finnhubRestClient
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder.path(QUOTE_ENDPOINT).queryParam("symbol", tickerSymbol).build())
              .header(TOKEN_HEADER, finnhubApiKey)
              .retrieve()
              .body(FinnhubQuoteResponse.class);
    } catch (RestClientResponseException statusException) {
      int httpStatus = statusException.getStatusCode().value();
      throw callFailed(httpStatus, messageForStatus(httpStatus), statusException, startNanos);
    } catch (ResourceAccessException ioException) {
      throw callFailed(null, TIMED_OUT_MESSAGE, ioException, startNanos);
    } catch (RestClientException unreadableResponseException) {
      throw callFailed(null, UNAVAILABLE_MESSAGE, unreadableResponseException, startNanos);
    }
    boolean quoteAvailable = quoteResponse != null && quoteResponse.t() != 0;
    log.atInfo()
        .addKeyValue("eventName", "finnhubCallCompleted")
        .addKeyValue(
            "eventData",
            Map.of(
                "finnhubEndpoint",
                QUOTE_ENDPOINT,
                "tickerSymbol",
                tickerSymbol,
                "quoteAvailable",
                quoteAvailable,
                "durationTimeMs",
                elapsedMillis(startNanos)))
        .log("Finnhub call completed");
    return quoteAvailable ? Optional.of(quoteResponse.toQuote(tickerSymbol)) : Optional.empty();
  }

  private static String messageForStatus(int httpStatus) {
    if (httpStatus == 401 || httpStatus == 403) {
      return AUTHORIZATION_MESSAGE;
    }
    if (httpStatus == 429) {
      return RATE_LIMIT_MESSAGE;
    }
    return httpStatus >= 500 ? UNAVAILABLE_MESSAGE : REJECTED_MESSAGE;
  }

  /// Logs the failure once and builds the exception to throw.
  ///
  /// The cause is not attached: Spring's exception messages carry the URL or the upstream body.
  private static MarketDataException callFailed(
      Integer httpStatus, String modelReadableMessage, Exception cause, long startNanos) {
    boolean clientSideRejection = httpStatus != null && httpStatus < 500;
    Map<String, Object> failureData = new LinkedHashMap<>();
    failureData.put("httpStatus", httpStatus);
    failureData.put("finnhubEndpoint", QUOTE_ENDPOINT);
    failureData.put("exceptionType", cause.getClass().getName());
    failureData.put("durationTimeMs", elapsedMillis(startNanos));
    log.atLevel(clientSideRejection ? Level.WARN : Level.ERROR)
        .addKeyValue("eventName", "finnhubCallFailed")
        .addKeyValue("eventData", failureData)
        .log("Finnhub call failed");
    return new MarketDataException(modelReadableMessage);
  }

  private static long elapsedMillis(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }

  /// Finnhub's `/quote` response, with its one-letter field names.
  private record FinnhubQuoteResponse(
      BigDecimal c,
      BigDecimal d,
      BigDecimal dp,
      BigDecimal h,
      BigDecimal l,
      BigDecimal o,
      BigDecimal pc,
      long t) {

    Quote toQuote(String tickerSymbol) {
      return new Quote(tickerSymbol, c, d, dp, h, l, o, pc, Instant.ofEpochSecond(t).toString());
    }
  }
}
