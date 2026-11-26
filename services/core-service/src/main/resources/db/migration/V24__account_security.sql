ALTER TABLE app_users ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE authentication_events (
 id VARCHAR(100) PRIMARY KEY,
 event_type VARCHAR(50) NOT NULL,
 user_id VARCHAR(100),
 subject_hash VARCHAR(64) NOT NULL,
 address_hash VARCHAR(64) NOT NULL,
 created_at BIGINT NOT NULL
);
CREATE INDEX authentication_events_user_time ON authentication_events(user_id,created_at);
