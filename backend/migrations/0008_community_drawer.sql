-- A per-user revision lets the mobile drawer use a cheap conditional request.
-- Membership and visit changes bump it in the same transaction as the source
-- write, so an ETag can never acknowledge stale drawer state.
CREATE TABLE community_drawer_versions (
  user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  version INTEGER NOT NULL DEFAULT 1,
  updated_at INTEGER NOT NULL
);

INSERT INTO community_drawer_versions(user_id, version, updated_at)
SELECT id, 1, updated_at FROM users;

CREATE TRIGGER community_drawer_user_after_insert AFTER INSERT ON users BEGIN
  INSERT INTO community_drawer_versions(user_id, version, updated_at)
  VALUES (NEW.id, 1, NEW.created_at);
END;

CREATE TRIGGER community_drawer_member_after_insert AFTER INSERT ON subreddit_members BEGIN
  INSERT INTO community_drawer_versions(user_id, version, updated_at)
  VALUES (NEW.user_id, 1, NEW.updated_at)
  ON CONFLICT(user_id) DO UPDATE SET
    version = community_drawer_versions.version + 1,
    updated_at = excluded.updated_at;
END;

CREATE TRIGGER community_drawer_member_after_update
AFTER UPDATE OF role, updated_at ON subreddit_members BEGIN
  INSERT INTO community_drawer_versions(user_id, version, updated_at)
  VALUES (NEW.user_id, 1, NEW.updated_at)
  ON CONFLICT(user_id) DO UPDATE SET
    version = community_drawer_versions.version + 1,
    updated_at = excluded.updated_at;
END;

CREATE TRIGGER community_drawer_member_after_delete AFTER DELETE ON subreddit_members BEGIN
  INSERT INTO community_drawer_versions(user_id, version, updated_at)
  VALUES (OLD.user_id, 1, CAST(unixepoch('subsec') * 1000 AS INTEGER))
  ON CONFLICT(user_id) DO UPDATE SET
    version = community_drawer_versions.version + 1,
    updated_at = excluded.updated_at;
END;

CREATE TABLE community_visits (
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  subreddit_id TEXT NOT NULL REFERENCES subreddits(id) ON DELETE CASCADE,
  visited_at INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, subreddit_id)
);

CREATE INDEX community_visits_recent
ON community_visits(user_id, visited_at DESC, subreddit_id);

-- Retain mutation ids beyond the accepted offline command window. A random
-- batch token lets the operation following INSERT OR IGNORE determine whether
-- this request claimed the id, without relying on connection-local changes().
CREATE TABLE community_visit_commands (
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  mutation_id TEXT NOT NULL,
  batch_token TEXT NOT NULL,
  applied_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, mutation_id)
) WITHOUT ROWID;

CREATE INDEX community_visit_commands_applied
ON community_visit_commands(applied_at);

CREATE TRIGGER community_drawer_visit_after_insert AFTER INSERT ON community_visits BEGIN
  UPDATE community_drawer_versions
  SET version = version + 1, updated_at = NEW.updated_at
  WHERE user_id = NEW.user_id;
END;

CREATE TRIGGER community_drawer_visit_after_update
AFTER UPDATE OF visited_at, updated_at ON community_visits BEGIN
  UPDATE community_drawer_versions
  SET version = version + 1, updated_at = NEW.updated_at
  WHERE user_id = NEW.user_id;
END;

CREATE TRIGGER community_drawer_visit_after_delete AFTER DELETE ON community_visits BEGIN
  UPDATE community_drawer_versions
  SET version = version + 1, updated_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
  WHERE user_id = OLD.user_id;
END;
