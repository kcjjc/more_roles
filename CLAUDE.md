# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概览

基于 **Spring AI** 的中文多角色智能对话平台。核心能力：多套人格系统提示词管理、带窗口记忆 + 异步摘要的长会话、基于 pgvector 的 RAG 文档入库管线。

- 技术栈：Java 21、Spring Boot 3.5.11、Spring AI 1.1.2、PostgreSQL + pgvector、Redis、MinIO、Sa-Token 1.44
- 包根 `org.example`，入口类 `MoreRoleApplication`（类名是 `MoreRole`，非 `MoreRoles`）
- LLM 走 `spring-ai-starter-model-openai` 的 OpenAI 兼容接口：**chat = DeepSeek**（`deepseek-v4-flash`），**embedding = 阿里云 DashScope**（`text-embedding-v3`，**1024 维**）

## 常用命令

> 仓库**没有 Maven Wrapper**（`mvnw` 不存在），直接用系统 `mvn`。

```bash
mvn spring-boot:run           # 本地启动（监听 8080）
mvn clean package             # 打包（Lombok 已在 spring-boot-maven-plugin 中排除）
mvn test                      # 跑测试（当前 src/test 为空）
mvn test -Dtest=类名#方法名    # 跑单个测试
```

运行前置条件（均为**远程**基础设施，连接信息硬编码在 `application.yaml`）：PostgreSQL（需 pgvector 扩展）、Redis、MinIO。`spring.jpa.hibernate.ddl-auto=update` 会按实体自动建表，但 `doc_chunk`、全文检索触发器、HNSW 索引等需先执行 `src/main/resources/sql/schema.sql`。

## 架构要点

### 1. 对话主路径（`ChatService.chat`）
核心设计是"**主路径至多两次 LLM（绑库会话 = 路由一次 + 主回复一次，未绑库/路由失败退化为一次），落库走短事务 + 乐观锁，副作用异步化**"：
- 流程：`读上下文(人格 + 摘要) → (绑库时: RagRouterService 路由判定 + RetrievalService 检索) → 调主模型(事务外) → 短事务落库 user/assistant 消息 + token → 异步投递摘要/标题`
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
- **对话侧检索（`ChatService`，2026-08 起）**：绑库会话每条消息先过 `RagRouterService`（判定要不要查 + 结合最近对话改写检索句，多轮追问的指代补全靠它），需要才调 `RetrievalService.search`；命中拼进 system 的【参考资料】段。`/api/rag/search`、`/ask` 已加 kbId 归属校验（`RagService.requireOwnedKb`）。
- 启动时 `DataInitializer` 会把 `classpath:docs/*.txt` 灌进去（`document` 表为空时）。

### 4. 认证（Sa-Token）
`SaTokenConfigure`：`/api/**` 默认都要登录，仅放行 `/api/auth/**`。**userId 一律从 `StpUtil.getLoginIdAsLong()` 取，不信任请求体**。token 放请求头 `satoken: <tokenValue>`。

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
- **密钥明文写在 `application.yaml`**：DB / Redis / MinIO / LLM 的密码与 api-key 都是明文，且该文件尚未加入 git。

## 代码组织

扁平的按层分包（非按业务模块）：
- `controller/` REST 入口；`service/` 业务逻辑；`entity/` + `repository/` JPA 层；`config/` Bean 配置；`common/` 统一响应与全局异常；`advisor/` Spring AI Advisor；`tools/` `@Tool` 工具；`service/loader` 与 `service/splitter` 分别是文档解析与分块。
