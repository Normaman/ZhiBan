ALTER TABLE drafts ADD COLUMN product_cards TEXT NOT NULL DEFAULT '[]';
ALTER TABLE drafts ADD COLUMN cards_edited BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE decisions ADD COLUMN product_cards TEXT NOT NULL DEFAULT '[]';
CREATE OR REPLACE VIEW send_command_context AS
SELECT sc.*, COALESCE(de.final_content,mr.content) AS final_content,
 CASE WHEN mr.id IS NULL THEN de.decision ELSE 'approve' END AS decision,
 CASE WHEN mr.id IS NULL THEN 'agent' ELSE 'manual' END AS origin,
 co.shop_id,co.id AS conversation_id,co.buyer_ref,co.state AS conversation_state,
 d.state AS draft_state,COALESCE(d.conversation_revision,mr.conversation_revision) AS approved_conversation_revision,
 s.connector_account_id,s.platform_shop_id,s.mode,
 COALESCE(de.product_cards,'[]') AS product_cards
FROM send_commands sc
LEFT JOIN decisions de ON de.id=sc.approval_id
LEFT JOIN drafts d ON d.id=sc.draft_id
LEFT JOIN messages m ON m.id=d.message_id
LEFT JOIN manual_replies mr ON mr.id=sc.manual_reply_id
JOIN conversations co ON co.id=COALESCE(m.conversation_id,mr.conversation_id)
JOIN shops s ON s.id=co.shop_id;
