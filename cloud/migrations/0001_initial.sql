PRAGMA foreign_keys = ON;
CREATE TABLE IF NOT EXISTS teams (
 id TEXT PRIMARY KEY, name TEXT NOT NULL, created_by TEXT NOT NULL REFERENCES "user"(id),
 created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')), archived_at TEXT
);
CREATE TABLE IF NOT EXISTS team_memberships (
 team_id TEXT NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
 user_id TEXT NOT NULL REFERENCES "user"(id) ON DELETE CASCADE,
 role TEXT NOT NULL CHECK (role IN ('owner','admin','member')),
 joined_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 PRIMARY KEY (team_id,user_id)
);
CREATE INDEX IF NOT EXISTS idx_memberships_user ON team_memberships(user_id);
CREATE TABLE IF NOT EXISTS conversations (
 id TEXT PRIMARY KEY, owner_user_id TEXT NOT NULL REFERENCES "user"(id),
 team_id TEXT REFERENCES teams(id) ON DELETE CASCADE, title TEXT NOT NULL DEFAULT '',
 created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE INDEX IF NOT EXISTS idx_conversations_owner ON conversations(owner_user_id,updated_at);
CREATE INDEX IF NOT EXISTS idx_conversations_team ON conversations(team_id,updated_at);
CREATE TABLE IF NOT EXISTS messages (
 id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
 role TEXT NOT NULL CHECK (role IN ('user','assistant','tool')), content TEXT NOT NULL,
 created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE INDEX IF NOT EXISTS idx_messages_conversation ON messages(conversation_id,created_at);
CREATE TABLE IF NOT EXISTS audit_events (
 id TEXT PRIMARY KEY, actor_user_id TEXT REFERENCES "user"(id),
 team_id TEXT REFERENCES teams(id) ON DELETE SET NULL, event_type TEXT NOT NULL,
 resource_type TEXT NOT NULL, resource_id TEXT,
 created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE INDEX IF NOT EXISTS idx_audit_team_time ON audit_events(team_id,created_at);
CREATE INDEX IF NOT EXISTS idx_audit_actor_time ON audit_events(actor_user_id,created_at);
