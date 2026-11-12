package info.saagar.stockagent.marketdata.provider;

import java.math.BigDecimal;

/// A compact profile of one US-listed company, trimmed to the fields the model needs.
///
/// @param symbol ticker symbol, e.g. `AAPL`
/// @param name company name
/// @param exchange listing exchange
/// @param industry industry classification
/// @param country country of the company's headquarters
/// @param marketCapitalizationMillions market capitalization in millions of USD
/// @param sharesOutstandingMillions shares outstanding, in millions
/// @param ipoDate IPO date, ISO-8601, e.g. `1980-12-12`
/// @param webUrl company website
public record CompanyProfile(
    String symbol,
    String name,
    String exchange,
    String industry,
    String country,
    BigDecimal marketCapitalizationMillions,
    BigDecimal sharesOutstandingMillions,
    String ipoDate,
    String webUrl) {}
