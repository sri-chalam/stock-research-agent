package info.saagar.stockagent.marketdata.provider;

/// A failed call to the market data provider.
///
/// The message is one of a fixed set of sentences written for the model. It never contains the API
/// key, the request URL or the upstream response body.
public class MarketDataException extends RuntimeException {

  /// Creates the exception.
  ///
  /// @param modelReadableMessage fixed, model-readable description of the failure
  public MarketDataException(String modelReadableMessage) {
    super(modelReadableMessage);
  }
}
