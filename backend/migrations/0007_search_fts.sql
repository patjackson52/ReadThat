-- Search is maintained transactionally with the source tables. Each FTS table
-- stores only searchable text plus the stable source id; ACL and freshness are
-- always evaluated against the normalized source tables at read time.
CREATE VIRTUAL TABLE search_subreddits USING fts5(
  id UNINDEXED,
  name,
  display_name,
  description,
  tokenize = 'unicode61 remove_diacritics 2',
  prefix = '2 3 4'
);

CREATE VIRTUAL TABLE search_users USING fts5(
  id UNINDEXED,
  username,
  display_name,
  bio,
  tokenize = 'unicode61 remove_diacritics 2',
  prefix = '2 3 4'
);

CREATE VIRTUAL TABLE search_posts USING fts5(
  id UNINDEXED,
  title,
  body,
  tokenize = 'unicode61 remove_diacritics 2',
  prefix = '2 3 4'
);

CREATE VIRTUAL TABLE search_comments USING fts5(
  id UNINDEXED,
  body,
  tokenize = 'unicode61 remove_diacritics 2',
  prefix = '2 3 4'
);

INSERT INTO search_subreddits(id, name, display_name, description)
SELECT id, name, display_name, description FROM subreddits;

INSERT INTO search_users(id, username, display_name, bio)
SELECT id, username, display_name, bio FROM users;

INSERT INTO search_posts(id, title, body)
SELECT id, title, COALESCE(body, '') FROM posts WHERE deleted_at IS NULL;

INSERT INTO search_comments(id, body)
SELECT id, body FROM comments WHERE deleted_at IS NULL;

CREATE TRIGGER search_subreddits_after_insert AFTER INSERT ON subreddits BEGIN
  INSERT INTO search_subreddits(id, name, display_name, description)
  VALUES (NEW.id, NEW.name, NEW.display_name, NEW.description);
END;

CREATE TRIGGER search_subreddits_after_update
AFTER UPDATE OF name, display_name, description ON subreddits BEGIN
  DELETE FROM search_subreddits WHERE id = OLD.id;
  INSERT INTO search_subreddits(id, name, display_name, description)
  VALUES (NEW.id, NEW.name, NEW.display_name, NEW.description);
END;

CREATE TRIGGER search_subreddits_after_delete AFTER DELETE ON subreddits BEGIN
  DELETE FROM search_subreddits WHERE id = OLD.id;
END;

CREATE TRIGGER search_users_after_insert AFTER INSERT ON users BEGIN
  INSERT INTO search_users(id, username, display_name, bio)
  VALUES (NEW.id, NEW.username, NEW.display_name, NEW.bio);
END;

CREATE TRIGGER search_users_after_update
AFTER UPDATE OF username, display_name, bio ON users BEGIN
  DELETE FROM search_users WHERE id = OLD.id;
  INSERT INTO search_users(id, username, display_name, bio)
  VALUES (NEW.id, NEW.username, NEW.display_name, NEW.bio);
END;

CREATE TRIGGER search_users_after_delete AFTER DELETE ON users BEGIN
  DELETE FROM search_users WHERE id = OLD.id;
END;

CREATE TRIGGER search_posts_after_insert AFTER INSERT ON posts
WHEN NEW.deleted_at IS NULL BEGIN
  INSERT INTO search_posts(id, title, body)
  VALUES (NEW.id, NEW.title, COALESCE(NEW.body, ''));
END;

CREATE TRIGGER search_posts_after_update
AFTER UPDATE OF title, body, deleted_at ON posts BEGIN
  DELETE FROM search_posts WHERE id = OLD.id;
  INSERT INTO search_posts(id, title, body)
  SELECT NEW.id, NEW.title, COALESCE(NEW.body, '') WHERE NEW.deleted_at IS NULL;
END;

CREATE TRIGGER search_posts_after_delete AFTER DELETE ON posts BEGIN
  DELETE FROM search_posts WHERE id = OLD.id;
END;

CREATE TRIGGER search_comments_after_insert AFTER INSERT ON comments
WHEN NEW.deleted_at IS NULL BEGIN
  INSERT INTO search_comments(id, body) VALUES (NEW.id, NEW.body);
END;

CREATE TRIGGER search_comments_after_update
AFTER UPDATE OF body, deleted_at ON comments BEGIN
  DELETE FROM search_comments WHERE id = OLD.id;
  INSERT INTO search_comments(id, body)
  SELECT NEW.id, NEW.body WHERE NEW.deleted_at IS NULL;
END;

CREATE TRIGGER search_comments_after_delete AFTER DELETE ON comments BEGIN
  DELETE FROM search_comments WHERE id = OLD.id;
END;

-- Safe Search is forward-compatible even though existing creation surfaces do
-- not yet expose mature-content authoring. Keeping it on the normalized post
-- row makes the policy cheap and impossible for an FTS payload to bypass.
ALTER TABLE posts ADD COLUMN is_mature INTEGER NOT NULL DEFAULT 0
  CHECK (is_mature IN (0, 1));
CREATE INDEX posts_search_visibility
ON posts(is_mature, created_at DESC, id DESC)
WHERE deleted_at IS NULL;
