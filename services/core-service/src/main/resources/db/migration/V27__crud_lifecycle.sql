ALTER TABLE workspace_memberships ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE workspace_memberships ADD COLUMN revision BIGINT NOT NULL DEFAULT 1;

CREATE TABLE knowledge_proposal_revisions (
 proposal_id VARCHAR(100) NOT NULL REFERENCES knowledge_proposals(id) ON DELETE CASCADE,
 revision BIGINT NOT NULL,
 base_version VARCHAR(100), snapshot TEXT NOT NULL, state VARCHAR(20) NOT NULL,
 actor VARCHAR(200) NOT NULL, created_at BIGINT NOT NULL,
 review_note TEXT, published_version VARCHAR(100),
 PRIMARY KEY(proposal_id,revision)
);
INSERT INTO knowledge_proposal_revisions
 (proposal_id,revision,base_version,snapshot,state,actor,created_at,review_note,published_version)
 SELECT id,revision,base_version,snapshot,state,COALESCE(reviewed_by,created_by),
        COALESCE(reviewed_at,created_at),review_note,published_version
 FROM knowledge_proposals;
