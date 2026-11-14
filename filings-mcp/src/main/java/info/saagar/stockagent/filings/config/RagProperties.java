package info.saagar.stockagent.filings.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/// Filings RAG settings, bound from `filings.rag.*` in `application.yml`.
///
/// The database password is not here: it is read from the `FILINGS_DB_PASSWORD` environment
/// variable.
///
/// @param ingestTickers tickers whose latest 10-K is ingested on startup; each must be in
///   `filings.tickers`
/// @param chunkSizeChars maximum chunk length in characters (about 4 per token; the embedding model
///   reads at most 512 tokens)
/// @param chunkOverlapChars characters shared by neighbouring chunks
/// @param searchMaxResults number of chunks `searchFilings` returns
/// @param database Postgres connection used when `filings.rag.vector-store` is `pgvector`
@ConfigurationProperties("filings.rag")
public record RagProperties(
    List<String> ingestTickers,
    int chunkSizeChars,
    int chunkOverlapChars,
    int searchMaxResults,
    Database database) {

  /// Postgres connection settings for the pgvector store.
  ///
  /// @param host database host
  /// @param port database port
  /// @param name database name
  /// @param user database user, which can access only the `filings` schema
  /// @param table schema-qualified chunk table, e.g. `filings.filing_chunks`
  public record Database(String host, int port, String name, String user, String table) {}
}
