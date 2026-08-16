# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概览

基于 **Spring AI** 的中文多角色智能对话平台。核心能力：多套人格系统提示词管理、带窗口记忆 + 异步摘要的长会话、基于 pgvector 的 RAG 文档入库管线。

- 技术栈：Java 21、Spring Boot 3.5.11、Spring Cloud 2025.0.0（仅 Gateway）、Spring AI 1.1.2、PostgreSQL + pgvector、Redis、MinIO、Sa-Token 1.44
- **微服务结构（2026-08-16 拆分）**：Maven 多模块，包根统一 `org.example`。入口类三个：`gateway`(`GatewayApplication`:8080 统一入口+鉴权) / `chat-service`(`MoreRoleApplication`:8081，类名是 `MoreRole` 非 `MoreRoles`) / `rag-service`(`RagApplication`:8082)。轻量方案：无注册中心/配置中心/Feign，服务间手写 `RestClient`
- LLM 走 `spring-ai-starter-model-openai` 的 OpenAI 兼容接口：**chat = DeepSeek**（`deepseek-v4-flash`），**embedding = 阿里云 DashScope**（`text-embedding-v3`，**1024 维**）

## 常用命令

> 仓库**没有 Maven Wrapper**（`mvnw` 不存在），直接用系统 `mvn`。

```bash
mvn clean package -DskipTests   # 打包全部模块（Lombok 已在 spring-boot-maven-plugin 中排除）
mvn test                        # 跑测试（chat-service 下有 RagRouterServiceTest）
mvn -pl chat-service -am ...    # 只构建某模块（-am 连带其依赖模块，common 不在本地仓库时必须带）
mvn spring-boot:run -pl chat-service   # 本地起单个服务
```

启动三个服务 + 密钥环境变量见 **README.md「运行」**（`DEEPSEEK_API_KEY` / `EMBEDDING_API_KEY` / `APP_DB_PASSWORD` / `APP_REDIS_PASSWORD` / `MINIO_SECRET_KEY`，yaml 里全部是 `${...}` 占位符，**密钥不落盘**）。

运行前置条件：PostgreSQL（需 pgvector 扩展）、Redis、MinIO（远程或 docker compose 自建）。`spring.jpa.hibernate.ddl-auto=update` 会按实体自动建表，但 `doc_chunk` 全文检索触发器、HNSW 索引等需执行 `db/rag_schema.sql`（chat 域是 `db/chat_schema.sql`，全新环境引导用 `db/init.sql`）。

## 架构要点

### 1. 对话主路径（`ChatService.chat`）
核心设计是"**主路径至多两次 LLM（绑库会话 = 路由一次 + 主回复一次，未绑库/路由失败退化为一次），落库走短事务 + 乐观锁，副作用异步化**"：
- 流程：`读上下文(人格 + 摘要) → (绑库时: RagRouterService 路由判定 + RagRetrievalClient 跨服务检索) → 调主模型(事务外) → 短事务落库 user/assistant 消息 + token → 异步投递摘要/标题`
- 人格按需 `.system(persona)` 注入，用的是 `ChatService` 内部构建的、**不带 defaultSystem 的 ChatClient**。
- 记忆：`MessageWindowChatMemory`（窗口 20 条，底层走 `JpaChatMemoryRepository` 读 message 表）+ 窗口外的老消息异步压缩进 `conversation.summary`，摘要并入 system 发给模型。
- 并发安全：`Conversation.version` 乐观锁，落库 / 写摘要 / 写标题均带 3 次重试；摘要靠游标 `summarized_count` 幂等推进。
- **关键约束**：`@Async` 必须跨 Bean 走代理才生效 —— 摘要 / 标题被拆到独立 Bean `ChatPostProcessor`，不能放回 `ChatService` 内部调用。两个 `@Async` 方法都靠"游标 / 空值判断"幂等，失败或崩溃不丢，下次 `chat` 会重新投递。

### 2. 人格分片存储（`PersonaService`）
人格（systemPrompt）可能很长，按 **4000 字/片**切分入库（`persona_fragment` 表），多片共享一个 `persona_id`，读时按 `seq` 升序拼接，对上层屏蔽长度细节。软删除靠 `status`（1 有效 / 0 删除）。

### 3. RAG 入库管线（异步 ETL）
入口两个：`IndexService.submitTask`（直接给文本，跳过解析器，`DataInitializer` 用）和 `submitTaskFromMinio`（REST 上传的文件，从 MinIO 下载后走解析器）→ `IndexTaskLauncher.@Async("indexTaskExecutor")` → `LoadService.doIndex`：
- **解析**（`DocumentLoaderService`）：按文件扩展名选 `DocumentParser`（支持 PDF / DOCX / MD / TXT），产出 `ParseResult`（含页码 / 章节）。
- **分块**（`ChunkService`）：有章节结构走 `StructureAwareChunkSplitter`，否则走 `SlidingWindowChunkSplitter`；默认 512 字 / 64 重叠，过滤掉 <20 字的碎片。
- **向量化**（`EmbeddingService`）：先查 Redis 缓存（key = 文本 MD5，前缀 `emb:v1:`，TTL 默认 7d），未命中批量调 API（20 条/批，`RetryTemplate` 3 次指数退避——编程式重试，勿改回 `@Retryable`：`embedBatch` 自调用会绕过 AOP 代理使注解失效）。向量按逗号分隔字符串存 Redis（**不用** JSON 序列化器，避免浮点被当类名解析）。
- **落库**：删旧版本分块（按 `doc_version`）→ `saveAll` 写 `DocChunk`（embedding 已 set）→ 更新 `document` / `index_task` 状态。失败走指数退避重试（`LoadService.retryIfPossible`）。
- **对话侧检索（`ChatService`，2026-08 起）**：绑库会话每条消息先过 `RagRouterService`（判定要不要查 + 结合最近对话改写检索句，多轮追问的指代补全靠它），需要才经 `RagRetrievalClient`（chat 模块）跨服务调 rag 的 `POST /internal/retrieval`（`RetrievalService.search` 在 rag 进程内执行）；命中拼进 system 的【参考资料】段。`/api/rag/search`、`/ask` 已加 kbId 归属校验（`RagService.requireOwnedKb`）。
- 启动时 `DataInitializer` 会把 `classpath:docs/*.txt` 灌进去（`document` 表为空时）。

### 4. 认证（Sa-Token，三服务共享会话）
登录动作在 chat-service；会话统一存 **Redis**（`sa-token-redis-jackson`，三服务的 sa-token 配置必须完全一致才能互通）。网关 `SaReactorFilter` 与两个业务服务的 `SaTokenConfigure` 都执行同一规则（`/api/**`、`/test/**` 要登录，放行 `/api/auth/**`）——网关是第一道，业务服务兜底。**userId 一律从 `StpUtil.getLoginIdAsLong()` 取，不信任请求体**。token 放请求头 `satoken: <tokenValue>`。

### 5. 统一响应
所有 controller 返回 `Result<T>`（`{code, message, data}`，ok=200 / fail=500），异常由 `GlobalExceptionHandler` 收口。

## 关键 gotcha

- **向量维度硬约束 1024**：`DocChunk.embedding` 是 `vector(1024)`、`schema.sql` 也是 1024、对应 `text-embedding-v3`。换 embedding 模型需同步改实体 `columnDefinition` + schema + 重建索引。
- **`DocChunk.embedding` 是 NOT NULL**：分块入库必须带 embedding（`LoadService` 里 set 了）。但向量**相似度检索（`<=>`）和全文检索（`content_tsv`）JPQL 写不了**，检索要走原生 SQL / JdbcTemplate —— `DocChunkRepository` 只管元数据读取与清理，不承担检索。
- **多个 ChatClient Bean 共存**：`number1ChatClient`（猫娘内置 prompt）/ `catGirlChatClient`（读 `classpath:role/cat_girl.st`）/ `toolChatClient`（装配 `WeatherTools` + `UserTools`）。生产对话用的是 `ChatService` 内部构建的匿名 client。`TestController`（`/test/**`，**需登录**——因 `/test/getUser` 会触发 `UserTools` 查用户，2026-08 起与 `/api/**` 一并纳入 Sa-Token 拦截）是各 client + JPA 连通性的联调入口。
- **工具是静态装配**：`@Tool` 声明在 `WeatherTools` / `UserTools` 上，由 `ToolChatClientConfig` 固定挂到 `toolChatClient`，不是按对话动态过滤。
- **`@Async` 跨 Bean**：新增异步副作用务必放独立 Bean，否则代理不生效。
- **MinIO**：`MinioStorageService.upload`（>5MB 自动 multipart 分块，bucket 不存在自动建）与 `download` 已实现；REST 上传入库走 `POST /api/rag/kb/{kbId}/document`（`DocumentService` → MinIO → 建档 → 异步索引），同路径 GET 返回库内文档列表（含索引状态，上传后轮询用）。`delete` 仍是占位，删除文档功能待做。遗留：`DataInitializer` 灌的存量文档 minioPath 是假的（文件不在 MinIO），其重试路径（`executeFromMinio`）会下载失败。
- **会话绑定知识库**：`conversation.kb_id` 可空列，建会话时校验归属（`CreateConversationRequest` 带 `kbId`）。绑库消息经路由器按需检索；**检索命中只进当轮 prompt，绝不写成 message 落库**（否则挤占记忆窗口 + 被异步摘要污染）。路由失败 / `rag.route.enabled=false` 自动退回"拿原话总是检索"。路由 token 计入 `conversation.total_tokens`，`message.tokens` 只记主调用。
- **密钥走环境变量（2026-08-16 起）**：三份 `application.yaml` 里只有 `${DEEPSEEK_API_KEY}` 等占位符，真实值从环境变量注入，yaml 可安全入库。
- **rag 的 JDBC URL 必须 `currentSchema=rag_svc,public`**：pgvector 的 `vector` 类型装在 public schema，search_path 不带 public 会报 `type "vector" does not exist`（chat 只用 `currentSchema=chat_svc` 即可）。
- **服务间内部接口 `/internal/**`**：只有 `InternalRetrievalController`（rag），无登录拦截、网关不路由，靠"仅容器网络可达"保护。降级语义：检索失败返回空列表跳过 RAG；`kbOwned` 失败抛异常明确报错——两种失败处理是刻意不同的。

## 代码组织

Maven 多模块（2026-08-16 由单模块拆分，包根统一 `org.example`）：
- `gateway/` SCG 路由 + `SaReactorFilter` 鉴权（唯一引 Spring Cloud 的模块）
- `chat-service/` 认证/人格/对话：`controller` / `service`（含 `RagRouterService` 路由器、`RagRetrievalClient` 跨服务客户端）/ `entity`+`repository` / `config` / `advisor` / `tools`
- `rag-service/` 知识库/索引/检索：`controller`（含 `InternalRetrievalController`）/ `service`（含 `service/loader` 解析器、`service/splitter` 分块器）/ `entity`+`repository` / `config`
- `common/` 跨服务契约：`Result`、`GlobalExceptionHandler`、`common/rag` 下的 DTO
- `db/` SQL 脚本（chat_schema / rag_schema / init）
- 拆分后各服务内部仍是按层分包；`AsyncConfig` 每个服务各持一份（chat 只留 `chatExecutor`，rag 只留 `indexTaskExecutor`）。
