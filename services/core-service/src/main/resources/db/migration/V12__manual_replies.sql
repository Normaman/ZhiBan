CREATE TABLE manual_replies (
 id VARCHAR(100) PRIMARY KEY,
 conversation_id VARCHAR(100) NOT NULL REFERENCES conversations(id),
 request_id VARCHAR(100) NOT NULL,
 conversation_revision BIGINT NOT NULL,
 request_revision BIGINT NOT NULL,
 content TEXT NOT NULL,
 reviewer VARCHAR(100) NOT NULL,
 created_at BIGINT NOT NULL,
 UNIQUE(conversation_id,request_id)
);
ALTER TABLE send_commands ALTER COLUMN approval_id DROP NOT NULL;
ALTER TABLE send_commands ALTER COLUMN draft_id DROP NOT NULL;
ALTER TABLE send_commands ADD COLUMN manual_reply_id VARCHAR(100) UNIQUE REFERENCES manual_replies(id);
ALTER TABLE send_commands ADD CONSTRAINT send_command_origin CHECK (
 (manual_reply_id IS NULL AND draft_id IS NOT NULL AND approval_id IS NOT NULL) OR
 (manual_reply_id IS NOT NULL AND draft_id IS NULL AND approval_id IS NULL)
);
CREATE VIEW send_command_context AS
SELECT sc.*, COALESCE(de.final_content,mr.content) AS final_content,
 CASE WHEN mr.id IS NULL THEN de.decision ELSE 'approve' END AS decision,
 CASE WHEN mr.id IS NULL THEN 'agent' ELSE 'manual' END AS origin,
 co.shop_id,co.id AS conversation_id,co.buyer_ref,co.state AS conversation_state,
 d.state AS draft_state,COALESCE(d.conversation_revision,mr.conversation_revision) AS approved_conversation_revision,
 s.connector_account_id,s.platform_shop_id,s.mode
FROM send_commands sc
LEFT JOIN decisions de ON de.id=sc.approval_id
LEFT JOIN drafts d ON d.id=sc.draft_id
LEFT JOIN messages m ON m.id=d.message_id
LEFT JOIN manual_replies mr ON mr.id=sc.manual_reply_id
JOIN conversations co ON co.id=COALESCE(m.conversation_id,mr.conversation_id)
JOIN shops s ON s.id=co.shop_id;
