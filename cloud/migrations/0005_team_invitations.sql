CREATE TABLE IF NOT EXISTS team_invitations (
 id TEXT PRIMARY KEY NOT NULL,
 team_id TEXT NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
 invited_email TEXT NOT NULL,
 token_hash TEXT NOT NULL UNIQUE,
 invited_by_user_id TEXT NOT NULL REFERENCES "user"(id),
 expires_at TEXT NOT NULL,
 accepted_at TEXT,
 revoked_at TEXT,
 created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE INDEX IF NOT EXISTS idx_team_invites_team ON team_invitations(team_id, created_at);
CREATE INDEX IF NOT EXISTS idx_team_invites_email ON team_invitations(invited_email, expires_at);
CREATE UNIQUE INDEX IF NOT EXISTS idx_team_invites_pending_unique
 ON team_invitations(team_id, invited_email)
 WHERE accepted_at IS NULL AND revoked_at IS NULL;
