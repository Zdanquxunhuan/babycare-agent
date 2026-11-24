CREATE EXTENSION IF NOT EXISTS vector;
-- Store only dense BGE-M3 CLS embeddings (revision 5617a9f, input format v1).
CREATE TABLE IF NOT EXISTS bc_bgem3_chunk_v1 (
    chunk_id VARCHAR(36) PRIMARY KEY REFERENCES bc_policy_chunk(chunk_id) ON DELETE CASCADE,
    embedding vector(1024) NOT NULL
);
