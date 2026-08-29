# more_roles —— 多角色 AI 对话平台

基于 Spring AI 的中文多角色智能对话平台：注册登录、多套人格管理、带窗口记忆 + 异步摘要的长会话，以及基于 pgvector 的 RAG 知识库问答（文档上传 → 解析 → 分块 → 向量化 → 检索增强回答）。

采用**轻量微服务架构**（无注册中心 / 无配置中心 / 无 Feign）：两个业务服务 + 一个网关，服务间手写 `RestClient` HTTP 调用，docker-compose 一键编排。

## 功能特性

- **用户认证**：Sa-Token 登录态三服务共享（会话存 Redis），网关统一校验、业务服务兜底拦截
- **流式输出**：`POST .../messages/stream` 以 SSE 增量推送模型回复（打字机效果）；流结束后才落库，中断/断开则整轮丢弃；同步接口保留
- **A2A 协议（Agent2Agent v1.0，手写实现）**：rag-service 作为标准 A2A Server 暴露 Agent Card 与 `message:send`（知识库专家 agent，X-Api-Key 认证）；chat 的 agent 模式可切换为经 A2A 协议协作（`rag.a2a.client-enabled`），与 /internal 私有接口并行
- **人格管理**：多套 systemPrompt 独立管理，超长人格按 4000 字/片分片存储，对上层透明
- **长会话记忆**：窗口记忆（20 条）+ 窗口外老消息异步压缩为摘要，摘要并入 system 发给模型，短事务落库 + 乐观锁保证并发安全
- **知识库（RAG）**：上传 PDF / DOCX / MD / TXT 到 MinIO，异步管线解析 → 结构感知分块 → 向量化入 pgvector，支持相似度 + 阈值过滤检索
- **检索路由**：绑库会话每条消息先由路由器判定"要不要检索"，并结合最近对话改写检索句（补全多轮追问中的指代），未绑库或路由失败自动退化为直接对话
- **独立问答入口**：`/api/rag/ask` 对指定知识库直接提问，与对话功能解耦

## 架构

```
                          ┌──────────────────────────────────────────┐
  前端 ──── :8080 ────►   gateway (Spring Cloud Gateway)              │
                          │  统一入口 + Sa-Token 登录校验(会话在 Redis) │
                          │  /api/auth|chat|persona|test/** → chat    │
                          │  /api/rag/**                     → rag    │
                          │  /internal/** 不路由(容器内专用)           │
                          └────────┬──────────────────┬───────────────┘
                                   ▼                  ▼
                          chat-service :8081   rag-service :8082
                          认证/人格/对话编排    知识库/上传/解析/分块
                          窗口记忆+异步摘要     向量化索引/检索(RestClient)
                                   │                  │
                                   └───► /internal/retrieval + /internal/kb/{id}/owned
                                        (检索失败→降级跳过RAG; 归属校验失败→明确报错)
                                   └───► A2A v1.0(HTTP+JSON): /.well-known/agent-card.json
                                        + /message:send + /tasks/{id}   (rag 是标准 A2A Server,
                                        X-Api-Key 认证; chat 的 rag.a2a.client-enabled 开关)

  共享基础设施:  PostgreSQL(单实例双 schema: chat_svc / rag_svc[+pgvector])
                Redis(Sa-Token 共享会话 + embedding 缓存)    MinIO(文档存储)
```

## 模块

| 模块 | 端口 | 职责 | 数据（schema） |
|---|---|---|---|
| `gateway` | 8080 | 统一入口、登录校验、路由；不路由 `/internal/**` | — |
| `chat-service` | 8081 | 注册登录、人格分片、会话记忆、对话编排、RAG 路由器 | `chat_svc`: users / persona_fragment / conversation / message |
| `rag-service` | 8082 | 知识库管理、文档上传(MinIO)、解析/分块/向量化、检索、`/ask` | `rag_svc`: knowledge_base / document / doc_chunk / index_task |
| `common` | — | 跨服务契约 DTO + `Result` + 全局异常处理 | — |

服务间接口（仅容器网络内可达，网关不路由）：

- `POST /internal/retrieval` — 检索，body `{query, kbId, topK}` → 命中分块列表
- `GET /internal/kb/{kbId}/owned?userId=` — 知识库归属校验

## 技术栈

| 类别 | 选型 |
|---|---|
| 语言 / 框架 | Java 21、Spring Boot 3.5、Spring Cloud Gateway（仅网关模块） |
| AI 框架 | Spring AI 1.1（OpenAI 兼容接口） |
| 模型 | chat：DeepSeek（`deepseek-v4-flash`）；embedding：阿里云 `text-embedding-v3`（1024 维） |
| 存储 | PostgreSQL 16 + pgvector（向量）、Redis（会话 + 缓存）、MinIO（文档） |
| 认证 | Sa-Token（Redis 共享会话，token 放请求头 `satoken`） |
| 构建 | Maven 多模块（无 wrapper，直接用系统 `mvn`） |

## 核心设计

- **服务拆分边界**：RAG 的解析 / 向量化是 CPU 密集型异步任务，与对话服务资源特征不同，独立部署互不影响；`RagRouterService`（要不要检索、检索句改写）属于对话语义留在 chat，向量化 + 相似度检索归 rag
- **对话主路径**：绑库会话至多两次 LLM 调用（路由一次 + 主回复一次），模型调用在事务外，落库走短事务 + 乐观锁（3 次重试），摘要 / 标题等副作用异步投递、按游标幂等
- **RAG 入库管线**：异步 ETL（`@Async` 专用线程池），有章节结构走结构感知分块、否则滑窗（512 字 / 64 重叠），embedding 先查 Redis 缓存（MD5 key）未命中再批量调 API（10 条/批，DashScope v3 单批上限，指数退避重试），失败任务自动重试
- **检索增强的不变量**：检索命中只拼进当轮 prompt 的【参考资料】段，绝不作为 message 落库（避免挤占记忆窗口、污染异步摘要）
- **降级语义**：绑库对话中 rag 不可用 → 跳过 RAG 继续回答（聊天不中断）；新建绑库会话时 rag 不可用 → 明确报错（显式绑定不能静默失败）
- **数据所有权**：每个服务只访问自己的 schema，跨服务只有 HTTP 契约（common 模块 DTO），不跨库 join

## API 概览

统一响应 `{code, message, data}`；除登录注册外均需请求头 `satoken: <tokenValue>`。完整请求 / 响应示例见 [docs/API.md](docs/API.md)。

| 域 | 接口 | 说明 |
|---|---|---|
| 认证 | `POST /api/auth/register` `POST /api/auth/login` `POST /api/auth/logout` | 注册 / 登录 / 登出 |
| 人格 | `POST /api/persona/upload`、`GET /api/persona/list` | 上传人格（超长自动分片）、人格列表 |
| 会话 | `GET /api/chat/personas/{personaId}/conversations`、`POST /api/chat/conversations` | 会话列表（可带 `kbId` 绑定知识库）、新建会话 |
| 会话 | `GET /api/chat/conversations/{id}`、`DELETE /api/chat/conversations/{id}` | 会话详情（含消息）、删除会话 |
| 对话 | `POST /api/chat/conversations/{id}/messages` | 发送消息并获取回复 |
| 对话 | `POST /api/chat/conversations/{id}/messages/stream` | 发送消息并获取 SSE 流式回复（`delta` 增量帧 + `done` 收尾帧 + `error` 错误帧；agent 模式最终回复一次性推出） |
| 知识库 | `POST /api/rag/kb`、`GET /api/rag/list` | 新建知识库、知识库列表 |
| 文档 | `POST /api/rag/kb/{kbId}/document`、`GET /api/rag/kb/{kbId}/document` | 上传文档（≤50MB，异步索引）、文档列表（含索引状态，上传后轮询） |
| 问答 | `POST /api/rag/search`、`POST /api/rag/ask` | 相似度检索、对知识库直接提问 |

## 环境变量（密钥不落盘）

| 变量 | 用途 | 需要的服务 |
|---|---|---|
| `DEEPSEEK_API_KEY` | chat 模型（DeepSeek，rag 的 `/ask` 也用），必填 | chat / rag |
| `EMBEDDING_API_KEY` | 向量模型（阿里云 text-embedding-v3），必填 | rag |
| `APP_DB_PASSWORD` | PostgreSQL 密码，必填 | chat / rag |
| `APP_REDIS_PASSWORD` | Redis 密码，必填 | chat / rag / gateway |
| `MINIO_SECRET_KEY` | MinIO 密钥，必填 | rag |
| `EMBEDDING_BASE_URL` | 向量模型入口（默认阿里云 DashScope 兼容模式） | rag |
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USERNAME` | PostgreSQL 地址（默认 `localhost:5432/more_roles`，用户 `postgres`） | chat / rag |
| `REDIS_HOST` / `REDIS_PORT` | Redis 地址（默认 `localhost:6379`） | chat / rag / gateway |
| `MINIO_ENDPOINT` / `MINIO_ACCESS_KEY` | MinIO 地址与账号（默认 `http://localhost:9000` / `admin`） | rag |
| `RAG_BASE_URL` | rag 地址（默认 `http://localhost:8082`） | chat / gateway |
| `CHAT_BASE_URL` | chat 地址（默认 `http://localhost:8081`） | gateway |
| `A2A_API_KEY` | A2A 操作端点（`/message:send`、`/tasks/**`）的 X-Api-Key，两侧同值部署 | chat / rag |
| `A2A_BASE_URL` | A2A 基址（默认 `http://localhost:8082`，容器内用服务名，写进 Agent Card） | chat / rag |

## 快速开始

### 方式一：docker compose 全栈（含基础设施）

```bash
export DEEPSEEK_API_KEY=... EMBEDDING_API_KEY=... APP_DB_PASSWORD=... \
       APP_REDIS_PASSWORD=... MINIO_SECRET_KEY=...
mvn -DskipTests package
docker compose up -d --build
```

数据库引导：全新 PG 由 `db/init.sql` 建扩展与 schema，表结构由 `ddl-auto=update` 自动建；`doc_chunk` 的 HNSW 索引等按需手工执行 `db/rag_schema.sql`。

### 方式二：本地起服务（基础设施用远程或自备）

```bash
mvn -DskipTests package

# 终端 1: rag
DEEPSEEK_API_KEY=... EMBEDDING_API_KEY=... APP_DB_PASSWORD=... \
APP_REDIS_PASSWORD=... MINIO_SECRET_KEY=... \
java -jar rag-service/target/rag-service-1.0-SNAPSHOT.jar

# 终端 2: chat
DEEPSEEK_API_KEY=... APP_DB_PASSWORD=... APP_REDIS_PASSWORD=... \
java -jar chat-service/target/chat-service-1.0-SNAPSHOT.jar

# 终端 3: gateway
APP_REDIS_PASSWORD=... java -jar gateway/target/gateway-1.0-SNAPSHOT.jar
```

## 验证

```bash
# 登录（网关 8080；ckj/123456 是 db/chat_schema.sql 内置的演示账号）
curl -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
     -d '{"username":"ckj","password":"123456"}'
# 后续请求带响应里的 tokenValue: 请求头 satoken: <tokenValue>

# 未登录访问 → {"code":500,"message":"未登录或登录已过期","data":null}
# /internal/** 经网关 → 404（外部不可达）
```

## 目录结构

```
├── gateway/          # Spring Cloud Gateway：路由 + 登录校验
├── chat-service/     # 认证 / 人格 / 会话 / 对话编排 / RAG 路由器
├── rag-service/      # 知识库 / 文档上传 / 解析分块 / 向量化 / 检索
├── common/           # 跨服务契约 DTO / Result / 全局异常
├── db/               # init.sql（引导）+ chat/rag schema
├── docs/             # API 文档
└── docker-compose.yml
```
