# MoreRoles 接口文档

> 基于 Spring AI 的中文多角色智能对话平台。
> 本文档覆盖全部 HTTP 接口，包含请求/响应示例、数据结构与异常说明。
>
> - 服务版本：随仓库主干
> - 监听端口：`8080`（见 `application.yaml`）
> - 接口根路径：`/`（未配置 `server.servlet.context-path`）

---

## 目录

- [通用约定](#通用约定)
  - [Base URL](#base-url)
  - [统一响应结构](#统一响应结构)
  - [认证机制（Sa-Token）](#认证机制sa-token)
  - [时间格式](#时间格式)
- [接口总览](#接口总览)
- [1. 认证接口（公开）](#1-认证接口公开)
- [2. 人格管理接口](#2-人格管理接口)
- [3. 对话管理接口](#3-对话管理接口)
- [4. RAG 知识库问答接口](#4-rag-知识库问答接口)
- [5. 联调测试接口（公开）](#5-联调测试接口公开)
- [数据结构定义](#数据结构定义)
- [错误码与异常说明](#错误码与异常说明)
- [附录：典型业务流程](#附录典型业务流程)

---

## 通用约定

### Base URL

```
http://localhost:8080
```

> 部署到远程时替换为对应主机与端口。本文所有示例以本地为准。

### 统一响应结构

所有 Controller 均返回 `Result<T>` 信封（见 `common/Result.java`），结构如下：

```json
{
  "code": 200,
  "message": "success",
  "data": { }
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `code` | int | 状态码。`200` = 成功；`500` = 业务/系统失败 |
| `message` | string | 描述信息。成功固定为 `"success"`；失败为具体错误提示 |
| `data` | object/array/null | 业务数据。失败时为 `null` |

- 成功：`Result.ok(data)` → `code=200, message="success"`
- 失败：`Result.fail(message)` → `code=500, message=<错误提示>, data=null`
- 即便抛出异常，也由 `GlobalExceptionHandler` 收口为同一信封，**不会返回裸 500 + 堆栈**。

### 认证机制（Sa-Token）

- 拦截规则（见 `config/SaTokenConfigure.java`）：
  - `/api/**` 下**除** `/api/auth/**`（注册/登录/注销）外，**其余接口都必须登录**。
  - `/test/**` 联调接口**无需登录**。
- Token 传递：登录/注册成功后返回 `tokenValue`，后续请求需将其放入：
  - 请求头 `satoken: <tokenValue>`（推荐），**或**
  - Cookie（`is-read-cookie: true`）。
- Token 有效期：30 天（`timeout: 2592000`）；风格 `uuid`；同账号不共享 token（`is-share: false`）。
- **身份来源**：所有需登录接口的 `userId` 一律从 `StpUtil.getLoginIdAsLong()` 取，**不信任请求体**。
- 未登录访问受保护接口 → `code=500, message="未登录或登录已过期"`。

### 时间格式

所有时间字段（`createdAt` / `updatedAt`）为 Java `LocalDateTime`，默认序列化为 ISO-8601 字符串：

```
2026-08-09T14:30:00
```

---

## 接口总览

| # | 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- | --- |
| 1.1 | POST | `/api/auth/register` | 否 | 注册（成功后自动登录并返回 token） |
| 1.2 | POST | `/api/auth/login` | 否 | 登录 |
| 1.3 | POST | `/api/auth/logout` | 否 | 注销当前会话 |
| 2.1 | POST | `/api/persona/upload` | 是 | 上传人格（>4000 字自动分片入库） |
| 2.2 | GET | `/api/persona` | 是 | 取回某人格的完整内容 |
| 2.3 | GET | `/api/persona/list` | 是 | 列出当前用户的全部人格 |
| 3.1 | GET | `/api/chat/personas/{personaId}/conversations` | 是 | 列出某人格下的会话 |
| 3.2 | POST | `/api/chat/conversations` | 是 | 新建会话 |
| 3.3 | GET | `/api/chat/conversations/{id}` | 是 | 会话详情（含历史消息） |
| 3.4 | POST | `/api/chat/conversations/{id}/messages` | 是 | 发送消息并获取模型回复 |
| 3.5 | DELETE | `/api/chat/conversations/{id}` | 是 | 删除会话（连带消息） |
| 4.1 | POST | `/api/rag/search` | 是 | 纯向量检索（验证召回质量） |
| 4.2 | POST | `/api/rag/ask` | 是 | 检索增强问答（检索 + 调模型） |
| 5.1 | GET | `/test/hello` | 否 | 默认 ChatClient 联调 |
| 5.2 | GET | `/test/mao` | 否 | 猫娘人格 ChatClient 联调 |
| 5.3 | GET | `/test/teacher` | 否 | 读取 `role/teacher.st` 的人格联调 |
| 5.4 | GET | `/test/users` | 否 | 验证 JPA 连通（查全部用户名） |
| 5.5 | GET | `/test/getUser` | 否 | 验证工具调用（`UserTools`） |

> ⚠️ 第 5 节为联调/演示接口，直接返回字符串（非 `Result` 信封），**不要在生产前端依赖**。

---

## 1. 认证接口（公开）

模块：`AuthController`，路径前缀 `/api/auth`，**无需登录**。

### 1.1 注册

注册成功后**自动建立 Sa-Token 会话**并返回 token，无需再调用登录接口。

- **请求**：`POST /api/auth/register`
- **Content-Type**：`application/json`
- **请求体**：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `username` | string | 是 | 用户名，不能为空 |
| `password` | string | 是 | 密码，不能为空（⚠️ 当前明文入库，仅演示） |

```json
{
  "username": "alice",
  "password": "123456"
}
```

- **成功响应**（`data` 字段）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `userId` | long | 新建用户 id |
| `username` | string | 用户名 |
| `tokenName` | string | 固定 `"satoken"`，与请求头 key 一致 |
| `tokenValue` | string | 登录 token，后续请求放入请求头 `satoken` |

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "userId": 10,
    "username": "alice",
    "tokenName": "satoken",
    "tokenValue": "a3f9c0e1-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
  }
}
```

- **失败响应**：

| `message` | 触发条件 |
| --- | --- |
| `"用户名不能为空"` | `username` 为空 |
| `"密码不能为空"` | `password` 为空 |
| `"用户名已存在"` | 用户名已被注册 |

### 1.2 登录

- **请求**：`POST /api/auth/login`
- **Content-Type**：`application/json`
- **请求体**：同 [1.1 注册](#11-注册)。

```json
{ "username": "alice", "password": "123456" }
```

- **成功响应**（`data` 字段）：同 [1.1 注册](#11-注册) 的成功结构。

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "userId": 10,
    "username": "alice",
    "tokenName": "satoken",
    "tokenValue": "a3f9c0e1-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
  }
}
```

- **失败响应**：

| `message` | 触发条件 |
| --- | --- |
| `"用户名或密码错误"` | 用户名不存在或密码错误（统一提示，防枚举） |

### 1.3 注销

清除当前会话，使当前 token 失效。

- **请求**：`POST /api/auth/logout`
- **请求体**：无（建议携带当前 `satoken` 请求头以定位会话）。
- **成功响应**：

```json
{ "code": 200, "message": "success", "data": null }
```

---

## 2. 人格管理接口

模块：`PersonaController`，路径前缀 `/api/persona`，**均需登录**。

人格（systemPrompt）可能很长，底层按 **4000 字/片** 切分入库（`persona_fragment` 表），多片共享一个 `personaId`。本模块对上层屏蔽分片细节，调用方只管传/取整段文本。

### 2.1 上传人格

- **请求**：`POST /api/persona/upload`
- **请求头**：`satoken: <tokenValue>`
- **Content-Type**：`application/json`
- **请求体**：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `name` | string | 否 | 人格名称（可为 `null`） |
| `content` | string | 是 | 人格完整内容，任意长度；超过 4000 字自动分片 |

```json
{
  "name": "温柔姐姐",
  "content": "你是一个温柔的姐姐，说话轻声细语……（可任意长）"
}
```

- **成功响应**（`data` 字段）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `personaId` | string | 32 位无连字符 UUID，后续取回/新建会话均用它 |

```json
{
  "code": 200,
  "message": "success",
  "data": { "personaId": "a1b2c3d4e5f647008811122233344455" }
}
```

- **失败响应**：

| `message` | 触发条件 |
| --- | --- |
| `"人格内容不能为空"` | `content` 为空或空白 |

### 2.2 取回人格完整内容

底层查出该 `personaId` 的所有有效分片，按 `seq` 升序合并后返回原文。

- **请求**：`GET /api/persona`
- **请求头**：`satoken: <tokenValue>`
- **查询参数**：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `personaId` | string | 是 | 人格 id |

```
GET /api/persona?personaId=a1b2c3d4e5f647008811122233344455
```

- **成功响应**（`data` 字段）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `content` | string | 还原后的完整人格文本 |

```json
{
  "code": 200,
  "message": "success",
  "data": { "content": "你是一个温柔的姐姐……（完整内容）" }
}
```

- **失败响应**：

| `message` | 触发条件 |
| --- | --- |
| `"人格不存在或已删除"` | `personaId` 不存在、不属于当前用户，或已全部软删 |

### 2.3 列出当前用户的全部人格

返回每个 `personaId` 的概览（名称 + 分片数）。

- **请求**：`GET /api/persona/list`
- **请求头**：`satoken: <tokenValue>`
- **成功响应**：`data` 为 [`PersonaOverview`](#personaoverview) 数组（按 `personaId` 升序）。

```json
{
  "code": 200,
  "message": "success",
  "data": [
    { "personaId": "a1b2c3d4...", "name": "温柔姐姐", "fragmentCount": 1 },
    { "personaId": "e9f8a7b6...", "name": "毒舌管家", "fragmentCount": 3 }
  ]
}
```

---

## 3. 对话管理接口

模块：`ChatController`，路径前缀 `/api/chat`，**均需登录**。

核心设计：**主路径只调一次 LLM，落库走短事务 + 乐观锁，副作用（历史摘要、会话标题）异步化**。会话使用 **窗口记忆（最近 20 条）+ 窗口外老消息异步压缩进摘要** 的长记忆策略。

### 3.1 列出某人格下的会话

- **请求**：`GET /api/chat/personas/{personaId}/conversations`
- **请求头**：`satoken: <tokenValue>`
- **路径参数**：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `personaId` | string | 是 | 人格 id |

- **成功响应**：`data` 为 [`Conversation`](#conversation) 数组，按 `updatedAt` 倒序。返回空数组说明该人格下尚未对话。

```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "id": 7,
      "userId": 10,
      "personaId": "a1b2c3d4...",
      "title": "关于工作压力的聊天",
      "summary": "用户聊到了最近加班……",
      "summarizedCount": 8,
      "totalTokens": 2045,
      "version": 3,
      "createdAt": "2026-08-09T10:00:00",
      "updatedAt": "2026-08-09T10:30:00"
    }
  ]
}
```

### 3.2 新建会话

校验人格存在且属于当前用户后创建会话。

- **请求**：`POST /api/chat/conversations`
- **请求头**：`satoken: <tokenValue>`
- **Content-Type**：`application/json`
- **请求体**：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `personaId` | string | 是 | 人格 id |

```json
{ "personaId": "a1b2c3d4e5f647008811122233344455" }
```

- **成功响应**（`data` 字段）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `conversationId` | long | 新建会话 id |
| `personaId` | string | 绑定的人格 id |

```json
{
  "code": 200,
  "message": "success",
  "data": { "conversationId": 8, "personaId": "a1b2c3d4..." }
}
```

- **失败响应**：

| `message` | 触发条件 |
| --- | --- |
| `"人格不存在或已删除"` | `personaId` 不存在或不属于当前用户 |

### 3.3 会话详情（含历史消息）

- **请求**：`GET /api/chat/conversations/{id}`
- **请求头**：`satoken: <tokenValue>`
- **路径参数**：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `id` | long | 是 | 会话 id |

- **成功响应**：`data` 为 [`ConversationDetail`](#conversationdetail)，含会话元信息与历史消息（按时间升序）。

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "conversation": {
      "id": 8, "userId": 10, "personaId": "a1b2c3d4...",
      "title": null, "summary": null,
      "summarizedCount": 0, "totalTokens": 0,
      "version": 0,
      "createdAt": "2026-08-09T10:00:00",
      "updatedAt": "2026-08-09T10:00:00"
    },
    "messages": [
      {
        "id": 101, "conversationId": 8, "role": "user",
        "content": "你好", "tokens": 0,
        "createdAt": "2026-08-09T10:01:00"
      },
      {
        "id": 102, "conversationId": 8, "role": "assistant",
        "content": "你好呀，今天想聊点什么？", "tokens": 42,
        "createdAt": "2026-08-09T10:01:02"
      }
    ]
  }
}
```

- **失败响应**：

| `message` | 触发条件 |
| --- | --- |
| `"会话不存在"` | 会话 id 无效 |
| `"无权访问该会话"` | 会话不属于当前用户 |

### 3.4 发送消息

核心接口：发送一条用户消息，返回模型回复。

- **请求**：`POST /api/chat/conversations/{id}/messages`
- **请求头**：`satoken: <tokenValue>`
- **Content-Type**：`application/json`
- **路径参数**：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `id` | long | 是 | 会话 id |

- **请求体**：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `content` | string | 是 | 用户消息内容，不能为空 |

```json
{ "content": "你好" }
```

- **成功响应**（`data` 字段）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `reply` | string | 模型回复内容 |

```json
{
  "code": 200,
  "message": "success",
  "data": { "reply": "你好呀，今天想聊点什么？" }
}
```

- **失败响应**：

| `message` | 触发条件 |
| --- | --- |
| `"消息内容不能为空"` | `content` 为空 |
| `"会话不存在"` | 会话 id 无效 |
| `"无权访问该会话"` | 会话不属于当前用户 |
| `"人格不存在或已删除"` | 绑定的人格已失效 |
| `"模型暂时无响应，请重试"` | 模型返回为空（`IllegalStateException` 兜底） |
| `"操作冲突，请重试"` | 乐观锁冲突（并发落库重试 3 次仍失败） |

> **异步副作用说明**：响应返回后，系统会**异步**为本次会话补充两类信息（不阻塞本次响应）：
> - **历史摘要**：窗口外的老消息压缩进 `conversation.summary`；
> - **会话标题**：首轮对话后由模型总结生成 `conversation.title`。
>
> 因此在发消息后**立即**调用 [3.3 会话详情](#33-会话详情含历史消息)，`title`/`summary` 可能仍为 `null`，稍后再查即可看到。

### 3.5 删除会话

连带删除该会话下的全部消息。

- **请求**：`DELETE /api/chat/conversations/{id}`
- **请求头**：`satoken: <tokenValue>`
- **路径参数**：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `id` | long | 是 | 会话 id |

- **成功响应**：

```json
{ "code": 200, "message": "success", "data": null }
```

- **失败响应**：

| `message` | 触发条件 |
| --- | --- |
| `"会话不存在"` | 会话 id 无效 |
| `"无权访问该会话"` | 会话不属于当前用户 |

---

## 4. RAG 知识库问答接口

模块：`RagController`，路径前缀 `/api/rag`，**均需登录**。

> 说明：当前 RAG 侧**仅完成检索与问答**，且**未接入对话主路径**（`ChatService` 不查 `doc_chunk`）。知识库入库走启动时 `DataInitializer` 灌入 `classpath:docs/*.txt`，**暂无 REST 上传/管理接口**。问答按 `kbId` 隔离，**未做用户级权限**校验。

向量检索基于 pgvector 的 cosine 距离（`<=>`），对外分数换算为**相似度**（`1 - 距离`，越大越相关）。Embedding 模型为阿里云 DashScope `text-embedding-v3`（**1024 维**）。

### 4.1 纯向量检索

仅检索，不调用大模型。用于验证召回质量。

- **请求**：`POST /api/rag/search`
- **请求头**：`satoken: <tokenValue>`
- **Content-Type**：`application/json`
- **请求体**：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `query` | string | 是 | 检索问题原文 |
| `kbId` | long | 否 | 知识库 id；`null` = 全局检索（不限知识库） |
| `topK` | int | 否 | 召回条数；`null` 或 `≤0` 用默认值（配置 `rag.search.top-k`，默认 5） |

```json
{ "query": "年假有几天", "kbId": 1, "topK": 5 }
```

- **成功响应**：`data` 为 [`ChunkHit`](#chunkhit) 数组，按相似度从高到低，已过滤低于阈值（`rag.search.min-score`，默认 0 = 不过滤）的命中。

```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "id": 33,
      "docId": 5,
      "kbId": 1,
      "chunkIndex": 4,
      "content": "员工每年享有 5 天带薪年假，工龄满 5 年后增加至 10 天……",
      "pageNum": 2,
      "sectionTitle": "第三章 假期管理",
      "score": 0.872
    }
  ]
}
```

- **失败响应**：

| `message` | 触发条件 |
| --- | --- |
| `"query 不能为空"` | `query` 为空或请求体为 `null` |

### 4.2 检索增强问答

检索 → 拼接"参考资料 + 用户问题" → 调用大模型，返回基于文档的回答与命中来源。**独立于 `ChatService`**，不带会话记忆与人格。

- **请求**：`POST /api/rag/ask`
- **请求头**：`satoken: <tokenValue>`
- **Content-Type**：`application/json`
- **请求体**：同 [4.1 纯向量检索](#41-纯向量检索)。

```json
{ "query": "年假有几天", "kbId": 1, "topK": 5 }
```

- **成功响应**：`data` 为 [`AskResult`](#askresult)。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `answer` | string | 模型基于参考资料的回答（带防幻觉约束：资料无依据时回答"根据现有资料无法回答该问题。"） |
| `sources` | array | 命中的来源片段（[`ChunkHit`](#chunkhit)），供核验依据 |

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "answer": "根据资料，员工每年享有 5 天带薪年假，工龄满 5 年后增加至 10 天。",
    "sources": [
      {
        "id": 33, "docId": 5, "kbId": 1, "chunkIndex": 4,
        "content": "员工每年享有 5 天带薪年假……",
        "pageNum": 2, "sectionTitle": "第三章 假期管理",
        "score": 0.872
      }
    ]
  }
}
```

- **检索为空时的兜底回答**：`answer = "未检索到任何相关文档, 无法回答。"`，`sources = []`。
- **失败响应**：

| `message` | 触发条件 |
| --- | --- |
| `"query 不能为空"` | `query` 为空或请求体为 `null` |
| `"模型暂时无响应，请重试"` | 模型调用异常（`IllegalStateException` 兜底） |

---

## 5. 联调测试接口（公开）

模块：`TestController`，路径前缀 `/test`，**无需登录**。

> ⚠️ 这些接口用于联调各 ChatClient Bean 与 JPA 连通性，**直接返回字符串（非 `Result` 信封）**，仅用于开发演示。

| # | 方法 | 路径 | 说明 | 返回 |
| --- | --- | --- | --- | --- |
| 5.1 | GET | `/test/hello` | 默认 `ChatClient`（无固定人格）发"你好" | 模型字符串 |
| 5.2 | GET | `/test/mao` | 猫娘人格 `number1ChatClient` 发"你好" | 模型字符串 |
| 5.3 | GET | `/test/teacher` | 读取 `classpath:role/teacher.st` 作为 system，问"你是谁" | 模型字符串 |
| 5.4 | GET | `/test/users` | 验证 JPA：查询全部用户名 | `string[]` |
| 5.5 | GET | `/test/getUser` | 验证工具调用：`toolChatClient`（挂 `UserTools`）查询用户名 ckj | 模型字符串 |

示例（`GET /test/users`）：

```json
["alice", "bob", "ckj"]
```

---

## 数据结构定义

### Conversation

会话元信息（对应 `conversation` 表）。出现在 [3.1 列会话](#31-列出某人格下的会话) 与 [3.3 会话详情](#33-会话详情含历史消息)。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | long | 会话 id（主键） |
| `userId` | long | 发起会话的用户 id |
| `personaId` | string | 绑定的人格 id |
| `title` | string? | 会话标题，首轮对话后**异步**生成，初始为 `null` |
| `summary` | string? | 历史摘要，窗口外老消息压缩而成，初始为 `null` |
| `summarizedCount` | int | 已纳入摘要的消息条数（增量摘要进度） |
| `totalTokens` | int | 本会话累计消耗 token 数 |
| `version` | int? | 乐观锁版本号（JPA `@Version` 自动维护） |
| `createdAt` | datetime | 会话创建时间 |
| `updatedAt` | datetime | 会话最后更新时间（每轮对话刷新） |

### Message

单条对话消息（对应 `message` 表）。出现在 [3.3 会话详情](#33-会话详情含历史消息) 的 `messages` 数组。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | long | 消息 id |
| `conversationId` | long | 所属会话 id |
| `role` | string | 角色：`"user"` / `"assistant"` / `"system"` / `"tool"` |
| `content` | string | 消息内容（TEXT） |
| `tokens` | int | 本条消息消耗 token 数 |
| `createdAt` | datetime | 消息创建时间（用于排序还原对话顺序） |

### ConversationDetail

会话详情 DTO，`ChatService.ConversationDetail`（record）。出现在 [3.3 会话详情](#33-会话详情含历史消息)。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `conversation` | [Conversation](#conversation) | 会话元信息 |
| `messages` | [Message](#message)[] | 历史消息列表（按 `createdAt` 升序） |

### PersonaOverview

人格概览 DTO，`PersonaService.PersonaOverview`。出现在 [2.3 列出人格](#23-列出当前用户的全部人格)。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `personaId` | string | 人格 id |
| `name` | string? | 人格名称（取首片 name） |
| `fragmentCount` | int | 分片数 |

### ChunkHit

检索命中分块 + 相似度分数，`RetrievalService.ChunkHit`（record）。出现在 [4.1 检索](#41-纯向量检索) 与 [4.2 问答](#42-检索增强问答)。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | long | 分块 id（`doc_chunk.id`） |
| `docId` | long | 所属文档 id |
| `kbId` | long | 所属知识库 id |
| `chunkIndex` | int | 分块在文档中的序号 |
| `content` | string | 分块文本 |
| `pageNum` | int? | 来源页码（无页码结构时为 `null`） |
| `sectionTitle` | string? | 来源章节标题（无章节结构时为 `null`） |
| `score` | double | 相似度分数（`1 - cosine 距离`，0~1，越大越相关，保留 3 位小数） |

### AskResult

RAG 问答结果，`RagService.AskResult`（record）。出现在 [4.2 问答](#42-检索增强问答)。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `answer` | string | 模型基于参考资料的回答 |
| `sources` | [ChunkHit](#chunkhit)[] | 命中的来源片段 |

---

## 错误码与异常说明

所有接口统一返回 `code=200`（成功）或 `code=500`（失败），HTTP 状态码本身恒为 200（异常由 `GlobalExceptionHandler` 收口为信封）。常见失败提示：

| `message` | 来源 | 触发场景 |
| --- | --- | --- |
| `"未登录或登录已过期"` | `NotLoginException` | 受保护接口未携带/已失效的 token |
| `"操作冲突，请重试"` | `OptimisticLockingFailureException` | 同一会话并发发消息，乐观锁重试 3 次仍失败 |
| `"模型暂时无响应，请重试"` | `IllegalStateException` | 模型返回空内容或调用异常（RAG/对话兜底） |
| `"用户名或密码错误"` / `"用户名已存在"` 等 | `IllegalArgumentException` | 业务校验失败，`message` 为具体提示 |
| `"会话不存在"` / `"无权访问该会话"` / `"人格不存在或已删除"` | `IllegalArgumentException` | 资源归属/存在性校验失败 |

> 注：业务校验类 `IllegalArgumentException` 在 controller 内被 `try/catch` 直接转 `Result.fail`；系统级异常（`NotLoginException`、`OptimisticLockingFailureException`、`IllegalStateException`）由全局处理器兜底。

---

## 附录：典型业务流程

### A. 完整对话流程

```text
1. POST /api/auth/register          → 拿到 tokenValue
2. POST /api/persona/upload         → 拿到 personaId
   POST /api/chat/conversations     → 拿到 conversationId
3. POST /api/chat/conversations/{id}/messages  → 拿到模型 reply（可循环）
4. GET  /api/chat/conversations/{id}           → 拉取历史（title/summary 稍后异步填充）
5. DELETE /api/chat/conversations/{id}         → 清理
```

**请求头**：除第 1 步外，其余均需 `satoken: <第1步返回的 tokenValue>`。

### B. 知识库问答流程

```text
1. POST /api/auth/login             → 拿到 tokenValue（知识库已由启动时灌入）
2. POST /api/rag/search             → 验证召回质量（可选）
3. POST /api/rag/ask                → 拿到基于文档的 answer + sources
```

**请求头**：第 2、3 步需 `satoken: <tokenValue>`。
