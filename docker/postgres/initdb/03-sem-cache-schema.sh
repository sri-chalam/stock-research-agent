#!/bin/sh
# Runs once, when the Postgres container starts with an empty data directory, after
# 02-portfolio-schema.sh. Creates the sem_cache schema with its own user, which can use only that
# schema. The user's password comes from SEM_CACHE_DB_PASSWORD (see docker-compose.yml).
set -eu

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v sem_cache_password="$SEM_CACHE_DB_PASSWORD" <<'SQL'
CREATE ROLE sem_cache LOGIN PASSWORD :'sem_cache_password';
GRANT CONNECT ON DATABASE :"DBNAME" TO sem_cache;
-- agent-app owns the semantic cache schema; its sem-cache-schema.sql creates the table and HNSW
-- index there on startup. The default search_path ("$user", public) resolves to this schema first.
CREATE SCHEMA sem_cache AUTHORIZATION sem_cache;
SQL
