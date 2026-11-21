-- ============================================
-- 多知识库迁移脚本（可重复执行）
-- 适用版本：20260529
-- 说明：现有文档统一归入默认知识库（id=1）
-- ============================================

CREATE TABLE IF NOT EXISTS knowledge_bases (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  description TEXT,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ
);

INSERT INTO knowledge_bases (id, name, description, enabled)
VALUES (1, '默认知识库', '系统默认知识库，存储所有上传的代码规范和技术文档', TRUE)
ON CONFLICT (id) DO NOTHING;

SELECT setval('knowledge_bases_id_seq', GREATEST((SELECT MAX(id) FROM knowledge_bases), 1));

ALTER TABLE knowledge_documents ADD COLUMN IF NOT EXISTS kb_id BIGINT;
UPDATE knowledge_documents SET kb_id = 1 WHERE kb_id IS NULL;
ALTER TABLE knowledge_documents ALTER COLUMN kb_id SET DEFAULT 1;
ALTER TABLE knowledge_documents ALTER COLUMN kb_id SET NOT NULL;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'fk_knowledge_documents_kb'
  ) THEN
    ALTER TABLE knowledge_documents
      ADD CONSTRAINT fk_knowledge_documents_kb
      FOREIGN KEY (kb_id) REFERENCES knowledge_bases(id);
  END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_knowledge_documents_kb_id ON knowledge_documents(kb_id);

-- 注意：如果已启用向量检索，旧 vector_store 数据缺少 kb_id 元数据。
-- 届时请对文档重新执行向量化，或在启用向量过滤前补充 kb_id。
