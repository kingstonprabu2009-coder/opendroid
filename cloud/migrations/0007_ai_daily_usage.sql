-- Per-account daily request budget to protect the shared free inference allowance.
CREATE TABLE IF NOT EXISTS ai_daily_usage (
  user_id TEXT NOT NULL REFERENCES "user"(id) ON DELETE CASCADE,
  usage_date TEXT NOT NULL,
  request_count INTEGER NOT NULL DEFAULT 0 CHECK (request_count >= 0),
  updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  PRIMARY KEY (user_id, usage_date)
);
CREATE INDEX IF NOT EXISTS idx_ai_daily_usage_date ON ai_daily_usage(usage_date);
