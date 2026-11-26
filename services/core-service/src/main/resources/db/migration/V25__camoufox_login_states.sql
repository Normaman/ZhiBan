CREATE TABLE camoufox_login_states (
  shop_id VARCHAR(100) PRIMARY KEY REFERENCES shops(id) ON DELETE CASCADE,
  platform_shop_id VARCHAR(200) NOT NULL,
  account_id VARCHAR(200) NOT NULL,
  encrypted_state TEXT NOT NULL,
  revision VARCHAR(36) NOT NULL,
  updated_at BIGINT NOT NULL,
  invalidated_at BIGINT
);
