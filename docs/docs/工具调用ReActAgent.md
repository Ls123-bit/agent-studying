# 工具调用（ReAct Agent）深度剖析

> 模块位置：`nexus-agent-business/nexus-agent-business-chat/src/main/java/org/javaup/ai/chatagent/`
> 核心类：`TavilySearchTool`（联网搜索工具）、`ChatCheckpointManager`（检查点持久化）、`ReactAgentExecutor`（执行器）、`ChatAgentConfiguration`（Agent 装配与安全限制）
> 一句话定位：**开放式问答模式下，把"要不要搜、搜什么、怎么用搜索结果"的决策权交给大模型，由 ReAct 循环自主完成 推理 → 调用 Tavily 搜索 → 观察结果 → 继续推理/给最终答案，同时用 Hook 限额、拦截器兜底、MySQL 检查点保断点续跑。**

---

## 一、整体架构一图流（ASCII 函数调用关系图）

### 1.1 主链路：从 HTTP 请求到 SSE 流式返回

```
 客户端 (Vue, POST /api/chat/stream, text/event-stream)
        │  ChatRequestDto
        ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ BusinessChatController.stream(ChatRequestDto): Flux<String>                 │
│   └─► businessChatService.openConversationStream(dto)                       │
└───────────────────────────────┬─────────────────────────────────────────────┘
                                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ BusinessChatService  [编排层 - 会话启动]                                      │
│                                                                             │
│  openConversationStream(ChatRequestDto): Flux<String>                       │
│    └─► Flux.defer ─► openDeferredConversationStream(request)                │
│          │                                                                  │
│          ├─① buildLaunchPlan(request): StreamLaunchPlan                     │
│          │      (规整 question/conversationId、解析 chatMode、               │
│          │       解析选中文档、取当前日期 Asia/Shanghai)                      │
│          │                                                                  │
│          ├─② claimConversationLease(plan): boolean   ──否──► rejectionFlux   │
│          │      (Redis 租约：同一会话同一时刻只允许一个执行流)                 │
│          │                                                                  │
│          └─③ bootstrapConversation(plan): BootstrapResult                   │
│                 ├─ conversationArchiveStore.startExchange(...)  持久化交换记录│
│                 ├─ createTaskInfo(plan, exchangeView): TaskInfo             │
│                 │     ├─ Sinks.Many<String> sink（SSE 单播管道）             │
│                 │     ├─ buildSessionConfig(conversationId): RunnableConfig │
│                 │     │     └─ RunnableConfig.builder().threadId(会话ID)    │
│                 │     ├─ context 塞入：EVENT_SINK / THINKING_STEPS /        │
│                 │     │   REFERENCES / USED_TOOLS / CURRENT_DATE /          │
│                 │     │   DEBUG_TRACE / QUESTION / CHAT_MODE ...            │
│                 │     └─ new ConversationTraceRecorder(...)                 │
│                 ├─ chatRuntimeRegistry.register(taskInfo): boolean          │
│                 └─ BootstrapResult.ready(bindClientChannel(taskInfo))       │
│                        │                                                    │
│                        ▼                                                    │
│          bindClientChannel(taskInfo): Flux<String>                          │
│                 └─ taskInfo.sink().asFlux()                                 │
│                      .doOnSubscribe ─► activateGeneration(taskInfo)         │
│                      .doOnCancel    ─► stopTask(taskInfo, "客户端已取消")    │
└───────────────────────────────┬─────────────────────────────────────────────┘
                                │  客户端订阅时才触发（懒执行）
                                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ activateGeneration(taskInfo)                                                │
│   ├─ startLeaseRenewal(taskInfo): Disposable （每10s续租，续租失败→stopTask） │
│   └─ buildConversationExecution(taskInfo).subscribe()                       │
└───────────────────────────────┬─────────────────────────────────────────────┘
                                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ buildConversationExecution(taskInfo): Flux<String>   [准备 + 路由 + 执行]    │
│                                                                             │
│  Mono.fromCallable(─► prepareExecutionPlan(taskInfo))                       │
│        .subscribeOn(boundedElastic)      ← 阻塞准备阶段切到弹性线程池        │
│        .flatMapMany(plan -> {                                               │
│             executor = conversationExecutorRegistry.get(plan.getMode())     │
│             return executor.execute(taskInfo);   ← 策略路由（EnumMap）       │
│        })                                                                   │
│        .publishOn(boundedElastic)                                           │
│        .doOnNext(chunk ─► emitModelChunk(taskInfo, chunk))   ──┐            │
│        .doOnError   (error ─► finishWithFailure(taskInfo, e))  │ 写回 sink  │
│        .doOnComplete(       ─► finishSuccessfully(taskInfo))   ─┘           │
└───────────────────────────────┬─────────────────────────────────────────────┘
                                │  plan.getMode() == ExecutionMode.REACT_AGENT
                                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ ConversationExecutorRegistry.get(ExecutionMode): ConversationExecutor       │
│   └─ EnumMap<ExecutionMode, ConversationExecutor> 策略注册表                 │
│      GRAPH_ONLY / GRAPH_THEN_EVIDENCE / RETRIEVAL / REACT_AGENT /           │
│      CLARIFication 各有独立执行器，本篇聚焦 ──► ReactAgentExecutor           │
└───────────────────────────────┬─────────────────────────────────────────────┘
                                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ ReactAgentExecutor implements ConversationExecutor     [ReAct 执行器]        │
│                                                                             │
│  mode(): ExecutionMode = REACT_AGENT                                        │
│  execute(taskInfo): Flux<String>                                            │
│    ├─ publishThinking("当前问题进入开放式 Agent 自主执行阶段。")              │
│    ├─ traceRecorder.startStage(REACT_AGENT, ...)                            │
│    └─ reactAgent.stream(plan.getAgentQuestion(), taskInfo.runnableConfig()) │
│           : Flux<NodeOutput>          ← 交给框架 ReAct 循环（见 1.2）        │
│         .publishOn(boundedElastic)                                          │
│         .concatMap(output ─► extractTextChunk(output, streamedText))        │
│              │  只放行 AGENT_MODEL_STREAMING / AGENT_MODEL_FINISHED 文本，   │
│              │  AtomicBoolean streamedText 防止"流式+收尾"内容重复           │
│         .doOnComplete ─► traceRecorder.completeStage(REACT_AGENT, ...)      │
│         .doOnError    ─► traceRecorder.failStage(REACT_AGENT, ...)          │
└───────────────────────────────┬─────────────────────────────────────────────┘
                                │ 文本块 Flux<String>
                                ▼
   emitModelChunk(taskInfo, chunk)  →  answerBuffer 追加 + 首响应耗时打点
                                     →  safeEmit(sink, streamEventWriter.text(...))
                                     →  SSE 推给客户端
   finishSuccessfully(taskInfo)     →  references 去重 + 落库 + 释放租约 + complete
═══════════════════════════════════════════════════════════════════════════════
```

### 1.2 核心内核：ReactAgent 的 ReAct 循环（框架层，Bean 装配于 ChatAgentConfiguration）

```
ChatAgentConfiguration（@Configuration，启动期装配，一次构建处处复用）
│
├─ mysqlCheckpointSaver(DataSource): MysqlSaver ────────────┐
│     MysqlSaver.builder().dataSource(...).createOption(    │ 检查点保存器
│       CreateOption.CREATE_IF_NOT_EXISTS).build()          │
├─ tavilySearchToolCallback(TavilySearchTool): ToolCallback │ 工具适配器
│     FunctionToolCallback.builder("tavily_search",         │ （把方法引用
│       tool::search).description("联网搜索...")             │   注册成 LLM 可见
│       .inputType(TavilySearchRequest.class).build()       │   的 function）
└─ businessChatReactAgent(...): ReactAgent                  │
      ReactAgent.builder()                                  │
        .name("business_chat_agent")                        │
        .model(chatModel)                    ← ChatModel    │
        .instruction(systemPrompt)                          │
        .tools(tavilySearchToolCallback) ───────────────────┤ 只有这一个工具
        .saver(mysqlCheckpointSaver) ───────────────────────┘ 状态落 MySQL
        .parallelToolExecution(true).maxParallelTools(4)
        .hooks(   ModelCallLimitHook  runLimit=8  threadLimit=40 ExitBehavior.END )
        .hooks(   ToolCallLimitHook   toolName="tavily_search"
                  runLimit=6  threadLimit=30  ExitBehavior.END )
        .interceptors( DashScopeCompatibilityInterceptor,      ← 模型兼容
                       TavilyToolInputFallbackInterceptor,     ← 入参兜底改写
                       ToolRetryInterceptor(maxRetries=2,
                          200~1200ms 退避+抖动,
                          onFailure=RETURN_MESSAGE),
                       ToolErrorInterceptor )
      .build()

运行期 ReAct 循环（reactAgent.stream 内部）：
                 ┌────────────────────────────────────────────────┐
                 │            ┌───────────────────────────┐       │
                 │            │  ① 模型推理（ChatModel）   │◄────┐ │
                 │            │     ModelCallLimitHook    │     │ │ run≤8次/轮
                 │            │     校验 run≤8 thread≤40  │     │ │ thread≤40次
                 │            └────────────┬──────────────┘     │ │
                 │                有 tool_calls?                │ │
                 │            否（最终答案）│ 是                  │ │
                 │        ┌───────────────┴───────────────┐      │ │
                 │        ▼                               ▼      │ │
                 │  流式输出文本块               ② ToolCallLimitHook │ │
                 │  (AGENT_MODEL_STREAMING)     tavily_search       │ │
                 │        │                     run≤6 thread≤30     │ │
                 │        │                 超限→END(优雅收尾)       │ │
                 │        │                       │                 │ │
                 │        │                       ▼                 │ │
                 │        │     ③ 拦截器链（可改写入参/重试/吞错）    │ │
                 │        │     TavilyToolInputFallbackInterceptor  │ │
                 │        │       → 空参/坏 JSON 兜底改写为 {"query"}│ │
                 │        │     ToolRetryInterceptor                │ │
                 │        │       → 失败重试2次(指数退避+抖动)       │ │
                 │        │     ToolErrorInterceptor                │ │
                 │        │                       │                 │ │
                 │        │                       ▼                 │ │
                 │        │     ④ TavilySearchTool.search(request,  │ │
                 │        │            toolContext)  [见 1.3]       │ │
                 │        │                       │                 │ │
                 │        │                       ▼                 │ │
                 │        │     ⑤ Observation：TavilySearchToolResult│ │
                 │        │        序列化为 ToolResponseMessage      │ │
                 │        │        追加进消息历史 ──────────────────┘ │
                 │        └──► SSE ──► 客户端                       │
                 └────────────────────────────────────────────────┘
```

### 1.3 工具内部：TavilySearchTool.search 的内部调用顺序

```
TavilySearchTool.search(TavilySearchRequest, ToolContext): TavilySearchToolResult
│
├─ ① 前置校验
│    query 为空                → throw IllegalArgumentException
│    properties.isEnabled()=false → throw IllegalStateException("工具已禁用")
│    apiKey 为空               → throw IllegalStateException("API Key 未配置")
│
├─ ② resolveTopic(request): String          topic 白名单 general/news/finance，
│                                           非法值回退 general
├─ ③ registerToolTrace(toolContext, trace): ChatToolTrace
│                                           往 DEBUG_TRACE 挂一条 RUNNING 轨迹
├─ ④ markToolUsed(toolContext,"tavily_search")   往 USED_TOOLS 集合登记
├─ ⑤ publishThinking(toolContext,"🔍 正在联网搜索: ...")
│       └─ 从 EVENT_SINK 取 Sinks.Many<String> 发 SSE 思考事件
│       └─ 同步追加 THINKING_STEPS 列表
│
├─ ⑥ buildEffectiveQuery(query, toolContext): String
│       └─ resolveCurrentDate(toolContext): String   从 RunnableConfig.context
│       │                                            取 CURRENT_DATE
│       └─ TimeSensitiveQueryHelper.buildEffectiveSearchQuery(query, 日期)
│            "最新/最近/今天..."类相对时间问题 → 锚定日期后搜索
│
├─ ⑦ restClient.post() ──HTTPS──► Tavily API /search
│       请求体 TavilySearchApiRequest(query, topic, searchDepth,
│                                   maxResults, includeAnswer, includeRawContent)
│       响应   TavilySearchApiResponse(answer, List<TavilyResultItem>)
│
├─ ⑧ 结果映射: TavilyResultItem(title,url,content) ─► SearchReference
│       └─ appendReferences(toolContext, references)  累加进 REFERENCES 容器
├─ ⑨ publishThinking("📚 搜索完成，找到 N 条候选来源")
├─ ⑩ completeToolTrace(trace, response, N, startTime)
│       status=COMPLETED，durationMs=耗时，outputSummary=答案摘要(截断160字)
│
└─ 返回 new TavilySearchToolResult(effectiveQuery, answer, List<SearchReference>)
         │
         │  （异常分支 RuntimeException：failToolTrace → status=FAILED +
         │    publishThinking("⚠️ 搜索失败") → 原样抛出，交给
         │    ToolRetryInterceptor 重试 / ToolErrorInterceptor 兜底）
         ▼
   序列化为 ToolResponseMessage 回填给大模型 → 进入下一轮 ReAct 推理
```

### 1.4 检查点：ChatCheckpointManager 与会话重置/断点续跑

```
┌────────────────────────────────────────────────────────────────────┐
│ 启动期: ChatAgentConfiguration.mysqlCheckpointSaver(DataSource)     │
│         → MysqlSaver (表: graph_thread / graph_checkpoint,         │
│           CREATE_IF_NOT_EXISTS，threadId = conversationId)          │
│                                                                    │
│ 运行期: ReactAgent.builder().saver(mysqlSaver)                     │
│         → ReAct 每一步(模型消息/工具消息)自动持久化为 Checkpoint     │
│                                                                    │
│ 读取:   ChatCheckpointManager                                      │
│   get(RunnableConfig)  : Optional<Checkpoint>    ─► saver.get()    │
│   list(RunnableConfig) : Collection<Checkpoint>  ─► saver.list()   │
│                                                                    │
│ 清理:   resetConversation(conversationId): ConversationResetVo     │
│   └─ stopConversation(...)  先停流                                 │
│   └─ checkpointManager.clearThread(threadId): int  [@Transactional]│
│        ├─ graph_thread 按 threadName 查 threads                    │
│        ├─ 统计并删除 graph_checkpoint (threadId IN threadIds)      │
│        ├─ 删除 graph_thread (threadName = threadId)                │
│        └─ 返回删除的 checkpoint 数量                               │
│   （断点续跑：同 threadId 再次提问时，Agent 从 checkpoint 恢复      │
│     历史消息，天然具备多轮记忆；线程级限额 threadLimit 也依赖它）   │
└────────────────────────────────────────────────────────────────────┘
```

---

## 二、方法之间的调用关系与执行顺序

### 2.1 执行顺序总览（时间轴）

| 顺序 | 阶段 | 触发时机 | 关键调用 |
|---|---|---|---|
| 0 | **装配期**（启动一次） | Spring 启动 | `mysqlCheckpointSaver` → `tavilySearchToolCallback` → `businessChatReactAgent`（Bean 依赖顺序） |
| 1 | 请求接入 | HTTP POST 到达 | `Controller.stream` → `openConversationStream` → `openDeferredConversationStream` |
| 2 | 启动准备 | defer 内同步执行 | `buildLaunchPlan` → `claimConversationLease` → `bootstrapConversation`（内部 `startExchange` → `createTaskInfo` → `register` → `bindClientChannel`） |
| 3 | 订阅触发 | 客户端订阅 SSE | `bindClientChannel` 的 `doOnSubscribe` → `activateGeneration`（先续租定时器，后 `buildConversationExecution().subscribe()`） |
| 4 | 规划路由 | boundedElastic 线程 | `prepareExecutionPlan` → `chatPreparationOrchestrator.prepare` → `buildAgentQuestion` → `registry.get(mode)` |
| 5 | ReAct 执行 | mode == REACT_AGENT | `ReactAgentExecutor.execute` → `reactAgent.stream(agentQuestion, runnableConfig)`，进入 1.2 的框架循环 |
| 6 | 工具调用 | 模型决定调用 tavily_search | 拦截器链 → `TavilySearchTool.search`（1.3 的 ①~⑩）→ 结果回填消息历史 |
| 7 | 流式回传 | 每个文本块 | `extractTextChunk` → `emitModelChunk` → `sink` → SSE |
| 8 | 收尾 | complete / error / cancel | `finishSuccessfully`（引用去重落库、释放租约）或 `finishWithFailure` / `stopTask`；`resetConversation` 时额外走 `checkpointManager.clearThread` |

### 2.2 关键设计点（面试必讲）

1. **懒执行 + 订阅触发**：`openConversationStream` 用 `Flux.defer` 包裹，`bindClientChannel` 用 `sink.asFlux().doOnSubscribe(activateGeneration)` —— 只有客户端真正订阅才开始跑 Agent，连接断开（`doOnCancel`）立即 `stopTask` 释放资源。
2. **背压解耦**：Agent 执行（生产者）与 SSE 输出（消费者）通过 `Sinks.Many<String>` 单播管道解耦；`prepareExecutionPlan` 等阻塞操作切到 `Schedulers.boundedElastic()`，不占用 Netty 事件循环线程。
3. **策略模式路由**：五种 `ExecutionMode` 各对应一个 `ConversationExecutor`，`ConversationExecutorRegistry` 在构造时收集所有执行器塞进 `EnumMap`，`get(mode)` O(1) 分发；`ReactAgentExecutor.mode()` 返回 `REACT_AGENT` 完成自注册。
4. **上下文跨层传递用 ToolContext / RunnableConfig.context**：`search(TavilySearchRequest, ToolContext)` 的第二个参数由框架注入，工具通过 `ToolContextHelper.getConfig(toolContext)` 拿到 `RunnableConfig`，从而读写 `EVENT_SINK`（SSE 推流）、`REFERENCES`（引用容器）、`USED_TOOLS`、`CURRENT_DATE`、`DEBUG_TRACE` —— 业务数据不经过 LLM 提示词，避免污染上下文。
5. **双保险防重复输出**：ReAct 流里既有增量块（`AGENT_MODEL_STREAMING`）又有收尾全量块（`AGENT_MODEL_FINISHED`），`ReactAgentExecutor` 用 `AtomicBoolean streamedText` 保证"流过就丢收尾块"，避免答案重复。
6. **安全限制三层**：
   - `ModelCallLimitHook`：单轮 8 次 / 单线程（会话）40 次模型调用，超限 `ExitBehavior.END` 优雅结束（不抛异常，强制让模型收尾）；
   - `ToolCallLimitHook`：`tavily_search` 单轮 6 次 / 单会话 30 次，防止 Agent 陷入"无限搜索"死循环烧钱；
   - Redis 租约（`claimConversationLease` + 每 10s 续租）：同一会话并发请求直接拒绝，实例宕机租约 30s TTL 自动过期，不会死锁。
7. **工具健壮性**：`TavilyToolInputFallbackInterceptor` 兜底模型给出的空参/坏 JSON；`ToolRetryInterceptor` 指数退避重试 2 次（200ms→1200ms 带抖动），失败 `RETURN_MESSAGE` 把错误信息作为工具消息返回给模型让它自行调整，而不是整个会话崩掉。
8. **检查点持久化**：`MysqlSaver` + `threadId=conversationId`，ReAct 每一步状态自动落库 → 支持断点续跑、跨轮记忆、会话重置一键清理（`clearThread` 事务内先删 checkpoint 再删 thread）。

---

## 三、各方法参数类型与返回值类型一览

### 3.1 入口层

| 类.方法 | 参数类型 | 返回值类型 |
|---|---|---|
| `BusinessChatController.stream` | `ChatRequestDto`（@Valid） | `Flux<String>`（SSE） |
| `BusinessChatService.openConversationStream` | `ChatRequestDto` | `Flux<String>` |
| `openDeferredConversationStream` (private) | `ChatRequestDto` | `Flux<String>` |
| `buildLaunchPlan` (private) | `ChatRequestDto` | `StreamLaunchPlan` |
| `claimConversationLease` (private) | `StreamLaunchPlan` | `boolean` |
| `bootstrapConversation` (private) | `StreamLaunchPlan` | `BootstrapResult` |
| `createTaskInfo` (private) | `StreamLaunchPlan`, `ConversationExchangeView` | `TaskInfo` |
| `buildSessionConfig` (private) | `String conversationId` | `RunnableConfig` |
| `bindClientChannel` (private) | `TaskInfo` | `Flux<String>` |
| `activateGeneration` (private) | `TaskInfo` | `void` |
| `buildConversationExecution` (private) | `TaskInfo` | `Flux<String>` |
| `prepareExecutionPlan` (private) | `TaskInfo` | `ConversationExecutionPlan` |
| `buildAgentQuestion` (private) | `ConversationExecutionPlan` | `String` |
| `emitModelChunk` (private) | `TaskInfo`, `String chunk` | `void` |
| `finishSuccessfully` / `finishWithFailure` (private) | `TaskInfo`（后者加 `Throwable`） | `void` |
| `stopConversation` | `String conversationId`（可加 `String reason`） | `ConversationStopVo` |
| `resetConversation` | `String conversationId` | `ConversationResetVo` |

### 3.2 执行器层

| 类.方法 | 参数类型 | 返回值类型 |
|---|---|---|
| `ConversationExecutorRegistry.get` | `ExecutionMode` | `ConversationExecutor` |
| `ConversationExecutorRegistry.<init>` | `List<ConversationExecutor>` | —（构造器） |
| `ReactAgentExecutor.mode` | 无 | `ExecutionMode`（返回 `REACT_AGENT`） |
| `ReactAgentExecutor.execute` | `TaskInfo` | `Flux<String>` |
| `ReactAgentExecutor.extractTextChunk` (private) | `NodeOutput`, `AtomicBoolean streamedText` | `Mono<String>` |
| `ReactAgentExecutor.extractStreamingText` (private) | `StreamingOutput<?>` | `String` |
| 框架 `ReactAgent.stream` | `String question`, `RunnableConfig` | `Flux<NodeOutput>`（元素实际为 `StreamingOutput<?>`） |

### 3.3 工具层 TavilySearchTool

| 方法 | 参数类型 | 返回值类型 |
|---|---|---|
| `search`（公开，被 `FunctionToolCallback` 以方法引用注册） | `TavilySearchRequest`, `ToolContext` | `TavilySearchToolResult` |
| `buildEffectiveQuery` (private) | `String query`, `ToolContext` | `String` |
| `resolveCurrentDate` (private) | `ToolContext` | `String` |
| `resolveTopic` (private) | `TavilySearchRequest` | `String` |
| `normalizeTopic` (private) | `String rawTopic` | `String`（null 表示不合法） |
| `appendReferences` (private) | `ToolContext`, `List<SearchReference>` | `void` |
| `markToolUsed` (private) | `ToolContext`, `String toolName` | `void` |
| `publishThinking` (private) | `ToolContext`, `String content` | `void` |
| `resolveMetadata` (private) | `RunnableConfig` | `StreamEventMetadata` |
| `registerToolTrace` (private) | `ToolContext`, `ChatToolTrace` | `ChatToolTrace` |
| `completeToolTrace` (private) | `ChatToolTrace`, `TavilySearchApiResponse`, `int referenceCount`, `long startTime` | `void` |
| `failToolTrace` (private) | `ChatToolTrace`, `RuntimeException`, `long startTime` | `void` |
| `clipText` (private) | `String value`, `int maxLength` | `String` |
| `StreamEventWriter.thinking` | `String content`[, `StreamEventMetadata`] | `String`（JSON 事件帧） |
| `TimeSensitiveQueryHelper.buildEffectiveSearchQuery` (static) | `String query`, `String currentDate` | `String` |

### 3.4 检查点层 ChatCheckpointManager

| 方法 | 参数类型 | 返回值类型 | 说明 |
|---|---|---|---|
| `get` | `RunnableConfig` | `Optional<Checkpoint>` | 委托 `MysqlSaver.get`，取最新检查点 |
| `list` | `RunnableConfig` | `Collection<Checkpoint>` | 该线程全部检查点 |
| `clearThread`（@Transactional） | `String threadId` | `int`（删除的 checkpoint 数） | 先查 `graph_thread` → 删 `graph_checkpoint` → 删 `graph_thread` |
| `toInt` (private) | `Long` | `int` | null 安全转换 |
| `MysqlSaver`（Bean） | `DataSource` | `MysqlSaver` | `CreateOption.CREATE_IF_NOT_EXISTS` |

### 3.5 配置层 ChatAgentConfiguration

| Bean 方法 | 参数（依赖注入）类型 | 返回值类型 |
|---|---|---|
| `mysqlCheckpointSaver` | `DataSource` | `MysqlSaver` |
| `tavilySearchToolCallback` | `TavilySearchTool` | `ToolCallback`（实际 `FunctionToolCallback`） |
| `businessChatReactAgent` | `ChatModel`, `MysqlSaver`, `ToolCallback`, `ChatAgentProperties`, `DashScopeCompatibilityInterceptor`, `TavilyToolInputFallbackInterceptor` | `ReactAgent` |

### 3.6 核心数据模型

| 类型 | 字段 / 说明 |
|---|---|
| `TavilySearchRequest`（LLM 入参 schema） | `String query`、`String topic`、`Integer maxResults` |
| `TavilySearchToolResult`（工具返回，回给 LLM） | `String query`（生效查询词）、`String answer`（Tavily 直答）、`List<SearchReference> results` |
| `SearchReference`（统一引用来源） | `title/url/snippet` + 构造器固定 `sourceType="WEB"`, `channel="web-search"`, `toolName="tavily_search"`；`uniqueKey()` 按 url 去重 |
| `ChatToolTrace` | `toolName/status(RUNNING→COMPLETED/FAILED)/inputSummary/effectiveInput/outputSummary/errorMessage/referenceCount/topic/durationMs` |
| `TaskInfo` | 会话执行上下文聚合：`runnableConfig`、`sink`、`thinkingSteps`、`references`、`usedTools`、`answerBuffer`、`finalized`、租约信息等 |
| 内部 record（Tavily API 协议） | `TavilySearchApiRequest(query, topic, search_depth, max_results, include_answer, include_raw_content)`、`TavilySearchApiResponse(answer, results)`、`TavilyResultItem(title, url, content)` |

---

## 四、面试讲述辅助

### 4.1 30 秒电梯版

> "我们项目里开放式问答走 ReAct Agent：用户提问后，先经过规划路由判定该问题 RAG 和结构图都覆盖不了，就交给 `ReactAgentExecutor`。它调用 Spring AI Alibaba 的 `ReactAgent.stream()`，模型在 ReAct 循环里自主决定要不要调 `tavily_search` 联网搜索工具——要搜就通过 `FunctionToolCallback` 反射进入我们的 `TavilySearchTool.search()`，里面做参数校验、时间敏感 query 改写、调 Tavily API、把结果封装成 `SearchReference` 引用列表，同时通过 `ToolContext` 拿到 SSE 管道实时推'正在搜索'的思考过程给前端。搜索结果作为 Observation 回填给模型继续推理，直到给出最终答案流式输出。为防失控，用 `ModelCallLimitHook` 限制单轮 8 次模型调用、`ToolCallLimitHook` 限制单轮 6 次工具调用，超限优雅结束；每步状态通过 `MysqlSaver` 检查点落库，支持断点续跑和多轮记忆。"

### 4.2 两分钟结构化版（按"输入 → 循环 → 防护 → 输出"讲）

1. **输入侧**：`/api/chat/stream` 返回 SSE `Flux<String>`。服务层先 `buildLaunchPlan` 规整请求，Redis 租约保证同一会话串行，然后创建 `TaskInfo`——核心是构造 `RunnableConfig`（threadId=conversationId），把 **SSE 管道、引用容器、已用工具集合、当前日期、调试轨迹** 全部塞进 graph context，这样后面任何一层（哪怕深在工具里）都能拿到，不需要层层传参。
2. **循环侧**：`ConversationExecutorRegistry` 按 `ExecutionMode.REACT_AGENT` 路由到 `ReactAgentExecutor`，它只做三件事：发思考事件、开追踪 stage、把规划阶段拼好的 `agentQuestion` 交给 `reactAgent.stream()`。之后就是框架的 **Reason(模型推理) → Act(工具调用) → Observe(结果回填)** 循环：模型返回 tool_calls 就并行执行（最多 4 个），工具结果序列化成 `ToolResponseMessage` 追加历史再进下一轮；没有 tool_calls 就流式吐最终答案。
3. **防护侧**（最能体现生产思维，重点讲）：
   - **防烧钱**：`ModelCallLimitHook`(8/轮, 40/会话) + `ToolCallLimitHook`(tavily_search 6/轮, 30/会话)，`ExitBehavior.END` 是优雅收尾不是报错；
   - **防并发**：Redis 租约 30s TTL + 每 10s 续租，宕机自动过期；
   - **防脏输入**：`TavilyToolInputFallbackInterceptor` 把模型给的空参/坏 JSON 自动改写成 `{"query": ...}`；
   - **防抖**：`ToolRetryInterceptor` 失败重试 2 次、200~1200ms 指数退避加抖动，最终失败也只把错误作为工具消息还给模型自行决策。
4. **输出侧**：执行器里 `AtomicBoolean streamedText` 处理"增量流式块 + 收尾全量块"二选一，防止答案重复；文本块统一经 `emitModelChunk` 写 answerBuffer（供落库）、打点首响应耗时、推 SSE。搜索到的引用通过 context 里的 REFERENCES 容器汇入 `finishSuccessfully` 统一去重落库并推 `references` 事件。
5. **状态侧**：`MysqlSaver` 检查点按 threadId 持久化每一步消息状态——同一会话下一轮提问自动带上历史（多轮记忆），中断可续跑，`resetConversation` 时 `clearThread` 在一个事务里把 checkpoint 和 thread 记录清干净。

### 4.3 高频追问 Q&A

**Q1：模型怎么"知道"有个搜索工具可以调？**
A：`ChatAgentConfiguration` 里用 `FunctionToolCallback.builder("tavily_search", tool::search).inputType(TavilySearchRequest.class)` 把方法引用注册成工具，Spring AI 会根据 `TavilySearchRequest` 的字段生成 JSON Schema 随请求发给模型；description 里写明了"必须传非空 query，topic 只允许 general/news/finance"，模型按 schema 产出 tool_calls，框架反序列化成 `TavilySearchRequest` 后反射调用方法。

**Q2：`ToolContext` 是什么？为什么 `search` 有第二个参数但没有出现在 JSON Schema 里？**
A：`ToolContext` 是框架运行时注入的上下文（Spring AI 约定 `ToolContext` 类型参数不参与 schema 生成），我们用它透传 `RunnableConfig`，从中读写 `EVENT_SINK`（推 SSE 思考事件）、`REFERENCES`/`USED_TOOLS`/`DEBUG_TRACE`（业务容器）。好处是 LLM 看不到这些业务数据，提示词不被污染，且工具执行层能与 Web 层解耦。

**Q3：为什么限额用 Hook 而不是在工具里自己计数？**
A：Hook 由框架在每次模型调用/工具调用前统一回调，计数与 ReAct 循环解耦、可声明式配置 run/thread 两个维度，并且 `ExitBehavior.END` 能触发优雅收尾（让模型基于已有信息结束，而不是抛异常毁掉整轮回答）。自己计数容易漏掉"模型调用"这个维度，且侵入业务代码。

**Q4：超限后用户体验是什么样？**
A：`ExitBehavior.END` 会让 Agent 直接进入收尾：基于已收集的 Observation 生成最终回答，前端看到的是"回答变短了"而不是报错；限额命中情况记录在 `ChatDebugTrace`/trace stage 里可观测。

**Q5：断点续跑怎么实现的？**
A：`ReactAgent.builder().saver(mysqlCheckpointSaver)`，`RunnableConfig.threadId = conversationId`。框架在图每个节点执行后把 State（消息列表等）序列化存入 MySQL（graph_thread/graph_checkpoint 两张表）。同 threadId 再进来时从最近 checkpoint 恢复，所以天然多轮记忆；如果中途实例崩溃，重试可从检查点继续。`ChatCheckpointManager` 是对 saver 的薄封装，额外提供事务化的 `clearThread` 清理能力。

**Q6：流式输出怎么避免内容重复？**
A：ReAct 每轮模型输出有两类流事件：`AGENT_MODEL_STREAMING`（增量）和 `AGENT_MODEL_FINISHED`（该轮完整文本）。若增量已经流给用户了，收尾全量块就必须丢弃，否则同一段话出现两遍。`extractTextChunk` 用 `AtomicBoolean streamedText` 记录"本轮运行是否已流过"，`FINISHED` 块只在从未流过时才放行。

**Q7：搜索失败会导致整个会话失败吗？**
A：不会立刻。异常先被 `failToolTrace` 记录（status=FAILED + 耗时 + 错误信息），`ToolRetryInterceptor` 退避重试 2 次；仍失败时 `ToolErrorInterceptor`/`RETURN_MESSAGE` 策略把错误信息包装成工具消息还给模型，模型可以选择换个 query 再搜（限额内）或直接基于已有信息回答。只有超出这些兜底（如模型调用本身失败）才会走 `finishWithFailure`。

**Q8：时间敏感问题（"今天的金价"）怎么处理？**
A：两层。规划层 `TimeSensitiveQueryHelper.requiresCurrentDateAnchoring/requiresFreshSearch` 判定后，`buildAgentQuestion` 模板会注入当前日期提示；工具层 `buildEffectiveQuery` 再用 context 里的 `CURRENT_DATE` 对相对时间词做锚定改写（"最新"→"2026年9月"），确保 Tavily 检索词不带歧义。

**Q9：多个工具并发执行怎么保证线程安全？**
A：`parallelToolExecution(true).maxParallelTools(4)`。共享容器全部用并发安全结构：`thinkingSteps`/`references` 是 `Collections.synchronizedList`，`usedTools` 是 `ConcurrentHashMap.newKeySet()`，`answerBuffer` 是 `StringBuffer`；引用去重时再 `synchronized` 做快照。

### 4.4 一页速记卡（进场前 1 分钟看）

```
入口   Controller.stream : Flux<String> (SSE)
准备   buildLaunchPlan → Redis租约 → createTaskInfo(RunnableConfig.context 塞6大件)
路由   prepareExecutionPlan → Registry.get(REACT_AGENT) → ReactAgentExecutor
执行   reactAgent.stream(agentQuestion, runnableConfig) : Flux<NodeOutput>
循环   Reason(模型,≤8次/轮) → Act(tavily_search,≤6次/轮,并行≤4) → Observe(回填)
工具   search(Request, ToolContext): TavilySearchToolResult
       校验→topic白名单→trace(RUNNING)→USED_TOOLS→思考SSE→query锚定日期
       →POST Tavily /search→SearchReference→REFERENCES→trace(COMPLETED)
防护   ModelCallLimitHook / ToolCallLimitHook(END优雅收尾) / Fallback+Retry拦截器
       / Redis租约(30s TTL,10s续租)
流式   extractTextChunk(streamedText 防重) → emitModelChunk → sink → SSE
状态   MysqlSaver(threadId=conversationId) 检查点 → 断点续跑+多轮记忆
       ChatCheckpointManager: get/list/clearThread(事务清理)
收尾   finishSuccessfully(引用去重落库+释放租约) / finishWithFailure / stopTask
```

---

## 五、涉及源码文件索引

| 文件 | 路径（nexus-agent-business/nexus-agent-business-chat/src/main/java/org/javaup/ai/chatagent/） |
|---|---|
| 控制器 | `controller/BusinessChatController.java` |
| 编排服务 | `service/BusinessChatService.java` |
| 执行上下文 | `service/TaskInfo.java` |
| 检查点管理 | `service/ChatCheckpointManager.java` |
| 执行器接口/注册表 | `rag/executor/ConversationExecutor.java`、`rag/executor/ConversationExecutorRegistry.java` |
| ReAct 执行器 | `rag/executor/ReactAgentExecutor.java` |
| 执行模式枚举 | `rag/model/ExecutionMode.java` |
| Agent 装配与限额 | `config/ChatAgentConfiguration.java`、`config/ChatAgentProperties.java`、`config/TavilySearchProperties.java` |
| 搜索工具 | `tool/TavilySearchTool.java`、`tool/TavilySearchRequest.java`、`tool/TavilySearchToolResult.java` |
| 入参兜底拦截器 | `support/TavilyToolInputFallbackInterceptor.java` |
| 时间锚定 | `support/TimeSensitiveQueryHelper.java` |
| SSE 事件帧 | `support/StreamEventWriter.java` |
| 引用模型 | `model/SearchReference.java`、`model/debug/ChatToolTrace.java`、`model/debug/ChatDebugTrace.java` |
