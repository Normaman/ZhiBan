ALTER TABLE messages ADD COLUMN related_platform_message_id VARCHAR(200);
CREATE INDEX messages_product_source_link ON messages(conversation_id, related_platform_message_id);
