ALTER TABLE posts ADD COLUMN rank_value INTEGER NOT NULL DEFAULT 0;

UPDATE posts SET rank_value = score * 1000000000 + created_at;

CREATE INDEX posts_rank_feed
ON posts(rank_value DESC, id DESC)
WHERE deleted_at IS NULL;

CREATE INDEX posts_subreddit_rank_feed
ON posts(subreddit_id, rank_value DESC, id DESC)
WHERE deleted_at IS NULL;

CREATE TRIGGER posts_rank_after_insert
AFTER INSERT ON posts
BEGIN
  UPDATE posts
  SET rank_value = NEW.score * 1000000000 + NEW.created_at
  WHERE id = NEW.id;
END;

CREATE TRIGGER posts_rank_after_score_update
AFTER UPDATE OF score ON posts WHEN NEW.score <> OLD.score
BEGIN
  UPDATE posts
  SET rank_value = NEW.score * 1000000000 + NEW.created_at
  WHERE id = NEW.id;
END;
