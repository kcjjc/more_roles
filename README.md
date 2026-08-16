# more_roles —— 多角色 AI 对话平台（微服务版）

基于 Spring AI 的中文多角色智能对话平台：多套人格管理、带窗口记忆 + 异步摘要的长会话、基于 pgvector 的 RAG 文档问答。

采用**轻量微服务架构**（无注册中心 / 无配置中心 / 无 Feign）：两个业务服务 + 一个网关，服务间手写 `RestClient` HTTP 调用，路由写死，docker-compose 编排。

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

## 环境变量（密钥不落盘）

| 变量 | 用途 | 需要的服务 |
|---|---|---|
| `DEEPSEEK_API_KEY` | chat 模型（DeepSeek，rag 的 `/ask` 也用） | chat / rag |
| `EMBEDDING_API_KEY` | 向量模型（阿里云 text-embedding-v3） | rag |
| `APP_DB_PASSWORD` | PostgreSQL 密码 | chat / rag |
| `APP_REDIS_PASSWORD` | Redis 密码 | chat / rag / gateway |
| `MINIO_SECRET_KEY` | MinIO 密钥 | rag |
| `RAG_BASE_URL` | rag 地址（默认 `http://localhost:8082`） | chat / gateway |
| `CHAT_BASE_URL` | chat 地址（默认 `http://localhost:8081`） | gateway |

## 运行

### 方式一：本地起服务（基础设施用远程或自备）

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

### 方式二：docker compose 全栈（含基础设施）

```bash
export DEEPSEEK_API_KEY=... EMBEDDING_API_KEY=... APP_DB_PASSWORD=... \
       APP_REDIS_PASSWORD=... MINIO_SECRET_KEY=...
mvn -DskipTests package
docker compose up -d --build
```

数据库引导：全新 PG 由 `db/init.sql` 建扩展与 schema，表结构由 `ddl-auto=update` 自动建；`doc_chunk` 的 HNSW 索引等按需手工执行 `db/rag_schema.sql`。

## 验证

```bash
# 登录（网关 8080）
curl -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
     -d '{"username":"ckj","password":"123456"}'
# 后续请求带响应里的 tokenValue: 请求头 satoken: <tokenValue>

# 未登录访问 → {"code":500,"message":"未登录或登录已过期","data":null}
# /internal/** 经网关 → 404（外部不可达）
```

## 设计要点（面试常问）

- **拆分边界**：RAG 的解析/向量化是 CPU 密集型异步任务，与对话服务资源特征不同，独立扩容互不影响；`RagRouterService`（要不要检索、检索句改写）属于对话语义留在 chat，向量化 + 相似度检索归 rag。
- **共享登录态**：Sa-Token 会话统一存 Redis，网关校验、两个业务服务各自兜底拦截（双道防线）。
- **降级语义**：绑库对话中 rag 不可用 → 跳过 RAG 继续回答（聊天不中断）；新建绑库会话时 rag 不可用 → 明确报错（显式绑定不能静默失败）。
- **数据所有权**：每个服务只访问自己的 schema，跨服务只有 HTTP 契约（common 模块的 DTO），不跨库 join。
