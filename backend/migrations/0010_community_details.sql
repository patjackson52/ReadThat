ALTER TABLE subreddits ADD COLUMN avatar_url TEXT;

CREATE TABLE subreddit_rules (
  id TEXT PRIMARY KEY,
  subreddit_id TEXT NOT NULL REFERENCES subreddits(id) ON DELETE CASCADE,
  title TEXT NOT NULL,
  description TEXT NOT NULL DEFAULT '',
  sort_order INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  UNIQUE(subreddit_id, sort_order)
);
CREATE INDEX subreddit_rules_order ON subreddit_rules(subreddit_id, sort_order, id);

INSERT INTO subreddit_rules (id, subreddit_id, title, description, sort_order, created_at, updated_at)
SELECT 'default:' || id, id, 'Be respectful',
       'Keep discussion relevant and address ideas rather than attacking people.',
       0, created_at, updated_at
FROM subreddits;
