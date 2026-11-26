CREATE TABLE knowledge_proposals (
 id VARCHAR(100) PRIMARY KEY,
 shop_id VARCHAR(100) NOT NULL REFERENCES shops(id),
 base_version VARCHAR(100), snapshot TEXT NOT NULL,
 state VARCHAR(20) NOT NULL CHECK (state IN ('draft','pending','published','rejected','withdrawn')),
 revision BIGINT NOT NULL DEFAULT 1,
 created_by VARCHAR(200) NOT NULL, created_at BIGINT NOT NULL,
 reviewed_by VARCHAR(200), reviewed_at BIGINT, review_note TEXT,
 published_version VARCHAR(100)
);
CREATE INDEX knowledge_proposals_shop_time ON knowledge_proposals(shop_id,created_at DESC);
