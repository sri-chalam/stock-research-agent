package info.saagar.stockagent.marketdata.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RestClientFinnhubClientTest {

  private static final String FINNHUB_BASE_URL = "https://finnhub.test/api/v1";
  private static final String AAPL_QUOTE_URL = FINNHUB_BASE_URL + "/quote?symbol=AAPL";
  private static final String FINNHUB_API_KEY = "synthetic-finnhub-key";

  private final RestClient.Builder restClientBuilder =
      RestClient.builder().baseUrl(FINNHUB_BASE_URL);
  private final MockRestServiceServer finnhubServer =
      MockRestServiceServer.bindTo(restClientBuilder).build();
  private final RestClientFinnhubClient finnhubClient =
      new RestClientFinnhubClient(restClientBuilder.build(), FINNHUB_API_KEY);

  @Test
  void sendsKeyInHeaderOnlyAndReturnsTrimmedQuote() {
    finnhubServer
        .expect(requestTo(AAPL_QUOTE_URL))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("X-Finnhub-Token", FINNHUB_API_KEY))
        .andExpect(
            finnhubRequest ->
                assertThat(finnhubRequest.getURI().toString()).doesNotContain(FINNHUB_API_KEY))
        .andRespond(
            withSuccess(
                """
                {"c":189.42,"d":-0.7,"dp":-0.37,"h":191.05,"l":188.1,"o":190.2,"pc":190.12,\
                "t":1777645800}""",
                MediaType.APPLICATION_JSON));

    Optional<Quote> quote = finnhubClient.fetchQuote("AAPL");

    assertThat(quote)
        .contains(
            new Quote(
                "AAPL",
                new BigDecimal("189.42"),
                new BigDecimal("-0.7"),
                new BigDecimal("-0.37"),
                new BigDecimal("191.05"),
                new BigDecimal("188.1"),
                new BigDecimal("190.2"),
                new BigDecimal("190.12"),
                "2026-05-01T14:30:00Z"));
    finnhubServer.verify();
  }

  @Test
  void returnsEmptyWhenFinnhubHasNoQuoteForSymbol() {
    finnhubServer
        .expect(requestTo(FINNHUB_BASE_URL + "/quote?symbol=ZZZZ"))
        .andRespond(
            withSuccess(
                """
                {"c":0,"d":null,"dp":null,"h":0,"l":0,"o":0,"pc":0,"t":0}""",
                MediaType.APPLICATION_JSON));

    assertThat(finnhubClient.fetchQuote("ZZZZ")).isEmpty();
  }

  @ParameterizedTest
  @CsvSource({
    "401, Market data provider rejected the request (authorization).",
    "403, Market data provider rejected the request (authorization).",
    "429, Market data rate limit reached; try again in a minute.",
    "500, Market data provider is unavailable.",
    "503, Market data provider is unavailable."
  })
  void mapsHttpErrorStatusToFixedMessage(int httpStatus, String expectedMessage) {
    finnhubServer
        .expect(requestTo(AAPL_QUOTE_URL))
        .andRespond(
            withStatus(HttpStatus.valueOf(httpStatus))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"upstream body " + FINNHUB_API_KEY + "\"}"));

    assertThatThrownBy(() -> finnhubClient.fetchQuote("AAPL"))
        .isInstanceOf(MarketDataException.class)
        .hasMessage(expectedMessage);
  }

  @Test
  void mapsTimeoutToFixedMessage() {
    finnhubServer
        .expect(requestTo(AAPL_QUOTE_URL))
        .andRespond(withException(new SocketTimeoutException("Read timed out")));

    assertThatThrownBy(() -> finnhubClient.fetchQuote("AAPL"))
        .isInstanceOf(MarketDataException.class)
        .hasMessage("Market data provider timed out.");
  }
}
