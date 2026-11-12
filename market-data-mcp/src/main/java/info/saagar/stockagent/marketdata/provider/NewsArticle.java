package info.saagar.stockagent.marketdata.provider;

/// One news article, trimmed to the fields the model needs.
///
/// @param headline article headline
/// @param source publisher, e.g. `Reuters`
/// @param publishedAt publication time, ISO-8601 in UTC
/// @param summary summary, cut to at most 300 characters
/// @param url link to the article
public record NewsArticle(
    String headline, String source, String publishedAt, String summary, String url) {}
