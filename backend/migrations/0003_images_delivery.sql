ALTER TABLE media ADD COLUMN image_uid TEXT;
ALTER TABLE media ADD COLUMN image_status TEXT NOT NULL DEFAULT 'not_applicable';
ALTER TABLE media ADD COLUMN image_error_message TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS idx_media_image_uid
  ON media(image_uid) WHERE image_uid IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_media_image_status
  ON media(image_status) WHERE kind = 'image';
