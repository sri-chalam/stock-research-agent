package info.saagar.stockagent.filings.provider;

/// A failed call to SEC EDGAR.
///
/// The message is one of a fixed set of sentences written for the model. It never contains the
/// User-Agent, the request URL or the upstream response body.
public class FilingsException extends RuntimeException {

  /// Creates the exception.
  ///
  /// @param modelReadableMessage fixed, model-readable description of the failure
  public FilingsException(String modelReadableMessage) {
    super(modelReadableMessage);
  }
}
