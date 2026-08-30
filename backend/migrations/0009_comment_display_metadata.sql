ALTER TABLE comments ADD COLUMN edited_at INTEGER;

-- Older cached trees do not contain display-name/avatar/edit metadata. Regenerating is bounded
-- (five-minute TTL, at most 200 selected comments) and avoids a dual payload contract.
DELETE FROM comment_tree_cache;
