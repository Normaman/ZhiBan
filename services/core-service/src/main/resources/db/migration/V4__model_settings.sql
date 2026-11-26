CREATE TABLE model_configurations (
 id VARCHAR(100) PRIMARY KEY,
 shop_id VARCHAR(100) NOT NULL REFERENCES shops(id),
 version BIGINT NOT NULL,
 base_url VARCHAR(1000) NOT NULL,
 model_name VARCHAR(200) NOT NULL,
 encrypted_api_key TEXT NOT NULL,
 key_hint VARCHAR(20) NOT NULL,
 max_rounds INTEGER NOT NULL,
 timeout_seconds INTEGER NOT NULL,
 test_state VARCHAR(30) NOT NULL DEFAULT 'untested',
 test_error_code VARCHAR(100),
 tested_at BIGINT,
 created_at BIGINT NOT NULL,
 UNIQUE(shop_id,version)
);
CREATE TABLE model_settings (
 shop_id VARCHAR(100) PRIMARY KEY REFERENCES shops(id),
 revision BIGINT NOT NULL,
 latest_id VARCHAR(100) REFERENCES model_configurations(id),
 active_id VARCHAR(100) REFERENCES model_configurations(id),
 environment_fallback BOOLEAN NOT NULL DEFAULT false
);
