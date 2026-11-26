WITH ranked AS (
  SELECT id, row_number() OVER (PARTITION BY job_id ORDER BY created_at, id) AS position
  FROM job_outbox
  WHERE state IN ('pending', 'publishing')
)
UPDATE job_outbox
SET state='superseded', locked_until=0, claim_id=NULL
WHERE id IN (SELECT id FROM ranked WHERE position > 1);

CREATE UNIQUE INDEX job_outbox_one_active_per_job
ON job_outbox(job_id)
WHERE state IN ('pending', 'publishing');
