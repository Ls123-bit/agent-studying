# Nexus Agent API 接口文档

| 项目 | 说明 |
| :--- | :--- |
| 文档版本 | v1.0 |
| 生成日期 | 2026-09-12 |
| 基于代码 | `master` 分支（commit `7ef51a5`），源码分析自动生成 |
| 服务名称 | `nexus-agent-business-chat`（主业务后端服务） |
| 服务端口 | `9082`（无 context-path） |
| 基础地址 | `http://127.0.0.1:9082` |

本文档覆盖主业务模块 `nexus-agent-business-chat` 对外暴露的全部 4 个 Controller、共 30 个接口。`ai-example` 目录下的各模块为独立运行的 Spring AI 示例工程（MCP、Memory、RAG、GraphRAG 等 Demo Controller），不属于主业务 API，本文档不展开。

---

## 目录

- [1. 通用约定](#1-通用约定)
  - [1.1 统一响应结构](#11-统一响应结构)
  - [1.2 错误响应与错误码](#12-错误响应与错误码)
  - [1.3 鉴权方式](#13-鉴权方式)
  - [1.4 只读预览模式](#14-只读预览模式)
  - [1.5 跨域与前端代理](#15-跨域与前端代理)
- [2. 后台认证模块 /admin/auth](#2-后台认证模块-adminauth)
- [3. 智能对话模块 /api/chat](#3-智能对话模块-apichat)
  - [3.1 对话流式接口（SSE）](#31-对话流式接口sse)
  - [3.2 会话与轮次查询](#32-会话与轮次查询)
  - [3.3 会话管理操作](#33-会话管理操作)
  - [3.4 检索观测与性能基准](#34-检索观测与性能基准)
- [4. 文档管理模块 /manage/document](#4-文档管理模块-managedocument)
- [5. 知识管理模块 /manage/knowledge](#5-知识管理模块-manageknowledge)
- [6. 数据字典（枚举）](#6-数据字典枚举)
- [7. 核心响应对象结构](#7-核心响应对象结构)

---

## 1. 通用约定

### 1.1 统一响应结构

除 SSE 流式接口外，所有接口返回统一 JSON 结构 `ApiResponse<T>`：

```json
{
  "code": 0,
  "message": null,
  "data": { }
}
```

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `code` | Integer | 业务状态码，`0` 表示成功 |
| `message` | String | 提示信息，成功时通常为 `null` |
| `data` | T | 业务数据，具体结构见各接口定义 |

### 1.2 错误响应与错误码

全局异常由 `DefaultExceptionHandler` 统一处理：

| 场景 | 响应示例 | 说明 |
| :--- | :--- | :--- |
| 业务异常（`SuperAgentFrameException`） | `{"code": <业务码>, "message": "错误描述"}` | 如"会话不存在"“chatMode 非法"等 |
| 参数校验失败（`MethodArgumentNotValidException`） | `{"code": 10054, "data": [{"argumentName": "question", "message": "question 不能为空"}]}` | `data` 为字段错误数组 |
| 未登录 / Token 无效（HTTP 401） | `{"code": 401, "message": "请先登录后台管理台"}` | HTTP 状态码与业务码均为 401 |
| 其他未捕获异常 | `{"code": -100, "message": "系统错误，请稍后重试!"}` | 兜底系统错误 |

常用错误码：

| 错误码 | 含义 |
| :--- | :--- |
| `0` | 成功 |
| `401` | 未登录 / Token 无效 |
| `-1` | 系统异常 |
| `-100` | 通用错误 |
| `10054` | 参数验证异常 |

### 1.3 鉴权方式

后台管理类接口采用 **JWT Bearer Token** 认证：

```
Authorization: Bearer <token>
```

- Token 通过 `POST /admin/auth/login` 获取，有效期默认 **720 分钟**（可配置 `app.admin-auth.token-expire-minutes`）。
- **需要鉴权的路径**：`/manage/**`、`/admin/auth/me`。
- **免鉴权的路径**：`/admin/auth/login`、`/admin/auth/logout`、`/api/chat/**`。
- 预检请求（`OPTIONS`）直接放行。
- 默认管理员账号 `admin`，密码 `REDACTED-ADMIN-PASSWORD`（可通过 `app.admin-auth.*` 配置覆盖），账号密码仅有一组，存储在配置中。

### 1.4 只读预览模式

通过配置 `app.preview-mode.enabled=true` 可开启只读展示模式（默认关闭）。开启后以下**写操作**接口将被拦截，返回 `{"code": <错误码>, "message": "当前环境为只读展示模式，仅开放浏览与检索能力"}`（消息可配置）：

```
/api/chat/stream                                /manage/knowledge/scope/save
/api/chat/session/stop                          /manage/knowledge/scope/delete
/api/chat/session/reset                         /manage/knowledge/topic/save
/api/chat/session/summary/rebuild               /manage/knowledge/topic/delete
/manage/document/upload                         /manage/knowledge/document/profile/regenerate
/manage/document/delete                         /manage/knowledge/document/profile/batch/regenerate
/manage/document/strategy/confirm               /manage/knowledge/topic/document/save
/manage/document/index/build                    /manage/knowledge/topic/document/remove
```

其中 `/api/chat/stream` 被拦截时以 SSE error 事件返回拒绝消息，其余接口返回普通 JSON。

### 1.5 跨域与前端代理

前端（`vue/`，Vite 开发服务器）将 `/api`、`/admin/auth`、`/manage` 三类路径代理到后端 `http://127.0.0.1:9082`（可通过环境变量 `VITE_PROXY_TARGET` 覆盖）。直连后端时无需额外处理跨域头。

---

## 2. 后台认证模块 `/admin/auth`

### 2.1 登录

```
POST /admin/auth/login
```

管理员登录，换取 JWT Token。

**请求体**（`AdminLoginRequest`）：

| 字段 | 类型 | 必填 | 说明 |
| :--- | :--- | :--- | :--- |
| `username` | String | 是 | 账号，不能为空 |
| `password` | String | 是 | 密码，不能为空 |

**请求示例**：

```json
{ "username": "admin", "password": "REDACTED-ADMIN-PASSWORD" }
```

**响应 `data`**（`AdminLoginVo`）：

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `username` | String | 登录账号 |
| `token` | String | JWT Token，后续请求放入 `Authorization: Bearer <token>` |
| `expireMinutes` | Long | Token 有效期（分钟），默认 720 |

### 2.2 登出

```
POST /admin/auth/logout
```

登出当前用户。无请求体，固定返回 `data: null`（`code: 0`）。服务端不维护 Token 黑名单，登出仅由前端清除本地 Token。

### 2.3 当前登录用户信息

```
GET /admin/auth/me
```

获取当前登录管理员信息。**需要鉴权**。

**响应 `data`**（`AdminProfileVo`）：

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `username` | String | 当前登录账号（解析自 JWT subject） |

---

## 3. 智能对话模块 `/api/chat`

对话模块是系统核心入口，负责：发起 SSE 流式对话、查询会话与轮次详情、停止/重置会话、重建会话记忆摘要、检索链路观测和阶段性能基准查询。该模块**不要求后台登录**。

### 3.1 对话流式接口（SSE）

```
POST /api/chat/stream
Content-Type: application/json
响应类型: text/event-stream;charset=UTF-8
```

发起一轮对话，以 SSE（Server-Sent Events）流式返回执行过程与回答。

**请求体**（`ChatRequestDto`）：

| 字段 | 类型 | 必填 | 说明 |
| :--- | :--- | :--- | :--- |
| `question` | String | 是 | 用户问题，不能为空 |
| `conversationId` | String | 否 | 会话 ID；不传或为空时服务端自动生成 UUID（新会话） |
| `chatMode` | String | 是 | 提问模式，枚举名不区分大小写：`DOCUMENT`（当前文档问答）/ `AUTO_DOCUMENT`（自动知识问答）/ `OPEN_CHAT`（开放式提问） |
| `selectedDocumentId` | String | 条件 | `DOCUMENT` 模式**必传**（且文档必须处于可检索状态）；`AUTO_DOCUMENT`、`OPEN_CHAT` 模式**禁止传**，否则报错 |

**chatMode 与 selectedDocumentId 的约束**：

| chatMode | selectedDocumentId | 行为 |
| :--- | :--- | :--- |
| `DOCUMENT` | 必传 | 在指定文档内问答（结构图查询 + 混合检索） |
| `AUTO_DOCUMENT` | 禁止传 | 由知识路由三级漏斗自动锁定文档 |
| `OPEN_CHAT` | 禁止传 | 开放式问题，走 ReAct Agent（联网搜索、工具调用） |

**请求示例**：

```json
{
  "question": "打印机墨盒怎么更换？",
  "conversationId": "3f8a2c1e5b6d4a7f9e0c8d7b6a5f4e3d",
  "chatMode": "DOCUMENT",
  "selectedDocumentId": "1024"
}
```

**SSE 事件协议**：

每个 SSE 事件（`data:` 行）的载荷是一个 JSON 对象，统一结构：

```json
{
  "type": "<事件类型>",
  "content": <内容>,
  "timestamp": "2026-09-12T10:30:00.123456Z",
  "conversationId": "3f8a2c1e...",
  "exchangeId": 1024
}
```

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `type` | String | 事件类型，见下表 |
| `content` | Object | 事件内容，类型随事件不同 |
| `timestamp` | String | 事件产生时间（ISO-8601 Instant） |
| `conversationId` | String | 会话 ID（拒绝场景可能缺失） |
| `exchangeId` | Long | 轮次 ID（正常流程必现；启动失败时可能缺失） |

**事件类型**：

| type | content 类型 | 说明 | 出现时机 |
| :--- | :--- | :--- | :--- |
| `thinking` | String | 思考过程步骤文本 | 执行开始（"正在分析问题上下文。"）及编排各阶段 |
| `text` | String | 回答正文增量分片 | 模型流式输出期间，多次推送，客户端按序拼接 |
| `reference` | `SearchReference[]` | 引用来源列表，额外字段 `count` | 回答完成后一次性补发（有引用时才有） |
| `recommend` | `String[]` | 推荐追问问题列表，额外字段 `count`（最多 3 个） | 回答完成后一次性补发；歧义澄清场景为澄清选项 |
| `status` | String | 状态提示（如 "⏹ 用户已停止生成"） | 会话被停止时 |
| `error` | String | 错误信息 | 执行失败或请求被拒绝（会话执行中、参数错误、预览模式拦截等） |

**正常事件序列**：`thinking` → `text`×N → `reference`（可选）→ `recommend`（可选）→ 流关闭（SSE complete）。
**失败序列**：`thinking` → `error` → 流关闭。
**拒绝场景**（会话正在执行中、预览模式等）：仅一个 `error` 事件即关闭。

**SearchReference（引用来源）结构**：

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `referenceId` | String | 引用编号 |
| `sourceType` | String | 来源类型：`WEB`（联网搜索）或文档类来源 |
| `title` | String | 来源标题 |
| `url` | String | 来源 URL（WEB 类型） |
| `snippet` | String | 命中文本片段 |
| `documentId` / `documentName` | Long / String | 命中文档 ID / 名称（文档类来源） |
| `chunkId` / `chunkNo` | Long / Integer | 命中 Child 块 ID / 序号 |
| `parentBlockId` / `parentBlockNo` | Long / Integer | 聚合后的 Parent 块 ID / 序号 |
| `sectionPath` | String | 所属章节路径 |
| `structureNodeId` / `structureNodeType` | Long / Integer | Neo4j 结构节点 ID / 类型 |
| `canonicalPath` | String | 规范化路径 |
| `itemIndex` | Integer | 条目索引 |
| `score` | Double | 命中分数 |
| `subQuestionIndex` / `subQuestion` | Integer / String | 关联子问题序号 / 内容 |
| `channel` | String | 命中通道（如 `web-search`） |
| `toolName` | String | 产生该引用的工具名（如 `tavily_search`） |
| `knowledgeScopeCode` / `knowledgeScopeName` | String | 知识域编码 / 名称 |

**执行说明**：

- 同一会话（`conversationId`）同一时刻只允许一轮执行。重复发起会收到 `error` 事件："该会话当前正在执行中，请稍后再试"（Redis 租约 + JVM 任务注册双重互斥，租约 30s TTL、10s 自动续期）。
- 客户端断开连接（取消订阅）会触发服务端停止生成并落库为 `STOPPED` 状态。
- 后端按前置编排决策选择执行器：歧义澄清（CLARIFICATION）、结构图查询（GRAPH_*）、RAG 知识问答（RETRIEVAL）或 ReAct Agent（REACT_AGENT）。

### 3.2 会话与轮次查询

#### 3.2.1 查询可选题文档列表

```
POST /api/chat/document/options
```

获取所有处于可检索状态的文档，用于 `DOCUMENT` 模式下的文档选择下拉框。无请求体。

**响应 `data`**：`KnowledgeDocumentOptionView[]`

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `documentId` | String | 文档 ID |
| `documentName` | String | 文档名称 |
| `knowledgeScopeName` | String | 所属知识域名称 |
| `businessCategory` | String | 业务分类 |
| `documentTags` | String | 文档标签 |

#### 3.2.2 查询会话详情

```
POST /api/chat/session/detail
```

查询单个会话的完整信息，包含全部轮次、会话记忆摘要，并叠加运行时快照（若该会话正在执行中，返回实时生成内容与 `RUNNING` 状态）。

**请求体**（`ConversationIdentityDto`）：

| 字段 | 类型 | 必填 | 说明 |
| :--- | :--- | :--- | :--- |
| `conversationId` | String | 是 | 会话 ID |

**响应 `data`**：`ConversationSessionView`，结构见 [7.1](#71-conversationsessionview-会话视图)。会话不存在时报业务异常。

#### 3.2.3 查询轮次详情

```
POST /api/chat/exchange/detail
```

查询单个轮次（一问一答）的详情，包含轮次内容与全链路执行阶段追踪。

**请求体**（`ConversationExchangeDetailQueryDto`）：

| 字段 | 类型 | 必填 | 说明 |
| :--- | :--- | :--- | :--- |
| `conversationId` | String | 是 | 会话 ID |
| `exchangeId` | String | 是 | 轮次 ID |

**响应 `data`**（`ConversationExchangeDetailView`）：

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `conversationId` | String | 会话 ID |
| `exchange` | ConversationExchangeView | 轮次内容，结构见 [7.2](#72-conversationexchangeview-轮次视图) |
| `stageTraces` | `ConversationTraceStageView[]` | 执行阶段追踪，结构见 [7.3](#73-conversationtracestageview-阶段追踪视图) |

#### 3.2.4 分页查询会话列表

```
POST /api/chat/session/list
```

分页查询会话列表。请求体可省略（查全部，按默认分页）。列表项**不包含** `exchanges` 与 `memorySummary`。

**请求体**（`ConversationSessionListQueryDto`，可选）：

| 字段 | 类型 | 必填 | 说明 |
| :--- | :--- | :--- | :--- |
| `keyword` | String | 否 | 关键字，匹配会话内容 |
| `chatMode` | String | 否 | 按提问模式过滤：`DOCUMENT` / `AUTO_DOCUMENT` / `OPEN_CHAT` / `ALL`（不区分大小写） |
| `turnStatus` | String | 否 | 按最新轮次状态过滤：`RUNNING` / `COMPLETED` / `FAILED` / `STOPPED` / `ALL` |
| `pageNo` | String | 否 | 页码，默认 `1` |
| `pageSize` | String | 否 | 每页条数，默认 `20` |

**响应 `data`**（`ConversationSessionListVo`）：

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `pageNo` | long | 当前页码 |
| `pageSize` | long | 每页条数 |
| `totalSize` | long | 总条数 |
| `totalPages` | long | 总页数 |
| `sessions` | `ConversationSessionView[]` | 会话列表（不含轮次明细与记忆摘要） |

### 3.3 会话管理操作

#### 3.3.1 停止生成

```
POST /api/chat/session/stop
```

停止该会话正在进行的生成任务。轮次落库为 `STOPPED`，SSE 流补发 `status` 事件后关闭。

**请求体**（`ConversationIdentityDto`）：`conversationId`（必填）。

**响应 `data`**（`ConversationStopVo`）：

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `conversationId` | String | 会话 ID |
| `stopped` | Boolean | 是否成功触发停止 |
| `message` | String | 结果说明（如"没有找到正在执行的会话"“会话已经结束"“已停止会话生成"） |

#### 3.3.2 重置会话

```
POST /api/chat/session/reset
```

清空会话：停止执行中的任务（如有）、删除对话归档与轮次、删除会话记忆摘要、删除执行阶段追踪、删除检索观测数据、清空 Agent Checkpoint。

**请求体**（`ConversationIdentityDto`）：`conversationId`（必填）。

**响应 `data`**（`ConversationResetVo`）：

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `conversationId` | String | 会话 ID |
| `stoppedRunningTask` | Boolean | 是否停止了执行中的任务 |
| `removedDialogueCount` | int | 删除的对话消息条数 |
| `removedExchangeCount` | int | 删除的轮次数 |
| `removedCheckpointCount` | int | 删除的 Checkpoint 数 |
| `message` | String | 结果说明（"会话已重置"） |

#### 3.3.3 重建会话记忆摘要

```
POST /api/chat/session/summary/rebuild
```

强制重建该会话的长期记忆摘要（摘要压缩策略）。

**请求体**（`ConversationIdentityDto`）：`conversationId`（必填）。

**响应 `data`**（`ConversationMemorySummaryView`）：

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `conversationId` | String | 会话 ID |
| `compressionApplied` | boolean | 是否已应用压缩 |
| `coveredExchangeId` | long | 摘要已覆盖到的轮次 ID |
| `coveredExchangeCount` | int | 已覆盖轮次数 |
| `compressionCount` | int | 压缩执行次数 |
| `summaryVersion` | int | 摘要版本号 |
| `summaryText` | String | 摘要文本 |
| `summaryPayload` | Object | 结构化摘要：`summary`、`conversationGoal`、`stableFacts[]`、`userPreferences[]`、`resolvedPoints[]`、`pendingQuestions[]`、`retrievalHints[]` |
| `lastSourceEditTime` | Instant | 源数据最后变更时间 |
| `updatedAt` | Instant | 摘要更新时间 |

### 3.4 检索观测与性能基准

#### 3.4.1 查询轮次检索结果明细

```
POST /api/chat/exchange/retrieval/results
```

查询某一轮次全部检索通道的候选结果明细（用于观测面板）。

**请求体**（`RetrievalObserveQueryDto`）：

| 字段 | 类型 | 必填 | 说明 |
| :--- | :--- | :--- | :--- |
| `conversationId` | String | 是 | 会话 ID |
| `exchangeId` | String | 是 | 轮次 ID（数字字符串） |

**响应 `data`**：`RetrievalResultView[]`

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `id` | Long | 记录 ID |
| `traceId` | String | 全链路追踪 ID |
| `subQuestionIndex` / `subQuestion` | int / String | 子问题序号 / 内容 |
| `channelType` | String | 检索通道类型（向量 / 关键词） |
| `channelRank` | Integer | 通道内排名 |
| `rrfRank` / `rrfScore` | Integer / BigDecimal | RRF 融合排名 / 分数 |
| `finalRank` | Integer | 最终排名 |
| `originalScore` | BigDecimal | 通道原始分数 |
| `rerankScore` | BigDecimal | Rerank 精排分数 |
| `gatePassed` | boolean | 是否通过通道阈值门限 |
| `isElevated` | boolean | 是否被 Parent 块聚合提升 |
| `isSelected` | boolean | 是否最终入选证据 |
| `selectionReason` | String | 入选/淘汰原因 |
| `documentId` / `documentName` | Long / String | 命中文档 |
| `chunkId` / `chunkNo` | Long / Integer | Child 块 ID / 序号 |
| `parentBlockId` / `parentBlockNo` | Long / Integer | Parent 块 ID / 序号 |
| `sectionPath` | String | 章节路径 |
| `chunkTextPreview` | String | 块文本预览 |
| `chunkCharCount` | Integer | 块字符数 |
| `createTime` | Instant | 记录时间 |

#### 3.4.2 查询轮次通道执行统计

```
POST /api/chat/exchange/channel/executions
```

查询某一轮次各检索通道的执行统计（召回数、接受数、耗时等）。

**请求体**：同 3.4.1（`conversationId` + `exchangeId`）。

**响应 `data`**：`ChannelExecutionView[]`

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `id` | Long | 记录 ID |
| `traceId` | String | 追踪 ID |
| `subQuestionIndex` / `subQuestion` | int / String | 子问题序号 / 内容 |
| `channelType` | String | 通道类型 |
| `executionState` | int | 执行状态 |
| `startTime` / `endTime` | Instant | 起止时间 |
| `durationMs` | Long | 耗时（毫秒） |
| `recalledCount` / `acceptedCount` / `finalSelectedCount` | int | 召回数 / 通过门限数 / 最终入选数 |
| `avgScore` / `maxScore` / `minScore` | BigDecimal | 分数统计 |
| `errorMessage` | String | 错误信息 |
| `createTime` | Instant | 记录时间 |

#### 3.4.3 查询执行阶段性能基准

```
POST /api/chat/stage/benchmarks
```

查询各执行阶段（记忆、意图、改写、路由、检索、生成等）的全局耗时基准（P50/P90/P99 分位）。无请求体。

**响应 `data`**：`StageBenchmarkView[]`

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `stageCode` | String | 阶段编码，见 [6.4 阶段编码](#64-执行阶段编码-conversationtracestagecode) |
| `executionMode` | String | 执行模式 |
| `p50DurationMs` / `p90DurationMs` / `p99DurationMs` | Long | 50/90/99 分位耗时（毫秒） |
| `avgDurationMs` / `maxDurationMs` / `minDurationMs` | Long | 平均 / 最大 / 最小耗时 |
| `sampleCount` | int | 样本数 |

---

## 4. 文档管理模块 `/manage/document`

文档全生命周期管理：上传解析 → 策略推荐 → 策略确认 → 索引构建 → 块与任务日志查询。**本模块全部接口需要 JWT 鉴权**（`Authorization: Bearer <token>`）。

### 4.1 上传文档并投递解析任务

```
POST /manage/document/upload
Content-Type: multipart/form-data
```

上传文件到 MinIO，并通过 Kafka 异步触发 Tika 解析任务。

**请求参数**（form-data 分部）：

| 分部 | 类型 | 必填 | 说明 |
| :--- | :--- | :--- | :--- |
| `file` | file | 是 | 文档文件（支持 PDF/DOC/DOCX/TXT/MD/HTML 等） |
| `meta` | JSON 字符串 | 否 | 文档元信息（`DocumentUploadDto`） |

`meta` 分部内容（`DocumentUploadDto`）：

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `documentName` | String | 文档名称（缺省用文件名） |
| `operatorId` | String | 操作人 ID |
| `knowledgeScopeCode` | String | 知识域编码 |
| `knowledgeScopeName` | String | 知识域名称 |
| `businessCategory` | String | 业务分类 |
| `documentTags` | String | 文档标签 |

**响应 `data`**（`DocumentUploadVo`）：

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `documentId` | Long | 文档 ID |
| `taskId` | Long | 解析任务 ID |
| `documentName` | String | 文档名称 |
| `parseStatus` | Integer | 解析状态，见 [6.2](#62-文档状态类枚举) |
| `strategyStatus` | Integer | 策略状态 |
| `indexStatus` | Integer | 索引状态 |

### 4.2 分页查询文档列表

```
POST /manage/document/page/query
```

**请求体**（`DocumentPageQueryDto`）：

| 字段 | 类型 | 必填 | 说明 |
| :--- | :--- | :--- | :--- |
| `pageNo` | Integer | 否 | 页码 |
| `pageSize` | Integer | 否 | 每页条数 |
| `keyword` | String | 否 | 关键字（匹配文档名） |

**响应 `data`**（`DocumentPageQueryVo`）：

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `pageNo` / `pageSize` | Integer | 分页参数 |
| `total` | Long | 总条数 |
| `records` | `DocumentListItemVo[]` | 文档列表，单项结构见 [7.4](#74-documentlistitemvo-文档列表项) |

### 4.3 查询文档详情

```
POST /manage/document/detail/query
```

**请求体**（`DocumentDetailQueryDto`）：`documentId`（Long，必填）。

**响应 `data`**：`DocumentListItemVo`，结构见 [7.4](#74-documentlistitemvo-文档列表项)。

### 4.4 删除文档及其关联数据

```
POST /manage/document/delete
```

删除文档及其关联数据（块、索引、图谱等）。

**请求体**（`DocumentDeleteDto`）：`documentId`（String，必填）。

**响应 `data`**（`DocumentDeleteVo`）：

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `documentId` | Long | 已删除的文档 ID |
| `documentName` | String | 已删除的文档名称 |

### 4.5 查询文档策略推荐结果

```
POST /manage/document/strategy/plan/query
```

查询系统为文档自动推荐的最优切块策略方案（解析完成后可查）。

**请求体**（`DocumentStrategyPlanQueryDto`）：`documentId`（Long，必填）。

**响应 `data`**（`DocumentStrategyPlanQueryVo`）：

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `documentId` | Long | 文档 ID |
| `documentName` | String | 文档名称 |
| `parseStatus` / `parseStatusName` | Integer / String | 解析状态及名称 |
| `strategyStatus` / `strategyStatusName` | Integer / String | 策略状态及名称 |
| `indexStatus` / `indexStatusName` | Integer / String | 索引状态及名称 |
| `parseErrorMsg` | String | 解析错误信息 |
| `planReady` | Boolean | 推荐方案是否已生成 |
| `plan` | DocumentStrategyPlanVo | 推荐方案，结构见下 |

`DocumentStrategyPlanVo`：

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `planId` | Long | 方案 ID（确认策略、构建索引时使用） |
| `planVersion` | Integer | 方案版本 |
| `planSource` / `planSourceName` | Integer / String | 方案来源 |
| `planStatus` / `planStatusName` | Integer / String | 方案状态 |
| `strategySnapshot` | String | 策略快照 JSON |
| `recommendReason` | String | 推荐理由 |
| `parentPipeline` | DocumentStrategyPipelineVo | 父块流水线 |
| `childPipeline` | DocumentStrategyPipelineVo | 子块流水线 |

`DocumentStrategyPipelineVo`：

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `pipelineType` / `pipelineTypeName` | String / String | 流水线类型：`PARENT`（父块）/ `CHILD`（子块） |
| `strategySnapshot` | String | 策略快照 |
| `steps` | `DocumentStrategyStepVo[]` | 策略步骤列表 |

`DocumentStrategyStepVo`：

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `stepNo` | Integer | 步骤序号 |
| `pipelineType` / `pipelineTypeName` | String | 所属流水线 |
| `strategyType` / `strategyName` | Integer / String | 切块策略类型，见 [6.3](#63-切块策略类型-documentstrategytypeenum) |
| `strategyRole` / `strategyRoleName` | Integer / String | 策略角色（主策略/优化/兜底/增强） |
| `sourceType` / `sourceTypeName` | Integer / String | 来源（系统推荐/用户新增/用户保留） |
| `executeStatus` / `executeStatusName` | Integer / String | 执行状态 |
| `recommendReason` | String | 推荐理由 |

### 4.6 确认文档策略方案

```
POST /manage/document/strategy/confirm
```

确认（或调整后确认）文档切块策略方案。用户可调整步骤顺序、增删策略。

**请求体**（`DocumentStrategyConfirmDto`）：

| 字段 | 类型 | 必填 | 说明 |
| :--- | :--- | :--- | :--- |
| `documentId` | Long | 是 | 文档 ID |
| `basePlanId` | Long | 是 | 基于的推荐方案 ID |
| `adjustNote` | String | 否 | 调整说明 |
| `operatorId` | Long | 否 | 操作人 ID |
| `parentSteps` | `StepItem[]` | 是 | 父块流水线步骤，不能为空 |
| `childSteps` | `StepItem[]` | 是 | 子块流水线步骤，不能为空 |

其中 `StepItem`（`DocumentStrategyStepItemDto`）：

| 字段 | 类型 | 必填 | 说明 |
| :--- | :--- | :--- | :--- |
| `stepNo` | Integer | 否 | 步骤序号 |
| `strategyType` | Integer | 是 | 切块策略类型：1 结构切块 / 2 递归分块 / 3 语义分块 / 4 LLM 智能切块 |

**响应 `data`**（`DocumentStrategyConfirmVo`）：`documentId`、`planId`（确认后方案 ID）、`planVersion`、`strategyStatus`/`strategyStatusName`、`normalized`（服务端是否做了归一化修正）、`parentPipeline`、`childPipeline`（结构同 4.5）。

### 4.7 执行文档索引构建

```
POST /manage/document/index/build
```

按已确认的策略方案异步执行向量化与双引擎索引构建（向量库 + 倒排索引）。

**请求体**（`DocumentIndexBuildDto`）：

| 字段 | 类型 | 必填 | 说明 |
| :--- | :--- | :--- | :--- |
| `documentId` | Long | 是 | 文档 ID |
| `planId` | Long | 是 | 已确认的方案 ID |
| `operatorId` | Long | 否 | 操作人 ID |

**响应 `data`**（`DocumentIndexBuildVo`）：

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `documentId` | Long | 文档 ID |
| `taskId` | Long | 索引构建任务 ID |
| `taskType` / `taskTypeName` | Integer / String | 任务类型及名称 |
| `taskStatus` / `taskStatusName` | Integer / String | 任务状态及名称 |
| `indexStatus` / `indexStatusName` | Integer / String | 索引状态及名称 |

### 4.8 查询文档 chunk 列表

```
POST /manage/document/chunk/query
```

分页查询文档切块后的 Child 块列表。

**请求体**（`DocumentChunkQueryDto`）：

| 字段 | 类型 | 必填 | 说明 |
| :--- | :--- | :--- | :--- |
| `documentId` | Long | 是 | 文档 ID |
| `taskId` | Long | 否 | 任务 ID（不传取最新任务） |
| `pageNo` | Integer | 否 | 页码 |
| `pageSize` | Integer | 否 | 每页条数 |

**响应 `data`**（`DocumentChunkQueryVo`）：`documentId`、`taskId`、`planId`、`pageNo`、`pageSize`、`total`、`records`（`DocumentChunkItemVo[]`）。

`DocumentChunkItemVo`：

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `chunkId` | Long | 块 ID |
| `parentBlockId` / `parentBlockNo` | Long / Integer | 所属父块 ID / 序号 |
| `parentChildCount` | Integer | 父块下子块数量 |
| `parentStartChunkNo` / `parentEndChunkNo` | Integer | 父块覆盖的子块序号范围 |
| `chunkNo` | Integer | 块序号 |
| `sectionPath` | String | 所属章节路径 |
| `sourceType` / `sourceTypeName` | Integer / String | 来源：1 原文切块 / 2 后处理补全文本 |
| `charCount` / `tokenCount` | Integer | 字符数 / Token 数 |
| `vectorStatus` / `vectorStatusName` | Integer / String | 向量化状态 |
| `chunkText` | String | 块文本内容 |

### 4.9 查询单个 chunk 详情

```
POST /manage/document/chunk/detail/query
```

查询单个 Child 块详情，同时返回其所属 Parent 块全文与同级兄弟块。

**请求体**（`DocumentChunkDetailQueryDto`）：

| 字段 | 类型 | 必填 | 说明 |
| :--- | :--- | :--- | :--- |
| `documentId` | Long | 是 | 文档 ID |
| `chunkId` | Long | 是 | 块 ID |
| `taskId` | Long | 否 | 任务 ID |

**响应 `data`**（`DocumentChunkDetailVo`）：

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `documentId` / `taskId` / `planId` | Long | 关联 ID |
| `chunk` | DocumentChunkItemVo | 当前块（结构同 4.8） |
| `parentBlock` | DocumentParentBlockItemVo | 所属父块 |
| `siblingChunks` | `DocumentChunkItemVo[]` | 同父块下的兄弟子块 |

`DocumentParentBlockItemVo`：`parentBlockId`、`parentBlockNo`、`sectionPath`、`sourceType`/`sourceTypeName`、`charCount`、`tokenCount`、`childCount`、`startChunkNo`、`endChunkNo`、`parentText`（父块全文）。

### 4.10 查询任务执行日志

```
POST /manage/document/task/log/query
```

分页查询文档处理任务（解析/索引构建等）的分步骤执行日志。

**请求体**（`DocumentTaskLogQueryDto`）：

| 字段 | 类型 | 必填 | 说明 |
| :--- | :--- | :--- | :--- |
| `taskId` | Long | 是 | 任务 ID |
| `pageNo` | Integer | 否 | 页码 |
| `pageSize` | Integer | 否 | 每页条数 |

**响应 `data`**（`DocumentTaskLogQueryVo`）：

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `taskId` / `documentId` | Long | 任务 / 文档 ID |
| `taskType` / `taskTypeName` | Integer / String | 任务类型 |
| `taskStatus` / `taskStatusName` | Integer / String | 任务状态 |
| `currentStage` / `currentStageName` | Integer / String | 当前阶段 |
| `startTime` / `finishTime` | Date | 起止时间 |
| `costMillis` | Long | 总耗时（毫秒） |
| `errorCode` / `errorMsg` | String | 错误码 / 错误信息 |
| `total` | Long | 日志总条数 |
| `logs` | `DocumentTaskLogVo[]` | 日志明细 |

`DocumentTaskLogVo`：`id`、`stageType`/`stageTypeName`（阶段）、`eventType`/`eventTypeName`（事件类型）、`logLevel`/`logLevelName`（日志级别）、`content`（日志内容）、`detailJson`（明细 JSON）、`createTime`。

---

## 5. 知识管理模块 `/manage/knowledge`

知识路由底座的管理：知识域（Scope）、知识主题（Topic）、文档画像、主题-文档关联、知识路由追踪。**本模块全部接口需要 JWT 鉴权**。

### 5.1 知识范围（Scope）管理

#### 保存知识范围节点

```
POST /manage/knowledge/scope/save
```

新增或更新知识范围（知识域）节点，支持树形层级。

**请求体**（`KnowledgeScopeSaveDto`，所有字段均为 String）：

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `id` | String | 节点 ID（更新时传，新增不传） |
| `scopeCode` | String | 知识域编码 |
| `scopeName` | String | 知识域名称 |
| `parentScopeCode` | String | 父级知识域编码（构成层级） |
| `description` | String | 描述 |
| `aliases` | String | 别名 |
| `examples` | String | 示例问题 |
| `sortOrder` | String | 排序号 |
| `operatorId` | String | 操作人 ID |

**响应 `data`**：`KnowledgeScopeItemVo`（字段同请求，不含 `operatorId`）。

#### 删除知识范围节点

```
POST /manage/knowledge/scope/delete
```

**请求体**（`KnowledgeScopeDeleteDto`）：`scopeCode`（String）、`operatorId`（String）。

**响应 `data`**：`Boolean`，是否删除成功。

#### 查询知识范围列表

```
POST /manage/knowledge/scope/list
```

查询全部知识范围节点。无请求体。

**响应 `data`**：`KnowledgeScopeItemVo[]`（`id`、`scopeCode`、`scopeName`、`parentScopeCode`、`description`、`aliases`、`examples`、`sortOrder`）。

### 5.2 知识主题（Topic）管理

#### 保存知识主题节点

```
POST /manage/knowledge/topic/save
```

新增或更新知识主题（挂在某个知识域下）。

**请求体**（`KnowledgeTopicSaveDto`，所有字段均为 String）：

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `id` | String | 节点 ID（更新时传） |
| `topicCode` | String | 主题编码 |
| `topicName` | String | 主题名称 |
| `scopeCode` | String | 所属知识域编码 |
| `description` | String | 描述 |
| `aliases` | String | 别名 |
| `examples` | String | 示例问题 |
| `answerShape` | String | 期望回答形态 |
| `executionPreference` | String | 执行偏好 |
| `sortOrder` | String | 排序号 |
| `operatorId` | String | 操作人 ID |

**响应 `data`**：`KnowledgeTopicItemVo`（字段同请求，不含 `operatorId`）。

#### 删除知识主题节点

```
POST /manage/knowledge/topic/delete
```

**请求体**（`KnowledgeTopicDeleteDto`）：`topicCode`（String）、`operatorId`（String）。

**响应 `data`**：`Boolean`。

#### 查询知识主题列表

```
POST /manage/knowledge/topic/list
```

**请求体**（`KnowledgeTopicQueryDto`，可省略）：`scopeCode`（String，按知识域过滤）。

**响应 `data`**：`KnowledgeTopicItemVo[]`。

### 5.3 文档画像管理

#### 查询文档画像详情

```
POST /manage/knowledge/document/profile/detail
```

**请求体**（`DocumentProfileDetailQueryDto`）：`documentId`（String）。

**响应 `data`**（`DocumentProfileVo`）：

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `documentId` | String | 文档 ID |
| `documentSummary` | String | 文档内容摘要 |
| `documentType` | String | 文档类型 |
| `coreTopics` | String | 核心主题 |
| `exampleQuestions` | String | 示例问题 |
| `graphFriendly` | String | 是否适合图谱化 |
| `supportsGraphOutline` | String | 是否支持图谱大纲 |
| `supportsItemLookup` | String | 是否支持条目定位 |
| `supportsGraphAssist` | String | 是否支持图谱辅助问答 |
| `profileSource` | String | 画像来源 |
| `profileStatus` | String | 画像状态 |
| `errorMsg` | String | 生成错误信息 |

#### 重新生成文档画像

```
POST /manage/knowledge/document/profile/regenerate
```

触发单个文档画像的 LLM 重新生成。

**请求体**（`DocumentProfileRegenerateDto`）：`documentId`（String）、`operatorId`（String）。

**响应 `data`**：`DocumentProfileVo`（结构同上）。

#### 批量重新生成文档画像

```
POST /manage/knowledge/document/profile/batch/regenerate
```

**请求体**（`DocumentProfileBatchRegenerateDto`）：`documentIds`（`String[]`）、`operatorId`（String）。

**响应 `data`**：`DocumentProfileVo[]`。

### 5.4 主题-文档关联管理

#### 查询主题文档关联

```
POST /manage/knowledge/topic/document/list
```

**请求体**（`TopicDocumentRelationListQueryDto`，可省略）：`topicCode`（String，按主题过滤）。

**响应 `data`**：`TopicDocumentRelationItemVo[]`

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `topicCode` | String | 主题编码 |
| `documentId` / `documentName` | String | 文档 ID / 名称 |
| `knowledgeScopeCode` / `knowledgeScopeName` | String | 知识域编码 / 名称 |
| `businessCategory` | String | 业务分类 |
| `documentTags` | String | 文档标签 |
| `relationScore` | String | 关联分数 |
| `relationSource` | String | 关联来源 |
| `reason` | String | 关联理由 |

#### 保存主题文档关联

```
POST /manage/knowledge/topic/document/save
```

**请求体**（`TopicDocumentRelationSaveDto`，所有字段均为 String）：`topicCode`、`documentId`、`relationScore`（关联分数）、`relationSource`（来源）、`reason`（理由）、`operatorId`。

**响应 `data`**：`TopicDocumentRelationItemVo`。

#### 移除主题文档关联

```
POST /manage/knowledge/topic/document/remove
```

**请求体**（`TopicDocumentRelationRemoveDto`）：`topicCode`（String）、`documentId`（String）、`operatorId`（String）。

**响应 `data`**：`Boolean`。

### 5.5 知识路由追踪

#### 分页查询知识路由追踪

```
POST /manage/knowledge/route/trace/page/query
```

分页查询影子路由（Shadow Route）质量观测记录：系统推荐与用户实际选择的对比。

**请求体**（`KnowledgeRouteTraceQueryDto`，可省略，所有字段均为 String）：

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `conversationId` | String | 按会话 ID 过滤 |
| `mode` | String | 按提问模式过滤 |
| `routeStatus` | String | 按路由状态过滤 |
| `pageNo` | String | 页码 |
| `pageSize` | String | 每页条数 |

**响应 `data`**（`KnowledgeRouteTracePageVo`）：

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `pageNo` / `pageSize` | String | 分页参数 |
| `totalSize` / `totalPages` | String | 总条数 / 总页数 |
| `records` | `KnowledgeRouteTraceItemVo[]` | 追踪记录 |

`KnowledgeRouteTraceItemVo`：

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `id` | String | 记录 ID |
| `conversationId` / `exchangeId` | String | 会话 / 轮次 ID |
| `question` / `rewriteQuestion` | String | 原始问题 / 改写后问题 |
| `mode` | String | 提问模式 |
| `topScopesJson` | String | Top 命中知识域（JSON） |
| `topTopicsJson` | String | Top 命中主题（JSON） |
| `topDocumentsJson` | String | Top 命中文档（JSON） |
| `selectedDocumentId` | String | 用户实际选择的文档 ID |
| `hitSelectedDocument` | String | 系统推荐是否命中用户选择 |
| `confidence` | String | 路由置信度 |
| `routeStatus` | String | 路由状态 |
| `errorMsg` | String | 错误信息 |
| `createTime` | String | 创建时间 |

---

## 6. 数据字典（枚举）

### 6.1 对话类枚举

**提问模式 `ChatQueryMode`**（请求字段 `chatMode`，传枚举名，不区分大小写）：

| 枚举名 | code | 含义 |
| :--- | :--- | :--- |
| `DOCUMENT` | 1 | 当前文档问答（必传 selectedDocumentId） |
| `OPEN_CHAT` | 2 | 开放式提问（走 ReAct Agent，禁止传 selectedDocumentId） |
| `AUTO_DOCUMENT` | 3 | 自动知识问答（知识路由自动选文档，禁止传 selectedDocumentId） |

**轮次状态 `ChatTurnStatus`**（`latestTurnStatus`、`turnStatus` 过滤参数）：

| 枚举名 | code | 含义 |
| :--- | :--- | :--- |
| `RUNNING` | 1 | 进行中 |
| `COMPLETED` | 2 | 已完成 |
| `FAILED` | 3 | 失败 |
| `STOPPED` | 4 | 已停止 |

**执行模式 `ExecutionMode`**（`executionMode` 字段，前置编排器的执行器选择结果）：

| 枚举名 | 含义 |
| :--- | :--- |
| `GRAPH_ONLY` | 仅 Neo4j 结构图查询 |
| `GRAPH_THEN_EVIDENCE` | 图查询 + 证据检索 |
| `RETRIEVAL` | RAG 混合检索问答 |
| `REACT_AGENT` | ReAct Agent 开放执行 |
| `CLARIFICATION` | 歧义澄清追问 |

### 6.2 文档状态类枚举

**解析状态 `DocumentParseStatusEnum`**：

| code | 含义 |
| :--- | :--- |
| 1 | 待解析 |
| 2 | 解析中 |
| 3 | 解析成功 |
| 4 | 解析失败 |

**策略状态 `DocumentStrategyStatusEnum`**：

| code | 含义 |
| :--- | :--- |
| 1 | 待推荐 |
| 2 | 已推荐 |
| 3 | 已确认 |
| 4 | 已失效 |

**索引状态 `DocumentIndexStatusEnum`**：

| code | 含义 |
| :--- | :--- |
| 1 | 待构建 |
| 2 | 构建中 |
| 3 | 构建成功 |
| 4 | 构建失败 |

**文件类型 `DocumentFileTypeEnum`**：

| code | 含义 |
| :--- | :--- |
| 1 | PDF |
| 2 | DOC |
| 3 | DOCX |
| 4 | TXT |
| 5 | MD |
| 6 | HTML |

### 6.3 切块策略类型 `DocumentStrategyTypeEnum`

| code | 策略 | 角色 |
| :--- | :--- | :--- |
| 1 | 基于文档结构切块（STRUCTURE） | 主策略 |
| 2 | 递归分块（RECURSIVE） | 兜底策略 |
| 3 | 语义分块（SEMANTIC） | 优化策略 |
| 4 | 大模型智能切块（LLM） | 增强策略 |

### 6.4 执行阶段编码 `ConversationTraceStageCode`

阶段追踪（`stageTraces[].stageCode`）与性能基准（`stageCode`）的取值：

| stageCode | 阶段名称 | stageOrder |
| :--- | :--- | :--- |
| `MEMORY` | 会话记忆 | 10 |
| `INTENT` | 意图分析 | 20 |
| `REWRITE` | 问题改写 | 30 |
| `ROUTE` | 路由判定 | 40 |
| `GRAPH_QUERY` | 结构图查询 | 45 |
| `RAG_RETRIEVE` | RAG 检索 | 50 |
| `EVIDENCE_BUDGET` | 证据评估与预算控制 | 60 |
| `ANSWER_GENERATE` | 回答生成 | 70 |
| `REACT_AGENT` | ReAct Agent | 75 |
| `RECOMMENDATION` | 推荐问题 | 80 |
| `FINALIZE` | 收尾归档 | 90 |

---

## 7. 核心响应对象结构

### 7.1 `ConversationSessionView`（会话视图）

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `conversationId` | String | 会话 ID |
| `running` | boolean | 是否正在执行 |
| `checkpointCount` | int | Agent Checkpoint 数量 |
| `messageCount` | int | 消息条数（问+答） |
| `latestUserMessage` | String | 最新用户消息 |
| `latestAssistantMessage` | String | 最新助手回答 |
| `latestExchangeId` | Long | 最新轮次 ID |
| `latestTurnStatus` | String | 最新轮次状态（ChatTurnStatus 枚举名） |
| `latestTurnErrorMessage` | String | 最新轮次错误信息 |
| `chatMode` | ChatQueryMode | 当前提问模式 |
| `selectedDocumentId` | String | 选定文档 ID（字符串） |
| `selectedDocumentName` | String | 选定文档名称 |
| `createdAt` / `updatedAt` | Instant | 创建 / 更新时间 |
| `exchanges` | `ConversationExchangeView[]` | 轮次列表（仅会话详情接口返回，列表接口为空） |
| `memorySummary` | ConversationMemorySummaryView | 会话记忆摘要（仅会话详情接口返回，列表接口为 null） |

### 7.2 `ConversationExchangeView`（轮次视图）

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `exchangeId` | long | 轮次 ID |
| `question` | String | 用户问题 |
| `answer` | String | 助手回答（全文） |
| `thinkingSteps` | `String[]` | 思考步骤列表 |
| `references` | `SearchReference[]` | 引用来源（结构见 3.1） |
| `recommendations` | `String[]` | 推荐追问问题 |
| `usedTools` | `String[]` | 本次使用的工具名 |
| `debugTrace` | ChatDebugTrace | 调试追踪（编排与调用明细，见 7.5） |
| `status` | ChatTurnStatus | 轮次状态 |
| `errorMessage` | String | 错误信息 |
| `firstResponseTimeMs` | Long | 首字响应耗时（毫秒） |
| `totalResponseTimeMs` | Long | 总响应耗时（毫秒） |
| `createTime` / `editTime` | Date | 创建 / 编辑时间 |

### 7.3 `ConversationTraceStageView`（阶段追踪视图）

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `stageId` / `parentStageId` | long / Long | 阶段 ID / 父阶段 ID |
| `traceId` | String | 追踪 ID |
| `stageCode` | String | 阶段编码（见 6.4） |
| `stageName` | String | 阶段名称 |
| `stageOrder` / `stageLevel` | Integer | 阶段顺序 / 层级 |
| `executionMode` | String | 执行模式 |
| `stageState` | String | 阶段状态 |
| `startTime` / `endTime` | Instant | 起止时间 |
| `durationMs` | Long | 耗时（毫秒） |
| `summaryText` | String | 阶段摘要 |
| `errorMessage` | String | 错误信息 |
| `snapshot` | Map | 阶段快照（键值随阶段不同，如路由结果、检索统计等） |

### 7.4 `DocumentListItemVo`（文档列表项）

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `documentId` | Long | 文档 ID |
| `documentName` | String | 文档名称 |
| `originalFileName` | String | 原始文件名 |
| `fileType` / `fileTypeName` | Integer / String | 文件类型（见 6.2） |
| `fileSize` | Long | 文件大小（字节） |
| `charCount` / `tokenCount` | Integer | 字符数 / Token 数 |
| `parseStatus` / `parseStatusName` | Integer / String | 解析状态 |
| `strategyStatus` / `strategyStatusName` | Integer / String | 策略状态 |
| `indexStatus` / `indexStatusName` | Integer / String | 索引状态 |
| `parseErrorMsg` | String | 解析错误信息 |
| `knowledgeScopeCode` / `knowledgeScopeName` | String | 知识域编码 / 名称 |
| `businessCategory` | String | 业务分类 |
| `documentTags` | String | 文档标签 |
| `currentPlanId` | Long | 当前策略方案 ID |
| `lastIndexTaskId` | Long | 最近索引构建任务 ID |
| `latestTaskId` / `latestTaskType` / `latestTaskTypeName` | Long / Integer / String | 最近任务 ID / 类型 |
| `latestTaskStatus` / `latestTaskStatusName` | Integer / String | 最近任务状态 |
| `createTime` / `editTime` | Date | 创建 / 编辑时间 |

### 7.5 `ChatDebugTrace`（调试追踪，`exchange.debugTrace`）

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `executionMode` | String | 执行模式（ExecutionMode 枚举名） |
| `chatMode` | ChatQueryMode | 提问模式 |
| `originalQuestion` | String | 原始问题 |
| `rewriteQuestion` | String | 改写后问题 |
| `rewriteSubQuestions` | `String[]` | 改写拆分的子问题 |
| `retrievalQuestion` | String | 检索用问题 |
| `agentQuestion` | String | Agent 执行用问题 |
| `navigationDecision` | Object | 文档结构导航决策（`navigationAction`、`structureAnchor`、`itemAnchor`、`retrievalPlan`、`queryContextHints` 等） |
| `historySummary` / `longTermSummary` | String | 历史摘要 / 长期摘要 |
| `recentHistoryTranscript` / `answerRecentTranscript` | String | 最近历史 / 回答用最近上下文 |
| `answerHistoryContext` | String | 回答历史上下文渲染文本 |
| `answerHistoryFollowUpQuestion` | boolean | 是否追问型问题 |
| `historyCompressionApplied` | boolean | 是否应用了记忆压缩 |
| `historyCoveredExchangeId` / `historyCoveredExchangeCount` / `historyCompressionCount` | Long / Integer / Integer | 摘要覆盖信息 |
| `currentDateText` | String | 当前日期文本（如 "2026-09-12（星期六）"） |
| `requiresFreshSearch` | boolean | 是否需要联网时效性搜索 |
| `requiresCurrentDateAnchoring` | boolean | 是否需要日期锚定 |
| `retrievalSubQuestions` | `String[]` | 检索子问题列表 |
| `selectedDocumentId` / `selectedTaskId` | Long | 选定文档 / 任务 ID |
| `retrievalNotes` | `String[]` | 检索备注 |
| `usedChannels` | `String[]` | 使用的检索通道 |
| `toolTraces` | `ChatToolTrace[]` | 工具调用记录 |
| `modelUsageTraces` | `ChatModelUsageTrace[]` | 模型调用与 Token 消耗记录 |
| `limitStats` | ChatLimitStats | 调用限制统计 |
| `ragSystemPrompt` / `ragUserPrompt` | String | RAG 阶段实际注入的 System / User Prompt |
| `noEvidenceReply` | String | 无证据短路回复内容 |

`ChatToolTrace`：`toolName`、`status`、`inputSummary`、`effectiveInput`、`outputSummary`、`errorMessage`、`referenceCount`、`topic`、`durationMs`。

`ChatModelUsageTrace`：`stageName`、`provider`、`model`、`promptTokens`、`completionTokens`、`totalTokens`、`estimatedCost`、`durationMs`、`status`。

`ChatLimitStats`：`modelCallsUsed`、`modelCallsRunLimit`、`modelCallsThreadLimit`、`toolCallsUsed`、`toolCallsRunLimit`、`toolCallsThreadLimit`、`limitTriggered`、`limitReason`。

---

## 附录 A. 接口清单速查表

| # | 方法 | 路径 | 说明 | 鉴权 |
| :--- | :--- | :--- | :--- | :--- |
| 1 | POST | `/admin/auth/login` | 管理员登录 | 否 |
| 2 | POST | `/admin/auth/logout` | 登出 | 否 |
| 3 | GET | `/admin/auth/me` | 当前用户信息 | 是 |
| 4 | POST | `/api/chat/stream` | 对话流式接口（SSE） | 否 |
| 5 | POST | `/api/chat/document/options` | 可选文档列表 | 否 |
| 6 | POST | `/api/chat/session/stop` | 停止生成 | 否 |
| 7 | POST | `/api/chat/session/detail` | 会话详情 | 否 |
| 8 | POST | `/api/chat/exchange/detail` | 轮次详情 | 否 |
| 9 | POST | `/api/chat/session/list` | 会话列表 | 否 |
| 10 | POST | `/api/chat/session/reset` | 重置会话 | 否 |
| 11 | POST | `/api/chat/session/summary/rebuild` | 重建记忆摘要 | 否 |
| 12 | POST | `/api/chat/exchange/retrieval/results` | 检索结果明细 | 否 |
| 13 | POST | `/api/chat/exchange/channel/executions` | 通道执行统计 | 否 |
| 14 | POST | `/api/chat/stage/benchmarks` | 阶段性能基准 | 否 |
| 15 | POST | `/manage/document/upload` | 上传文档（multipart） | 是 |
| 16 | POST | `/manage/document/page/query` | 分页查询文档 | 是 |
| 17 | POST | `/manage/document/detail/query` | 文档详情 | 是 |
| 18 | POST | `/manage/document/delete` | 删除文档 | 是 |
| 19 | POST | `/manage/document/strategy/plan/query` | 策略推荐查询 | 是 |
| 20 | POST | `/manage/document/strategy/confirm` | 确认策略方案 | 是 |
| 21 | POST | `/manage/document/index/build` | 执行索引构建 | 是 |
| 22 | POST | `/manage/document/chunk/query` | chunk 列表 | 是 |
| 23 | POST | `/manage/document/chunk/detail/query` | chunk 详情 | 是 |
| 24 | POST | `/manage/document/task/log/query` | 任务日志 | 是 |
| 25 | POST | `/manage/knowledge/scope/save` | 保存知识域 | 是 |
| 26 | POST | `/manage/knowledge/scope/delete` | 删除知识域 | 是 |
| 27 | POST | `/manage/knowledge/scope/list` | 知识域列表 | 是 |
| 28 | POST | `/manage/knowledge/topic/save` | 保存知识主题 | 是 |
| 29 | POST | `/manage/knowledge/topic/delete` | 删除知识主题 | 是 |
| 30 | POST | `/manage/knowledge/topic/list` | 主题列表 | 是 |
| 31 | POST | `/manage/knowledge/document/profile/detail` | 文档画像详情 | 是 |
| 32 | POST | `/manage/knowledge/document/profile/regenerate` | 重新生成画像 | 是 |
| 33 | POST | `/manage/knowledge/document/profile/batch/regenerate` | 批量重新生成画像 | 是 |
| 34 | POST | `/manage/knowledge/topic/document/list` | 主题文档关联列表 | 是 |
| 35 | POST | `/manage/knowledge/topic/document/save` | 保存主题文档关联 | 是 |
| 36 | POST | `/manage/knowledge/topic/document/remove` | 移除主题文档关联 | 是 |
| 37 | POST | `/manage/knowledge/route/trace/page/query` | 路由追踪分页 | 是 |

## 附录 B. 典型调用流程示例

**文档入库到可检索的完整流程**（需要先登录获取 Token）：

```bash
BASE=http://127.0.0.1:9082

# 1. 登录
curl -X POST "$BASE/admin/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"REDACTED-ADMIN-PASSWORD"}'
# => data.token

TOKEN=<上一步返回的token>

# 2. 上传文档（file 为文件，meta 为 JSON 字符串分部）
curl -X POST "$BASE/manage/document/upload" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@./产品手册.pdf" \
  -F 'meta={"documentName":"XX-200智能网关产品手册","knowledgeScopeCode":"product","businessCategory":"产品文档"}'
# => data.documentId / data.taskId（解析任务异步执行）

# 3. 查询策略推荐（轮询直到 planReady=true）
curl -X POST "$BASE/manage/document/strategy/plan/query" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"documentId":1024}'

# 4. 确认策略方案
curl -X POST "$BASE/manage/document/strategy/confirm" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"documentId":1024,"basePlanId":501,"parentSteps":[{"stepNo":1,"strategyType":1}],"childSteps":[{"stepNo":1,"strategyType":1},{"stepNo":2,"strategyType":2}]}'

# 5. 构建索引
curl -X POST "$BASE/manage/document/index/build" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"documentId":1024,"planId":502}'

# 6. 观察任务日志直到构建成功
curl -X POST "$BASE/manage/document/task/log/query" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"taskId":9001}'
```

**发起一轮流式对话**（SSE，可用 curl 或浏览器 EventSource/Fetch-Stream 消费）：

```bash
curl -N -X POST "$BASE/api/chat/stream" \
  -H "Content-Type: application/json" \
  -d '{"question":"退款规则是什么？","chatMode":"AUTO_DOCUMENT"}'
```

返回流示例（`data:` 行为 JSON 事件）：

```
data:{"type":"thinking","content":"正在分析问题上下文。","timestamp":"...","conversationId":"...","exchangeId":1}
data:{"type":"thinking","content":"正在检索知识库。","timestamp":"...","conversationId":"...","exchangeId":1}
data:{"type":"text","content":"根据文档，","timestamp":"...","conversationId":"...","exchangeId":1}
data:{"type":"text","content":"退款规则如下……","timestamp":"...","conversationId":"...","exchangeId":1}
data:{"type":"reference","content":[{...SearchReference...}],"count":2,"timestamp":"...","conversationId":"...","exchangeId":1}
data:{"type":"recommend","content":["退款审批流程怎么走？","退款一般多久到账？"],"count":2,"timestamp":"...","conversationId":"...","exchangeId":1}
```
