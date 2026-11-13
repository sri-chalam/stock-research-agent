package info.saagar.stockagent.filings.provider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/// [EdgarClient] over Spring's `RestClient`.
///
/// SEC EDGAR requires a `User-Agent` with contact details; it is sent on every request and never
/// logged. Each failure is logged once here as `edgarCallFailed` and rethrown as a
/// [FilingsException] with a fixed message. The MCP library turns that exception into a tool error
/// without logging it, so this is the only place it is logged.
public class RestClientEdgarClient implements EdgarClient {

  private static final Logger log = LoggerFactory.getLogger(RestClientEdgarClient.class);

  private static final String SUBMISSIONS_ENDPOINT = "/submissions/CIK{cik}.json";

  static final String REJECTED_USER_AGENT_MESSAGE =
      "SEC EDGAR rejected the request (check SEC_USER_AGENT).";
  static final String RATE_LIMIT_MESSAGE = "SEC EDGAR rate limit reached; try again shortly.";
  static final String REJECTED_MESSAGE = "SEC EDGAR rejected the request.";
  static final String UNAVAILABLE_MESSAGE = "SEC EDGAR is unavailable.";
  static final String TIMED_OUT_MESSAGE = "SEC EDGAR timed out.";

  private final RestClient edgarRestClient;
  private final String secUserAgent;

  /// Creates the client.
  ///
  /// @param edgarRestClient client with the EDGAR base URL and timeouts already set
  /// @param secUserAgent `User-Agent` sent to EDGAR, e.g. `stock-research-agent you@example.com`
  public RestClientEdgarClient(RestClient edgarRestClient, String secUserAgent) {
    this.edgarRestClient = edgarRestClient;
    this.secUserAgent = secUserAgent;
  }

  /// {@inheritDoc}
  @Override
  public List<EdgarFiling> fetchRecentFilings(String cik) {
    long startNanos = System.nanoTime();
    EdgarSubmissionsResponse submissionsResponse;
    try {
      submissionsResponse =
          edgarRestClient
              .get()
              .uri(SUBMISSIONS_ENDPOINT, cik)
              .header(HttpHeaders.USER_AGENT, secUserAgent)
              .retrieve()
              .body(EdgarSubmissionsResponse.class);
    } catch (RestClientResponseException statusException) {
      int httpStatus = statusException.getStatusCode().value();
      throw callFailed(cik, httpStatus, messageForStatus(httpStatus), statusException, startNanos);
    } catch (ResourceAccessException ioException) {
      throw callFailed(cik, null, TIMED_OUT_MESSAGE, ioException, startNanos);
    } catch (RestClientException unreadableResponseException) {
      throw callFailed(cik, null, UNAVAILABLE_MESSAGE, unreadableResponseException, startNanos);
    }
    List<EdgarFiling> recentFilings = recentFilings(submissionsResponse);
    log.atInfo()
        .addKeyValue("eventName", "edgarCallCompleted")
        .addKeyValue(
            "eventData",
            Map.of(
                "edgarEndpoint",
                SUBMISSIONS_ENDPOINT,
                "cik",
                cik,
                "recentFilingCount",
                recentFilings.size(),
                "durationTimeMs",
                elapsedMillis(startNanos)))
        .log("SEC EDGAR call completed");
    return recentFilings;
  }

  /// Zips the parallel arrays of `filings.recent` into rows.
  private static List<EdgarFiling> recentFilings(EdgarSubmissionsResponse submissionsResponse) {
    if (submissionsResponse == null
        || submissionsResponse.filings() == null
        || submissionsResponse.filings().recent() == null
        || submissionsResponse.filings().recent().form() == null) {
      return List.of();
    }
    EdgarRecentFilings recent = submissionsResponse.filings().recent();
    List<EdgarFiling> recentFilings = new ArrayList<>(recent.form().size());
    for (int filingIndex = 0; filingIndex < recent.form().size(); filingIndex++) {
      String reportDate = recent.reportDate().get(filingIndex);
      recentFilings.add(
          new EdgarFiling(
              recent.form().get(filingIndex),
              recent.filingDate().get(filingIndex),
              reportDate == null || reportDate.isBlank() ? null : reportDate,
              recent.accessionNumber().get(filingIndex),
              recent.primaryDocument().get(filingIndex)));
    }
    return recentFilings;
  }

  private static String messageForStatus(int httpStatus) {
    if (httpStatus == 403) {
      return REJECTED_USER_AGENT_MESSAGE;
    }
    if (httpStatus == 429) {
      return RATE_LIMIT_MESSAGE;
    }
    return httpStatus >= 500 ? UNAVAILABLE_MESSAGE : REJECTED_MESSAGE;
  }

  /// Logs the failure once and builds the exception to throw.
  ///
  /// The cause is not attached: Spring's exception messages carry the URL or the upstream body.
  private static FilingsException callFailed(
      String cik,
      Integer httpStatus,
      String modelReadableMessage,
      Exception cause,
      long startNanos) {
    boolean clientSideRejection = httpStatus != null && httpStatus < 500;
    Map<String, Object> failureData = new LinkedHashMap<>();
    failureData.put("httpStatus", httpStatus);
    failureData.put("edgarEndpoint", SUBMISSIONS_ENDPOINT);
    failureData.put("cik", cik);
    failureData.put("exceptionType", cause.getClass().getName());
    failureData.put("durationTimeMs", elapsedMillis(startNanos));
    log.atLevel(clientSideRejection ? Level.WARN : Level.ERROR)
        .addKeyValue("eventName", "edgarCallFailed")
        .addKeyValue("eventData", failureData)
        .log("SEC EDGAR call failed");
    return new FilingsException(modelReadableMessage);
  }

  private static long elapsedMillis(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }

  /// The part of EDGAR's submissions response that is read.
  private record EdgarSubmissionsResponse(EdgarFilingsSection filings) {}

  /// `filings` of the submissions response; the older `files` pages are not read.
  private record EdgarFilingsSection(EdgarRecentFilings recent) {}

  /// `filings.recent`: parallel arrays with one entry per filing, newest first.
  private record EdgarRecentFilings(
      List<String> form,
      List<String> filingDate,
      List<String> reportDate,
      List<String> accessionNumber,
      List<String> primaryDocument) {}
}
