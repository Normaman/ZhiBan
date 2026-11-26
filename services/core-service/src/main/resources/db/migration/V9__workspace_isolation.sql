CREATE TABLE workspaces (
 id VARCHAR(100) PRIMARY KEY, name VARCHAR(200) NOT NULL,
 owner_user_id VARCHAR(100), created_at BIGINT NOT NULL
);
INSERT INTO workspaces(id,name,created_at) SELECT id,name,0 FROM shops;
ALTER TABLE shops ADD COLUMN workspace_id VARCHAR(100) REFERENCES workspaces(id);
UPDATE shops SET workspace_id=id;
ALTER TABLE shops ALTER COLUMN workspace_id SET NOT NULL;
ALTER TABLE shops ADD COLUMN binding_state VARCHAR(20) NOT NULL DEFAULT 'bound';

CREATE TABLE workspace_memberships (
 workspace_id VARCHAR(100) NOT NULL REFERENCES workspaces(id),
 user_id VARCHAR(100) NOT NULL REFERENCES app_users(id),
 role VARCHAR(20) NOT NULL, created_at BIGINT NOT NULL,
 PRIMARY KEY(workspace_id,user_id)
);
INSERT INTO workspace_memberships(workspace_id,user_id,role,created_at)
 SELECT shop_id,id,role,created_at FROM app_users;
UPDATE workspaces SET owner_user_id=(SELECT MIN(u.id) FROM app_users u WHERE u.shop_id=workspaces.id AND u.role='ADMIN');

CREATE TABLE workspace_invitations (
 id VARCHAR(100) PRIMARY KEY, workspace_id VARCHAR(100) NOT NULL REFERENCES workspaces(id),
 token_hash VARCHAR(64) NOT NULL UNIQUE, created_by VARCHAR(100) NOT NULL,
 created_at BIGINT NOT NULL, expires_at BIGINT NOT NULL, consumed_at BIGINT,
 revoked BOOLEAN NOT NULL DEFAULT FALSE
);

ALTER TABLE jobs ADD COLUMN shop_id VARCHAR(100) REFERENCES shops(id);
UPDATE jobs SET shop_id=(SELECT m.shop_id FROM agent_runs r JOIN messages m ON m.id=r.message_id WHERE r.id=jobs.reference_id) WHERE kind='generate';
UPDATE jobs SET shop_id=(SELECT m.shop_id FROM send_commands s JOIN drafts d ON d.id=s.draft_id JOIN messages m ON m.id=d.message_id WHERE s.id=jobs.reference_id) WHERE kind='send';
ALTER TABLE jobs ALTER COLUMN shop_id SET NOT NULL;
CREATE INDEX jobs_shop_poll ON jobs(shop_id,state,available_at);
CREATE INDEX shops_workspace ON shops(workspace_id);
CREATE TABLE connector_bindings (
 platform_shop_id VARCHAR(100) PRIMARY KEY,
 shop_id VARCHAR(100) NOT NULL UNIQUE REFERENCES shops(id)
);
INSERT INTO connector_bindings(platform_shop_id,shop_id) SELECT platform_shop_id,id FROM shops WHERE mode='live';

ALTER TABLE knowledge_versions ADD COLUMN version_label VARCHAR(100);
UPDATE knowledge_versions SET version_label=id;
ALTER TABLE knowledge_versions ALTER COLUMN version_label SET NOT NULL;
CREATE UNIQUE INDEX knowledge_shop_label ON knowledge_versions(shop_id,version_label);
