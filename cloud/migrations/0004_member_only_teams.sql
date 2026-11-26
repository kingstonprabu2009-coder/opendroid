-- Team creator is not a platform Owner or Admin.
-- Every team member has the same basic membership role. Platform authority lives
-- only in the owner configuration and platform_admins table.
PRAGMA foreign_keys = OFF;
BEGIN TRANSACTION;
ALTER TABLE team_memberships RENAME TO team_memberships_legacy;
CREATE TABLE team_memberships (
 team_id TEXT NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
 user_id TEXT NOT NULL REFERENCES "user"(id) ON DELETE CASCADE,
 role TEXT NOT NULL DEFAULT 'member' CHECK (role = 'member'),
 joined_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
 PRIMARY KEY (team_id,user_id)
);
INSERT INTO team_memberships (team_id, user_id, role, joined_at)
 SELECT team_id, user_id, 'member', joined_at FROM team_memberships_legacy;
DROP TABLE team_memberships_legacy;
CREATE INDEX IF NOT EXISTS idx_memberships_user ON team_memberships(user_id);
COMMIT;
PRAGMA foreign_keys = ON;
