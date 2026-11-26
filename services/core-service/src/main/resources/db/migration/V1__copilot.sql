CREATE TABLE shops (
 id VARCHAR(100) PRIMARY KEY, name VARCHAR(200) NOT NULL,
 connector_account_id VARCHAR(100) NOT NULL, platform_shop_id VARCHAR(100) NOT NULL,
 connection_state VARCHAR(40) NOT NULL DEFAULT 'disconnected', connection_seen_at BIGINT NOT NULL DEFAULT 0,
 connection_epoch VARCHAR(100), connection_sequence BIGINT NOT NULL DEFAULT 0,
 knowledge_version VARCHAR(100), mode VARCHAR(20) NOT NULL
);
CREATE TABLE knowledge_versions (
 id VARCHAR(100) PRIMARY KEY, shop_id VARCHAR(100) NOT NULL REFERENCES shops(id),
 snapshot TEXT NOT NULL, created_at BIGINT NOT NULL
);
CREATE TABLE conversations (
 id VARCHAR(100) PRIMARY KEY, shop_id VARCHAR(100) NOT NULL REFERENCES shops(id),
 buyer_ref VARCHAR(200) NOT NULL, revision BIGINT NOT NULL DEFAULT 0,
 state VARCHAR(40) NOT NULL DEFAULT 'open', updated_at BIGINT NOT NULL,
 UNIQUE(shop_id,buyer_ref)
);
CREATE TABLE messages (
 id VARCHAR(100) PRIMARY KEY, shop_id VARCHAR(100) NOT NULL REFERENCES shops(id),
 conversation_id VARCHAR(100) NOT NULL REFERENCES conversations(id), platform_message_id VARCHAR(200),
 event_id VARCHAR(100) NOT NULL, direction VARCHAR(20) NOT NULL,
 message_type VARCHAR(40) NOT NULL, content TEXT NOT NULL, occurred_at VARCHAR(60),
 received_at VARCHAR(60) NOT NULL, payload_hash VARCHAR(64) NOT NULL,
 state VARCHAR(40) NOT NULL, created_at BIGINT NOT NULL,
 UNIQUE(shop_id,platform_message_id), UNIQUE(shop_id,event_id)
);
CREATE TABLE agent_runs (
 id VARCHAR(100) PRIMARY KEY, message_id VARCHAR(100) NOT NULL REFERENCES messages(id),
 conversation_revision BIGINT NOT NULL, knowledge_version VARCHAR(100),
 state VARCHAR(40) NOT NULL, result TEXT, created_at BIGINT NOT NULL
);
CREATE TABLE drafts (
 id VARCHAR(100) PRIMARY KEY, message_id VARCHAR(100) NOT NULL UNIQUE REFERENCES messages(id),
 run_id VARCHAR(100) REFERENCES agent_runs(id), revision BIGINT NOT NULL,
 conversation_revision BIGINT NOT NULL, state VARCHAR(40) NOT NULL,
 content TEXT NOT NULL, citations TEXT NOT NULL, risk_codes TEXT NOT NULL,
 knowledge_version VARCHAR(100), outcome VARCHAR(40) NOT NULL, updated_at BIGINT NOT NULL
);
CREATE TABLE decisions (
 id VARCHAR(100) PRIMARY KEY, draft_id VARCHAR(100) NOT NULL REFERENCES drafts(id),
 revision BIGINT NOT NULL, decision VARCHAR(40) NOT NULL, final_content TEXT NOT NULL,
 reviewer VARCHAR(100) NOT NULL, created_at BIGINT NOT NULL, UNIQUE(draft_id,revision)
);
CREATE TABLE send_commands (
 id VARCHAR(100) PRIMARY KEY, approval_id VARCHAR(100) NOT NULL UNIQUE REFERENCES decisions(id),
 draft_id VARCHAR(100) NOT NULL REFERENCES drafts(id), revision BIGINT NOT NULL,
 attempt INTEGER NOT NULL DEFAULT 1, state VARCHAR(40) NOT NULL,
 receipt TEXT, error_code VARCHAR(100), updated_at BIGINT NOT NULL
);
CREATE TABLE jobs (
 id VARCHAR(100) PRIMARY KEY, kind VARCHAR(20) NOT NULL, reference_id VARCHAR(100) NOT NULL,
 state VARCHAR(20) NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
 available_at BIGINT NOT NULL, locked_until BIGINT NOT NULL DEFAULT 0, claim_id VARCHAR(100),
 UNIQUE(kind,reference_id)
);
CREATE INDEX jobs_poll ON jobs(state, available_at);
CREATE TABLE audit_events (
 id VARCHAR(100) PRIMARY KEY, shop_id VARCHAR(100) NOT NULL,
 event_type VARCHAR(60) NOT NULL, subject_id VARCHAR(100) NOT NULL,
 actor VARCHAR(100) NOT NULL, trace_id VARCHAR(100) NOT NULL, created_at BIGINT NOT NULL
);
