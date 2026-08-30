-- The original Stream migration constrained the provider before Images was
-- enabled. Preserve that historical column for an additive, online-safe D1
-- migration, then replace it with the complete provider domain.
ALTER TABLE media RENAME COLUMN delivery_provider TO delivery_provider_v1;
ALTER TABLE media ADD COLUMN delivery_provider TEXT NOT NULL DEFAULT 'r2'
  CHECK (delivery_provider IN ('r2', 'stream', 'images'));
UPDATE media SET delivery_provider = delivery_provider_v1;
