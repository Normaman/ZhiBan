CREATE TABLE message_reads (
 message_id VARCHAR(100) NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
 reader_username VARCHAR(48) NOT NULL,
 read_at BIGINT NOT NULL,
 PRIMARY KEY(message_id,reader_username)
);
CREATE INDEX messages_conversation_inbound ON messages(conversation_id,direction,message_type);
CREATE TABLE platform_read_markers (
 shop_id VARCHAR(100) NOT NULL REFERENCES shops(id),
 buyer_ref VARCHAR(200) NOT NULL,
 user_last_read VARCHAR(200),
 min_supported_message_id VARCHAR(200) NOT NULL,
 observed_at BIGINT NOT NULL,
 PRIMARY KEY(shop_id,buyer_ref)
);
