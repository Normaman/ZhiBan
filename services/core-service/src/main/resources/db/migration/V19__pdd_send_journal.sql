CREATE TABLE pdd_send_parts (
 shop_id VARCHAR(100) NOT NULL REFERENCES shops(id),
 command_id VARCHAR(100) NOT NULL REFERENCES send_commands(id),
 attempt INTEGER NOT NULL,
 part_key VARCHAR(240) NOT NULL,
 payload_hash VARCHAR(64) NOT NULL,
 state VARCHAR(40) NOT NULL,
 receipt TEXT,
 updated_at BIGINT NOT NULL,
 PRIMARY KEY(command_id,attempt,part_key)
);
CREATE INDEX pdd_send_parts_shop ON pdd_send_parts(shop_id,updated_at);
