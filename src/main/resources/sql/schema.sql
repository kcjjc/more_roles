-- ============================================================
-- 用户表 (PostgreSQL)
-- 建议建在 more_roles 数据库下
-- ============================================================

-- 注意: 表名用 users 而不是 user
-- 因为 user 在 PG 里是保留关键字(CURRENT_USER / SET ROLE 等语法用到),
-- 直接叫 user 容易踩坑。若一定要用, 必须全程加双引号:  "user"
CREATE TABLE IF NOT EXISTS users (
    id        BIGSERIAL    PRIMARY KEY,          -- 自增主键: BIGSERIAL = BIGINT + 自动序列
    username  VARCHAR(50)  NOT NULL UNIQUE,      -- 用户名, 唯一(登录要用)
    password  VARCHAR(100) NOT NULL              -- 密码: 实际项目必须存哈希(BCrypt), 切勿明文!
);


-- ============================================================
-- 人格信息【分片表】 persona_fragment (PostgreSQL)
-- ============================================================
-- 为什么叫"分片表":
--   人格信息(系统提示词等)可能很长, 单列存大文本不安全/不好控,
--   这里固定按 4000 字/片切分, 超长就拆成多行存, 读取时再按序拼回。
-- 关键字段怎么配合:
--   * persona_id  —— 标识"同一条人格"。一条人格无论被切成几片, 都共享同一个 persona_id。
--                    (查询时就是靠"这一个字段"把同一条人格的几条数据查出来)
--   * seq         —— 分片序号(从 0 开始), 决定拼接顺序。代码端 ORDER BY seq ASC 再拼接。
--   * status      —— 软删除标记: 1=有效, 0=已删除; 查询一律带 status = 1 过滤。
--   * user_id     —— 所属用户(关联 users.id)。一个用户可以有多条 persona(多个 persona_id)。
CREATE TABLE IF NOT EXISTS persona_fragment (
    id          BIGSERIAL       PRIMARY KEY,
    user_id     BIGINT          NOT NULL,                       -- 所属用户(关联 users.id)
    persona_id  VARCHAR(64)     NOT NULL,                       -- 标识同一条人格(多片共享), 存去掉横线的 UUID
    seq         INTEGER         NOT NULL,                       -- 分片序号, 从 0 开始, 决定拼接顺序
    name        VARCHAR(100),                                    -- 人格名称(冗余字段, 每片都存一份, 列表展示用)
    content     VARCHAR(4000)   NOT NULL,                       -- 内容片段, 单片最多 4000 字
    status      INTEGER         NOT NULL DEFAULT 1,             -- 软删除: 1=有效, 0=已删除
    created_at  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_persona_seq UNIQUE (persona_id, seq)          -- 同一人格同一序号唯一, 防重复分片
);

-- 索引: 覆盖两种典型查询
CREATE INDEX IF NOT EXISTS idx_pf_user_status  ON persona_fragment (user_id, status);            -- 列出某用户的有效人格
CREATE INDEX IF NOT EXISTS idx_pf_user_persona ON persona_fragment (user_id, persona_id, seq);   -- 取出某用户某人格的全部分片(按序)


-- ============================================================
-- 对话会话表 conversation (PostgreSQL)
-- ============================================================
-- 一行 = 用户与某个大模型人格的【一次会话】. 这里只存会话【元信息】,
-- 具体的每一轮对话消息存在下面的 message 子表(每条消息一行).
--   * user_id          发起会话的用户.
--   * persona_id       本次会话使用的人格(逻辑关联 persona_fragment.persona_id).
--   * title            会话标题: 首轮对话后由大模型总结第一次会话内容生成(首轮结束前为 NULL).
--   * summary          历史摘要: 窗口外的老消息压缩成的一段摘要, 实现"长会话记忆".
--   * summarized_count 已纳入摘要的消息条数, 用于增量摘要的进度追踪.
--   * total_tokens     本会话累计消耗的 token 数.
--   * version          乐观锁版本号: 防止同一会话并发发消息时 token/summary 丢失更新.
--   * created_at       会话发起时间; updated_at 每追加一轮对话刷新一次.
-- 注: persona_id 不建物理外键 —— persona_fragment.persona_id 非唯一(多片共享), 无法做 FK 目标,
--     这里仅逻辑关联, 与 persona_fragment → users 的处理保持一致.
CREATE TABLE IF NOT EXISTS conversation (
    id               BIGSERIAL     PRIMARY KEY,
    user_id          BIGINT        NOT NULL,                       -- 发起会话的用户(关联 users.id)
    persona_id       VARCHAR(64)   NOT NULL,                       -- 本次会话使用的人格(关联 persona_fragment.persona_id)
    title            VARCHAR(200),                                 -- 会话标题: 首轮对话后总结生成
    summary          TEXT,                                         -- 历史摘要: 窗口外老消息压缩成的一段摘要
    summarized_count INTEGER       NOT NULL DEFAULT 0,             -- 已纳入摘要的消息条数(增量摘要进度)
    total_tokens     INTEGER       NOT NULL DEFAULT 0,             -- 本会话累计消耗的 token 数
    version          INTEGER       NOT NULL DEFAULT 0,             -- 乐观锁版本号(防并发丢失更新)
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 索引: 列出某用户的会话(按时间倒序) / 某用户某人格的会话
CREATE INDEX IF NOT EXISTS idx_conv_user          ON conversation (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_conv_user_persona  ON conversation (user_id, persona_id);


-- ============================================================
-- 对话消息表 message (PostgreSQL)
-- ============================================================
-- conversation 1 ──< message: 一个会话有多条消息, 每条消息一行.
--   * conversation_id  所属会话(关联 conversation.id).
--   * role             消息角色: user(用户提问) / assistant(模型回复) / system / tool.
--                      取值用小写, 与大模型 API 的角色约定一致, 可直接喂给 Spring AI.
--   * content          消息内容, TEXT 无长度限制.
--   * tokens           本条消息消耗的 token 数(便于按条统计).
CREATE TABLE IF NOT EXISTS message (
    id              BIGSERIAL     PRIMARY KEY,
    conversation_id BIGINT        NOT NULL,                        -- 所属会话(关联 conversation.id)
    role            VARCHAR(20)   NOT NULL,                        -- 消息角色: user / assistant / system / tool
    content         TEXT          NOT NULL,                        -- 消息内容
    tokens          INTEGER       NOT NULL DEFAULT 0,              -- 本条消息消耗的 token 数
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 索引: 取某会话的消息(按时间正序, 还原对话顺序) / 取最近 N 条(窗口, JpaChatMemoryRepository 用)
CREATE INDEX IF NOT EXISTS idx_msg_conv ON message (conversation_id, created_at);


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
CREATE INDEX idx_task_doc_id ON index_task (doc_id);