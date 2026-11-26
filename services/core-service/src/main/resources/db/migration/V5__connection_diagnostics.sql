ALTER TABLE shops ADD COLUMN connection_error_code VARCHAR(100);
CREATE TABLE workspace_initializations (
 id VARCHAR(100) PRIMARY KEY,
 source VARCHAR(100) NOT NULL,
 created_at BIGINT NOT NULL
);
