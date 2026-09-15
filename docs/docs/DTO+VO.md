# Nexus Agent 项目 DTO / VO 分析

| 项目 | 说明 |
| :--- | :--- |
| 分析日期 | 2026-09-12 |
| 基于代码 | `master` 分支（commit `7ef51a5`），源码逐类分析 |
| 分析范围 | 全仓库（主业务模块 + 公共模块 + ai-example 示例工程） |

---

## 目录

- [1. 总览与分层约定](#1-总览与分层约定)
- [2. 公共模块（nexus-agent-common-web）](#2-公共模块nexus-agent-common-web)
- [3. 主业务模块 DTO（入参对象，30 个）](#3-主业务模块-dto入参对象30-个)
- [4. 主业务模块 VO（出参对象，28 个）](#4-主业务模块-vo出参对象28-个)
- [5. model 包的视图对象与内部模型（50 个）](#5-model-包的视图对象与内部模型50-个)
- [6. ai-example 示例工程的传输对象（record 风格）](#6-ai-example-示例工程的传输对象record-风格)
- [7. 设计特点总结](#7-设计特点总结)

---

## 1. 总览与分层约定

### 1.1 命名与分层规则

本项目按照经典的 Controller → Service → Mapper 分层，对"传输对象"做了四类划分：

| 类别 | 包位置 | 命名后缀 | 职责 | 典型技术 |
| :--- | :--- | :--- | :--- | :--- |
| **DTO**（入参） | `xxx/dto/` | `*Dto`（个别 `*Request`） | 承接 Controller 的请求体，携带 `@NotNull` / `@NotBlank` 校验注解 | Lombok `@Data` + Jakarta Validation |
| **VO**（出参） | `xxx/vo/` | `*Vo` | Controller 返回给前端的视图对象，按前端需要裁剪字段、附加名称类字段（`xxxName`） | Lombok `@Data` |
| **View / 领域模型** | `xxx/model/` | `*View`、无后缀 | 对话侧的响应视图（`*View`）与检索编排/ES/图查询的内部领域对象 | Lombok `@Data` / `@Builder` |
| **Record 传输对象** | ai-example 各模块 `model/`、`dto/`、Controller 内部类 | record | 示例工程的轻量请求/响应/LLM 结构化输出载体 | Java `record` |

统一响应包装由公共模块的 `ApiResponse<T>`（`{code, message, data}`，`code=0` 成功）承担，DTO/VO 都作为 `data` 的载荷。

### 1.2 数量统计

| 位置 | 包 | DTO（入参） | VO（出参） | model（视图/内部） |
| :--- | :--- | :--- | :--- | :--- |
| nexus-agent-common-web | `org.javaup.database.dto` / `.page` | 1（BasePageDto） | 1（PageVo，位于 `page` 包） | — |
| nexus-agent-business-chat | `org.javaup.ai.auth.dto` / `.vo` | 1 | 2 | — |
| nexus-agent-business-chat | `org.javaup.ai.chatagent.dto` / `.vo` | 5 | 3 | 18（`chatagent/model` 树） |
| nexus-agent-business-chat | `org.javaup.ai.manage.dto` / `.vo` | 24 | 23 | 16（`manage/model` 树） |
| nexus-agent-business-chat | `org.javaup.ai.chatagent.rag.model` | — | — | 16 |
| ai-example（各示例模块） | 散布于 `model/`、`dto/`、Controller 内部 | — | — | 约 13 个 record |
| **合计** | | **31** | **29** | **约 63（含示例工程 record）** |

### 1.3 技术特征

- 所有业务 DTO/VO 均使用 **Lombok**（`@Data`、`@NoArgsConstructor`、`@AllArgsConstructor`，个别 `@Builder`）生成访问器。
- 入参校验统一使用 **Jakarta Validation**（`@NotNull` / `@NotBlank` / `@NotEmpty` / `@Valid`），校验失败由公共 `DefaultExceptionHandler` 捕获为 `{"code":10054, "data":[{argumentName, message}]}`。
- 仅公共模块 `BasePageDto` 使用了 **Swagger 注解**（`@Schema`），主业务模块的接口描述依赖 `@Operation(summary=...)` 放在 Controller 上。
- 主业务模块实体为**可变 POJO**；ai-example 示例工程统一改用**不可变 Java record**，两种风格并存。

---

## 2. 公共模块（nexus-agent-common-web）

### 2.1 `BasePageDto` — 分页请求基类（dto 包）

| 字段 | 类型 | 校验 | 说明 |
| :--- | :--- | :--- | :--- |
| `pageNumber` | Integer | `@NotNull` | 页码（Swagger 标注 REQUIRED） |
| `pageSize` | Integer | `@NotNull` | 每页大小（Swagger 标注 REQUIRED） |

供其它子项目继承使用；主业务模块的 `DocumentPageQueryDto`、`ConversationSessionListQueryDto` 等**并未继承它**，而是自行内联分页字段（见 [7.2](#72-可改进点)）。

### 2.2 `PageVo<T>` — 通用分页响应（page 包，VO 角色）

泛型分页包装，实现 `Serializable`：

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `pageNum` | long | 页码 |
| `pageSize` | long | 每页大小 |
| `totalSize` | long | 总条数 |
| `list` | `List<T>` | 当前页数据 |

### 2.3 `ApiResponse<T>` — 统一响应包装（frame 模块）

虽不在 dto/vo 包，但它是所有 DTO/VO 的外层容器：`code`（Integer，0=成功）、`message`（String）、`data`（T）。静态工厂：`ok()` / `ok(t)` / `error(...)`（通用错误码 `-100`）。

---

## 3. 主业务模块 DTO（入参对象，30 个）

### 3.1 后台认证（auth/dto，1 个）

#### `AdminLoginRequest`

> 注：该类位于 `dto` 包但命名用 `*Request` 后缀，是全项目唯一不带 `Dto` 后缀的入参对象。

| 字段 | 类型 | 校验 | 说明 |
| :--- | :--- | :--- | :--- |
| `username` | String | `@NotBlank("请输入账号")` | 登录账号 |
| `password` | String | `@NotBlank("请输入密码")` | 登录密码 |

### 3.2 智能对话（chatagent/dto，5 个）

全部字段为 String 类型，无校验注解的均可缺省。

#### `ChatRequestDto` — 发起对话（SSE 接口入参）

| 字段 | 类型 | 校验 | 说明 |
| :--- | :--- | :--- | :--- |
| `question` | String | `@NotBlank` | 用户问题 |
| `conversationId` | String | — | 会话 ID，为空则服务端生成 UUID |
| `chatMode` | String | `@NotBlank` | 提问模式：`DOCUMENT` / `AUTO_DOCUMENT` / `OPEN_CHAT`（服务端按枚举名解析） |
| `selectedDocumentId` | String | — | 选定文档 ID；`DOCUMENT` 模式必传，其余模式禁止传（业务校验在 Service 层） |

#### `ConversationIdentityDto` — 会话身份（stop / detail / reset / summary 复用）

| 字段 | 类型 | 校验 | 说明 |
| :--- | :--- | :--- | :--- |
| `conversationId` | String | `@NotBlank` | 会话 ID |

> 这是一个典型的**复用型入参**：停止生成、会话详情、重置会话、重建摘要 4 个接口共用同一个 DTO。

#### `ConversationExchangeDetailQueryDto` — 轮次详情查询

| 字段 | 类型 | 校验 | 说明 |
| :--- | :--- | :--- | :--- |
| `conversationId` | String | `@NotBlank` | 会话 ID |
| `exchangeId` | String | `@NotBlank` | 轮次 ID |

#### `ConversationSessionListQueryDto` — 会话列表查询

| 字段 | 类型 | 校验 | 说明 |
| :--- | :--- | :--- | :--- |
| `keyword` | String | — | 关键字 |
| `chatMode` | String | — | 模式过滤，枚举名或 `ALL` |
| `turnStatus` | String | — | 轮次状态过滤，枚举名或 `ALL` |
| `pageNo` | String | — | 页码（字符串，服务端解析，默认 1） |
| `pageSize` | String | — | 每页条数（默认 20） |

#### `RetrievalObserveQueryDto` — 检索观测查询

| 字段 | 类型 | 校验 | 说明 |
| :--- | :--- | :--- | :--- |
| `conversationId` | String | `@NotBlank` | 会话 ID |
| `exchangeId` | String | `@NotNull` | 轮次 ID（服务端 `Long.parseLong` 转换） |

### 3.3 文档与知识管理（manage/dto，24 个）

#### 文档生命周期类

| 类 | 字段（类型：校验） | 用途 |
| :--- | :--- | :--- |
| `DocumentUploadDto` | `documentName`、`operatorId`、`knowledgeScopeCode`、`knowledgeScopeName`、`businessCategory`、`documentTags`（均 String，无校验） | 上传文档时以 `meta` 分部（JSON）随 multipart 提交 |
| `DocumentPageQueryDto` | `pageNo`、`pageSize`（Integer）；`keyword`（String） | 文档分页列表 |
| `DocumentDetailQueryDto` | `documentId`（Long：`@NotNull`） | 文档详情 |
| `DocumentDeleteDto` | `documentId`（String：`@NotBlank`） | 删除文档 |
| `DocumentTaskLogQueryDto` | `taskId`（Long：`@NotNull`）；`pageNo`、`pageSize`（Integer） | 任务执行日志 |

#### 切块策略类

| 类 | 字段（类型：校验） | 用途 |
| :--- | :--- | :--- |
| `DocumentStrategyPlanQueryDto` | `documentId`（Long：`@NotNull`） | 查询系统推荐的切块策略方案 |
| `DocumentStrategyConfirmDto` | `documentId`、`basePlanId`（Long：`@NotNull`）；`adjustNote`（String）；`operatorId`（Long）；`parentSteps`、`childSteps`（`List<DocumentStrategyStepItemDto>`：`@Valid @NotEmpty`） | 确认（可调整）父/子块两条流水线 |
| `DocumentStrategyStepItemDto` | `stepNo`（Integer）；`strategyType`（Integer：`@NotNull`，1结构/2递归/3语义/4LLM） | 策略步骤明细，是唯一**嵌套集合明细 DTO** |
| `DocumentIndexBuildDto` | `documentId`、`planId`（Long：`@NotNull`）；`operatorId`（Long） | 触发索引构建 |

#### 块（chunk）查询类

| 类 | 字段（类型：校验） | 用途 |
| :--- | :--- | :--- |
| `DocumentChunkQueryDto` | `documentId`（Long：`@NotNull`）；`taskId`（Long）；`pageNo`、`pageSize`（Integer） | 分页查询 Child 块 |
| `DocumentChunkDetailQueryDto` | `documentId`、`chunkId`（Long：`@NotNull`）；`taskId`（Long） | 单块详情（含父块与兄弟块） |

#### 文档画像类

| 类 | 字段（类型） | 用途 |
| :--- | :--- | :--- |
| `DocumentProfileDetailQueryDto` | `documentId`（String） | 查询画像 |
| `DocumentProfileRegenerateDto` | `documentId`、`operatorId`（String） | 单个重新生成画像 |
| `DocumentProfileBatchRegenerateDto` | `documentIds`（`List<String>`）、`operatorId`（String） | 批量重新生成画像 |

#### 知识域 / 主题 / 关联类（全部字段 String，无校验注解，业务约束在 Service 层）

| 类 | 字段 |
| :--- | :--- |
| `KnowledgeScopeSaveDto` | `id`、`scopeCode`、`scopeName`、`parentScopeCode`、`description`、`aliases`、`examples`、`sortOrder`、`operatorId` |
| `KnowledgeScopeDeleteDto` | `scopeCode`、`operatorId` |
| `KnowledgeTopicQueryDto` | `scopeCode` |
| `KnowledgeTopicSaveDto` | `id`、`topicCode`、`topicName`、`scopeCode`、`description`、`aliases`、`examples`、`answerShape`、`executionPreference`、`sortOrder`、`operatorId` |
| `KnowledgeTopicDeleteDto` | `topicCode`、`operatorId` |
| `TopicDocumentRelationListQueryDto` | `topicCode` |
| `TopicDocumentRelationSaveDto` | `topicCode`、`documentId`、`relationScore`、`relationSource`、`reason`、`operatorId` |
| `TopicDocumentRelationRemoveDto` | `topicCode`、`documentId`、`operatorId` |
| `KnowledgeRouteTraceQueryDto` | `conversationId`、`mode`、`routeStatus`、`pageNo`、`pageSize` |

#### 测试类

| 类 | 字段 | 说明 |
| :--- | :--- | :--- |
| `TestDto` | `id`（Long：`@NotNull`） | 遗留的联调测试入参，无业务 Controller 引用价值 |

---

## 4. 主业务模块 VO（出参对象，28 个）

### 4.1 后台认证（auth/vo，2 个）

| 类 | 字段（类型） | 说明 |
| :--- | :--- | :--- |
| `AdminLoginVo` | `username`（String）、`token`（String）、`expireMinutes`（Long） | 登录成功返回 JWT 与有效期 |
| `AdminProfileVo` | `username`（String） | 当前登录用户 |

### 4.2 智能对话（chatagent/vo，3 个）

| 类 | 字段（类型） | 说明 |
| :--- | :--- | :--- |
| `ConversationStopVo` | `conversationId`（String）、`stopped`（boolean）、`message`（String） | 停止生成结果；`stopped=false` 时 `message` 说明原因（无执行中会话 / 已结束 / 被新执行接管） |
| `ConversationResetVo` | `conversationId`、`stoppedRunningTask`（boolean）、`removedDialogueCount`、`removedExchangeCount`、`removedCheckpointCount`（int）、`message` | 重置会话结果，汇报各类清理数量 |
| `ConversationSessionListVo` | `pageNo`、`pageSize`、`totalSize`、`totalPages`（long）、`sessions`（`List<ConversationSessionView>`） | 会话分页列表；列表项不含轮次明细 |

> 对话侧真正的响应主体（`ConversationSessionView`、`ConversationExchangeView`、`SearchReference`、`ChatDebugTrace` 等）放在 `chatagent/model` 包，见第 5 章。

### 4.3 文档管理（manage/vo，14 个）

#### 上传与删除

| 类 | 字段（类型） | 说明 |
| :--- | :--- | :--- |
| `DocumentUploadVo` | `documentId`、`taskId`（Long）、`documentName`（String）、`parseStatus`、`strategyStatus`、`indexStatus`（Integer） | 上传结果：文档 ID + 解析任务 ID + 三状态 |
| `DocumentDeleteVo` | `documentId`（Long）、`documentName`（String） | 删除结果回显 |

#### 列表与详情（核心大对象）

`DocumentListItemVo` — 文档列表/详情条目，28 个字段，是全项目最大的 VO：

| 字段 | 类型 | 说明 |
| :--- | :--- | :--- |
| `documentId` | Long | 文档 ID |
| `documentName` / `originalFileName` | String | 文档名 / 原始文件名 |
| `fileType` / `fileTypeName` | Integer / String | 文件类型 code + 名称 |
| `fileSize` | Long | 文件大小（字节） |
| `charCount` / `tokenCount` | Integer | 字符数 / Token 数 |
| `parseStatus` / `parseStatusName` | Integer / String | 解析状态 |
| `strategyStatus` / `strategyStatusName` | Integer / String | 策略状态 |
| `indexStatus` / `indexStatusName` | Integer / String | 索引状态 |
| `parseErrorMsg` | String | 解析错误 |
| `knowledgeScopeCode` / `knowledgeScopeName` | String | 知识域 |
| `businessCategory` / `documentTags` | String | 业务分类 / 标签 |
| `currentPlanId` / `lastIndexTaskId` | Long | 当前方案 / 最近索引任务 |
| `latestTaskId` / `latestTaskType` / `latestTaskTypeName` / `latestTaskStatus` / `latestTaskStatusName` | Long / Integer / String / Integer / String | 最近任务概要 |
| `createTime` / `editTime` | Date | 时间戳 |

#### 分页包装

| 类 | 字段 | 说明 |
| :--- | :--- | :--- |
| `DocumentPageQueryVo` | `pageNo`、`pageSize`（Integer）、`total`（Long）、`records`（`List<DocumentListItemVo>`） | 文档分页 |

#### 策略方案族（5 个，父子结构）

```
DocumentStrategyPlanQueryVo（查询结果）
 ├─ plan: DocumentStrategyPlanVo（方案）
 │    ├─ parentPipeline / childPipeline: DocumentStrategyPipelineVo（流水线）
 │    │    └─ steps: List<DocumentStrategyStepVo>（步骤）
 └─ （确认后的回执 DocumentStrategyConfirmVo 结构类似，多 normalized 字段）
```

| 类 | 关键字段 |
| :--- | :--- |
| `DocumentStrategyPlanQueryVo` | `documentId`、`documentName`、三状态（code+Name）、`parseErrorMsg`、`planReady`（Boolean）、`plan` |
| `DocumentStrategyPlanVo` | `planId`、`planVersion`、`planSource/Name`、`planStatus/Name`、`strategySnapshot`、`recommendReason`、两条 `pipeline` |
| `DocumentStrategyConfirmVo` | `documentId`、`planId`、`planVersion`、`strategyStatus/Name`、`normalized`（Boolean，服务端是否归一化修正）、两条 `pipeline` |
| `DocumentStrategyPipelineVo` | `pipelineType/Name`（PARENT/CHILD）、`strategySnapshot`、`steps` |
| `DocumentStrategyStepVo` | `stepNo`、`pipelineType/Name`、`strategyType/Name`、`strategyRole/Name`（主/优化/兜底/增强）、`sourceType/Name`（系统推荐/用户新增/用户保留）、`executeStatus/Name`、`recommendReason` |

#### 索引与任务日志

| 类 | 字段（类型） |
| :--- | :--- |
| `DocumentIndexBuildVo` | `documentId`、`taskId`、`taskType/Name`、`taskStatus/Name`、`indexStatus/Name` |
| `DocumentTaskLogQueryVo` | `taskId`、`documentId`、`taskType/Name`、`taskStatus/Name`、`currentStage/Name`、`startTime`/`finishTime`（Date）、`costMillis`（Long）、`errorCode`、`errorMsg`、`total`（Long）、`logs`（List） |
| `DocumentTaskLogVo` | `id`、`stageType/Name`、`eventType/Name`、`logLevel/Name`、`content`、`detailJson`、`createTime`（Date） |

#### 块（chunk）族（4 个）

| 类 | 关键字段 |
| :--- | :--- |
| `DocumentChunkQueryVo` | `documentId`、`taskId`、`planId`、`pageNo`、`pageSize`、`total`、`records`（`List<DocumentChunkItemVo>`） |
| `DocumentChunkItemVo` | `chunkId`、`parentBlockId/No`、`parentChildCount`、`parentStartChunkNo`、`parentEndChunkNo`、`chunkNo`、`sectionPath`、`sourceType/Name`（1原文/2补全）、`charCount`、`tokenCount`、`vectorStatus/Name`、`chunkText` |
| `DocumentChunkDetailVo` | `documentId`、`taskId`、`planId`、`chunk`、`parentBlock`（DocumentParentBlockItemVo）、`siblingChunks`（List） |
| `DocumentParentBlockItemVo` | `parentBlockId`、`parentBlockNo`、`sectionPath`、`sourceType/Name`、`charCount`、`tokenCount`、`childCount`、`startChunkNo`、`endChunkNo`、`parentText`（父块全文） |

### 4.4 知识管理（manage/vo，7 个）

| 类 | 字段（类型） | 说明 |
| :--- | :--- | :--- |
| `KnowledgeScopeItemVo` | `id`、`scopeCode`、`scopeName`、`parentScopeCode`、`description`、`aliases`、`examples`、`sortOrder`（全 String） | 知识域节点；保存接口同时用它做回显（入参回读） |
| `KnowledgeTopicItemVo` | `id`、`topicCode`、`topicName`、`scopeCode`、`description`、`aliases`、`examples`、`answerShape`、`executionPreference`、`sortOrder`（全 String） | 知识主题节点 |
| `TopicDocumentRelationItemVo` | `topicCode`、`documentId`、`documentName`、`knowledgeScopeCode`、`knowledgeScopeName`、`businessCategory`、`documentTags`、`relationScore`、`relationSource`、`reason`（全 String） | 主题-文档关联条目 |
| `DocumentProfileVo` | `documentId`、`documentSummary`、`documentType`、`coreTopics`、`exampleQuestions`、`graphFriendly`、`supportsGraphOutline`、`supportsItemLookup`、`supportsGraphAssist`、`profileSource`、`profileStatus`、`errorMsg`（全 String） | 文档画像（LLM 生成画像的回显） |
| `KnowledgeRouteTracePageVo` | `pageNo`、`pageSize`、`totalSize`、`totalPages`、`records`（全 String 包装） | 影子路由追踪分页 |
| `KnowledgeRouteTraceItemVo` | `id`、`conversationId`、`exchangeId`、`question`、`rewriteQuestion`、`mode`、`topScopesJson`、`topTopicsJson`、`topDocumentsJson`、`selectedDocumentId`、`hitSelectedDocument`、`confidence`、`routeStatus`、`errorMsg`、`createTime`（全 String） | 单条路由追踪（Top 命中为 JSON 字符串直传） |
| `TestVo` | `id`（Long） | 遗留测试出参，与 `TestDto` 配对 |

### 4.5 返回 Boolean 的接口

知识域删除、主题删除、主题文档移除 3 个接口直接返回 `ApiResponse<Boolean>`，未定义专用 VO。

---

## 5. model 包的视图对象与内部模型（50 个）

`model` 包不叫 dto/vo，但承担两类职责：**对话侧 API 响应视图**（直接序列化给前端）与**检索/ES/图查询的内部领域模型**（不直接出 API）。

### 5.1 `chatagent/model`（18 个）— 对话侧响应视图

#### 顶层（9 个 `*View` / 载体）

| 类 | 职责 | 关键字段 |
| :--- | :--- | :--- |
| `ConversationSessionView` | 会话视图（session/detail 与 session/list 的载体） | `conversationId`、`running`、`checkpointCount`、`messageCount`、`latestUserMessage/AssistantMessage`、`latestExchangeId`、`latestTurnStatus`、`chatMode`、`selectedDocumentId/Name`、`exchanges`、`memorySummary` |
| `ConversationExchangeView` | 轮次视图 | `exchangeId`、`question`、`answer`、`thinkingSteps`、`references`、`recommendations`、`usedTools`、`debugTrace`、`status`、`errorMessage`、`firstResponseTimeMs`、`totalResponseTimeMs` |
| `ConversationExchangeDetailView` | 轮次详情包装 | `conversationId` + `exchange` + `stageTraces` |
| `ConversationMemorySummaryView` | 会话记忆摘要视图 | `compressionApplied`、`coveredExchangeId/Count`、`compressionCount`、`summaryVersion`、`summaryText`、`summaryPayload` |
| `SearchReference` | 引用来源（SSE reference 事件 + 轮次引用） | `sourceType`（WEB/文档）、`title`、`url`、`snippet`、`documentId/Name`、`chunkId/No`、`parentBlockId/No`、`sectionPath`、`structureNodeId`、`score`、`channel`、`toolName` 等 22 字段；提供 `uniqueKey()` 去重键（PARENT: / DOCUMENT: / WEB:） |
| `KnowledgeDocumentOptionView` | 文档下拉选项 | `documentId`、`documentName`、`knowledgeScopeName`、`businessCategory`、`documentTags` |
| `RetrievalResultView` | 检索候选明细（观测） | 双通道分数（`originalScore`/`rrfScore`/`rerankScore`）、各级排名、`gatePassed`、`isElevated`、`isSelected`、`selectionReason`、块定位字段 |
| `ChannelExecutionView` | 检索通道执行统计（观测） | `channelType`、`recalledCount`、`acceptedCount`、`finalSelectedCount`、`avg/max/minScore`、`durationMs` |
| `StageBenchmarkView` | 阶段性能基准 | `stageCode`、P50/P90/P99、avg/max/min、`sampleCount` |

#### `debug/`（4 个）— 调试追踪族

| 类 | 关键字段 |
| :--- | :--- |
| `ChatDebugTrace` | 编排全量快照：`executionMode`、`originalQuestion`、`rewriteQuestion`、`rewriteSubQuestions`、`retrievalQuestion`、`navigationDecision`、各历史上下文字段、`requiresFreshSearch`、`retrievalSubQuestions`、`usedChannels`、`toolTraces`、`modelUsageTraces`、`limitStats`、`ragSystemPrompt`、`ragUserPrompt`、`noEvidenceReply` |
| `ChatToolTrace` | `toolName`、`status`、`inputSummary`、`effectiveInput`、`outputSummary`、`referenceCount`、`durationMs` |
| `ChatModelUsageTrace` | `stageName`、`provider`、`model`、`promptTokens`、`completionTokens`、`totalTokens`、`estimatedCost`、`durationMs` |
| `ChatLimitStats` | 模型/工具调用次数与 Run/Thread 上限、`limitTriggered`、`limitReason` |

#### `trace/`（3 个）— 执行阶段追踪

| 类 | 说明 |
| :--- | :--- |
| `ConversationTraceStageView` | 阶段视图：`stageId`、`parentStageId`、`stageCode/Name`、`stageOrder`、`stageLevel`、`stageState`、起止时间、`durationMs`、`summaryText`、`snapshot`（Map） |
| `ConversationTraceStageCode` | 枚举：MEMORY/INTENT/REWRITE/ROUTE/GRAPH_QUERY/RAG_RETRIEVE/EVIDENCE_BUDGET/ANSWER_GENERATE/REACT_AGENT/RECOMMENDATION/FINALIZE |
| `ConversationTraceStageState` | 阶段状态枚举 |

#### `memory/`（2 个）— 记忆结构

| 类 | 关键字段 |
| :--- | :--- |
| `ConversationSummaryPayload` | 结构化摘要：`summary`、`conversationGoal`、`stableFacts`、`userPreferences`、`resolvedPoints`、`pendingQuestions`、`retrievalHints` |
| `ConversationMemoryContext` | 记忆装配上下文（内部）：`assembledHistory`、`longTermSummary`、`recentTranscript`、`answerRecentTranscript`、`summaryPayload`、覆盖信息 |

### 5.2 `chatagent/rag/model`（16 个）— 前置编排内部模型

这一组是编排器五步决策链（改写 → 拆分 → 路由 → 歧义 → 执行计划）的中间产物，**不出 API**，通过 `ChatDebugTrace` 间接透出：

| 类 | 类型 | 职责 |
| :--- | :--- | :--- |
| `ConversationExecutionPlan` | @Data @Builder | **核心执行计划**：`mode`、改写/检索问题、子问题列表、历史上下文、导航决策、选定文档、澄清回复、无证据回复等 30+ 字段 |
| `ExecutionMode` | 枚举 | GRAPH_ONLY / GRAPH_THEN_EVIDENCE / RETRIEVAL / REACT_AGENT / CLARIFICATION（+废弃 RAG_CHAT） |
| `DocumentNavigationDecision` | @Data | 结构图导航决策：`navigationAction`（8 种枚举动作）、`structureAnchor`、`itemAnchor`、`retrievalPlan`、hints |
| `DocumentNavigationAction` | 枚举 | TOPIC_CONTINUE / TOPIC_SWITCH / FRESH_TOPIC / SIBLING_SECTION_SWITCH / CHILD_SECTION_DESCEND / ANCESTOR_SECTION_RETURN / ITEM_REFERENCE / SECTION_ADJACENCY_LOOKUP |
| `ConversationStructureAnchor` | @Data | 章节锚点：`rootSectionCode/Title`、`targetSectionHint`、`structureNodeId`、`canonicalPath`、`scopeMode` |
| `ConversationItemAnchor` | @Data | 条目锚点：`itemIndex`、`itemText`、`structureNodeId`、`canonicalPath` |
| `NavigationScopeMode` | 枚举 | NONE / SOFT / HARD_SECTION / HARD_ITEM / HARD_PARENT_WITH_SIBLINGS |
| `RetrievalQuestionPlan` | @Data | 检索问题计划：`retrievalQuestion` + `subQuestions` |
| `RagRewriteResult` | @Data | 问题改写结果：`rewrittenQuestion` + `subQuestions` + `rawModelOutput` |
| `RagRetrievalContext` | @Data | 检索上下文：`retrievalQuestion`、`subQuestionEvidenceList`、`retrievalNotes`、`usedChannels`；提供 `flattenReferences()` |
| `SubQuestionEvidence` | @Data | 单子问题证据：`subQuestionIndex`、`documents`、`references`、`channelTraces`、三类候选计数 |
| `SubQuestionChannelTrace` | @Data | 通道级统计：`channelName`、`recalledCount`、`acceptedCount` |
| `AnswerHistoryContext` | @Data @Builder | 回答历史上下文：`renderedText`、四类预算（total/recent/structured）、`followUpQuestion` |
| `HistoryPlanningContext` | @Data @Builder | 历史规划上下文：`conversationGoal`、`stableFacts`、`pendingQuestions`、`retrievalHints`、`queryContextHints` |
| `RagPromptAssemblyResult` | @Data | Prompt 组装结果：`systemPrompt`、`userPrompt`、预算、已渲染/被裁剪引用明细 |
| `EvidenceSatisfactionResult<T>` | @Data @Builder | 泛型证据满足度判定：`satisfied`、`notes`、`acceptedEvidence` |

### 5.3 `manage/model`（16 个）— 检索/索引/图查询内部模型

| 子包 | 类 | 职责 |
| :--- | :--- | :--- |
| 根 | `DocumentRetrieveRequest` | 检索请求：`question`、`retrievalQuery`、文档/任务 ID 列表、`topK`、`filters`、`queryContextHints` |
| 根 | `DocumentRetrieveFilters` | 检索过滤提示：文档名/业务分类/标签/章节路径/规范路径/结构节点/条目/年份 8 类 hints |
| 根 | `KnowledgeDocumentDescriptor` | 可检索文档描述符：`documentId`、`documentName`、`lastIndexTaskId`、知识域、分类、标签 |
| `es/` | `DocumentKeywordIndexRecord`、`DocumentNavigationIndexRecord`、`KnowledgeRouteIndexRecord` | Elasticsearch 倒排索引的写入记录（关键词检索、结构导航、知识路由三类索引） |
| `graph/` | `GraphSection`、`GraphSectionWithChildren`、`GraphSectionWithSiblings`、`GraphItem`、`GraphItemWithContext`、`GraphQueryResult` | Neo4j 文档结构图谱查询结果（Document→Section→Item 层级的各种视图形态） |
| `route/` | `ScopeRouteCandidate`、`TopicRouteCandidate`、`DocumentRouteCandidate`、`KnowledgeRouteDecision` | 三级漏斗（Scope→Topic→Document）各级候选与最终路由决策 |

### 5.4 服务层内部 record（不出 API，不属于 DTO/VO）

`chatagent/service` 下还有一组纯内部 record：`TaskInfo`（执行任务全量上下文）、`StreamLaunchPlan`（启动计划）、`BootstrapResult`（启动结果）、`ChatCheckpointManager` 相关记录等。它们不参与序列化传输，本文仅划清边界不展开。

---

## 6. ai-example 示例工程的传输对象（record 风格）

示例工程统一使用 **Java record** 定义传输对象（不可变、自动生成访问器），与主业务模块的可变 POJO 形成鲜明对比。部分直接作为 Controller 的请求/响应体，部分作为 **LLM 结构化输出**（structured output）的目标类型。

### 6.1 MCP 客户端示例（ai-example-spring-ai-office-mcp-client）

`AssistantController` 内部 record：

| record | 字段 | 用途 |
| :--- | :--- | :--- |
| `ChatRequest` | `message` | 对话请求体 |
| `ChatResponse` | `reply` | 对话响应体 |

### 6.2 会话记忆示例（ai-example-spring-ai-memory，memory/model）

| record | 字段 | 用途 |
| :--- | :--- | :--- |
| `MemoryChatResponse` | `strategy`、`sessionId`、`question`、`answer`、`estimatedPromptTokens`、`summary`、`compressionCount`、`memoryMessages` | 单轮对话响应（含记忆策略与注入上下文明细） |
| `ConversationMessageView` | `role`、`content` | 记忆中的单条消息视图 |
| `MemoryComparisonResponse` | `scriptName`、`turns`、`slidingWindowFinalState`、`summaryFinalState` | 三种记忆策略对照实验整体结果 |
| `ComparisonTurnResponse` | `round`、`question`、`noMemoryAnswer`、`slidingWindowAnswer`、`summaryAnswer`、`summarySnapshot`、`summaryCompressionCount` | 对照实验单轮结果 |

### 6.3 Milvus 向量检索示例（ai-example-spring-ai-rag-milvus，ai/model 与 hybrid/model）

| record | 字段 | 用途 |
| :--- | :--- | :--- |
| `MilvusChunkRequest` | `id`、`content`、`docId`、`category`（均 `@NotBlank`）、`source`、`metadata`（Map） | 插入向量块请求体（示例中唯一带校验注解的 record） |
| `MilvusSearchResult` | `id`、`content`、`score`、`docId`、`category`、`metadata` | 向量检索结果 |
| `MilvusIndexInfo` | `fieldName`、`indexName`、`state`、`indexedRows`、`totalRows`、`params` | Milvus 索引信息 |
| `MilvusCollectionStatusResponse` | `uri`、`databaseName`、`collectionName`、`exists`、`loadState`、`statistics`、`indexes`、`collectionSummary` | 集合状态响应 |
| `HybridSearchResult` | `id`、`content`、`score`（float）、`mode` | 混合检索（向量+BM25）结果 |

### 6.4 其它示例

| 位置 | record | 字段 | 用途 |
| :--- | :--- | :--- | :--- |
| ai-example-spring-ai-rag-neo4j（graphrag/dto） | `InstructorCoursesDto` | `instructor`、`otherCourses` | GraphRAG 中 LLM 结构化抽取的目标类型，是示例工程中唯一放在 `dto` 包的 record |
| ai-example-spring-ai-alibaba（ai/model） | `OrderInfo` | `orderId`、`productName`、`status`、`logisticsNo`、`expectedDelivery`、`canRefund` | 订单助手 Agent 的模拟订单数据（工具返回模型） |

其余示例控制器（Tika、Rerank、QueryRewrite、RouteChat、GraphRAG 问答、Spring AI 基础示例等）未定义 DTO，直接使用 `@RequestParam` 接参、`Map<String, Object>` 返回。

---

## 7. 设计特点总结

### 7.1 优点与一致的做法

1. **职责边界清晰**：请求体一律 `dto` 包 + `*Dto` 后缀，响应一律 `vo`/`model` 包，Controller 层零业务逻辑，DTO 不做运算直接透传给 Service。
2. **入参校验前移**：必填约束全部用 Bean Validation 声明在 DTO 字段上，配合 `@Valid` 与全局异常处理器，Controller 无需手写校验。
3. **命名后缀语义化**，可从名字直接推断用途：
   - `XxxQueryDto`（查询入参）、`XxxSaveDto`（保存入参）、`XxxDeleteDto`（删除入参）、`XxxConfirmDto`（确认操作）、`XxxRequest`（登录）；
   - `XxxPageQueryVo` / `XxxQueryVo`（分页出参）、`XxxListItemVo` / `XxxItemVo`（列表条目）、`XxxDetailVo`（详情）、`XxxView`（对话侧视图）。
4. **code + Name 成对冗余**：所有状态类字段（parseStatus、strategyStatus、indexStatus、executeStatus、logLevel 等）都在 VO 中同时返回整数 code 与中文名称，前端免维护枚举映射。
5. **分页出参结构统一**：`pageNo / pageSize / total(Size) / records|sessions|logs`，主业务各分页 VO 字段名基本一致。
6. **复用型 DTO**：`ConversationIdentityDto`、`RetrievalObserveQueryDto` 等被多个接口共用，避免重复定义。
7. **内外隔离**：编排器内部模型（`rag/model`）、ES/图查询模型（`manage/model`）不直接暴露给前端，通过 `ChatDebugTrace`、`RetrievalResultView` 等专用视图裁剪后输出。

### 7.2 可改进点

1. **ID 类型不统一**：同一业务概念在不同类中类型漂移。例如 `documentId` 在 manage 文档族里是 `Long`，在 `DocumentDeleteDto`/画像/知识管理族里是 `String`；`exchangeId` 在 SSE 里是 `Long`、在查询 DTO 里是 String。前后端需要约定序列化行为，服务端频繁 `parseLong`。
2. **分页参数形态不一**：公共 `BasePageDto`（`pageNumber`/`pageSize`，Integer + `@NotNull`）与主业务各分页 DTO（`pageNo`/`pageSize`，且会话列表是 String、文档列表是 Integer，均无校验）并存，未复用基类。
3. **校验覆盖不均**：manage 文档族 DTO 校验完整，但知识管理族 DTO（`KnowledgeScopeSaveDto` 等）与对话列表 DTO 完全没有校验注解，必填约束只存在于 Service 层抛错。
4. **遗留测试类**：`TestDto` / `TestVo`（manage 包）为脚手架遗留，可清理。
5. **命名小瑕疵**：`AdminLoginRequest` 未遵循 `*Dto` 后缀；`PageVo` 位于 `page` 包而非 `vo` 包；`BasePageDto` 的字段名 `pageNumber` 与业务侧 `pageNo` 不一致。
6. **两种风格并存**：主业务可变 POJO（Lombok `@Data`）与示例工程 record 并存。主业务内部 record（`TaskInfo` 等）与 model 类混用 `@Builder`，可逐步统一。
7. **JSON 字符串直传**：`KnowledgeRouteTraceItemVo` 的 `topScopesJson`/`topTopicsJson`/`topDocumentsJson` 将结构化数据以 JSON 字符串塞进 VO，前端需二次解析，属于为省映射成本的做法。
