-- Ordered media membership for gallery posts. posts.media_id remains the
-- canonical cover/legacy media so old clients and existing indexes keep working.
CREATE TABLE post_media (
  post_id TEXT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
  media_id TEXT NOT NULL REFERENCES media(id),
  position INTEGER NOT NULL CHECK (position BETWEEN 0 AND 19),
  PRIMARY KEY (post_id, position),
  UNIQUE (post_id, media_id)
);

CREATE INDEX post_media_media ON post_media(media_id, post_id);

INSERT INTO post_media (post_id, media_id, position)
SELECT id, media_id, 0
FROM posts
WHERE media_id IS NOT NULL;
