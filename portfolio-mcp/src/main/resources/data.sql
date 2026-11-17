-- Two synthetic users. At most 3 holdings each, so the Market data agent can quote every holding
-- within its max-steps. ON CONFLICT DO NOTHING keeps restarts from duplicating rows.
INSERT INTO holding (user_id, ticker, shares) VALUES
    ('alice', 'AAPL', 50),
    ('alice', 'MSFT', 30),
    ('alice', 'NVDA', 20),
    ('bob', 'JPM', 40),
    ('bob', 'XOM', 60)
ON CONFLICT DO NOTHING;

INSERT INTO watchlist_item (user_id, ticker) VALUES
    ('alice', 'GOOGL'),
    ('alice', 'AMZN'),
    ('bob', 'TSLA'),
    ('bob', 'META'),
    ('bob', 'NFLX')
ON CONFLICT DO NOTHING;
