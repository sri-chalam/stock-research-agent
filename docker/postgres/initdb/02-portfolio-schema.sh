#!/bin/sh
# Runs once, when the Postgres container starts with an empty data directory, after
# 01-filings-schema.sh. Creates the portfolio schema with its own user, which can use only that
# schema. The user's password comes from PORTFOLIO_DB_PASSWORD (see docker-compose.yml).
set -eu

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v portfolio_password="$PORTFOLIO_DB_PASSWORD" <<'SQL'
CREATE ROLE portfolio_mcp LOGIN PASSWORD :'portfolio_password';
GRANT CONNECT ON DATABASE :"DBNAME" TO portfolio_mcp;
-- portfolio-mcp owns its schema; its schema.sql creates the tables there on startup. The
-- search_path lets that script and the app's queries use unqualified table names.
CREATE SCHEMA portfolio AUTHORIZATION portfolio_mcp;
ALTER ROLE portfolio_mcp SET search_path = portfolio;
SQL
