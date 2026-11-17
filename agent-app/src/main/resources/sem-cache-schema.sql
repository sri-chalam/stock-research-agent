-- Runs on every agent-app start (SemanticCacheConfig), so every statement is idempotent.
-- The vector type comes from the extension in the public schema (docker/postgres/initdb).
-- Expired rows are never deleted; lookups filter on expires_at.
CREATE TABLE IF NOT EXISTS sem_cache.semantic_cache_entry (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    question           TEXT        NOT NULL,
    question_embedding vector(384) NOT NULL,
    -- Sorted, comma-separated ticker set, e.g. 'AAPL,MSFT'; '' when the prompt names no ticker.
    ticker_key         TEXT        NOT NULL,
    freshness_class    VARCHAR(16) NOT NULL,
    answer             TEXT        NOT NULL,
    run_total_tokens   BIGINT      NOT NULL,
    expires_at         TIMESTAMPTZ NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Approximate nearest-neighbour search on cosine distance (<=>) instead of scanning every row.
CREATE INDEX IF NOT EXISTS semantic_cache_entry_embedding_idx
    ON sem_cache.semantic_cache_entry USING hnsw (question_embedding vector_cosine_ops);
