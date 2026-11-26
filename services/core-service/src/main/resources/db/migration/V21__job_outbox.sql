CREATE TABLE job_outbox (
 id VARCHAR(100) PRIMARY KEY,
 job_id VARCHAR(100) NOT NULL REFERENCES jobs(id),
 state VARCHAR(20) NOT NULL DEFAULT 'pending',
 available_at BIGINT NOT NULL,
 attempts INTEGER NOT NULL DEFAULT 0,
 locked_until BIGINT NOT NULL DEFAULT 0,
 claim_id VARCHAR(100),
 error_code VARCHAR(100),
 created_at BIGINT NOT NULL,
 published_at BIGINT
);
CREATE INDEX job_outbox_poll ON job_outbox(state,available_at,locked_until);

INSERT INTO job_outbox(id,job_id,state,available_at,created_at)
SELECT 'initial:' || id, id, 'pending',
       CASE WHEN state='running' THEN GREATEST(available_at,locked_until+1) ELSE available_at END,
       available_at
FROM jobs
WHERE state IN ('pending','running');
