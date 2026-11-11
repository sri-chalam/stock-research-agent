package info.saagar.stockagent.common.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/// The one place where request-handling exceptions are logged and turned into responses.
///
/// Each exception is logged once at `error` with `errorCode`, `httpStatus` and `exceptionType`. The
/// stack trace is attached only for 5xx failures. Spring MVC exceptions can echo request fragments,
/// so their messages are suppressed from the log. Clients get an RFC 9457 problem body with an
/// `errorCode` property and never the exception message of a 5xx failure.
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  static final String INTERNAL_ERROR_CODE = "INTERNAL_ERROR";

  /// Handles any exception that no more specific handler claimed, as a 500.
  ///
  /// @param unhandledException the exception thrown while handling the request
  /// @return a 500 problem response
  @ExceptionHandler(Exception.class)
  public ProblemDetail handleUnexpected(Exception unhandledException) {
    logHandledException(
        unhandledException, INTERNAL_ERROR_CODE, HttpStatus.INTERNAL_SERVER_ERROR.value(), true);
    ProblemDetail problemDetail =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR, "The request could not be completed.");
    problemDetail.setProperty("errorCode", INTERNAL_ERROR_CODE);
    return problemDetail;
  }

  /// Logs a Spring MVC exception (bad input, unsupported method, ...) and adds its `errorCode`.
  ///
  /// @param frameworkException the Spring MVC exception
  /// @param responseBody body chosen by the base class, usually a problem detail
  /// @param responseHeaders headers for the response
  /// @param httpStatusCode status chosen by the base class
  /// @param webRequest the current request
  /// @return the problem response
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception frameworkException,
      @Nullable Object responseBody,
      HttpHeaders responseHeaders,
      HttpStatusCode httpStatusCode,
      WebRequest webRequest) {
    HttpStatus resolvedStatus = HttpStatus.resolve(httpStatusCode.value());
    String errorCode =
        resolvedStatus == null ? "HTTP_" + httpStatusCode.value() : resolvedStatus.name();
    logHandledException(frameworkException, errorCode, httpStatusCode.value(), false);
    ResponseEntity<Object> problemResponse =
        super.handleExceptionInternal(
            frameworkException, responseBody, responseHeaders, httpStatusCode, webRequest);
    if (problemResponse != null && problemResponse.getBody() instanceof ProblemDetail problem) {
      problem.setProperty("errorCode", errorCode);
    }
    return problemResponse;
  }

  private static void logHandledException(
      Exception handledException, String errorCode, int httpStatus, boolean includeMessage) {
    Map<String, Object> exceptionData = new LinkedHashMap<>();
    exceptionData.put("errorCode", errorCode);
    exceptionData.put("httpStatus", httpStatus);
    exceptionData.put("exceptionType", handledException.getClass().getName());
    if (includeMessage) {
      exceptionData.put("exceptionMessage", handledException.getMessage());
    } else {
      exceptionData.put("exceptionMessageSuppressed", true);
    }
    LoggingEventBuilder exceptionEvent =
        log.atError()
            .addKeyValue("eventName", "exceptionHandled")
            .addKeyValue("eventData", exceptionData);
    if (httpStatus >= 500) {
      exceptionEvent = exceptionEvent.setCause(handledException);
    }
    exceptionEvent.log("Request failed with a handled exception");
  }
}
