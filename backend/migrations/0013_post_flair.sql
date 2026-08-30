CREATE TABLE post_flairs (
  id TEXT PRIMARY KEY,
  subreddit_id TEXT NOT NULL REFERENCES subreddits(id) ON DELETE CASCADE,
  text TEXT NOT NULL CHECK (length(text) BETWEEN 1 AND 64),
  background_color TEXT NOT NULL CHECK (background_color GLOB '#[0-9A-Fa-f]*' AND length(background_color) = 7),
  text_color TEXT NOT NULL CHECK (text_color GLOB '#[0-9A-Fa-f]*' AND length(text_color) = 7),
  sort_order INTEGER NOT NULL,
  enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  UNIQUE(subreddit_id, text),
  UNIQUE(subreddit_id, sort_order)
);

CREATE INDEX post_flairs_subreddit_order
ON post_flairs(subreddit_id, enabled, sort_order, id);

INSERT INTO post_flairs (
  id, subreddit_id, text, background_color, text_color,
  sort_order, enabled, created_at, updated_at
)
SELECT 'flair:discussion:' || id, id, 'Discussion', '#E4E9EC', '#0B1416', 0, 1, created_at, updated_at
FROM subreddits
UNION ALL
SELECT 'flair:question:' || id, id, 'Question', '#46A508', '#FFFFFF', 1, 1, created_at, updated_at
FROM subreddits
UNION ALL
SELECT 'flair:project:' || id, id, 'Project', '#0A66C2', '#FFFFFF', 2, 1, created_at, updated_at
FROM subreddits
UNION ALL
SELECT 'flair:news:' || id, id, 'News', '#FF4500', '#FFFFFF', 3, 1, created_at, updated_at
FROM subreddits
UNION ALL
SELECT 'flair:tutorial:' || id, id, 'Tutorial', '#8E44AD', '#FFFFFF', 4, 1, created_at, updated_at
FROM subreddits;

ALTER TABLE posts ADD COLUMN flair_id TEXT REFERENCES post_flairs(id);
CREATE INDEX posts_flair ON posts(flair_id, created_at DESC) WHERE flair_id IS NOT NULL;
