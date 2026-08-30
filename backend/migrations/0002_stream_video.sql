-- Cloudflare Stream is the delivery/transcoding plane for videos. R2 remains
-- the resumable ingest plane and the image origin. Existing media rows stay
-- valid and continue to use R2.
ALTER TABLE media ADD COLUMN delivery_provider TEXT NOT NULL DEFAULT 'r2'
  CHECK (delivery_provider IN ('r2', 'stream'));
ALTER TABLE media ADD COLUMN stream_uid TEXT;
ALTER TABLE media ADD COLUMN stream_status TEXT NOT NULL DEFAULT 'not_applicable'
  CHECK (stream_status IN ('not_applicable', 'waiting', 'processing', 'ready', 'error'));
ALTER TABLE media ADD COLUMN stream_progress INTEGER NOT NULL DEFAULT 0
  CHECK (stream_progress BETWEEN 0 AND 100);
ALTER TABLE media ADD COLUMN hls_url TEXT;
ALTER TABLE media ADD COLUMN dash_url TEXT;
ALTER TABLE media ADD COLUMN thumbnail_url TEXT;
ALTER TABLE media ADD COLUMN preview_url TEXT;
ALTER TABLE media ADD COLUMN stream_error_code TEXT;
ALTER TABLE media ADD COLUMN stream_error_message TEXT;
ALTER TABLE media ADD COLUMN source_deleted_at INTEGER;

CREATE UNIQUE INDEX media_stream_uid ON media(stream_uid) WHERE stream_uid IS NOT NULL;
CREATE INDEX media_stream_processing ON media(stream_status, created_at)
  WHERE kind = 'video' AND stream_status IN ('waiting', 'processing');
