-- Enforce the initial JARVIS capacity limit at the database boundary.
-- This also protects against simultaneous sign-ups racing past an application check.
CREATE TRIGGER IF NOT EXISTS jarvis_limit_users
BEFORE INSERT ON "user"
WHEN (SELECT COUNT(*) FROM "user") >= 10
BEGIN
  SELECT RAISE(ABORT, 'JARVIS_USER_LIMIT_REACHED');
END;
