ALTER TABLE users ADD COLUMN avatar_media_id TEXT REFERENCES media(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_users_avatar_media
  ON users(avatar_media_id) WHERE avatar_media_id IS NOT NULL;
