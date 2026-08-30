PRAGMA foreign_keys = ON;

CREATE TABLE users (
  id TEXT PRIMARY KEY,
  username TEXT NOT NULL COLLATE NOCASE UNIQUE,
  display_name TEXT NOT NULL,
  bio TEXT NOT NULL DEFAULT '',
  avatar_url TEXT,
  password_hash TEXT NOT NULL,
  password_salt TEXT NOT NULL,
  password_iterations INTEGER NOT NULL,
  karma INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  CHECK (length(username) BETWEEN 3 AND 24),
  CHECK (length(display_name) BETWEEN 1 AND 50)
);

CREATE TABLE sessions (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  access_hash TEXT NOT NULL UNIQUE,
  refresh_hash TEXT NOT NULL UNIQUE,
  access_expires_at INTEGER NOT NULL,
  refresh_expires_at INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  last_seen_at INTEGER NOT NULL,
  revoked_at INTEGER
);
CREATE INDEX sessions_access_active ON sessions(access_hash, access_expires_at) WHERE revoked_at IS NULL;
CREATE INDEX sessions_refresh_active ON sessions(refresh_hash, refresh_expires_at) WHERE revoked_at IS NULL;
CREATE INDEX sessions_user ON sessions(user_id, created_at DESC);

CREATE TABLE subreddits (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL COLLATE NOCASE UNIQUE,
  display_name TEXT NOT NULL,
  description TEXT NOT NULL DEFAULT '',
  access_type TEXT NOT NULL DEFAULT 'public' CHECK (access_type IN ('public', 'restricted', 'private')),
  created_by TEXT NOT NULL REFERENCES users(id),
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE TABLE subreddit_members (
  subreddit_id TEXT NOT NULL REFERENCES subreddits(id) ON DELETE CASCADE,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  role TEXT NOT NULL CHECK (role IN ('owner', 'moderator', 'member', 'subscriber', 'banned')),
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (subreddit_id, user_id)
);
CREATE INDEX subreddit_members_user ON subreddit_members(user_id, role, subreddit_id);

CREATE TABLE media (
  id TEXT PRIMARY KEY,
  uploader_id TEXT NOT NULL REFERENCES users(id),
  kind TEXT NOT NULL CHECK (kind IN ('image', 'video')),
  content_type TEXT NOT NULL,
  byte_size INTEGER NOT NULL CHECK (byte_size > 0),
  r2_key TEXT NOT NULL UNIQUE,
  status TEXT NOT NULL CHECK (status IN ('pending', 'ready', 'failed', 'aborted')),
  upload_mode TEXT NOT NULL CHECK (upload_mode IN ('single', 'multipart')),
  r2_upload_id TEXT,
  upload_token_hash TEXT NOT NULL,
  upload_expires_at INTEGER NOT NULL,
  etag TEXT,
  width INTEGER,
  height INTEGER,
  duration_seconds INTEGER,
  alt_text TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL,
  completed_at INTEGER,
  CHECK (width IS NULL OR width > 0),
  CHECK (height IS NULL OR height > 0),
  CHECK (duration_seconds IS NULL OR duration_seconds >= 0)
);
CREATE INDEX media_uploader_status ON media(uploader_id, status, created_at DESC);

CREATE TABLE media_parts (
  media_id TEXT NOT NULL REFERENCES media(id) ON DELETE CASCADE,
  part_number INTEGER NOT NULL CHECK (part_number BETWEEN 1 AND 10000),
  etag TEXT NOT NULL,
  byte_size INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (media_id, part_number)
);

CREATE TABLE posts (
  id TEXT PRIMARY KEY,
  subreddit_id TEXT NOT NULL REFERENCES subreddits(id),
  author_id TEXT NOT NULL REFERENCES users(id),
  kind TEXT NOT NULL CHECK (kind IN ('text', 'image', 'video', 'link')),
  title TEXT NOT NULL,
  body TEXT,
  url TEXT,
  media_id TEXT REFERENCES media(id),
  crosspost_parent_id TEXT REFERENCES posts(id),
  score INTEGER NOT NULL DEFAULT 0,
  upvotes INTEGER NOT NULL DEFAULT 0,
  downvotes INTEGER NOT NULL DEFAULT 0,
  comment_count INTEGER NOT NULL DEFAULT 0,
  version INTEGER NOT NULL DEFAULT 1,
  client_mutation_id TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  deleted_at INTEGER,
  UNIQUE (author_id, client_mutation_id),
  CHECK (length(title) BETWEEN 1 AND 300),
  CHECK (
    (kind = 'text' AND body IS NOT NULL AND media_id IS NULL) OR
    (kind = 'link' AND url IS NOT NULL AND media_id IS NULL) OR
    (kind IN ('image', 'video') AND media_id IS NOT NULL)
  )
);
CREATE INDEX posts_subreddit_feed ON posts(subreddit_id, created_at DESC, id DESC) WHERE deleted_at IS NULL;
CREATE INDEX posts_author_feed ON posts(author_id, created_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX posts_global_feed ON posts(created_at DESC, id DESC) WHERE deleted_at IS NULL;
CREATE INDEX posts_crosspost_parent ON posts(crosspost_parent_id) WHERE crosspost_parent_id IS NOT NULL;

CREATE TABLE comments (
  id TEXT PRIMARY KEY,
  post_id TEXT NOT NULL REFERENCES posts(id),
  parent_id TEXT REFERENCES comments(id),
  author_id TEXT NOT NULL REFERENCES users(id),
  body TEXT NOT NULL,
  depth INTEGER NOT NULL CHECK (depth >= 0),
  score INTEGER NOT NULL DEFAULT 0,
  upvotes INTEGER NOT NULL DEFAULT 0,
  downvotes INTEGER NOT NULL DEFAULT 0,
  child_count INTEGER NOT NULL DEFAULT 0,
  version INTEGER NOT NULL DEFAULT 1,
  client_mutation_id TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  deleted_at INTEGER,
  UNIQUE (author_id, client_mutation_id),
  CHECK (length(body) BETWEEN 1 AND 10000)
);
CREATE INDEX comments_post_parent ON comments(post_id, parent_id, score DESC, id);
CREATE INDEX comments_post_created ON comments(post_id, created_at DESC);
CREATE INDEX comments_author ON comments(author_id, created_at DESC);

CREATE TABLE comment_tree_cache (
  post_id TEXT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
  sort TEXT NOT NULL,
  requested_count INTEGER NOT NULL,
  requested_depth INTEGER NOT NULL,
  root_key TEXT NOT NULL,
  post_version INTEGER NOT NULL,
  payload_json TEXT NOT NULL,
  cached_at INTEGER NOT NULL,
  PRIMARY KEY (post_id, sort, requested_count, requested_depth, root_key)
);

CREATE TABLE votes (
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  target_type TEXT NOT NULL CHECK (target_type IN ('post', 'comment')),
  target_id TEXT NOT NULL,
  value INTEGER NOT NULL CHECK (value IN (-1, 0, 1)),
  version INTEGER NOT NULL DEFAULT 1,
  last_mutation_id TEXT NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, target_type, target_id)
);
CREATE INDEX votes_target ON votes(target_type, target_id, value);

CREATE TABLE vote_mutations (
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  mutation_id TEXT NOT NULL,
  target_type TEXT NOT NULL CHECK (target_type IN ('post', 'comment')),
  target_id TEXT NOT NULL,
  value INTEGER NOT NULL CHECK (value IN (-1, 0, 1)),
  created_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, mutation_id)
);

CREATE TABLE moderation_log (
  id TEXT PRIMARY KEY,
  subreddit_id TEXT NOT NULL REFERENCES subreddits(id) ON DELETE CASCADE,
  actor_id TEXT NOT NULL REFERENCES users(id),
  target_user_id TEXT REFERENCES users(id),
  action TEXT NOT NULL,
  details_json TEXT NOT NULL DEFAULT '{}',
  created_at INTEGER NOT NULL
);
CREATE INDEX moderation_log_subreddit ON moderation_log(subreddit_id, created_at DESC);

CREATE TRIGGER comments_after_insert
AFTER INSERT ON comments
BEGIN
  UPDATE posts
  SET comment_count = comment_count + 1, version = version + 1, updated_at = NEW.created_at
  WHERE id = NEW.post_id;
  UPDATE comments
  SET child_count = child_count + 1, version = version + 1, updated_at = NEW.created_at
  WHERE id = NEW.parent_id;
END;

CREATE TRIGGER votes_post_after_insert
AFTER INSERT ON votes WHEN NEW.target_type = 'post'
BEGIN
  UPDATE posts SET
    score = score + NEW.value,
    upvotes = upvotes + CASE WHEN NEW.value = 1 THEN 1 ELSE 0 END,
    downvotes = downvotes + CASE WHEN NEW.value = -1 THEN 1 ELSE 0 END,
    version = version + 1,
    updated_at = NEW.updated_at
  WHERE id = NEW.target_id;
END;

CREATE TRIGGER votes_post_after_update
AFTER UPDATE OF value ON votes WHEN NEW.target_type = 'post'
BEGIN
  UPDATE posts SET
    score = score + NEW.value - OLD.value,
    upvotes = upvotes + CASE WHEN NEW.value = 1 THEN 1 ELSE 0 END - CASE WHEN OLD.value = 1 THEN 1 ELSE 0 END,
    downvotes = downvotes + CASE WHEN NEW.value = -1 THEN 1 ELSE 0 END - CASE WHEN OLD.value = -1 THEN 1 ELSE 0 END,
    version = version + CASE WHEN NEW.value <> OLD.value THEN 1 ELSE 0 END,
    updated_at = NEW.updated_at
  WHERE id = NEW.target_id;
END;

CREATE TRIGGER votes_comment_after_insert
AFTER INSERT ON votes WHEN NEW.target_type = 'comment'
BEGIN
  UPDATE comments SET
    score = score + NEW.value,
    upvotes = upvotes + CASE WHEN NEW.value = 1 THEN 1 ELSE 0 END,
    downvotes = downvotes + CASE WHEN NEW.value = -1 THEN 1 ELSE 0 END,
    version = version + 1,
    updated_at = NEW.updated_at
  WHERE id = NEW.target_id;
  UPDATE posts SET version = version + 1, updated_at = NEW.updated_at
  WHERE id = (SELECT post_id FROM comments WHERE id = NEW.target_id);
END;

CREATE TRIGGER votes_comment_after_update
AFTER UPDATE OF value ON votes WHEN NEW.target_type = 'comment'
BEGIN
  UPDATE comments SET
    score = score + NEW.value - OLD.value,
    upvotes = upvotes + CASE WHEN NEW.value = 1 THEN 1 ELSE 0 END - CASE WHEN OLD.value = 1 THEN 1 ELSE 0 END,
    downvotes = downvotes + CASE WHEN NEW.value = -1 THEN 1 ELSE 0 END - CASE WHEN OLD.value = -1 THEN 1 ELSE 0 END,
    version = version + CASE WHEN NEW.value <> OLD.value THEN 1 ELSE 0 END,
    updated_at = NEW.updated_at
  WHERE id = NEW.target_id;
  UPDATE posts SET
    version = version + CASE WHEN NEW.value <> OLD.value THEN 1 ELSE 0 END,
    updated_at = NEW.updated_at
  WHERE id = (SELECT post_id FROM comments WHERE id = NEW.target_id);
END;

CREATE TRIGGER posts_karma_after_vote_insert
AFTER INSERT ON votes WHEN NEW.target_type = 'post'
  AND NEW.user_id <> (SELECT author_id FROM posts WHERE id = NEW.target_id)
BEGIN
  UPDATE users SET karma = karma + NEW.value, updated_at = NEW.updated_at
  WHERE id = (SELECT author_id FROM posts WHERE id = NEW.target_id);
END;

CREATE TRIGGER posts_karma_after_vote_update
AFTER UPDATE OF value ON votes WHEN NEW.target_type = 'post'
  AND NEW.user_id <> (SELECT author_id FROM posts WHERE id = NEW.target_id)
BEGIN
  UPDATE users SET karma = karma + NEW.value - OLD.value, updated_at = NEW.updated_at
  WHERE id = (SELECT author_id FROM posts WHERE id = NEW.target_id);
END;

CREATE TRIGGER comments_karma_after_vote_insert
AFTER INSERT ON votes WHEN NEW.target_type = 'comment'
  AND NEW.user_id <> (SELECT author_id FROM comments WHERE id = NEW.target_id)
BEGIN
  UPDATE users SET karma = karma + NEW.value, updated_at = NEW.updated_at
  WHERE id = (SELECT author_id FROM comments WHERE id = NEW.target_id);
END;

CREATE TRIGGER comments_karma_after_vote_update
AFTER UPDATE OF value ON votes WHEN NEW.target_type = 'comment'
  AND NEW.user_id <> (SELECT author_id FROM comments WHERE id = NEW.target_id)
BEGIN
  UPDATE users SET karma = karma + NEW.value - OLD.value, updated_at = NEW.updated_at
  WHERE id = (SELECT author_id FROM comments WHERE id = NEW.target_id);
END;
