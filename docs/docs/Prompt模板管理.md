# Prompt 模板管理

> 对应文档描述：所有发给大模型的提示词都通过模板引擎管理，方便统一维护和调整。
> 核心类：`PromptTemplateService`（基于 StringTemplate4 的模板引擎，`render()`）、`PromptTemplateNames`（模板名称常量）。
> 模板文件：所有 `.st` 模板放在 `src/main/resources/prompt/` 目录下。
>
> 注：文档页面上写"16 个模板"，实际代码中 `PromptTemplateNames` 定义了 **17 个**常量，`resources/prompt/` 下也是 17 个 `.st` 文件（文档略滞后于代码）。

---

## 一、模块定位（一句话说清这个业务）

项目中所有要发给大模型（LLM）的提示词，都不允许在 Java 代码里硬编码拼接，而是统一放在 `src/main/resources/prompt/*.st` 模板文件里，由 `PromptTemplateService` 基于 **StringTemplate4（ST4）** 引擎做"模板名 → 模板文本 → 变量填充 → 最终 Prompt 字符串"的渲染。业务代码只负责准备变量 Map，然后调用一个方法：`promptTemplateService.render(模板名, 变量Map)` 拿到最终的 Prompt 字符串。

这样设计的好处：

1. **提示词与代码解耦**：改提示词不用改 Java 代码，直接改 `.st` 文件（热更新只需重启或配合资源刷新）。
2. **统一出口**：所有 Prompt 都走 `render()` 一个口子，便于统一做缓存、校验、trim、排查（全项目 grep `render(PromptTemplateNames.` 就能列出所有提示词调用点）。
3. **名称即契约**：`PromptTemplateNames` 常量类把"模板名"收敛成编译期常量，避免调用方写错字符串。

---

## 二、核心类与文件

| 组件 | 位置 | 角色 |
|---|---|---|
| `PromptTemplateService` | `org.javaup.ai.prompt.PromptTemplateService`（`nexus-agent-business-chat` 模块） | 模板渲染引擎封装，`@Component`，唯一公开方法 `render()` |
| `PromptTemplateNames` | `org.javaup.ai.prompt.PromptTemplateNames` | `final` 工具类（私有构造），17 个 `public static final String` 模板名常量 |
| 17 个 `.st` 模板 | `src/main/resources/prompt/*.st` | ST4 语法的提示词模板（`<变量>` 占位、`<if(x)>...<endif>` 条件段） |
| `StTemplateRenderer` | Spring AI 提供的渲染器（`org.springframework.ai.template.st`） | 底层引擎，包装 StringTemplate4 |

`PromptTemplateService` 的三个成员：

```java
private static final String PROMPT_DIR = "prompt/";                 // 模板目录前缀
private static final String TEMPLATE_SUFFIX = ".st";               // 模板后缀
private final ResourceLoader resourceLoader;                       // Spring 注入，读 classpath 资源
private final StTemplateRenderer templateRenderer;                 // 构造器里手动构建的 ST4 渲染器
private final Map<String, String> templateCache = new ConcurrentHashMap<>(); // 模板文本缓存（路径→内容）
```

构造器中初始化渲染器，锁死三个关键行为：

```java
public PromptTemplateService(ResourceLoader resourceLoader) {
    this.resourceLoader = resourceLoader;
    this.templateRenderer = StTemplateRenderer.builder()
        .startDelimiterToken('<')            // 占位符起始符：<，如 <originalQuestion>
        .endDelimiterToken('>')              // 占位符结束符：>
        .validationMode(ValidationMode.THROW) // 模板里出现未传入的变量 → 直接抛异常（fail-fast）
        .build();
}
```

---

## 三、方法清单：参数类型与返回值类型

### 3.1 PromptTemplateService（4 个方法 + 1 个构造器）

| 方法 | 可见性 | 参数类型 | 返回值类型 | 职责 |
|---|---|---|---|---|
| `PromptTemplateService(...)` | public 构造器 | `ResourceLoader resourceLoader` | — | 注入资源加载器；构建 ST4 渲染器（`<` `>` 分隔符 + `ValidationMode.THROW`） |
| `render(templateName, variables)` | **public（唯一入口）** | `String templateName, Map<String, ?> variables` | `String`（渲染完成的 Prompt 文本，已 trim） | 编排：路径规范化 → 缓存加载 → 变量归一 → ST4 渲染 |
| `normalizeTemplatePath(templateName)` | private | `String templateName` | `String`（如 `prompt/rag-answer-user.st`） | 补全目录前缀与 `.st` 后缀 |
| `loadTemplate(templatePath)` | private | `String templatePath` | `String`（模板原文，UTF-8） | 从 classpath 读文件；不存在抛 `IllegalArgumentException`，读取失败抛 `IllegalStateException` |
| `normalizeVariables(variables)` | private | `Map<String, ?> variables` | `Map<String, Object>`（`LinkedHashMap`，保序） | 变量归一：null 值替换为空串 `""`，防止 ST4 渲染出 "null" 或抛错 |

### 3.2 PromptTemplateNames（0 个业务方法）

纯常量类：`final class` + `private` 构造器（禁止实例化），17 个 `public static final String`，按业务域分 4 组：

| 业务域 | 常量 → 模板文件 | 用途 |
|---|---|---|
| RAG 问答（8 个） | `RAG_ANSWER_SYSTEM / RAG_ANSWER_USER / RAG_ANSWER_SUB_QUESTION_EVIDENCE / RAG_ANSWER_WEB_REFERENCE / RAG_ANSWER_DOCUMENT_REFERENCE / RAG_ANSWER_REUSE_REFERENCE / RAG_ANSWER_NO_EVIDENCE / RAG_ANSWER_OMITTED_EVIDENCE` | 问答最终 Prompt 的骨架与证据块拼装 |
| 对话规划与记忆（4 个） | `AGENT_QUESTION / CHAT_QUERY_REWRITE / CONVERSATION_SUMMARY_SYSTEM / CONVERSATION_SUMMARY_MERGE` | 执行计划生成、查询改写、会话长期摘要 |
| 文档意图路由（1 个） | `DOCUMENT_GRAPH_ONLY_INTENT` | 判断问题是否只需查图谱 |
| 文档管理（3 个） | `DOCUMENT_LLM_SPLIT / DOCUMENT_STRUCTURE_AMBIGUITY / DOCUMENT_STRUCTURE_AMBIGUITY_CANDIDATE` | 文档 LLM 拆分、结构歧义判定 |
| 推荐（1 个） | `RECOMMENDATION_USER` | 追问/推荐问题生成 |

### 3.3 最典型调用方 RagPromptAssemblyService（面试最常被追问的组装器）

| 方法 | 可见性 | 参数类型 | 返回值类型 | 职责 |
|---|---|---|---|---|
| `assemble(plan, context)` | public | `ConversationExecutionPlan, RagRetrievalContext` | `RagPromptAssemblyResult`（含 systemPrompt、userPrompt、预算与省略统计） | 一次性组装完整 RAG Prompt |
| `buildSystemPrompt()` | public | 无 | `String` | 配置了自定义 systemPrompt 就用配置，否则渲染 `RAG_ANSWER_SYSTEM` |
| `buildUserPrompt(plan, context)` | public | `ConversationExecutionPlan, RagRetrievalContext` | `String` | 便捷方法，内部转调 `assemble(...).getUserPrompt()` |
| `buildEvidenceBlocks(...)` | private | `RagRetrievalContext, Set<String>, PromptBudget` | `String` | 遍历每个子问题的证据，逐块渲染 |
| `appendReferences(...)` | private | `StringBuilder, List<SearchReference>, Set<String>, PromptBudget` | `void` | 逐条渲染引用（网页/文档/重复/无证据/超预算），写入预算控制 |
| `buildWebReferenceBlock(reference)` | private | `SearchReference` | `String` | 渲染网页引用块（snippet 截断 900 字） |
| `buildDocumentReferenceBlock(reference)` | private | `SearchReference` | `String` | 渲染文档引用块（snippet 截断 1100 字） |
| `PromptBudget.tryConsume(size)` | private 内部类 | `int size` | `boolean` | 两级预算扣减：总预算 + 单子问题预算，装不下返回 false |
| `PromptBudget.markRendered / markOmitted` | private 内部类 | `String detail` | `void` | 记录"已纳入/已省略"引用明细，写入链路追踪 |

---

## 四、render() 内部执行顺序（4 步，面试必背）

```
render("rag-answer-user", variables)
 │
 ├─ ① normalizeTemplatePath(templateName)          String → String
 │     "rag-answer-user"            → "prompt/rag-answer-user.st"
 │     "/prompt/rag-answer-user.st" → "prompt/rag-answer-user.st"   (去掉前导 /)
 │     "classpath:prompt/xx.st"     → "prompt/xx.st"                (去掉 classpath: 前缀)
 │     无 prompt/ 前缀则补、无 .st 后缀则补
 │
 ├─ ② templateCache.computeIfAbsent(path, this::loadTemplate)
 │     缓存命中 → 直接拿模板文本（O(1)，无 IO）
 │     缓存未命中（仅首次）→ loadTemplate(path)：
 │         resourceLoader.getResource("classpath:" + path)
 │         ├─ 资源不存在 → throw IllegalArgumentException("Prompt 模板不存在: ...")
 │         └─ UTF-8 InputStreamReader + FileCopyUtils.copyToString 读全文
 │             └─ 读失败 → throw IllegalStateException("读取 Prompt 模板失败: ...", e)
 │
 ├─ ③ normalizeVariables(variables)                Map<String,?> → Map<String,Object>
 │     null 值 → ""（LinkedHashMap 保持变量插入顺序）
 │
 └─ ④ templateRenderer.apply(template, normalizedVars).trim()
       StringTemplate4 按 <变量>/<if(...)> 语法渲染
       ValidationMode.THROW：变量缺失/多传 → 抛异常（fail-fast，不静默出错 Prompt）
       .trim()：去掉模板文件首尾空行，保证 Prompt 干净
       ↓
     return 最终 Prompt 字符串（String）
```

**两个设计细节（追问高频点）：**

- **为什么缓存用 `ConcurrentHashMap` + `computeIfAbsent`？** 模板是 classpath 只读资源，进程内内容永不变化；`computeIfAbsent` 保证并发下同一模板只加载一次，且读取路径不加全局锁，渲染主路径无锁竞争。
- **为什么 `normalizeVariables` 要把 null 换成 `""`？** ST4 对 null 变量默认渲染报错或输出异常内容；业务侧大量使用 `Map.of(...)`（不允许 null 值），统一在引擎侧兜底后，调用方可以放心传"可能为空的业务值先转空串"。

---

## 五、ASCII 函数关系调用图

### 5.1 模块内部（PromptTemplateService 自身）

```
 业务方调用
     │
     ▼
+----------------------------------------------------------------------+
| PromptTemplateService.render(String templateName,                    |
|                               Map<String,?> variables)  : String     |
+----------------------------------------------------------------------+
     |
     |-- 1> normalizeTemplatePath(String) ................. -> String
     |        (去 classpath: 前缀 / 去前导 '/' / 补 "prompt/" / 补 ".st")
     |
     |-- 2> templateCache.computeIfAbsent(path, this::loadTemplate)
     |            |
     |            +-- [首次未命中] loadTemplate(String) .... -> String
     |                    |-- resourceLoader.getResource("classpath:"+path)
     |                    |-- 不存在 --> IllegalArgumentException
     |                    +-- UTF-8 读取, 失败 --> IllegalStateException
     |
     |-- 3> normalizeVariables(Map<String,?>) ............. -> Map<String,Object>
     |        (null --> "", LinkedHashMap 保序)
     |
     +-- 4> templateRenderer.apply(template, vars).trim()  -> String
              (StTemplateRenderer: ST4 引擎, < > 分隔符,
               ValidationMode.THROW 缺变量即抛异常)

PromptTemplateNames (final 常量类, 私有构造)
     +-- 17 个 public static final String  -->  仅作为 render() 的第一个入参
```

### 5.2 模块外部（谁在调用 render，一共 8 个业务类、17 个调用点）

```
                              [用户在聊天窗口提问]
                                      |
                                      v
  +-----------------------------------------------------------------------------------+
  | BusinessChatService (chatagent/service)                                           |
  |   buildAgentQuestion(ConversationExecutionPlan) : String                          |
  |     └─ render(AGENT_QUESTION, {currentDateText, requiresCurrentDateAnchoring,     |
  |            requiresFreshSearch, hasHistorySummary, ...})                 <步骤1>  |
  +-----------------------------------------------------------------------------------+
                                      |  (生成执行计划 plan)
                                      v
  +-----------------------------------------------------------------------------------+
  | ChatPreparationOrchestrator.prepare(TaskInfo) : ConversationExecutionPlan         |
  |   |                                                                               |
  |   |-- summarizeHistory() --> ConversationMemoryService                            |
  |   |     └─ PersistentConversationMemoryService (chatagent/service)                |
  |   |          mergeSummaryPayload(existing, batch, trace) : ConversationSummaryPayload
  |   |            ├─ render(CONVERSATION_SUMMARY_SYSTEM, {})       <步骤2>           |
  |   |            └─ buildSummaryMergePrompt(...) : String                           |
  |   |                 └─ render(CONVERSATION_SUMMARY_MERGE,                         |
  |   |                      {existingSummaryJson, newConversationBatch})   <步骤3>   |
  |   |                                                                               |
  |   |-- ChatQueryRewriteService.rewrite(question, historySummary, trace)            |
  |   |     : RagRewriteResult                (chatagent/rag/service)                 |
  |   |     └─ render(CHAT_QUERY_REWRITE, {question, historySummary, ...})  <步骤4>   |
  |   |                                                                               |
  |   └-- DocumentQuestionRouter.route(...)                   (chatagent/rag/service) |
  |         classifyQuestionIntentWithModel(original, rewritten, route, local)        |
  |         : DocumentQuestionIntentDecision                                          |
  |         └─ render(DOCUMENT_GRAPH_ONLY_INTENT,                                     |
  |                {originalQuestion, rewrittenQuestion, routeText})        <步骤5>   |
  +-----------------------------------------------------------------------------------+
                                      |  (plan 就绪, 进入检索)
                                      v
                        RagRetrievalEngine 检索证据 (不涉及模板)
                                      |
                                      v
  +-----------------------------------------------------------------------------------+
  | RagChatExecutor.streamFromRetrievalContext(taskInfo, plan, context) : Flux<String>|
  |   └─ RagPromptAssemblyService.assemble(plan, context) : RagPromptAssemblyResult   |
  |        |                                                                          |
  |        |-- [先] buildEvidenceBlocks(context, keys, budget) : String               |
  |        |     └─ 对每个子问题: appendReferences(...) : void                        |
  |        |          ├─ render(RAG_ANSWER_SUB_QUESTION_EVIDENCE,            <步骤6>  |
  |        |          │      {subQuestionIndex, subQuestion, references})             |
  |        |          └─ 逐条引用:                                                    |
  |        |             ├─ 网页来源: buildWebReferenceBlock(ref) : String            |
  |        |             │   └─ render(RAG_ANSWER_WEB_REFERENCE,             <步骤7a> |
  |        |             │          {referenceId, title, url, snippet})               |
  |        |             ├─ 文档来源: buildDocumentReferenceBlock(ref) : String       |
  |        |             │   └─ render(RAG_ANSWER_DOCUMENT_REFERENCE,        <步骤7b> |
  |        |             │          {referenceId, documentName, sectionPath,          |
  |        |             │           snippet})                                        |
  |        |             ├─ 重复引用: render(RAG_ANSWER_REUSE_REFERENCE,     <步骤7c> |
  |        |             │      {referenceId})  ← renderedReferenceKeys 去重          |
  |        |             ├─ 无证据:   render(RAG_ANSWER_NO_EVIDENCE, {})     <步骤7d> |
  |        |             └─ 超预算:   render(RAG_ANSWER_OMITTED_EVIDENCE, {}) <步骤7e>|
  |        |                  (PromptBudget.tryConsume(int) : boolean 两级预算控制)   |
  |        |                                                                          |
  |        |-- [后] render(RAG_ANSWER_USER, {currentDate, originalQuestion,  <步骤8>  |
  |        │            hasRetrievalQuestion, retrievalQuestion, hasHistoryContext,   |
  |        │            historyContext, hasSubQuestions, subQuestions,                |
  |        │            evidenceBlocks})                                              |
  |        │            ← 注意: evidenceBlocks 由前面步骤6/7 先渲染好再当变量传入      |
  |        │                                                                          |
  |        └─ buildSystemPrompt() : String                                            |
  |             └─ 配置有自定义 systemPrompt ? 直接用 : render(                <步骤9> |
  |                    RAG_ANSWER_SYSTEM, {})                                         |
  +-----------------------------------------------------------------------------------+
                                      |  (systemPrompt + userPrompt)
                                      v
              ObservedChatModelService.streamText("rag_answer", systemPrompt,
                   userPrompt, trace) : Flux<String>   ──────►  LLM 流式生成回答
```

### 5.3 两条旁路链路（不在聊天主流程里）

```
 [旁路A: 推荐问题]  RecommendationService.buildRecommendPrompt(...)
      └─ render(RECOMMENDATION_USER, {basePrompt, recentContext, question, answer})

 [旁路B: 文档管理]
      DocumentStrategyServiceImpl.llmSplit(chatModel, sourceText) : List<String>
      └─ render(DOCUMENT_LLM_SPLIT, {…})          ← 文档导入时 LLM 拆分段落

      DocumentStructureAmbiguityResolver          ← 结构解析歧义时 LLM 仲裁
      ├─ buildPrompt(documentTitle, ambiguousSignals, allLines) : String
      │   └─ render(DOCUMENT_STRUCTURE_AMBIGUITY, {documentTitle, candidateBlocks})
      └─ buildCandidateBlocks(ambiguousSignals, allLines) : String
          └─ 对每个歧义行: render(DOCUMENT_STRUCTURE_AMBIGUITY_CANDIDATE,
                 {lineNo, contextLines, initialKind, initialTitle, initialCode})
                 ← candidateBlocks 先渲染好, 再作为变量传给外层模板 (嵌套组装, 同 RAG)
```

---

## 六、执行顺序总结（按一次 RAG 问答的时间线）

| 步骤 | 触发方法 | 渲染的模板 | 所处阶段 |
|---|---|---|---|
| 1 | `BusinessChatService.buildAgentQuestion()` | `AGENT_QUESTION` | 规划：生成 Agent 执行问题 |
| 2 | `PersistentConversationMemoryService.mergeSummaryPayload()` | `CONVERSATION_SUMMARY_SYSTEM` | 记忆：长期摘要压缩的 system 提示 |
| 3 | 同上类 `buildSummaryMergePrompt()` | `CONVERSATION_SUMMARY_MERGE` | 记忆：旧摘要 + 新对话批次合并 |
| 4 | `ChatQueryRewriteService.rewrite()` | `CHAT_QUERY_REWRITE` | 检索前：结合历史改写用户问题 |
| 5 | `DocumentQuestionRouter.classifyQuestionIntentWithModel()` | `DOCUMENT_GRAPH_ONLY_INTENT` | 路由：判断"只查图谱"意图 |
| 6–7 | `RagPromptAssemblyService.buildEvidenceBlocks()` → `appendReferences()` | `SUB_QUESTION_EVIDENCE` / `WEB_REFERENCE` / `DOCUMENT_REFERENCE` / `REUSE_REFERENCE` / `NO_EVIDENCE` / `OMITTED_EVIDENCE` | 组装：逐子问题渲染证据块（受 PromptBudget 预算约束） |
| 8 | `RagPromptAssemblyService.assemble()` | `RAG_ANSWER_USER` | 组装：用户 Prompt 主模板 |
| 9 | `RagPromptAssemblyService.buildSystemPrompt()` | `RAG_ANSWER_SYSTEM` | 组装：系统 Prompt（可被配置覆盖） |
| 10 | `ObservedChatModelService.streamText(...)` | —（消费前 9 步产物） | 调用 LLM 流式生成回答 |

记忆顺序上注意两点：

1. **先拼证据块（6/7），再渲染主模板（8）**——因为证据块整体是主模板的一个变量 `evidenceBlocks`，这是"子模板先渲染、再作为变量传给父模板"的嵌套组装模式；`DocumentStructureAmbiguityResolver` 的 `candidateBlocks` 也是同样的模式。
2. **步骤 2/3（摘要合并）只在需要压缩长期记忆时发生**，是懒触发；步骤 1/4/5 在 `prepare()` 阶段，步骤 6–9 在检索完成后。

---

## 七、模板语法示例（以 `rag-answer-user.st` 为例）

```
当前日期：<currentDate>

用户原始问题：
<originalQuestion>

<if(hasRetrievalQuestion)>检索理解后的问题：
<retrievalQuestion>

<endif><if(hasHistoryContext)><historyContext>

<endif><if(hasSubQuestions)>请按下面这些子问题逐一回答：
<subQuestions>

<endif>证据材料：
<evidenceBlocks>
```

要点：

- 占位符语法 `<变量名>`，与引擎配置的 `<` `>` 分隔符一致；
- `<if(布尔变量)>...<endif>` 控制可选段落（"有没有检索改写后的问题 / 有没有历史上下文 / 有没有子问题"），布尔变量由调用方预先计算好传入（如 `hasRetrievalQuestion`、`hasHistoryContext`）——**模板不做逻辑运算，逻辑全部收敛在 Java 侧**，这是"模板保持简单、Java 保持可控"的取舍；
- 证据块 `evidenceBlocks` 是一段已经渲染好的多行文本，由 Java 侧逐块拼接后整体传入。

---

## 八、面试讲述脚本

### 8.1 30 秒电梯版

> 我们项目里所有发给大模型的提示词都不在代码里拼字符串，而是统一放在 `resources/prompt` 下的 17 个 `.st` 模板文件里。`PromptTemplateService.render(模板名, 变量Map)` 是唯一入口：先把模板名规范化成 `prompt/xxx.st` 路径，再从 `ConcurrentHashMap` 缓存里取模板文本（没命中就用 Spring 的 `ResourceLoader` 以 UTF-8 读一次并缓存），变量 Map 会把 null 归一成空串，最后交给基于 StringTemplate4 的 `StTemplateRenderer` 渲染并 trim 返回。渲染器配置了 `ValidationMode.THROW`，模板需要但调用方没传的变量会直接抛异常，属于 fail-fast 设计。模板名收敛在 `PromptTemplateNames` 常量类里，17 个模板覆盖 RAG 问答、查询改写、会话摘要、意图路由、文档拆分和推荐六个业务域。

### 8.2 3 分钟完整版（按"问题 → 方案 → 细节 → 价值"讲）

> **问题**：RAG 问答的提示词很长很复杂——有 system 骨架、用户问题、历史上下文、子问题列表、证据块、引用格式，而且证据块还要区分网页来源和文档来源、处理重复引用和预算裁剪。如果这些都在 Java 里硬编码拼接，改一个措辞就要发版，也没法统一审查提示词质量。
>
> **方案**：我们做了统一的 Prompt 模板管理。所有提示词都是 `resources/prompt` 下的 StringTemplate4 模板文件，`PromptTemplateService` 是唯一的渲染出口，`PromptTemplateNames` 把模板名收敛成编译期常量，防止调错名字。
>
> **实现细节**（这是加分段）：
> 1. `render()` 里做了四件事：路径规范化（调用方传 `xxx`、`/prompt/xxx.st`、`classpath:prompt/xxx.st` 都能兜住）、模板文本缓存（`ConcurrentHashMap.computeIfAbsent`，classpath 只读资源进程内只加载一次，并发下也只加载一次）、变量归一（null 转 `""`，因为 ST4 渲染 null 会出问题，同时业务侧 `Map.of` 又不允许 null 值）、最后交给 ST4 渲染器输出并 trim。
> 2. 渲染器用 `<` `>` 做分隔符，配 `ValidationMode.THROW` 严格校验：模板里写了但没传的变量直接抛异常，避免静默生成残缺 Prompt 去调 LLM，浪费一次昂贵的模型调用。
> 3. 最复杂的调用方是 `RagPromptAssemblyService`：它用一个内部类 `PromptBudget` 做两级字符预算（总预算 + 每个子问题的预算），逐条渲染证据引用时先 `tryConsume` 扣预算，装不下就停止渲染并记录"已省略"明细；重复引用通过 `LinkedHashSet` 按 uniqueKey 去重，只渲染一行"同参考[X]"。渲染的顺序是先逐块渲染子问题证据块，再把拼好的证据文本作为变量传给 `RAG_ANSWER_USER` 主模板，system Prompt 单独由 `RAG_ANSWER_SYSTEM` 渲染（运营配置了自定义 systemPrompt 时优先用配置）。最终产物 `RagPromptAssemblyResult` 连同预算统计、省略明细一起写进链路追踪，方便排查"为什么模型没看到某条引用"。
> 4. 失败处理分级：模板不存在抛 `IllegalArgumentException`（编码错误，快速失败）；读文件失败抛 `IllegalStateException`（包装原始异常保留根因）；而 LLM 调用失败则有业务兜底，比如会话摘要合并失败会降级到规则压缩 `fallbackMerge`。
>
> **价值**：提示词修改零代码发布、全项目提示词调用点可以一 grep 全览（`render(PromptTemplateNames.`）、模板严格校验把问题拦在调用 LLM 之前、预算与省略明细让长上下文裁剪可观测。

---

## 九、面试追问 Q&A

**Q1：为什么选 StringTemplate4，而不是 `String.format` / `MessageFormat` / Freemarker？**
A：`String.format` 只能按位置替换，模板里全是 `%s` 无法维护；ST4 支持**命名占位 + 条件段（`<if>`）**，语法极简且是纯文本输出（不会像某些引擎偷偷加 HTML 转义），还有严格的"引用了未设置属性就报错"语义，天然适合 Prompt 这种纯文本 + 可选段落的场景。Spring AI 官方也直接提供了 `StTemplateRenderer` 封装，接入成本低。

**Q2：为什么分隔符选 `<` `>`？会不会和模板内容冲突？**
A：ST4 默认分隔符是 `$...$` 或 `<...>` 可配；选 `<>` 后模板可读性好（一眼看出占位符）。Prompt 正文里如果确实要输出字面 `<`，需要转义处理，但我们的模板是给模型看的中文说明文本，冲突概率低，且模板全部集中在 `prompt/` 目录可控。

**Q3：模板缓存为什么不用 Caffeine/带过期的缓存？**
A：模板是打包进 jar 的 classpath 只读资源，运行期内容不变，所以用 `ConcurrentHashMap` 永久缓存即可，没有失效问题；`computeIfAbsent` 已保证并发只加载一次。如果未来要支持外置热更新模板，再升级为带刷新策略的缓存。

**Q4：`ValidationMode.THROW` 和宽松模式的取舍？**
A：一次 LLM 调用又慢又贵，残缺 Prompt 的代价远大于启动/调用时抛异常。THROW 模式让"模板与调用方变量契约"的破坏在第一时间暴露（fail-fast），配合模板集中管理，回归测试成本低。

**Q5：证据太长怎么办？模型上下文超限怎么办？**
A：`PromptBudget` 两级字符预算：`totalEvidenceMaxChars`（全部证据总预算）+ `perSubQuestionEvidenceMaxChars`（单个子问题预算，每换一个子问题重置）。渲染每条引用前 `tryConsume(length)`，装不下立即止损并 `markOmitted` 记录省略原因与明细；这些明细随 `RagPromptAssemblyResult` 返回并写入 trace，链路里能精确回答"这条引用为什么没进 Prompt"。同时每条引用的 snippet 在渲染前还有硬截断（网页 900 字、文档 1100 字）。

**Q6：同一条引用在多个子问题里都检索到了，会不会重复占预算？**
A：不会。`renderedReferenceKeys`（`LinkedHashSet<String>`）按 `reference.uniqueKey()` 去重，第二次遇到只渲染一行 `RAG_ANSWER_REUSE_REFERENCE`（"同参考[X]"），成本极小。

**Q7：system Prompt 为什么也走模板？**
A：为了兜底统一。`buildSystemPrompt()` 优先用配置中心下发的 `answerSystemPrompt`，没配才渲染 `RAG_ANSWER_SYSTEM`——运营可以不发版调整 system 提示词，模板只是保底默认值。

**Q8：这套模板机制覆盖了哪些业务？**
A：六大场景、8 个业务类、17 个调用点：① RAG 问答最终 Prompt 组装（9 个模板）；② 对话规划与查询改写（AGENT_QUESTION、CHAT_QUERY_REWRITE）；③ 会话长期记忆摘要（SUMMARY_SYSTEM/MERGE，LLM 失败降级规则压缩）；④ 文档问题意图路由（GRAPH_ONLY_INTENT，一次 LLM 调用同时判意图）；⑤ 文档导入的 LLM 拆分（LLM_SPLIT）与结构歧义仲裁（AMBIGUITY + CANDIDATE）；⑥ 下一问推荐（RECOMMENDATION_USER）。

---

## 十、一页速记（面试前 1 分钟看这里）

```
核心: PromptTemplateService.render(String, Map<String,?>) -> String   ← 唯一入口
      内部 4 步: 规范路径 → computeIfAbsent 缓存/加载 → null→"" → ST4 渲染+trim
引擎: StTemplateRenderer (Spring AI 封装 StringTemplate4)
      分隔符 < >  |  ValidationMode.THROW (缺变量抛异常, fail-fast)
      loadTemplate: ResourceLoader 读 classpath, UTF-8
      异常: 不存在=IllegalArgumentException / 读取失败=IllegalStateException
常量: PromptTemplateNames (final, 私有构造, 17 个 String 常量)
      RAG_ANSWER_* ×8 | 规划改写摘要 ×4 | 图谱意图 ×1 | 文档 ×3 | 推荐 ×1

主链路顺序:
  buildAgentQuestion(AGENT_QUESTION)                 ← 规划
  → 摘要合并(SUMMARY_SYSTEM + SUMMARY_MERGE)          ← 记忆(懒触发)
  → rewrite(CHAT_QUERY_REWRITE)                      ← 查询改写
  → 路由(GRAPH_ONLY_INTENT)                          ← 意图
  → 检索(无模板)
  → assemble: 先逐块渲染证据(6/7, PromptBudget 两级预算+去重)
              再渲染 USER 主模板(8) ← 证据块作为变量传入
              最后 SYSTEM(9, 配置优先)
  → LLM streamText

亮点关键词: 模板与代码解耦 | 单一出口可追溯 | fail-fast 校验
            ConcurrentHashMap 无锁渲染路径 | 两级字符预算可观测
            子模板先渲染再传入父模板(嵌套组装) | LLM 失败规则兜底
```
