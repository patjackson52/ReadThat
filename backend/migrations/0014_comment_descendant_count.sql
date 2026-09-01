ALTER TABLE comments
ADD COLUMN descendant_count INTEGER NOT NULL DEFAULT 0 CHECK (descendant_count >= 0);

-- Backfill every live comment's complete subtree size. This closure is migration-only;
-- normal reads consume the denormalized value and normal writes touch one ancestor chain.
WITH RECURSIVE ancestry(descendant_id, ancestor_id) AS (
  SELECT id, parent_id
  FROM comments
  WHERE parent_id IS NOT NULL AND deleted_at IS NULL

  UNION ALL

  SELECT ancestry.descendant_id, parent.parent_id
  FROM ancestry
  JOIN comments parent ON parent.id = ancestry.ancestor_id
  WHERE parent.parent_id IS NOT NULL AND parent.deleted_at IS NULL
), totals AS (
  SELECT ancestor_id, COUNT(*) AS descendant_count
  FROM ancestry
  GROUP BY ancestor_id
)
UPDATE comments
SET descendant_count = COALESCE((
  SELECT totals.descendant_count
  FROM totals
  WHERE totals.ancestor_id = comments.id
), 0);

-- A new reply adds exactly one descendant to every ancestor. D1 serializes the
-- statement and its trigger, so concurrent inserts cannot lose increments.
DROP TRIGGER comments_after_insert;
CREATE TRIGGER comments_after_insert
AFTER INSERT ON comments
BEGIN
  UPDATE posts
  SET comment_count = comment_count + 1, version = version + 1, updated_at = NEW.created_at
  WHERE id = NEW.post_id;

  UPDATE comments
  SET child_count = child_count + 1, version = version + 1, updated_at = NEW.created_at
  WHERE id = NEW.parent_id;

  UPDATE comments
  SET descendant_count = descendant_count + 1
  WHERE id IN (
    WITH RECURSIVE ancestors(id) AS (
      SELECT NEW.parent_id

      UNION ALL

      SELECT parent.parent_id
      FROM comments parent
      JOIN ancestors ON parent.id = ancestors.id
      WHERE parent.parent_id IS NOT NULL AND parent.deleted_at IS NULL
    )
    SELECT id FROM ancestors WHERE id IS NOT NULL
  );
END;
