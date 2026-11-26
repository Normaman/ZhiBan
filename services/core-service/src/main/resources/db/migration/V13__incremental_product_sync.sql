ALTER TABLE product_snapshots ADD COLUMN checked_at BIGINT NOT NULL DEFAULT 0;
UPDATE product_snapshots SET checked_at=created_at;
ALTER TABLE product_details ADD COLUMN source_hash VARCHAR(64);
ALTER TABLE product_sync_items ADD COLUMN source_hash VARCHAR(64);
ALTER TABLE product_sync_runs ADD COLUMN skipped INTEGER NOT NULL DEFAULT 0;
