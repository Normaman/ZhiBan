CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE IF NOT EXISTS rag_manifests (
 shop_id TEXT NOT NULL, knowledge_version TEXT NOT NULL, model_revision TEXT NOT NULL,
 fingerprint TEXT NOT NULL, indexed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(shop_id, knowledge_version, model_revision)
);
CREATE TABLE IF NOT EXISTS rag_chunks (
 shop_id TEXT NOT NULL, knowledge_version TEXT NOT NULL, model_revision TEXT NOT NULL,
 source_id TEXT NOT NULL, part INTEGER NOT NULL, embedding vector(512) NOT NULL,
 PRIMARY KEY(shop_id, knowledge_version, model_revision, source_id, part)
);
-- Exact vector ranking within the filtered version is deliberate for bounded shop knowledge.
-- It avoids approximate index filtering starving small tenant/product candidate sets.
