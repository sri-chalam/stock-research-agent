package info.saagar.stockagent.portfolio.repository;

/// One position in a user's paper portfolio.
///
/// @param ticker US ticker symbol, e.g. `AAPL`
/// @param shares number of shares held
public record Holding(String ticker, int shares) {}
