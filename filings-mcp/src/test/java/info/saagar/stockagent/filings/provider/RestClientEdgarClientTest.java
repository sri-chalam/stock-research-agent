package info.saagar.stockagent.filings.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RestClientEdgarClientTest {

  private static final String EDGAR_BASE_URL = "https://edgar.test";
  private static final String MSFT_SUBMISSIONS_URL =
      EDGAR_BASE_URL + "/submissions/CIK0000789019.json";
  private static final String SEC_USER_AGENT = "synthetic-agent test@example.com";

  private final RestClient.Builder restClientBuilder = RestClient.builder().baseUrl(EDGAR_BASE_URL);
  private final MockRestServiceServer edgarServer =
      MockRestServiceServer.bindTo(restClientBuilder).build();
  private final RestClientEdgarClient edgarClient =
      new RestClientEdgarClient(restClientBuilder.build(), SEC_USER_AGENT);

  @Test
  void sendsUserAgentAndMapsRecentFilingsToRows() {
    edgarServer
        .expect(requestTo(MSFT_SUBMISSIONS_URL))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("User-Agent", SEC_USER_AGENT))
        .andRespond(
            withSuccess(
                """
                {"cik":"789019","name":"SYNTHETIC CORP","tickers":["MSFT"],"filings":{"recent":{\
                "accessionNumber":["0000950170-26-000002","0000950170-26-000001"],\
                "filingDate":["2026-07-30","2026-04-25"],\
                "reportDate":["2026-06-30",""],\
                "acceptanceDateTime":["2026-07-30T16:05:00.000Z","2026-04-25T16:05:00.000Z"],\
                "form":["10-K","8-K"],\
                "primaryDocument":["syn-20260630.htm","syn-8k.htm"],\
                "isXBRL":[1,0]},"files":[]}}""",
                MediaType.APPLICATION_JSON));

    assertThat(edgarClient.fetchRecentFilings("0000789019"))
        .containsExactly(
            new EdgarFiling(
                "10-K", "2026-07-30", "2026-06-30", "0000950170-26-000002", "syn-20260630.htm"),
            new EdgarFiling("8-K", "2026-04-25", null, "0000950170-26-000001", "syn-8k.htm"));
    edgarServer.verify();
  }

  @ParameterizedTest
  @CsvSource({
    "403, SEC EDGAR rejected the request (check SEC_USER_AGENT).",
    "429, SEC EDGAR rate limit reached; try again shortly.",
    "503, SEC EDGAR is unavailable."
  })
  void mapsHttpErrorStatusToFixedMessage(int httpStatus, String expectedMessage) {
    edgarServer
        .expect(requestTo(MSFT_SUBMISSIONS_URL))
        .andRespond(withStatus(HttpStatus.valueOf(httpStatus)));

    assertThatThrownBy(() -> edgarClient.fetchRecentFilings("0000789019"))
        .isInstanceOf(FilingsException.class)
        .hasMessage(expectedMessage);
  }

  @Test
  void mapsTimeoutToFixedMessage() {
    edgarServer
        .expect(requestTo(MSFT_SUBMISSIONS_URL))
        .andRespond(withException(new SocketTimeoutException("Read timed out")));

    assertThatThrownBy(() -> edgarClient.fetchRecentFilings("0000789019"))
        .isInstanceOf(FilingsException.class)
        .hasMessage("SEC EDGAR timed out.");
  }
}
