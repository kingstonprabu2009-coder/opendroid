-- Platform-level authority is separate from ordinary team membership.
-- The one Owner is configured out-of-band with JARVIS_OWNER_EMAIL.
-- Trusted Admins are granted only by that Owner.
CREATE TABLE IF NOT EXISTS platform_admins (
  user_id TEXT PRIMARY KEY NOT NULL REFERENCES "user"(id) ON DELETE CASCADE,
  granted_by_user_id TEXT NOT NULL REFERENCES "user"(id),
  created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE INDEX IF NOT EXISTS idx_platform_admins_granted_by ON platform_admins(granted_by_user_id);
