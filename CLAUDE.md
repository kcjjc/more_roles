# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概览

基于 **Spring AI** 的中文多角色智能对话平台。核心能力：多套人格系统提示词管理、带窗口记忆 + 异步摘要的长会话、基于 pgvector 的 RAG 文档入库管线，以及 pre（前置路由检索）/ agent（ReAct 工具检索）双模式的对话侧 RAG。

- 技术栈：Java 21、Spring Boot 3.5.11、Spring Cloud 2025.0.0（仅 Gateway）、Spring AI 1.1.2、PostgreSQL + pgvector、Redis、MinIO、Sa-Token 1.44
- **微服务结构（2026-08-16 拆分）**：Maven 多模块，包根统一 `org.example`。入口类三个：`gateway`(`GatewayApplication`:8080 统一入口+鉴权) / `chat-service`(`MoreRoleApplication`:8081，类名是 `MoreRole` 非 `MoreRoles`) / `rag-service`(`RagApplication`:8082)。轻量方案：无注册中心/配置中心/Feign，服务间手写 `RestClient`
- LLM 走 `spring-ai-starter-model-openai` 的 OpenAI 兼容接口：**chat = DeepSeek**（`deepseek-v4-flash`），**embedding = 阿里云 DashScope**（`text-embedding-v3`，**1024 维**）

## 常用命令

> 仓库**没有 Maven Wrapper**（`mvnw` 不存在），直接用系统 `mvn`。

```bash
mvn clean package -DskipTests   # 打包全部模块（Lombok 已在 spring-boot-maven-plugin 中排除）
mvn test                        # 跑测试（chat: RagRouterServiceTest / ReActExecutorTest / RagToolsTest / ChatStreamAssemblerTest / A2aAgentToolTest；rag: A2aServiceTest，均为纯 mock 单测）
mvn test -pl chat-service -am -Dtest=ReActExecutorTest -Dsurefire.failIfNoSpecifiedTests=false   # 只跑单个测试类（-am 因 common 未 install 必带，否则 common 模块无匹配测试会构建失败）
mvn -pl chat-service -am ...    # 只构建某模块（-am 连带其依赖模块，common 不在本地仓库时必须带）
mvn spring-boot:run -pl chat-service   # 本地起单个服务
```

启动三个服务 + 密钥环境变量见 **README.md「运行」**（`DEEPSEEK_API_KEY` / `EMBEDDING_API_KEY` / `APP_DB_PASSWORD` / `APP_REDIS_PASSWORD` / `MINIO_SECRET_KEY`，yaml 里全部是 `${...}` 占位符，**密钥不落盘**）。

运行前置条件：PostgreSQL（需 pgvector 扩展）、Redis、MinIO（远程或 docker compose 自建）。`spring.jpa.hibernate.ddl-auto=update` 会按实体自动建表，但 `doc_chunk` 全文检索触发器、HNSW 索引等需执行 `db/rag_schema.sql`（chat 域是 `db/chat_schema.sql`，全新环境引导用 `db/init.sql`）。

## 架构要点

### 1. 对话主路径（`ChatService.chat`）
核心设计是"**主路径 LLM 调用数有界（pre 模式至多两次 = 路由一次 + 主回复一次，未绑库/路由失败退化为一次；agent 模式为 ReAct 循环、以 `agent.max-iterations` 封顶），落库走短事务 + 乐观锁，副作用异步化**"：
- 流程：`读上下文(人格 + 摘要) → (绑库时按 rag.mode 分流: pre = RagRouterService 路由判定 + RagRetrievalClient 跨服务检索后单次主调用 | agent = ReActExecutor 显式循环, 检索作为工具交给模型) → 短事务落库 user/assistant 消息 + token → 异步投递摘要/标题`
- 人格按需 `.system(persona)` 注入，用的是 `ChatService` 内部构建的、**不带 defaultSystem 的 ChatClient**。
- 记忆：`MessageWindowChatMemory`（窗口 20 条，底层走 `JpaChatMemoryRepository` 读 message 表）+ 窗口外的老消息异步压缩进 `conversation.summary`，摘要并入 system 发给模型。
- 并发安全：`Conversation.version` 乐观锁，落库 / 写摘要 / 写标题均带 3 次重试；摘要靠游标 `summarized_count` 幂等推进。
- **流式版 `chatStream`**（`POST .../messages/stream`，SSE，原同步接口保留）：编排与 `chat` 共用 `preparePreContext`，差异只在交付——同步段（返回 Flux 前）做校验 + pre 路由检索 / agent ReAct 整体执行，异常上抛给 controller 转单 `error` 帧（刻意不走 `GlobalExceptionHandler`：Accept 是 text/event-stream，`Result` JSON 无法与之协商 content-type）；流段由 `ChatStreamAssembler` 拼 `delta`/`done`/`error` 帧，**流正常结束后才落库**（`persistWithRetry` 是编程式事务，reactor 回调线程可用），流中断/客户端断开/聚合为空则整轮丢弃不落库。pre 主调用 `invokeModelStream` 开 `streamUsage(true)`（usage 只在末块）。agent 模式是伪流式：循环保持同步（工具中间轮不外泄），最终回复一帧推出。注意 `onErrorResume` 吞错后主链会"正常完成"，收尾帧必须靠 `errored` 门闩短路（否则中断的部分回复仍会被落库）。`spring.mvc.async.request-timeout: -1` 防 Tomcat 30s 掐断长流；网关 SCG 对 SSE 按 chunk 透传无需配置。
- **关键约束**：`@Async` 必须跨 Bean 走代理才生效 —— 摘要 / 标题被拆到独立 Bean `ChatPostProcessor`，不能放回 `ChatService` 内部调用。两个 `@Async` 方法都靠"游标 / 空值判断"幂等，失败或崩溃不丢，下次 `chat` 会重新投递。

### 2. 人格分片存储（`PersonaService`）
人格（systemPrompt）可能很长，按 **4000 字/片**切分入库（`persona_fragment` 表），多片共享一个 `persona_id`，读时按 `seq` 升序拼接，对上层屏蔽长度细节。软删除靠 `status`（1 有效 / 0 删除）。

### 3. RAG 入库管线（异步 ETL）
入口两个：`IndexService.submitTask`（直接给文本，跳过解析器，`DataInitializer` 用）和 `submitTaskFromMinio`（REST 上传的文件，从 MinIO 下载后走解析器）→ `IndexTaskLauncher.@Async("indexTaskExecutor")` → `LoadService.doIndex`：
- **解析**（`DocumentLoaderService`）：按文件扩展名选 `DocumentParser`（支持 PDF / DOCX / MD / TXT），产出 `ParseResult`（含页码 / 章节）。
- **分块**（`ChunkService`）：有章节结构走 `StructureAwareChunkSplitter`，否则走 `SlidingWindowChunkSplitter`；默认 512 字 / 64 重叠，过滤掉 <20 字的碎片。
- **向量化**（`EmbeddingService`）：先查 Redis 缓存（key = 文本 MD5，前缀 `emb:v1:`，TTL 默认 7d），未命中批量调 API（默认 10 条/批 `rag.embedding.batch-size`——**DashScope text-embedding-v3 单批硬上限 10**，超限 400，曾用 20 导致块数 >10 的文档索引必败；`RetryTemplate` 3 次指数退避且排除 `NonTransientAiException`（400 类确定性失败不重试）——编程式重试，勿改回 `@Retryable`：`embedBatch` 自调用会绕过 AOP 代理使注解失效）。向量按逗号分隔字符串存 Redis（**不用** JSON 序列化器，避免浮点被当类名解析）。
- **落库**：删旧版本分块（按 `doc_version`）→ `saveAll` 写 `DocChunk`（embedding 已 set）→ 更新 `document` / `index_task` 状态。失败走指数退避重试（`LoadService.retryIfPossible`）。**已删除守卫**：删除接口（FAILED 状态可删）可能与失败重试的退避窗口并发，因此 `LoadService` 全部写入路径以"文档不存在或已软删即放弃"为前提——任务入口检查、写分块前重查一次（防分块写回，也防过期实体 merge 把软删标记冲掉导致文档复活）、status/errorMsg 回写加 `!isDeleted()` 条件。已知限制：进程索引中途宕机会让文档永久卡 PROCESSING（无轮询自愈），需手动修库后才能删。
- **对话侧检索（`ChatService`，按 `rag.mode` 分流，仅绑库会话生效）**：
  - `pre`（默认）：每条消息先过 `RagRouterService`（判定要不要查 + 结合最近对话改写检索句，多轮追问的指代补全靠它），需要才经 `RagRetrievalClient`（chat 模块）跨服务调 rag 的 `POST /internal/retrieval`（`RetrievalService.search` 在 rag 进程内执行）；命中拼进 system 的【参考资料】段，主模型单次调用。
  - `agent`（Agentic RAG）：跳过路由器，由 `ReActExecutor` 驱动显式 Reason→Act 循环——关闭框架内置工具自动执行（`internalToolExecutionEnabled=false`）手动步进，检索作为工具（`RagTools.searchKnowledgeBase`）由模型自主决定是否调用/用什么 query/查几轮；轮数以 `agent.max-iterations`（默认 5）封顶，达到后去掉工具强制收尾不阻断聊天；token 逐轮累加（内置循环只暴露最后一轮 usage），轨迹（`ReActStep` + `[react]` 日志）可观测。
  - 共用不变量：检索命中与工具中间消息（assistant toolCall / toolResponse）**只进当轮 prompt，绝不落 message 表**。
  `/api/rag/search`、`/ask` 已加 kbId 归属校验（`RagService.requireOwnedKb`）。
- 启动时 `DataInitializer` 会把 `classpath:docs/*.txt` 灌进去（`document` 表为空时）。

### 4. 认证（Sa-Token，三服务共享会话）
登录动作在 chat-service；会话统一存 **Redis**（`sa-token-redis-jackson`，三服务的 sa-token 配置必须完全一致才能互通）。网关 `SaReactorFilter` 与两个业务服务的 `SaTokenConfigure` 都执行同一规则（`/api/**`、`/test/**` 要登录，放行 `/api/auth/**`）——网关是第一道，业务服务兜底。**userId 一律从 `StpUtil.getLoginIdAsLong()` 取，不信任请求体**。token 放请求头 `satoken: <tokenValue>`。

### 5. 统一响应
所有 controller 返回 `Result<T>`（`{code, message, data}`，ok=200 / fail=500），异常由 `GlobalExceptionHandler` 收口。**例外**：流式 `/messages/stream` 返回 SSE 帧（`delta`/`done`/`error`，错误不走 GlobalExceptionHandler——Accept 与 Result JSON 无法协商 content-type）；A2A 端点说协议自己的错误格式（google.rpc.Status）。

### 6. A2A（Agent2Agent v1.0，HTTP+JSON 绑定，2026-08-29 手写最小实现）
rag-service 是标准 **A2A Server**（知识库专家 agent），chat-service 是 **A2A Client**——把"检索作为工具（MCP 形态，`RagTools` 调 /internal 私有接口）"升级为"与不透明远程 agent 协作"，协议细节全手写（学习导向，不用 SDK）。
**架构定位（2026-08-29 定）：星型拓扑，刻意不做 agent 间互相编排**——会话里的主人格（persona 入库 + 记忆 + ReAct）是唯一编排中心；其他 agent 一律是"固定人格专家"（system prompt 写死在各自服务里，**不入 persona_fragment 库**，如 rag 的防幻觉问答角色）。新增能力 = 新增一个固定 prompt 的 A2A Server + chat 侧一个桥接工具，不存在主编/诗人/审校式的多级派活。
- **契约 DTO** 在 `common/common/a2a/`（record + `@JsonInclude(NON_NULL)`，命名带 `A2a` 前缀防撞名）。协议要点：Part **无 kind 判别符**（字段名即类型）；Task 状态 `TASK_STATE_*` SCREAMING_SNAKE_CASE；时间戳 ISO-8601 UTC；`message.metadata` 是自由 KV（用来跨协议传 kbId）。
- **Server**（rag）：`GET /.well-known/agent-card.json`（公开发现，Card 由 `a2a.base-url` 组装，Cache-Control 1h）、`POST /message:send`（同步阻塞至终态，`A2aService` 复用 `RagService.ask` → COMPLETED Task + Artifact（回答+来源），失败转 **FAILED Task 而非 HTTP 5xx**——任务失败是协议内状态）、`GET /tasks/{id}`（404 返回 google.rpc.Status + `reason=TASK_NOT_FOUND`）。任务仓 `A2aTaskStore` 是**内存 LRU**（1000 条，重启即空，getTask 返回 TaskNotFound 符合规范）。
- **认证**：`A2aAuthFilter`（rag）拦 `/message:send` 与 `/tasks/**` 校验 `X-Api-Key`（`${A2A_API_KEY}`，与 Card 的 securitySchemes 声明一致；**未配置 key = 部署失误，503 拒绝服务**）。A2A 端点跨信任边界，不能沿用 /internal 的"仅容器网络"裸奔假设；Sa-Token 只拦 /api、/test，两套认证互不干扰。
- **Client**（chat）：`A2aClient`（Card 拉取+1h 缓存；send 带 `X-Api-Key`/`A2A-Version: 1.0` 头）+ `A2aAgentTool`（`@Tool searchKnowledgeBaseAgent`，与 `RagTools` 同构：非单例、`forConversation` 工厂、**kbId 绝不暴露给模型**、不可达/FAILED/空产物一律降级文案不中断对话）。`rag.a2a.client-enabled=true`（默认 false）时 `chatReAct` 的工具**替换**为 A2A 版，ReAct 循环零改动。
- 刻意未做（后续演进）：`message/stream`（SSE task 事件，流式基建已就绪）、`tasks/{id}:cancel`、push notification、extendedAgentCard、Card JWS 签名、任务落库。

## 关键 gotcha

- **向量维度硬约束 1024**：`DocChunk.embedding` 是 `vector(1024)`、`schema.sql` 也是 1024、对应 `text-embedding-v3`。换 embedding 模型需同步改实体 `columnDefinition` + schema + 重建索引。
- **`DocChunk.embedding` 是 NOT NULL**：分块入库必须带 embedding（`LoadService` 里 set 了）。但向量**相似度检索（`<=>`）和全文检索（`content_tsv`）JPQL 写不了**，检索要走原生 SQL / JdbcTemplate —— `DocChunkRepository` 只管元数据读取与清理，不承担检索。
- **多个 ChatClient Bean 共存**：`number1ChatClient`（猫娘内置 prompt）/ `catGirlChatClient`（读 `classpath:role/cat_girl.st`）/ `toolChatClient`（装配 `WeatherTools` + `UserTools`）。生产对话用的是 `ChatService` 内部构建的匿名 client。`TestController`（`/test/**`，**需登录**——因 `/test/getUser` 会触发 `UserTools` 查用户，2026-08 起与 `/api/**` 一并纳入 Sa-Token 拦截）是各 client + JPA 连通性的联调入口。
- **工具两套装配方式**：`WeatherTools` / `UserTools` 是静态装配（`ToolChatClientConfig` 固定挂到 `toolChatClient`，不按对话动态过滤）；`RagTools` 反其道——刻意**不做** `@Component` 单例，`RagTools.forConversation(client, kbId)` 按会话现造实例交给 `ReActExecutor`（经 `MethodToolCallbackProvider` 反射扫描 `@Tool` 方法）。**kbId 绝不作为工具参数暴露**：模型可控的只有 query，否则模型幻觉出他人库 id 即可越权检索。
- **`@Async` 跨 Bean**：新增异步副作用务必放独立 Bean，否则代理不生效。
- **MinIO**：`MinioStorageService.upload`（>5MB 自动 multipart 分块，bucket 不存在自动建）、`download`、`delete`（removeObject，幂等，对象不存在不报错）已实现；REST 上传入库走 `POST /api/rag/kb/{kbId}/document`（`DocumentService` → MinIO → 建档 → 异步索引），同路径 GET 返回库内文档列表（含索引状态，上传后轮询用），`DELETE /kb/{kbId}/document/{docId}` 删文档（`DocumentService.deleteFile`）。**删除语义**：`doc_chunk` 物理删（检索原生 SQL 不 join document，分块不删则检索仍命中）+ document 软删 + MinIO 对象事务提交后清理（失败仅 warn 不回滚——反之会出现"源文件没了但检索仍命中"）；PENDING/PROCESSING 拒绝删除。遗留：`DataInitializer` 灌的存量文档 minioPath 是假的（文件不在 MinIO），其重试路径（`executeFromMinio`）会下载失败；删除它们的 MinIO 步骤因 removeObject 幂等而无感。
- **会话绑定知识库**：`conversation.kb_id` 可空列，建会话时校验归属（`CreateConversationRequest` 带 `kbId`）。绑库消息按 `rag.mode` 走 pre（路由器按需检索）/ agent（ReAct 工具检索）；**检索命中与工具中间消息只进当轮 prompt，绝不写成 message 落库**（否则挤占记忆窗口 + 被异步摘要污染）。pre 模式路由失败 / `rag.route.enabled=false` 自动退回"拿原话总是检索"；agent 模式 rag-service 不可用时工具返回"未检索到"文案由模型自行兜底。路由 / ReAct 全循环 token 计入 `conversation.total_tokens`，`message.tokens` 只记主调用。
- **`ReActExecutor` 不挂 `MessageChatMemoryAdvisor`**：advisor 的 before 回调每轮循环都会重复注入历史，因此消息全量自管（system + 调用方传入的窗口历史，每轮全量重发）；工具执行异常由框架默认处理器转成错误文本回传模型自救，不中断对话。
- **同一 callResult 只能触发一次链执行（全局坑，踩过三处）**：对同一个 `CallResponseSpec` 连调 `content()` 和 `chatResponse()` 各自独立触发一次 advisor 链（一次性 Deque，`nextCall` 走完即空），第二次抛 `No CallAdvisors available to execute`。`RagRouterService.route`、`ChatPostProcessor` 的摘要/标题曾因此**长期静默失败**（异常被降级 catch 吞掉）。统一写法：取一次 `chatResponse()`，回复文本与 token 都从同一响应里拿（`ChatService.invokeModel` 是范本）。
- **异步请求的 ASYNC 二次分发**：SSE 流式接口（`/messages/stream`）流结束后容器会把请求 dispatch 回 `DispatcherServlet` 收尾，该次分发**重走拦截器链但线程上没有 Sa-Token 上下文**（1.44 的 ThreadLocal 模式只在 REQUEST 分发类型初始化）→ `SaTokenContextException`。chat 的 `SaTokenConfigure` 已对 `DispatcherType.ASYNC` 直接放行（首次 REQUEST 分发已完成鉴权）；新增拦截器/过滤器时同样要考虑 ASYNC 分发。
- **绝不能用 substring 按 char 截断要发给 LLM 的文本**：emoji 是代理对（占 2 个 char），切在中间留下孤立高代理（U+D800..U+DBFF），Jackson 序列化成不完整 unicode 转义后 DeepSeek 直接 400 `unexpected end of hex escape` 拒绝整个请求。`RagRouterService.truncate` 已改为逐代码点截断 + 顺带清洗既有孤立代理（历史脏数据）；`ReActExecutor.truncate` 只进日志不进请求体，暂不需要。注意 Java 源码注释里写 `\uXXXX` 也会被编译器当 Unicode 转义预处理（踩过：javadoc 里写 `\uD83D` 直接编译错误），文档里用 `U+XXXX` 写法。
- **Lombok 在命令行构建可能静默失效**：maven-compiler-plugin 3.13+ 的 `proc` 参数不再默认隐式运行 classpath 上的注解处理器——rag-service 首次命令行编译时 `@Builder`/`@Slf4j` 生成的符号全部"找不到"（IDEA 有 Lombok 插件所以平时无感，chat/common 不用 Lombok 也不暴露）。根 pom 已设 `<maven.compiler.proc>full</maven.compiler.proc>`。
- **Spring MVC 注解的 consumes/produces 是 String 数组**：不能传 `MediaType` 对象（编译不过），需要 MediaType 实例时另起常量 `MediaType.parseMediaType(...)`（见 `A2aController`）。
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
