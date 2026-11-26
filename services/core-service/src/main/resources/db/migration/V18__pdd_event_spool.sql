CREATE TABLE pdd_event_spool (
 id VARCHAR(100) PRIMARY KEY,
 shop_id VARCHAR(100) NOT NULL REFERENCES shops(id),
 event_key VARCHAR(300) NOT NULL,
 event_type VARCHAR(30) NOT NULL,
 payload TEXT NOT NULL,
 state VARCHAR(20) NOT NULL DEFAULT 'pending',
 attempts INTEGER NOT NULL DEFAULT 0,
 available_at BIGINT NOT NULL,
 error_code VARCHAR(100),
 created_at BIGINT NOT NULL,
 delivered_at BIGINT,
 UNIQUE(shop_id,event_key)
);
CREATE INDEX pdd_event_spool_poll ON pdd_event_spool(state,available_at,created_at);
