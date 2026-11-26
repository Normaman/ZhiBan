CREATE TABLE agent_execution_results (
 run_id VARCHAR(100) PRIMARY KEY REFERENCES agent_runs(id) ON DELETE CASCADE,
 request_payload TEXT NOT NULL,
 lease_id VARCHAR(100),
 lease_until BIGINT NOT NULL DEFAULT 0,
 result TEXT,
 completed_at BIGINT
);
