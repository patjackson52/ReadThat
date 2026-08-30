CREATE INDEX posts_media_rank_feed
ON posts(kind, rank_value DESC, id DESC)
WHERE deleted_at IS NULL AND media_id IS NOT NULL;

CREATE INDEX posts_subreddit_media_rank_feed
ON posts(subreddit_id, kind, rank_value DESC, id DESC)
WHERE deleted_at IS NULL AND media_id IS NOT NULL;
