CREATE TABLE IF NOT EXISTS bc_policy (
  policy_key VARCHAR(80) PRIMARY KEY,
  active_version INTEGER,
  last_published INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS bc_policy_version (
  policy_key VARCHAR(80) NOT NULL REFERENCES bc_policy(policy_key),
  rev INTEGER NOT NULL,
  title VARCHAR(120) NOT NULL,
  store_scope VARCHAR(16) NOT NULL,
  expires_on DATE,
  markdown TEXT NOT NULL,
  reviewed_by VARCHAR(80),
  PRIMARY KEY (policy_key, rev)
);
CREATE TABLE IF NOT EXISTS bc_policy_chunk (
  chunk_id VARCHAR(36) PRIMARY KEY,
  policy_key VARCHAR(80) NOT NULL,
  rev INTEGER NOT NULL,
  position_no INTEGER NOT NULL,
  section TEXT NOT NULL,
  excerpt TEXT NOT NULL,
  FOREIGN KEY (policy_key, rev) REFERENCES bc_policy_version(policy_key, rev)
);
CREATE TABLE IF NOT EXISTS bc_policy_term (
  term VARCHAR(80) NOT NULL,
  chunk_id VARCHAR(36) NOT NULL REFERENCES bc_policy_chunk(chunk_id),
  weight INTEGER NOT NULL,
  PRIMARY KEY (term, chunk_id)
);
CREATE TABLE IF NOT EXISTS bc_answer_feedback (
  message_id VARCHAR(36) NOT NULL,
  guide_name VARCHAR(80) NOT NULL,
  evidence_id VARCHAR(80) NOT NULL,
  version_label VARCHAR(40) NOT NULL,
  helpful BOOLEAN NOT NULL,
  PRIMARY KEY (guide_name,message_id,evidence_id)
);
