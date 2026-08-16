-- ============================================================
-- rag-service 表结构(schema: rag_svc)
-- 表: knowledge_base / document / doc_chunk / index_task
-- 依赖 pgvector 扩展(装在 public, search_path 需带上)
-- ============================================================
CREATE SCHEMA IF NOT EXISTS rag_svc;
SET search_path TO rag_svc, public;

-- 启用 PGVector 扩展（必须，向量存储依赖）
CREATE
EXTENSION IF NOT EXISTS vector;


CREATE TABLE IF NOT EXISTS knowledge_base
(
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE
)
COMMENT ON TABLE knowledge_base IS '知识库，一个用户可以创建多个知识库';
CREATE INDEX idx_user ON knowledge_base (created_by) WHERE is_deleted = FALSE;

-- ================================================================
-- 文档表
-- ================================================================
CREATE TABLE document
(
    id BIGSERIAL PRIMARY KEY,
    kb_id       BIGINT       NOT NULL,
    file_name   VARCHAR(255) NOT NULL,
    file_type   VARCHAR(20)  NOT NULL,           -- PDF / DOCX / MD / TXT
    file_size   BIGINT       NOT NULL,           -- 字节数
    minio_path  VARCHAR(500) NOT NULL,           -- MinIO 中的对象路径
    status      VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    -- PENDING / PROCESSING / DONE / FAILED
    error_msg   TEXT,                            -- 失败原因
    chunk_count INT                   DEFAULT 0, -- 索引后的分块数量
    token_count INT                   DEFAULT 0, -- 向量化消耗的 Token 数
    version     INT          NOT NULL DEFAULT 1, -- 文档版本号，更新时递增
    uploaded_by BIGINT       NOT NULL,
    uploaded_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    indexed_at  TIMESTAMP,                       -- 最近一次索引完成时间
    is_deleted  BOOLEAN      NOT NULL DEFAULT FALSE
);

COMMENT ON TABLE document IS '上传到知识库的文档，一个文档对应多个分块';
COMMENT ON COLUMN document.version IS '每次重建索引版本号加1，旧版本分块通过版本号识别并删除';

CREATE INDEX idx_doc_kb_id ON document (kb_id) WHERE is_deleted = FALSE;
CREATE INDEX idx_doc_status ON document (status) WHERE is_deleted = FALSE;


-- ================================================================
-- 文档分块表（核心表，含向量字段）
-- ================================================================
CREATE TABLE doc_chunk (
    id              BIGSERIAL PRIMARY KEY,
    doc_id          BIGINT          NOT NULL,
    kb_id           BIGINT          NOT NULL,           -- 冗余存储，检索时避免 JOIN
    chunk_index     INT             NOT NULL,           -- 在文档中的顺序（0-based）
    content         TEXT            NOT NULL,           -- 分块原文
    content_tsv     TSVECTOR,                          -- 全文检索索引（自动维护）
    embedding       VECTOR(1024)    NOT NULL,           -- 向量（text-embedding-v3 是 1024 维）
    page_num        INT,                               -- 来自文档第几页（PDF 专用）
    section_title   VARCHAR(500),                      -- 所在章节标题（如果能识别）
    token_count     INT             NOT NULL DEFAULT 0, -- 该块的 Token 估算数
    doc_version     INT             NOT NULL,           -- 对应的文档版本号
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE doc_chunk IS '文档分块表，每条记录是一个可检索的最小单元';
COMMENT ON COLUMN doc_chunk.content_tsv IS '全文检索向量，由触发器自动更新';
COMMENT ON COLUMN doc_chunk.doc_version IS '冗余版本号，重建索引后删除旧版本时使用';

-- 向量检索索引（HNSW，适合高并发检索）
-- m=16: 每个节点的最大连接数，越大越准但更占内存
-- ef_construction=128: 构建索引时的搜索宽度，越大越准但建索引更慢
CREATE INDEX idx_chunk_embedding ON doc_chunk
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 128);

-- 全文检索索引
CREATE INDEX idx_chunk_content_tsv ON doc_chunk USING GIN (content_tsv);

-- 按 kb_id 过滤的索引（多租户场景必须有）
CREATE INDEX idx_chunk_kb_id ON doc_chunk (kb_id);
CREATE INDEX idx_chunk_doc_id ON doc_chunk (doc_id);

-- 触发器：自动维护全文检索向量
-- 简单版：用默认英文分词（中文效果一般，但不需要额外扩展）
-- 注意：中文全文检索效果不佳，主要靠向量检索；全文检索作为补充用于精确词搜索
CREATE
OR
REPLACE FUNCTION update_chunk_tsv()
RETURNS TRIGGER AS $$
BEGIN NEW.content_tsv := to_tsvector('simple', NEW.content);
RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trigger_chunk_tsv
    BEFORE INSERT OR UPDATE OF content
                     ON doc_chunk
                         FOR EACH ROW
                         EXECUTE FUNCTION update_chunk_tsv();



-- ============================================
-- 任务索引表
-- ============================================
CREATE TABLE IF NOT EXISTS index_task
(
    id          BIGSERIAL PRIMARY KEY,
    doc_id      BIGINT      NOT NULL,
    task_type   VARCHAR(20) NOT NULL DEFAULT 'INDEX',   -- INDEX | REOMDEX
    status      VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INT         NOT NULL DEFAULT 0,
    max_retry   INT         NOT NULL DEFAULT 3,
    error_msg   TEXT,
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
    started_at  TIMESTAMP,
    finished_at TIMESTAMP
);

CREATE INDEX idx_task_status ON index_task (status,created_at);
