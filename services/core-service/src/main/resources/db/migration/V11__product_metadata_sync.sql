CREATE TABLE product_details (
 shop_id VARCHAR(100) NOT NULL REFERENCES shops(id), goods_id VARCHAR(200) NOT NULL,
 payload TEXT NOT NULL, category_path TEXT NOT NULL, sku_count INTEGER NOT NULL,
 attributes_count INTEGER NOT NULL, updated_at BIGINT NOT NULL,
 PRIMARY KEY(shop_id,goods_id)
);
CREATE TABLE product_sync_runs (
 id VARCHAR(100) PRIMARY KEY, shop_id VARCHAR(100) NOT NULL REFERENCES shops(id),
 state VARCHAR(30) NOT NULL, total INTEGER NOT NULL, completed INTEGER NOT NULL DEFAULT 0,
 failed INTEGER NOT NULL DEFAULT 0, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL
);
CREATE INDEX product_sync_shop ON product_sync_runs(shop_id,created_at);
CREATE TABLE product_sync_items (
 id VARCHAR(100) PRIMARY KEY, run_id VARCHAR(100) NOT NULL REFERENCES product_sync_runs(id),
 goods_id VARCHAR(200) NOT NULL, state VARCHAR(30) NOT NULL, error_code VARCHAR(100),
 UNIQUE(run_id,goods_id)
);
