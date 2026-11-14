#!/bin/sh
# Runs once, when the Postgres container starts with an empty data directory.
# One database for the project, one schema per app. This script creates the pgvector extension and
# the filings schema with its own user, which can use only that schema. The user's password comes
# from FILINGS_DB_PASSWORD (see docker-compose.yml).
set -eu

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v filings_password="$FILINGS_DB_PASSWORD" <<'SQL'
-- In public, so every app's schema can use the vector type. App users cannot create objects there.
CREATE EXTENSION IF NOT EXISTS vector;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

-- Only roles granted CONNECT can open the database.
REVOKE CONNECT ON DATABASE :"DBNAME" FROM PUBLIC;

CREATE ROLE filings_mcp LOGIN PASSWORD :'filings_password';
GRANT CONNECT ON DATABASE :"DBNAME" TO filings_mcp;
-- filings-mcp owns its schema; PgVectorEmbeddingStore creates its table there on startup.
CREATE SCHEMA filings AUTHORIZATION filings_mcp;
SQL
