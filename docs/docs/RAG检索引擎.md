# RAG 检索引擎（双通道混合检索）深度剖析

> 模块位置：`nexus-agent-business/nexus-agent-business-chat/src/main/java/org/javaup/ai/chatagent/rag/`
> 这是项目中最复杂的模块之一，涉及**双通道检索、RRF 融合排序、证据闸门、父块提升、Rerank 重排、证据预算控制**等。

---

## 一、整体架构一图流（ASCII 函数调用关系图）

```
                            ┌─────────────────────────────────────────────────────────────┐
                            │  ConversationExecutorRegistry.get(RETRIEVAL)                │
                            │  (按执行模式路由到知识问答执行器)                             │
                            └──────────────────────────────┬──────────────────────────────┘
                                                           │ execute(TaskInfo): Flux<String>
                                                           ▼
        ┌──────────────────────────────────────────────────────────────────────────────────────┐
        │ RagChatExecutor.execute(TaskInfo)                                     [上游编排层]   │
        │                                                                                      │
        │  ① Mono.fromCallable(─► ragRetrievalEngine.retrieve(plan, traceRecorder))            │
        │     .subscribeOn(boundedElastic)          ← 检索阻塞操作切到弹性线程池                │
        │                                                                                      │
        │  ② streamFromRetrievalContext(taskInfo, plan, context)                               │
        │     ├─ context.isEmpty()? ──是──► 返回兜底回复 noEvidenceReply                        │
        │     └─否─► ragPromptAssemblyService.assemble(plan, context)                          │
        │                 │ 返回 RagPromptAssemblyResult(systemPrompt, userPrompt, 预算明细)    │
        │                 ▼                                                                    │
        │     observedChatModelService.streamText("rag_answer", systemPrompt, userPrompt, ...) │
        │                                                 （流式生成最终答案）                  │
        └──────────────────────────────┬───────────────────────────────────────────────────────┘
                                       │
═════════════════════════════════════════════════════════════════════════════════════════════════
                                       ▼
        ┌──────────────────────────────────────────────────────────────────────────────────────┐
        │ RagRetrievalEngine.retrieve(plan, traceRecorder): RagRetrievalContext  [检索总入口]   │
        │                                                                                      │
        │  1. 初始化 RagRetrievalContext（usedChannels/retrievalNotes 为同步 List）             │
        │  2. 取子问题列表（无子问题则降级为 [retrievalQuestion] 单问题）                        │
        │  3. 对每个子问题 i：                                                                  │
        │     CompletableFuture.supplyAsync(─► retrieveSingleSubQuestion(i, q, plan, ...),     │
        │                                    chatRagExecutorService)                           │
        │       .orTimeout(subQuestionTimeoutMs = 12000ms)   ← 子问题级超时                    │
        │       .exceptionally(─► 空证据 SubQuestionEvidence，失败不拖垮整体)                   │
        │  4. join 所有 future ──► List<SubQuestionEvidence>                                   │
        │  5. assignReferenceIds(evidenceList)   ← 全局去重后分配引用编号 [1][2][3]...          │
        │     └─ SearchReferenceMapper.fromDocument(document, subQIndex, subQ, 0)              │
        │  6. return context（subQuestionEvidenceList 已填充）                                 │
        └──────────────────────────────┬───────────────────────────────────────────────────────┘
                                       │（每个子问题并发执行一次）
                                       ▼
        ┌──────────────────────────────────────────────────────────────────────────────────────┐
        │ RagRetrievalEngine.retrieveSingleSubQuestion(...)     [单子问题检索流水线，私有方法]   │
        │                                                                                      │
        │  STEP A 双通道并发召回                                                                │
        │  ┌──────────────────────────────────────────────────────────────────────────┐        │
        │  │ retrievalChannels.stream()                                               │        │
        │  │   .filter(ch -> ch.supports(plan))          ← 通道是否适用（文档级过滤）  │        │
        │  │   .map(ch -> supplyAsync(─► ch.retrieve(subQuestion, plan))              │        │
        │  │               .orTimeout(channelTimeoutMs = 5000ms)  ← 通道级超时        │        │
        │  │               .exceptionally(─► 空结果降级))                              │        │
        │  │   ──join──► rawChannelResults: List<RetrievalChannelResult>              │        │
        │  │                                                                          │        │
        │  │   ┌──────────────────────────────┐      ┌──────────────────────────────┐ │        │
        │  │   │ VectorRetrievalChannel       │      │ KeywordRetrievalChannel      │ │        │
        │  │   │ .retrieve(subQ, plan)        │      │ .retrieve(subQ, plan)        │ │        │
        │  │   │  │                           │      │  │                           │ │        │
        │  │   │  ▼                           │      │ ▼                            │ │        │
        │  │   │ requestFactory.build(subQ,   │      │ requestFactory.build(subQ,   │ │        │
        │  │   │   plan, vectorTopK=8)        │      │   plan, keywordTopK=8)       │ │        │
        │  │   │  ▼                           │      │ ▼                            │ │        │
        │  │   │ knowledgeService.vectorSearch│      │ knowledgeService.keywordSearch│ │        │
        │  │   │   (request)                  │      │   (request)                  │ │        │
        │  │   └──────────────────────────────┘      └──────────────────────────────┘ │        │
        │  └──────────────────────────────────────────────────────────────────────────┘        │
        │                                                                                      │
        │  STEP B 证据闸门（每通道结果逐一过滤）applyEvidenceGate(result)                        │
        │     ├─ "vector"  ─► filterVectorCandidates:  分数 ≥ minVectorSimilarity(0.45)        │
        │     └─ "keyword" ─► filterKeywordCandidates: 分数 ≥ 最高分×relativeFloor(0.35)       │
        │                                                                                      │
        │  STEP C RRF 融合排序  fuseByRrf(channelResults)                                      │
        │     └─ accumulateRrf(result, holders)  对每通道按排名累加 1/(60+rank+1)               │
        │     排序取 candidateTopK=10，metadata 写入 RRF_SCORE / CHANNEL(=hybrid?)              │
        │                                                                                      │
        │  STEP D 父块提升                                                                     │
        │     documentKnowledgeService.elevateToParentBlocks(merged, parentMaxChars=2200)      │
        │                                                                                      │
        │  STEP E Rerank 重排   applyRerank(subQ, parentCandidates, usedChannels)              │
        │     └─ rerankPostProcessor.process(new Query(subQ), candidates)                      │
        │        = HttpDocumentRerankPostProcessor.process(...)   （调用外部 rerank API）      │
        │                                                                                      │
        │  STEP F 截断   finalDocuments = reranked.limit(finalTopK = 5)                        │
        │                                                                                      │
        │  STEP G 观测埋点 recordChannelObservations / recordRetrievalResultObservations       │
        │                                                                                      │
        │  return SubQuestionEvidence(index, subQ, finalDocuments, refs, traces,               │
        │                             fusedCount, parentCount, rerankedCount)                  │
        └──────────────────────────────────────────────────────────────────────────────────────┘
                    │                                          │
                    ▼（vector 通道落地）                        ▼（keyword 通道落地）
   ┌───────────────────────────────────────┐   ┌────────────────────────────────────────────┐
   │ DocumentKnowledgeServiceImpl          │   │ DocumentKnowledgeServiceImpl               │
   │ .vectorSearch(request)                │   │ .keywordSearch(request)                    │
   │                                       │   │                                            │
   │ 1 embeddingModel.embed(query)         │   │ ES 开启? ──是──► ElasticsearchDocument-    │
   │   ─► float[] ─► toVectorLiteral       │   │   KeywordSearchGateway.search(request)     │
   │   ─► "[0.12,0.83,...]"                │   │   （ElasticsearchClient multi_match 查询） │
   │ 2 PGVector SQL:                       │   │      │否（降级）                            │
   │   1 - (embedding <=> CAST(? AS vector))│  │      ▼                                      │
   │   AS similarity_score                 │   │  extractKeywordTerms(query)  ←中文n-gram/   │
   │   WHERE status=1 AND document_id IN.. │   │   停用词清洗/最多8个词                      │
   │   ORDER BY embedding <=> ?            │   │  PG SQL LIKE 加权打分 keyword_score         │
   │   LIMIT topK                          │   │  ORDER BY keyword_score DESC LIMIT topK     │
   │ 3 buildRetrievedDocument(...)         │   │ 3 buildRetrievedDocument(...)               │
   │   ─► List<Document>(含元数据)         │   │   ─► List<Document>(含元数据)               │
   └───────────────────────────────────────┘   └────────────────────────────────────────────┘

                    ▼（父块提升落地）
   ┌───────────────────────────────────────────────────────────────────────────────────────┐
   │ DocumentKnowledgeServiceImpl.elevateToParentBlocks(childDocuments, maxChars)          │
   │  1 按 metadata.parentBlockId 分组（无 parentId 的进 fallback 列表原样保留）            │
   │  2 parentBlockMapper.selectList(...) 批量查父块原文                                    │
   │  3 buildParentEvidenceDocument(parentBlock, children, maxChars) 每组生成一个父块文档   │
   │     ├─ renderParentEvidenceText: "[父块内容]+[命中子片段]摘要"，超长截断 maxChars      │
   │     └─ aggregateParentScore: 最优子分 × (1 + 支撑权重 + 多通道加权)                    │
   │  4 compareEvidenceDocument 排序（分数↓ → parentNo↑ → chunkNo↑）                       │
   │  ─► List<Document>                                                                    │
   └───────────────────────────────────────────────────────────────────────────────────────┘

                    ▼（Rerank 落地）
   ┌───────────────────────────────────────────────────────────────────────────────────────┐
   │ HttpDocumentRerankPostProcessor.process(Query query, List<Document> documents)        │
   │  （实现 Spring AI 的 DocumentPostProcessor 接口）                                      │
   │  0 未启用/无 apiKey/空列表 ──► 直接原样返回（自动降级）                                │
   │  1 restClient.post() ─► 外部 rerank API（硅基流动 bge-reranker-v2-m3）                │
   │     body: {model, query, documents, top_n, return_documents:false}                    │
   │  2 解析 results[]，按 relevance_score 降序映射回原文档，metadata 写 rerankScore 等     │
   │  3 limit(topN)；任何异常 ──► catch 后返回原始排序（绝不阻塞主流程）                    │
   └───────────────────────────────────────────────────────────────────────────────────────┘

                    ▼（证据预算落地，回到 RagChatExecutor 之后）
   ┌───────────────────────────────────────────────────────────────────────────────────────┐
   │ RagPromptAssemblyService.assemble(plan, context): RagPromptAssemblyResult             │
   │  1 new PromptBudget(totalEvidenceMaxChars=5200, perSubQEvidenceMaxChars=2200)         │
   │  2 buildEvidenceBlocks(context, renderedKeys, budget)                                 │
   │     └─ 每个子问题 appendReferences(builder, references, keys, budget)                 │
   │          ├─ 已渲染过的引用 ─► 只渲染"同[引用ID]"复用行                                 │
   │          ├─ tryConsume(块长度) 成功 ─► 渲染 + markRendered                            │
   │          └─ 预算不足 ─► markOmitted + break + 渲染"证据已省略"提示                    │
   │  3 promptTemplateService.render(RAG_ANSWER_USER, {...evidenceBlocks})                 │
   │  ─► (systemPrompt, userPrompt, 预算总量/已渲染数/已省略数及明细)                       │
   └───────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 二、各方法调用关系与执行顺序（逐层拆解）

### 2.0 上游：谁来调用检索引擎？

```
ConversationExecutorRegistry.get(ExecutionMode.RETRIEVAL)  →  RagChatExecutor
```

- `ConversationExecutorRegistry` 在构造时把所有 `ConversationExecutor` 实现按 `mode()` 收进 `EnumMap`；`RagChatExecutor.mode()` 返回 `ExecutionMode.RETRIEVAL`，所以知识问答请求被路由到它。
- `RagChatExecutor.execute(TaskInfo)` 是响应式入口（返回 `Flux<String>`）：
  1. 先发"正在执行双通道混合检索"的思考事件，开启 `RAG_RETRIEVE` 追踪阶段；
  2. `Mono.fromCallable(() -> ragRetrievalEngine.retrieve(plan, traceRecorder)).subscribeOn(Schedulers.boundedElastic())` —— **把同步阻塞的检索放到弹性调度器上，避免卡死 reactor 线程**；
  3. 检索成功后进入 `streamFromRetrievalContext()`：
     - 若 `context.isEmpty()`（所有子问题都没有引用）→ 直接流式返回 `noEvidenceReply` 兜底话术；
     - 否则开启 `EVIDENCE_BUDGET` 阶段，调用 `ragPromptAssemblyService.assemble(plan, context)` 组装 Prompt；
     - 最后开启 `ANSWER_GENERATE` 阶段，调用 `observedChatModelService.streamText("rag_answer", systemPrompt, userPrompt, traceRecorder)` 流式生成答案。

### 2.1 第一层：检索总入口 `RagRetrievalEngine.retrieve()`

**执行顺序：**

| 步骤 | 动作 | 关键点 |
|---|---|---|
| 1 | new `RagRetrievalContext`，初始化 `usedChannels`、`retrievalNotes` | 用 `Collections.synchronizedList` 包装，因为多线程并发写 |
| 2 | 取子问题列表 | `plan.getRetrievalSubQuestions()` 为空时降级为单问题 `List.of(retrievalQuestion)` |
| 3 | 每个子问题提交一个 `CompletableFuture.supplyAsync(..., chatRagExecutorService)` | **子问题之间并发执行**；线程池为固定 8 线程、队列 256、CallerRunsPolicy |
| 4 | `.orTimeout(subQuestionTimeoutMs=12s)` + `.exceptionally(...)` | 单个子问题超时/异常只会记一条 note 并返回**空证据**，不影响其他子问题（故障隔离） |
| 5 | `futures.stream().map(CompletableFuture::join).toList()` | 阻塞等全部完成 |
| 6 | `assignReferenceIds(evidenceList)` | 遍历每个文档 → `SearchReferenceMapper.fromDocument()` 转成 `SearchReference`；用 `uniqueKey()`（父块ID/块ID/URL）做 `LinkedHashMap` 去重，**全局递增分配引用编号 [1][2]...**，同一文档被多个子问题命中时复用同一编号 |
| 7 | 填充 context 并返回 | — |

### 2.2 第二层：单子问题流水线 `retrieveSingleSubQuestion()`（私有）

一个子问题内部的执行顺序，共 7 步（A→G）：

**STEP A 双通道并发召回**：`retrievalChannels` 是 Spring 注入的 `List<RetrievalChannel>`（运行时 = Vector + Keyword 两个实现）。
- 先 `channel.supports(plan)` 过滤：两个通道都要求 `plan.getSelectedDocumentId() != null`（必须已锁定文档），keyword 通道还要求 `keywordChannelEnabled=true`；
- 每个通道再包一层 `CompletableFuture`（同一线程池）+ `.orTimeout(channelTimeoutMs=5s)` + `.exceptionally` 降级为空结果 —— **通道级故障隔离**；
- `join` 得到 `rawChannelResults`（保留原始结果用于埋点对比）。

**STEP B 证据闸门 `applyEvidenceGate()`**：按通道名分派——
- vector → `filterVectorCandidates()`：绝对阈值，`score >= minVectorSimilarity (0.45)`，分数从 metadata 的 `SCORE` 或 `document.getScore()` 读取（`resolveScore()`）；
- keyword → `filterKeywordCandidates()`：**相对阈值**，取本通道最高分 × `keywordRelativeScoreFloor (0.35)` 作为及格线（关键词分数量纲不稳定，不能用固定阈值）；
- 过滤后非空的通道调用 `markUsedChannel()` 记入 `usedChannels`。

**STEP C RRF 融合排序 `fuseByRrf()`**：
- `accumulateRrf()` 按每个通道内的排名累加分数：`rrfScore = 1 / (60 + rank + 1)`（**RRF_K=60** 为业界经验常数，抑制头部排名的巨大分差）；
- 同一文档被两个通道同时召回 → 分数叠加，天然被排前（这就是"混合检索优于单通道"的数学表达）；
- 排序后 `limit(candidateTopK=10)`，写回 metadata：`SCORE`、`RRF_SCORE`，`CHANNEL` 命中多通道时改为 `"hybrid"`。

**STEP D 父块提升**：`documentKnowledgeService.elevateToParentBlocks(mergedCandidates, parentEvidenceMaxChars=2200)`（详见 2.4）。

**STEP E Rerank 重排 `applyRerank()`**：`rerankEnabled=true` 且候选非空时，`rerankPostProcessor.process(new Query(subQuestion), candidates)`（详见 2.5）。

**STEP F 最终截断**：`.limit(finalTopK=5)` 得到 `finalDocuments`。

**STEP G 观测埋点**：`buildChannelTraces()` 记录每通道"召回数/闸门后接受数"；`recordChannelObservations()` / `recordRetrievalResultObservations()` 把每篇文档的原始分、RRF 分、rerank 分、是否过闸门、最终排名、落选原因全部写进 `ConversationTraceRecorder`（供前端检索过程可视化）。埋点失败仅 warn，不影响主流程。

最后包装成 `SubQuestionEvidence`（含 fused/parent/reranked 三个阶段的数量，用于漏斗观测）。

### 2.3 第三层：两个检索通道

**`VectorRetrievalChannel.retrieve()`**（1 步）：
1. `documentRetrieveRequestFactory.build(subQuestion, plan, vectorTopK=8)` 构造检索请求；
2. `documentKnowledgeService.vectorSearch(request)` 执行 PGVector 语义检索。

**`KeywordRetrievalChannel.retrieve()`**：完全对称，`keywordTopK=8` + `keywordSearch(request)`。

**`DocumentRetrieveRequestFactory.build()`**（查询增强，两个通道共用）：
- `buildQueryAugmentation()`：若问题像**短追问**（长度<12 或含"它/这个/那个/刚才/前面/上面"），就把 `historyPlanningContext` 和 `navigationDecision` 里的上下文提示词拼接到检索词后面（**指代消解/查询补全**），同时产出 `queryContextHints`；
- `buildFilters()`：用正则从问题里抽取年份（`20\d{2}`）、章节（"第X章/附录A"）、文档名关键词（"手册/指南/FAQ"）等，生成 `DocumentRetrieveFilters`（下推到 SQL/ES 做 section_path 过滤）。

### 2.4 落地层：`DocumentKnowledgeServiceImpl`

**`vectorSearch(DocumentRetrieveRequest)`** —— PGVector 语义检索：
1. `isSearchableRequest()` 前置校验（question/retrievalQuery 非空、documentIds/taskIds 非空）；
2. `requireEmbeddingModel()` 拿 `EmbeddingModel`（`ObjectProvider` 懒获取，缺失直接抛异常）；
3. `embeddingModel.embed(query)` → `float[]` → `toVectorLiteral()` 拼成 PG vector 字面量 `"[0.12,0.83,...]"`；
4. 拼 SQL：`1 - (embedding <=> CAST(? AS vector)) AS similarity_score`（**余弦距离转相似度**），`WHERE status=1 AND document_id IN (...) AND task_id IN (...)` + 可选 section/structure 过滤，`ORDER BY embedding <=> ? LIMIT ?`；
5. `pgVectorJdbcTemplate.query(...)` 逐行 `buildRetrievedDocument()` → Spring AI `Document`，metadata 塞入 chunkId/parentBlockId/chunkNo/sectionPath/documentId/documentName/score/channel="vector" 等 18 个键。

**`keywordSearch(DocumentRetrieveRequest)`** —— 关键词检索（**双实现自动降级**）：
1. ES 开启（`app.manage.elasticsearch.enabled`）且网关 Bean 存在 → `ElasticsearchDocumentKeywordSearchGateway.search(request)`（ElasticsearchClient `multi_match`，同时还会把 `queryContextHints` 拼进查询）；
2. 否则降级为 PG 兜底实现：`extractKeywordTerms()` 抽词（英数字 token `[a-z0-9._-]{2,}` + 中文 2~4 元 n-gram，先剔除"请问/帮我/如何"等噪声短语，`的和及与或`切分，最多 8 个词），生成 LIKE 加权打分 SQL（`chunk_text` 命中权重 6-index，`section_path` 命中再 +2），`ORDER BY keyword_score DESC LIMIT ?`。

**`elevateToParentBlocks(List<Document> childDocuments, int maxChars)`** —— 父块提升（小块检索、大块回答）：
1. 遍历子块，按 metadata 的 `PARENT_BLOCK_ID` 分组（`LinkedHashMap` 保持顺序）；没有 parentId 的文档进 `fallbackDocuments` 原样保留；
2. `parentBlockMapper.selectList(... in(parentIds) ...)` 一次性查回所有父块（表 `super_agent_document_parent_block`）；
3. 每组调 `buildParentEvidenceDocument()`：
   - 以**得分最高的子块**的 metadata 为基底；
   - `renderParentEvidenceText()` 拼接 `"[父块内容]\n" + 父块全文` 与 `"[命中子片段]\n- child#no：摘要(140字)"`，超过 `maxChars` 截断加"…"；
   - `aggregateParentScore()`：`最优子块分 × (1 + min(0.36, 命中子块数-1)×0.12 + 多通道0.10)` —— **命中次数越多、跨通道命中的父块得分加成越高**；
   - 文档 id 改为 `"parent-{id}"`；
4. `compareEvidenceDocument()` 排序：分数降序 → parentNo 升序 → chunkNo 升序（同分段保持文档阅读顺序）。

### 2.5 落地层：`HttpDocumentRerankPostProcessor.process()`

实现 Spring AI 的 `DocumentPostProcessor` 接口（`process(Query query, List<Document> documents)`）：
1. **三重降级守卫**：文档空 → 原样返回；`rerank.enabled=false` → 原样返回；apiKey 空白 → warn + 原样返回；
2. 构造请求体 `{model: "BAAI/bge-reranker-v2-m3", query, documents:[文本...], top_n, return_documents:false}`，用带连接/读取超时（3s/6s）的 `RestClient` POST 到硅基流动 rerank 接口；
3. 响应 `results[]` 按 `relevance_score` 降序排序，`index` 映射回原始 Document，metadata 写入 `rerankScore/rerankModel/rerankQuery/rerankDurationMs/rerankOriginalIndex`，`limit(topN)`；
4. **整个调用包在 try-catch 里，任何异常都回退原始排序** —— rerank 是"锦上添花"，绝不能阻塞主链路。

### 2.6 末端：`RagPromptAssemblyService.assemble()` —— 证据预算控制

1. 创建内部类 `PromptBudget(totalEvidenceMaxChars=5200, perSubQuestionEvidenceMaxChars=2200)`，核心方法 `tryConsume(size)`：**双预算检查**（总预算 + 当前子问题预算都够才放行，同时扣减），每个子问题开始时 `resetSubQuestionBudget()`；
2. `buildEvidenceBlocks()`：遍历每个子问题的 references → `appendReferences()`：
   - `renderedReferenceKeys`（LinkedHashSet）按 `uniqueKey()` **跨子问题去重**：重复引用只渲染一行"同[引用ID]见上"（省 token）；
   - 每条引用按来源渲染：WEB 模板（title/url/snippet≤900字）或 DOCUMENT 模板（documentName/sectionPath/snippet≤1100字）；
   - 预算不够 → `markOmitted()` + break，并给模型渲染一句"部分证据因上下文预算被省略"（让模型知道证据不完整）；
3. 用 `PromptTemplateService.render(RAG_ANSWER_USER, ...)` 渲染用户 Prompt（含当前日期、原始问题、历史上下文、子问题列表、证据块）；
4. 返回 `RagPromptAssemblyResult(systemPrompt, userPrompt, totalBudget, perSubQuestionBudget, renderedReferenceCount, omittedReferenceCount, 两份明细)` —— 这些数字会进 trace，可观测"塞给大模型多少证据、省了哪些"。

---

## 三、方法签名速查表（参数类型 / 返回值类型）

### 3.1 RagChatExecutor（上游执行器）

| 方法 | 参数类型 | 返回值类型 | 可见性 |
|---|---|---|---|
| `mode()` | 无 | `ExecutionMode`（返回 `RETRIEVAL`） | public（接口实现） |
| `execute(TaskInfo)` | `TaskInfo`（record：executionPlan/traceRecorder/references/debugTrace 等） | `reactor.core.publisher.Flux<String>` | public |
| `streamFromRetrievalContext(...)` 私有 | `(TaskInfo, ConversationExecutionPlan, RagRetrievalContext)` | `Flux<String>` | private |

### 3.2 RagRetrievalEngine（检索总入口）

| 方法 | 参数类型 | 返回值类型 | 可见性 |
|---|---|---|---|
| 构造器 | `(List<RetrievalChannel>, ChatRagProperties, HttpDocumentRerankPostProcessor, DocumentKnowledgeService, @Qualifier("chatRagExecutorService") ExecutorService)` | — | public |
| `retrieve()` | `(ConversationExecutionPlan plan, ConversationTraceRecorder traceRecorder)` | `RagRetrievalContext` | public |
| `retrieveSingleSubQuestion()` | `(int subQuestionIndex, String subQuestion, ConversationExecutionPlan, List<String> usedChannels, List<String> notes, ConversationTraceRecorder)` | `SubQuestionEvidence` | private |
| `applyEvidenceGate()` | `(RetrievalChannelResult result)` | `RetrievalChannelResult` | private |
| `filterVectorCandidates()` | `(List<Document> documents)` | `List<Document>` | private |
| `filterKeywordCandidates()` | `(List<Document> documents)` | `List<Document>` | private |
| `fuseByRrf()` | `(List<RetrievalChannelResult> channelResults)` | `List<Document>` | private |
| `accumulateRrf()` | `(RetrievalChannelResult channelResult, Map<String, CandidateHolder> holders)` | `void` | private |
| `applyRerank()` | `(String subQuestion, List<Document> candidates, List<String> usedChannels)` | `List<Document>` | private |
| `assignReferenceIds()` | `(List<SubQuestionEvidence> evidenceList)` | `void` | private |
| `resolveScore()` | `(Document document)` | `Double`（可 null） | private |
| `buildChannelTraces()` | `(List<RetrievalChannelResult> rawResults, List<RetrievalChannelResult> filteredResults)` | `List<SubQuestionChannelTrace>` | private |
| `recordChannelObservations()` | `(ConversationTraceRecorder, int, String, List<RetrievalChannelResult>×2, List<SubQuestionChannelTrace>)` | `void` | private |
| `recordRetrievalResultObservations()` | `(ConversationTraceRecorder, int, String, List<RetrievalChannelResult>×2, List<Document>×3)` | `void` | private |
| `unwrapThrowable()` | `(Throwable)` | `Throwable` | private |
| 内部类 `CandidateHolder` | 字段：`Document document`、`LinkedHashSet<String> channels`、`double score` | — | private static |

### 3.3 检索通道（策略接口 + 两个实现）

```java
public interface RetrievalChannel {
    String channelName();                                          // → String
    boolean supports(ConversationExecutionPlan plan);              // → boolean
    RetrievalChannelResult retrieve(String subQuestion,
                                    ConversationExecutionPlan plan); // → RetrievalChannelResult
}
```

| 实现类 | channelName() 返回 | supports() 条件 | retrieve() 内部调用 |
|---|---|---|---|
| `VectorRetrievalChannel` | `"vector"`（`RetrievalChannelEnum.VECTOR.getName()`） | `plan.getSelectedDocumentId() != null` | `knowledgeService.vectorSearch(requestFactory.build(subQ, plan, 8))` |
| `KeywordRetrievalChannel` | `"keyword"` | 上述 + `properties.isKeywordChannelEnabled()` | `knowledgeService.keywordSearch(requestFactory.build(subQ, plan, 8))` |

`RetrievalChannelResult`（@Data）：`String channelName` + `List<Document> documents`。

### 3.4 DocumentRetrieveRequestFactory（查询增强）

| 方法 | 参数类型 | 返回值类型 |
|---|---|---|
| `build()` | `(String subQuestion, ConversationExecutionPlan plan, int topK)` | `DocumentRetrieveRequest` |
| `buildQueryAugmentation()` 私有 | `(String, HistoryPlanningContext, DocumentNavigationDecision)` | `QueryAugmentation`（私有 record：`String retrievalQuery, List<String> queryContextHints`） |
| `buildFilters()` 私有 | `(String question)` | `DocumentRetrieveFilters`（builder） |
| `looksLikeShortFollowUp()` 私有 | `(String question)` | `boolean` |
| `extractMeaningfulTerms()` 私有 | `(String question)` | `List<String>` |

`DocumentRetrieveRequest` 字段：`String question`、`String retrievalQuery`、`Long documentId`、`Long taskId`、`List<Long> documentIds/taskIds`、`int topK`、`DocumentRetrieveFilters filters`、`List<String> queryContextHints`；方法 `resolvedDocumentIds()/resolvedTaskIds()` → `List<Long>`。

### 3.5 DocumentKnowledgeService（接口）/ DocumentKnowledgeServiceImpl（实现）

| 方法 | 参数类型 | 返回值类型 |
|---|---|---|
| `listRetrievableDocuments()` | 无 | `List<KnowledgeDocumentDescriptor>` |
| `vectorSearch()` | `(DocumentRetrieveRequest request)` | `List<Document>`（Spring AI 的 `org.springframework.ai.document.Document`） |
| `keywordSearch()` | `(DocumentRetrieveRequest request)` | `List<Document>` |
| `elevateToParentBlocks()` | `(List<Document> childDocuments, int maxChars)` | `List<Document>` |

实现类关键私有方法：

| 方法 | 参数类型 | 返回值类型 | 作用 |
|---|---|---|---|
| `buildRetrievedDocument()` | `(long chunkId, String chunkText, long taskId, long parentBlockId, int chunkNo, String sectionPath, Long structureNodeId, Integer structureNodeType, String canonicalPath, Integer itemIndex, KnowledgeDocumentDescriptor descriptor, String channel, double score)` | `Document` | 把一行查询结果装成带 18 个 metadata 键的 Document |
| `buildParentEvidenceDocument()` | `(SuperAgentDocumentParentBlock, List<Document>, int maxChars)` | `Document` | 生成父块证据文档 |
| `aggregateParentScore()` | `(List<Document> childDocuments)` | `double` | 最优子分×(1+支撑+多通道加成) |
| `renderParentEvidenceText()` | `(SuperAgentDocumentParentBlock, List<Document>, int maxChars)` | `String` | 父块全文+命中摘要，截断 |
| `compareEvidenceDocument()` | `(Document left, Document right)` | `int` | 分数↓/parentNo↑/chunkNo↑ 排序 |
| `extractKeywordTerms()` | `(String question)` | `List<String>` | 中英关键词抽取（≤8个） |
| `toVectorLiteral()` | `(float[] embedding)` | `String` | `"[0.12,0.83,...]"` |
| `resolveScore()` | `(Document)` | `Double` | metadata 优先，兜底 document.getScore() |

`ElasticsearchDocumentKeywordSearchGateway implements DocumentKeywordSearchGateway`：
- `indexChunks(List<SuperAgentDocumentChunk>)` → `void`（bulk 写入 ES）
- `search(DocumentRetrieveRequest)` → `List<Document>`（multi_match 检索）
- `deleteByDocumentId(Long)` → `void`
- 条件装配：`@ConditionalOnProperty(prefix="app.manage.elasticsearch", name="enabled", havingValue="true", matchIfMissing=true)`

### 3.6 HttpDocumentRerankPostProcessor（Rerank）

| 方法 | 参数类型 | 返回值类型 |
|---|---|---|
| `process()`（实现 `DocumentPostProcessor`） | `(@NotNull Query query, @NotNull List<Document> documents)`，`Query` 为 `org.springframework.ai.rag.Query` | `List<Document>` |

### 3.7 RagPromptAssemblyService（证据预算）

| 方法 | 参数类型 | 返回值类型 |
|---|---|---|
| `buildSystemPrompt()` | 无 | `String` |
| `buildUserPrompt()` | `(ConversationExecutionPlan, RagRetrievalContext)` | `String` |
| `assemble()` | `(ConversationExecutionPlan plan, RagRetrievalContext context)` | `RagPromptAssemblyResult` |
| `buildEvidenceBlocks()` 私有 | `(RagRetrievalContext, Set<String> renderedReferenceKeys, PromptBudget)` | `String` |
| `appendReferences()` 私有 | `(StringBuilder, List<SearchReference>, Set<String>, PromptBudget)` | `void` |
| `buildWebReferenceBlock()` / `buildDocumentReferenceBlock()` 私有 | `(SearchReference)` | `String` |
| 内部类 `PromptBudget.tryConsume()` | `(int size)` | `boolean` |
| 内部类 `PromptBudget.markRendered()/markOmitted()` | `(String detail)` | `void` |

`RagPromptAssemblyResult` 字段：`String systemPrompt, String userPrompt, int totalBudget, int perSubQuestionBudget, int renderedReferenceCount, int omittedReferenceCount, List<String> renderedReferenceDetails, List<String> omittedReferenceDetails`。

### 3.8 核心数据模型

| 类 | 关键字段（类型） |
|---|---|
| `ConversationExecutionPlan`（@Data @Builder） | `ExecutionMode mode`、`String originalQuestion/rewriteQuestion/retrievalQuestion`、`List<String> retrievalSubQuestions`、`AnswerHistoryContext answerHistoryContext`、`DocumentNavigationDecision navigationDecision`、`Long selectedDocumentId/selectedTaskId`、`List<Long> retrievalDocumentIds/retrievalTaskIds`、`String noEvidenceReply` 等 |
| `RagRetrievalContext`（@Data） | `String retrievalQuestion`、`List<SubQuestionEvidence> subQuestionEvidenceList`、`List<String> retrievalNotes/usedChannels`；方法 `isEmpty(): boolean`、`flattenReferences(): List<SearchReference>` |
| `SubQuestionEvidence`（@Data） | `int subQuestionIndex`、`String subQuestion`、`List<Document> documents`、`List<SearchReference> references`、`List<SubQuestionChannelTrace> channelTraces`、`Integer fusedCandidateCount/parentCandidateCount/rerankedCandidateCount` |
| `SubQuestionChannelTrace`（@Data） | `String channelName`、`int recalledCount`、`int acceptedCount` |
| `SearchReference`（@Data） | `String referenceId/sourceType/title/url/snippet/documentName/sectionPath/channel...`、`Long documentId/chunkId/parentBlockId`、`Integer chunkNo/parentBlockNo/subQuestionIndex`、`Double score`；方法 `uniqueKey(): String`（PARENT:id / DOCUMENT:chunkId / WEB:url） |
| `ChatRagProperties`（@ConfigurationProperties("app.chat.rag")） | `vectorTopK=8, keywordTopK=8, candidateTopK=10, finalTopK=5, minVectorSimilarity=0.45, keywordRelativeScoreFloor=0.35, parentEvidenceMaxChars=2200, totalEvidenceMaxChars=5200, perSubQuestionEvidenceMaxChars=2200, channelTimeoutMs=5000, subQuestionTimeoutMs=12000, rerankEnabled=true`；内嵌 `RerankProperties{enabled=false, url=硅基流动, model=BAAI/bge-reranker-v2-m3, topN=5, connectTimeoutMs=3000, readTimeoutMs=6000}` |

---

## 四、面试讲述指南

### 4.1 30 秒电梯陈述（开场）

> "我们项目的 RAG 检索引擎是一个**双通道混合检索 + 多级漏斗**架构。用户问题先被规划层拆成若干子问题，每个子问题并发地走**向量检索（PGVector）**和**关键词检索（Elasticsearch，可降级到 PG LIKE）**两条通道；通道结果先经过**证据闸门**过滤低质候选，再用 **RRF 倒数排名融合**合并成统一候选集；然后做**父块提升**——用小块做检索保证精准，把它的父块大文本喂给大模型保证回答完整；接着调用外部 **Rerank 模型**做语义精排，截断 Top5 作为最终证据；最后由**证据预算控制器**按字符预算组装 Prompt，防止塞爆大模型上下文。整条链路是**子问题级 + 通道级两级超时、两级降级**的，任何一路挂掉都不会拖垮整体。"

### 4.2 为什么这么设计（高频追问与答法）

**Q1：为什么要双通道？一个不够吗？**
> 向量检索擅长语义泛化（"怎么部署"能召回"安装步骤"），但对专有名词、版本号、代码标识符不敏感；关键词检索正好相反。两者召回分布互补。RRF 融合时，**同一文档被两个通道同时召回会分数叠加**，天然把"既语义相关又字面匹配"的文档排到前面。

**Q2：RRF 是什么？为什么 K=60？**
> Reciprocal Rank Fusion，文档得分 = Σ 各通道 `1/(k + rank)`，我们实现为 `1/(60 + rank + 1)`。它只用**排名**不用原始分数，完美规避了向量余弦分（0~1）和关键词 BM25/LIKE 分（量纲不一）**不可直接相加**的问题。k=60 是论文经验值，用来压平头部排名的分差，让中后段排名仍有翻盘机会，避免单个通道的 top1 独裁。

**Q3：证据闸门为什么 vector 用绝对阈值、keyword 用相对阈值？**
> 向量余弦相似度物理意义统一（我们阈值 0.45），可以全局卡；关键词得分依赖词频和查询长度，不同问题之间量纲差异大，没有稳定的绝对标准，所以用"**本通道最高分 × 0.35**"做相对及格线，自适应每个查询。

**Q4：父块提升解决什么问题？（本模块最大亮点之一）**
> 经典矛盾：**chunk 切小检索才精准，但太小则上下文不完整、大模型答不对**。我们的做法是索引时保留 `parent_block_id`（子块属于哪个语义父段，父块表存了父段全文），检索和排序都在子块粒度进行，命中后**按 parentId 分组升级到父块**：父块文本 = 父段全文 + 命中子片段摘要，控制在 2200 字符内。父块得分 = 最优子块分 × (1 + 支撑加权 + 多通道加权)，即一个父块下被命中多个子块、或被向量和关键词同时命中，得分会更高。这样"小块检索精准、大块回答完整"两个目标同时达成。

**Q5：Rerank 为什么放在最后？挂了怎么办？**
> Rerank 是 cross-encoder 精排，最准但最贵（要逐对计算 query-doc 相关性），所以放在漏斗末端只对 10 个候选做。它是**外部 HTTP 调用**（硅基流动 bge-reranker-v2-m3），我们把三件事做满：**超时**（连接 3s/读取 6s）、**降级**（未启用/没配 apiKey/响应格式错/任何异常，全部原样返回 RRF 排序）、**观测**（rerankScore/rerankDurationMs 写进 metadata 和 trace）。它是锦上添花，不是链路依赖。

**Q6：证据预算怎么控制？**
> 双层字符预算：全局 `totalEvidenceMaxChars=5200` + 单子问题 `perSubQuestionEvidenceMaxChars=2200`（每个子问题重置局部额度）。用 `tryConsume()` 原子检查"总预算和局部预算都够才渲染"。还有两个细节：①**跨子问题引用去重**——同一文档第二次出现只渲染"同[3]见上"；②被裁掉的证据会给模型渲染一句"部分证据因预算省略"，让模型知道自己看到的不是全部，降低幻觉。

**Q7：并发模型是怎样的？线程池怎么配的？**
> 两级 CompletableFuture：**子问题级**（外层 supplyAsync）和**通道级**（内层 supplyAsync），共用一个专门定义的 `chatRagExecutorService`：固定 8 线程、LinkedBlockingQueue(256)、CallerRunsPolicy、守护线程。选 CallerRunsPolicy 是为了**背压**——队列满时让提交任务的线程自己跑，宁可降速也不丢任务。子问题超时 12s、通道超时 5s，`orTimeout + exceptionally` 保证单个失败只产生一条 note 和空证据。注意 `usedChannels/retrievalNotes` 必须用 `Collections.synchronizedList`，因为多线程并发写。整个 `retrieve()` 是同步阻塞方法，所以外层 `RagChatExecutor` 用 `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())` 把它移出 reactor 事件循环线程。

**Q8：检索过程可观测吗？**
> 每一层的漏斗数据都埋了点：每通道"原始召回数 → 闸门后接受数"（SubQuestionChannelTrace）、每篇文档的原始分/RRF分/rerank分/是否过闸/最终排名/**落选原因**（"向量闸门过滤：分数0.32<0.45"这类人话写进 trace），外加每子问题 fused→parent→reranked→final 四个数量的漏斗。前端可以完整回放"为什么这条证据被选/被丢"。

### 4.3 一分钟白板讲述模板（背这个）

```
① 入口：RagChatExecutor（执行模式=RETRIEVAL）从注册表路由进来，
   用 Mono.fromCallable 把同步检索切到 boundedElastic。
② 总入口：RagRetrievalEngine.retrieve(plan, traceRecorder) → RagRetrievalContext
   把子问题列表并发提交线程池（12s 子问题超时+异常降级为空证据）。
③ 每个子问题 retrieveSingleSubQuestion：
   A. 双通道并发（5s 通道超时）：
      vector → vectorSearch（PGVector，embedding <=> 余弦相似度，top8）
      keyword → keywordSearch（ES multi_match，可降级 PG LIKE 加权打分，top8）
      之前先过 DocumentRetrieveRequestFactory：短追问补全上下文 + 正则抽过滤条件
   B. 证据闸门：vector 卡绝对分 0.45；keyword 卡相对分（最高分×0.35）
   C. RRF 融合：1/(60+rank+1) 跨通道累加，取 top10，双通道命中=hybrid
   D. 父块提升：按 parentId 分组换大块，父分=最优子分×(1+支撑+多通道加权)
   E. Rerank：HTTP 调 bge-reranker-v2-m3，异常全降级
   F. 截断 finalTopK=5 → SubQuestionEvidence
④ 全局引用编号：uniqueKey 去重，[1][2][3]... 多子问题复用同号。
⑤ RagPromptAssemblyService.assemble：5200/2200 双层字符预算 + 跨子问题引用去重
   → RagPromptAssemblyResult(systemPrompt, userPrompt, 预算统计)。
⑥ streamText("rag_answer") 流式生成；全程 ConversationTraceRecorder 埋点可回放。
零证据时兜底：直接返回 noEvidenceReply，不打扰大模型。
```

### 4.4 可能被挑战的深挖点（提前准备）

| 追问 | 要点 |
|---|---|
| RRF 的 rank 从 0 还是 1 开始？ | 代码里 `1/(60 + rank + 1)`，rank 从 0 开始所以等效从 1 计 |
| 父块提升后 RRF 排序会乱吗？ | 会重排——父块按新分数（aggregateParentScore）排序，且原 RRF 分仍在 metadata 里可追溯 |
| 为什么用字符数而不是 token 数做预算？ | 字符数零成本同步计算，且中文场景 1 token≈1~1.5 字符，字符预算是工程上足够好的代理指标 |
| 两个通道同时挂了会怎样？ | 两个 exceptionally 都返回空结果 → fuseByRrf 得空列表 → 父块提升返回空 → SubQuestionEvidence 无文档 → context.isEmpty()=true → 上层直接返回 noEvidenceReply 兜底 |
| supports() 里 selectedDocumentId 是什么？ | 规划/导航阶段（DocumentQuestionRouter 等）锁定的目标文档 id，两个通道都只在该文档（及任务）范围内检索——文档级过滤先于语义检索 |
| 为什么检索通道是 List 注入？ | 策略模式 + Spring 集合注入：新增通道（如表格检索、Web 检索）只需实现 RetrievalChannel 接口注册为 @Component，引擎零改动（开闭原则） |
| keywordSearch 的 ES 降级路径在哪判断？ | `properties.getElasticsearch().getEnabled() && keywordSearchGateway != null`（ObjectProvider 懒查找），不满足就走 PG LIKE 兜底 SQL |

---

## 五、涉及文件索引

| 文件 | 路径（相对 `nexus-agent-business-chat/src/main/java/org/javaup/ai/`） |
|---|---|
| 检索总入口 | `chatagent/rag/service/RagRetrievalEngine.java` |
| 通道接口/结果 | `chatagent/rag/retrieve/channel/RetrievalChannel.java`、`RetrievalChannelResult.java` |
| 向量/关键词通道 | `chatagent/rag/retrieve/channel/VectorRetrievalChannel.java`、`KeywordRetrievalChannel.java` |
| 查询增强工厂 | `chatagent/rag/service/DocumentRetrieveRequestFactory.java` |
| 知识检索落地 | `manage/service/DocumentKnowledgeService.java`、`manage/service/impl/DocumentKnowledgeServiceImpl.java` |
| ES 网关 | `manage/service/keyword/DocumentKeywordSearchGateway.java`、`ElasticsearchDocumentKeywordSearchGateway.java` |
| Rerank 处理器 | `chatagent/rag/service/HttpDocumentRerankPostProcessor.java` |
| Prompt 组装/预算 | `chatagent/rag/service/RagPromptAssemblyService.java` |
| 上游执行器/路由 | `chatagent/rag/executor/RagChatExecutor.java`、`ConversationExecutorRegistry.java` |
| 线程池配置 | `chatagent/rag/config/ChatRagExecutorConfiguration.java` |
| 配置属性 | `chatagent/rag/config/ChatRagProperties.java`（prefix=`app.chat.rag`） |
| 数据模型 | `chatagent/rag/model/`：`ConversationExecutionPlan`、`RagRetrievalContext`、`SubQuestionEvidence`、`SubQuestionChannelTrace`、`RagPromptAssemblyResult` |
| 引用模型/映射 | `chatagent/model/SearchReference.java`、`chatagent/rag/support/SearchReferenceMapper.java` |
| 检索请求模型 | `manage/model/DocumentRetrieveRequest.java` |
| 通道枚举 | `nexus-agent-common/nexus-agent-common-frame/.../org/javaup/enums/RetrievalChannelEnum.java` |
