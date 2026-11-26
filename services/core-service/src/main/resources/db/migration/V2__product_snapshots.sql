CREATE TABLE product_snapshots (
 id VARCHAR(100) PRIMARY KEY, shop_id VARCHAR(100) NOT NULL REFERENCES shops(id),
 products TEXT NOT NULL, created_at BIGINT NOT NULL
);
