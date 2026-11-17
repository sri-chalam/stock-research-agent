-- Runs on every start (spring.sql.init.mode: always), so every statement is idempotent.
CREATE TABLE IF NOT EXISTS holding (
    user_id VARCHAR(64) NOT NULL,
    ticker  VARCHAR(10) NOT NULL,
    shares  INTEGER     NOT NULL,
    PRIMARY KEY (user_id, ticker)
);

CREATE TABLE IF NOT EXISTS watchlist_item (
    user_id VARCHAR(64) NOT NULL,
    ticker  VARCHAR(10) NOT NULL,
    PRIMARY KEY (user_id, ticker)
);
