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

-- (可选) 测试数据 —— 密码明文仅用于本地测试, 生产环境请存 BCrypt 哈希值
-- ON CONFLICT: 用户名已存在就跳过, 避免重复执行脚本报错 (PG 的 upsert 语法)
INSERT INTO users (username, password)
VALUES ('ckj', '123456')
ON CONFLICT (username) DO NOTHING;


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

-- (可选) 测试数据 —— 演示【一条 6000 字的人格信息, 拆成两条存储】
-- seq=0 存前 4000 字, seq=1 存后 2000 字; 两条共享同一个 persona_id='demo-persona-6000'
-- repeat('你', 4000) 生成 4000 个字符, 正好填满一片; 取出时按 seq 拼接即可还原 6000 字原文
INSERT INTO persona_fragment (user_id, persona_id, seq, name, content, status) VALUES
    (1, 'demo-persona-6000', 0, '超长人格示例', repeat('你', 4000), 1),
    (1, 'demo-persona-6000', 1, '超长人格示例', repeat('好', 2000), 1)
ON CONFLICT (persona_id, seq) DO NOTHING;

-- 再插一条【单片的短人格】(内容没超过 4000 字, 只占一行, seq=0)
INSERT INTO persona_fragment (user_id, persona_id, seq, name, content, status) VALUES
    (1, 'demo-persona-short', 0, '猫娘助手', '你是一个温柔可爱的猫娘助手，说话喜欢在句尾加上“喵~”。', 1)
ON CONFLICT (persona_id, seq) DO NOTHING;


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
