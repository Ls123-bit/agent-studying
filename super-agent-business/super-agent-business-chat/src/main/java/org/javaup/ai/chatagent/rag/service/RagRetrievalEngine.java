package org.javaup.ai.chatagent.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.model.ChannelExecutionView;
import org.javaup.ai.chatagent.model.RetrievalResultView;
import org.javaup.ai.chatagent.model.SearchReference;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.model.RagRetrievalContext;
import org.javaup.ai.chatagent.rag.model.SubQuestionEvidence;
import org.javaup.ai.chatagent.rag.model.SubQuestionChannelTrace;
import org.javaup.ai.chatagent.rag.retrieve.channel.RetrievalChannel;
import org.javaup.ai.chatagent.rag.retrieve.channel.RetrievalChannelResult;
import org.javaup.ai.chatagent.rag.support.SearchReferenceMapper;
import org.javaup.ai.chatagent.service.ConversationTraceRecorder;
import org.javaup.ai.manage.service.DocumentKnowledgeService;
import org.javaup.ai.manage.support.DocumentKnowledgeMetadataKeys;
import org.javaup.enums.RetrievalChannelEnum;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 聊天侧知识检索引擎。
 *
 * <p>它不是最终回答器，而是负责把“当前这轮要用什么证据”准备好。</p>
 * <p>工作内容包括：</p>
 * <p>1. 按子问题并行检索。</p>
 * <p>2. 向量 / 关键词双通道并行召回。</p>
 * <p>3. 用 RRF 做结果融合。</p>
 * <p>4. 按需执行 Rerank。</p>
 * <p>5. 保留子问题与证据的边界。</p>
 */
@Slf4j
@Service
public class RagRetrievalEngine {

    private static final int RRF_K = 60;

    private final List<RetrievalChannel> retrievalChannels;
    private final ChatRagProperties properties;
    private final DocumentPostProcessor rerankPostProcessor;
    private final DocumentKnowledgeService documentKnowledgeService;
    private final ExecutorService executorService;

    public RagRetrievalEngine(List<RetrievalChannel> retrievalChannels,
                              ChatRagProperties properties,
                              HttpDocumentRerankPostProcessor rerankPostProcessor,
                              DocumentKnowledgeService documentKnowledgeService,
                              @Qualifier("chatRagExecutorService") ExecutorService executorService) {
        this.retrievalChannels = retrievalChannels;
        this.properties = properties;
        this.rerankPostProcessor = rerankPostProcessor;
        this.documentKnowledgeService = documentKnowledgeService;
        this.executorService = executorService;
    }

    /**
     * 执行整轮知识检索。
     */
    public RagRetrievalContext retrieve(ConversationExecutionPlan plan, ConversationTraceRecorder traceRecorder) {
        RagRetrievalContext context = new RagRetrievalContext();
        context.setRetrievalQuestion(plan.getRetrievalQuestion());
        /*
         * 子问题检索是并发执行的，所以 usedChannels 和 retrievalNotes 不能再用普通 ArrayList。
         * 这里显式用同步列表，避免多个检索任务同时追加时出现并发写问题。
         */
        context.setUsedChannels(Collections.synchronizedList(new ArrayList<>()));
        context.setRetrievalNotes(Collections.synchronizedList(new ArrayList<>()));

        /*
         * 如果前置编排阶段没有拆出子问题，就把 rewrite 后的主问题当成唯一子问题。
         * 这样后面的检索逻辑始终围绕“子问题列表”统一推进。
         */
        List<String> subQuestions = plan.getRetrievalSubQuestions() == null || plan.getRetrievalSubQuestions().isEmpty()
            ? List.of(plan.getRetrievalQuestion())
            : plan.getRetrievalSubQuestions();

        List<CompletableFuture<SubQuestionEvidence>> futures = new ArrayList<>();
        for (int index = 0; index < subQuestions.size(); index++) {
            final int subQuestionIndex = index + 1;
            final String subQuestion = subQuestions.get(index);
            /*
             * 每个子问题单独起一个异步任务。
             * 这样一个复合问题被拆成多个子问题后，不需要串行等待所有检索过程。
             */
            futures.add(CompletableFuture.supplyAsync(
                    () -> retrieveSingleSubQuestion(subQuestionIndex, subQuestion, plan, context.getUsedChannels(), context.getRetrievalNotes(), traceRecorder),
                    executorService
                )
                .orTimeout(Math.max(properties.getSubQuestionTimeoutMs(), 1L), TimeUnit.MILLISECONDS)
                .exceptionally(throwable -> {
                    /*
                     * 子问题级别的降级粒度很重要：
                     * 单个子问题失败时，只让这一支证据树退化为空，
                     * 不让整轮回答直接失败。这样复合问题里还能保住其他子问题已经拿到的证据。
                     */
                    context.getRetrievalNotes().add("子问题" + subQuestionIndex + "检索失败或超时，已自动忽略。");
                    return new SubQuestionEvidence(subQuestionIndex, subQuestion, List.of(), new ArrayList<>(), List.of(), 0, 0, 0);
                }));
        }

        /*
         * join() 的这一刻，代表所有子问题的证据都已经准备完毕。
         * 之后再统一给引用编号，保证整轮回答里的编号是全局有序、稳定的。
         */
        List<SubQuestionEvidence> evidenceList = futures.stream()
            .map(CompletableFuture::join)
            .toList();
        assignReferenceIds(evidenceList);
        context.setSubQuestionEvidenceList(evidenceList);
        return context;
    }

    /**
     * 执行单个子问题的多通道检索。
     */
    private SubQuestionEvidence retrieveSingleSubQuestion(int subQuestionIndex,
                                                          String subQuestion,
                                                          ConversationExecutionPlan plan,
                                                          List<String> usedChannels,
                                                          List<String> notes,
                                                          ConversationTraceRecorder traceRecorder) {
        /*
         * 这里不再把“向量 / 关键词”通道直接写死在引擎里，
         * 而是改成统一遍历 RetrievalChannel。
         * 这样后续如果新增别的召回路径，
         * 只需要新增实现类，不需要继续改主引擎流程。
         */
        List<CompletableFuture<RetrievalChannelResult>> futures = retrievalChannels.stream()
            /*
             * 通道是否参与这轮检索，不是固定写死的，而是由 supports(plan) 动态决定。
             * 在当前教学版项目里，文档问答只保留向量 / 关键词两条内部检索通道。
             */
            .filter(channel -> channel.supports(plan))
            .map(channel -> CompletableFuture.supplyAsync(() -> channel.retrieve(subQuestion, plan), executorService)
                .orTimeout(Math.max(properties.getChannelTimeoutMs(), 1L), TimeUnit.MILLISECONDS)
                .exceptionally(throwable -> {
                    /*
                     * 通道级降级再往下细一层：
                     * 同一个子问题里如果 keyword / vector 其中一个失败，
                     * 也不应该拖垮另一个通道已经拿到的结果。
                     */
                    notes.add("子问题" + subQuestionIndex + "通道[" + channel.channelName() + "]检索失败或超时，已自动降级。");
                    return new RetrievalChannelResult(channel.channelName(), List.of());
                }))
            .toList();
        if (futures.isEmpty()) {
            notes.add("子问题" + subQuestionIndex + "没有可用的检索通道。");
            return new SubQuestionEvidence(subQuestionIndex, subQuestion, List.of(), new ArrayList<>(), List.of(), 0, 0, 0);
        }

        /*
         * 这里先把每个通道的原始结果都收齐，形成 channelResults。
         * 后面无论做统计、融合还是重排，都只面向这份统一结果结构。
         */
        List<RetrievalChannelResult> rawChannelResults = futures.stream()
            .map(CompletableFuture::join)
            .filter(result -> result.getDocuments() != null)
            .toList();
        List<RetrievalChannelResult> channelResults = rawChannelResults.stream()
            .map(this::applyEvidenceGate)
            .toList();
        List<SubQuestionChannelTrace> channelTraces = buildChannelTraces(rawChannelResults, channelResults);

        channelResults.stream()
            .filter(result -> !result.getDocuments().isEmpty())
            .forEach(result -> markUsedChannel(usedChannels, result.getChannelName()));

        /*
         * 方案 B 下的主链路分成三层：
         * 1. 先用 RRF 对 child 结果做粗融合
         * 2. 再把 child 提升成 parent 证据
         * 3. 最后在 parent 层做精排
         */
        List<Document> mergedCandidates = fuseByRrf(channelResults);
        List<Document> parentCandidates = documentKnowledgeService.elevateToParentBlocks(
            mergedCandidates,
            properties.getParentEvidenceMaxChars()
        );
        List<Document> rerankedCandidates = applyRerank(subQuestion, parentCandidates, usedChannels);
        /*
         * finalTopK 是真正会进入 Prompt 的证据数，不是每个通道自己的召回数。
         * 这里统一在 child 融合、parent 提升、parent 精排之后做最终裁剪。
         */
        List<Document> finalDocuments = rerankedCandidates.stream()
            .limit(properties.getFinalTopK())
            .toList();

        notes.add("子问题" + subQuestionIndex + "检索完成："
            + summarizeChannelResults(channelResults)
            + "，final=" + finalDocuments.size());

        /*
         * 把通道执行详情和检索结果快照写入观测表。
         * 这里用 try-catch 保护，记录失败不影响检索主流程。
         */
        if (traceRecorder != null) {
            try {
                recordChannelObservations(traceRecorder, subQuestionIndex, subQuestion,
                    rawChannelResults, channelResults, channelTraces);
                recordRetrievalResultObservations(traceRecorder, subQuestionIndex, subQuestion,
                    rawChannelResults, channelResults, mergedCandidates, rerankedCandidates, finalDocuments);
            } catch (RuntimeException exception) {
                log.warn("记录检索观测数据失败, subQuestionIndex={}", subQuestionIndex, exception);
            }
        }

        return new SubQuestionEvidence(
            subQuestionIndex,
            subQuestion,
            finalDocuments,
            new ArrayList<>(),
            channelTraces,
            mergedCandidates.size(),
            parentCandidates.size(),
            rerankedCandidates.size()
        );
    }

    private RetrievalChannelResult applyEvidenceGate(RetrievalChannelResult result) {
        if (result == null || result.getDocuments() == null || result.getDocuments().isEmpty()) {
            return result;
        }
        /*
         * 相关性闸门放在“每个通道自己的原始结果”上，而不是放在 RRF 之后做，
         * 是因为这里要先解决一个本质问题：
         * “某个通道随便给了几个低质量 topK”不能被视为真的找到了证据。
         *
         * 如果等到融合后再裁剪，低质量候选已经有机会参与：
         * - hybrid 分数累加
         * - rerank 输入
         * - prompt 空间占用
         *
         * 所以这里先把不够格的候选挡在通道出口，
         * 再让 RRF 和 rerank 只处理“至少像样”的候选集合。
         */
        List<Document> documents = switch (result.getChannelName()) {
            case "vector" -> filterVectorCandidates(result.getDocuments());
            case "keyword" -> filterKeywordCandidates(result.getDocuments());
            default -> result.getDocuments();
        };
        return new RetrievalChannelResult(result.getChannelName(), documents);
    }

    private List<Document> filterVectorCandidates(List<Document> documents) {
        return documents.stream()
            /*
             * 向量检索最容易出现的坏味道是：
             * 只要限定了 documentId/taskId，数据库总能给你返回 topK，
             * 但“有 topK”不等于“这些 topK 真和问题相关”。
             *
             * 因此这里显式引入最小相似度阈值，
             * 让“没有足够相似的片段”真的能落回空结果。
             */
            .filter(document -> {
                Double score = resolveScore(document);
                return score != null && score >= properties.getMinVectorSimilarity();
            })
            .toList();
    }

    private List<Document> filterKeywordCandidates(List<Document> documents) {
        Double topScore = documents.stream()
            .map(this::resolveScore)
            .filter(Objects::nonNull)
            .max(Double::compareTo)
            .orElse(null);
        if (topScore == null || topScore <= 0D) {
            return documents;
        }
        /*
         * 关键词检索这里不用绝对阈值，而用“相对 top score 的下限”。
         * 原因是 BM25 / SQL lexical score 的量纲很依赖命中词和索引分布，
         * 不同问题之间不太适合拿一个绝对数字做全局判断。
         *
         * 相对阈值的语义是：
         * “保留和当前最佳命中还算接近的那些候选，
         *  但把只蹭到一点边的弱命中扔掉。”
         */
        double acceptedFloor = topScore * Math.max(0D, properties.getKeywordRelativeScoreFloor());
        return documents.stream()
            .filter(document -> {
                Double score = resolveScore(document);
                return score != null && score >= acceptedFloor;
            })
            .toList();
    }

    /**
     * 使用 RRF 融合多通道结果。
     */
    private List<Document> fuseByRrf(List<RetrievalChannelResult> channelResults) {
        Map<String, CandidateHolder> holders = new LinkedHashMap<>();
        /*
         * 先把每个通道结果累积进统一 holder 表。
         * 同一个文档如果在多个通道里都被召回，会自然累加 RRF 分数。
         */
        for (RetrievalChannelResult retrievalChannelResult : channelResults) {
            accumulateRrf(retrievalChannelResult, holders);
        }

        return holders.values().stream()
            .sorted((left, right) -> Double.compare(right.score, left.score))
            .limit(properties.getCandidateTopK())
            .map(holder -> {
                /*
                 * 融合结束后，要把新的“融合后分数”和“最终来源通道标签”回写到 metadata。
                 * 后面的 Prompt、调试轨迹和前端展示都会直接消费这两个字段。
                 */
                holder.document.getMetadata().put(DocumentKnowledgeMetadataKeys.SCORE, holder.score);
                holder.document.getMetadata().put(DocumentKnowledgeMetadataKeys.RRF_SCORE, holder.score);
                holder.document.getMetadata().put(DocumentKnowledgeMetadataKeys.CHANNEL,
                    holder.channels.size() > 1 ? "hybrid" : holder.channels.iterator().next());
                return holder.document;
            })
            .toList();
    }

    /**
     * 把某一路结果累积进 RRF 评分表。
     */
    private void accumulateRrf(RetrievalChannelResult channelResult, Map<String, CandidateHolder> holders) {
        List<Document> documents = channelResult.getDocuments();
        for (int rank = 0; rank < documents.size(); rank++) {
            Document document = documents.get(rank);
            String documentId = document.getId();
            /*
             * 这里以 documentId 作为 RRF 融合键，
             * 前提就是各通道返回的同一份候选必须尽量使用稳定且真实唯一的 ID。
             * 这也是网页通道为什么要把 ID 从 hashCode 升级成 SHA-256 的根本原因。
             */
            /*
             * RRF 只看“这个候选在当前通道里排第几”，不直接比较各通道原始分数。
             * 这样向量分、关键词分和网页排名这种不同量纲的数据也能被统一融合。
             */
            double rrfScore = 1D / (RRF_K + rank + 1);
            CandidateHolder holder = holders.computeIfAbsent(documentId, ignored -> new CandidateHolder(document));
            holder.score += rrfScore;
            holder.channels.add(channelResult.getChannelName());
        }
    }

    /**
     * 在融合结果之上执行可选的重排序。
     */
    private List<Document> applyRerank(String subQuestion,
                                       List<Document> candidates,
                                       List<String> usedChannels) {
        if (!properties.isRerankEnabled() || candidates.isEmpty()) {
            return candidates;
        }
        /*
         * 一旦真正进入 rerank，这个通道名称也要记进 usedChannels，
         * 方便调试轨迹和后台观测页明确知道“这轮答案做过精排”。 
         */
        markUsedChannel(usedChannels, RetrievalChannelEnum.RERANK.getName());
        return rerankPostProcessor.process(new Query(subQuestion), candidates);
    }

    /**
     * 给最终引用分配稳定编号。
     */
    private void assignReferenceIds(List<SubQuestionEvidence> evidenceList) {
        final int[] referenceNumber = {1};
        Map<String, String> assignedIds = new LinkedHashMap<>();
        for (SubQuestionEvidence evidence : evidenceList) {
            List<SearchReference> references = new ArrayList<>();
            for (Document document : evidence.getDocuments()) {
                /*
                 * 这里统一给每条证据分配全局递增编号，而不是每个子问题单独从 1 开始。
                 * 这样最终 Prompt 和前端展示里的引用编号永远一一对应。
                 */
                SearchReference reference = SearchReferenceMapper.fromDocument(
                    document,
                    evidence.getSubQuestionIndex(),
                    evidence.getSubQuestion(),
                    0
                );
                String uniqueKey = reference.uniqueKey();
                /*
                 * 这里按 uniqueKey 复用编号，而不是“看到一条证据就自增一个编号”。
                 * 根本原因是：
                 * - 同一 chunk 可能被多个子问题同时命中
                 * - 成功收尾时最终 reference 列表还会再做去重
                 *
                 * 如果这里不先按 uniqueKey 固定编号，
                 * 那答案里出现的 [3]、[4] 很可能在最终 reference 列表里只剩一条 [1]，
                 * 前后就会对不上。
                 */
                String assignedId = assignedIds.computeIfAbsent(uniqueKey, ignored -> String.valueOf(referenceNumber[0]++));
                reference.setReferenceId(assignedId);
                references.add(reference);
            }
            evidence.setReferences(references);
        }
    }

    private Double resolveScore(Document document) {
        if (document == null) {
            return null;
        }
        /*
         * 这里优先读 metadata 里的 score，而不是只看 Document.score，
         * 是因为：
         * 1. 通道原始分数、RRF 分数、rerank 分数都可能回写到 metadata；
         * 2. 某些 Document 的 score 字段在不同构造路径里不一定始终可靠。
         *
         * 统一从 metadata 取，可以让后面的闸门和调试信息口径更一致。
         */
        Object metadataScore = document.getMetadata().get(DocumentKnowledgeMetadataKeys.SCORE);
        if (metadataScore instanceof Number number) {
            return number.doubleValue();
        }
        return document.getScore();
    }

    private void markUsedChannel(List<String> usedChannels, String channel) {
        /*
         * 这里保留顺序但不重复追加。
         * 后面生成调试轨迹时，既能看到通道使用顺序，也不会出现重复名称刷屏。
         */
        if (!usedChannels.contains(channel)) {
            usedChannels.add(channel);
        }
    }

    /**
     * 生成通道召回统计摘要，供调试轨迹和 thinking 复用。
     */
    private String summarizeChannelResults(List<RetrievalChannelResult> channelResults) {
        if (channelResults.isEmpty()) {
            return "没有启用任何检索通道";
        }
        return channelResults.stream()
            .map(result -> result.getChannelName() + "=" + result.getDocuments().size())
            .reduce((left, right) -> left + "，" + right)
            .orElse("没有检索结果");
    }

    private List<SubQuestionChannelTrace> buildChannelTraces(List<RetrievalChannelResult> rawResults,
                                                             List<RetrievalChannelResult> filteredResults) {
        if ((rawResults == null || rawResults.isEmpty()) && (filteredResults == null || filteredResults.isEmpty())) {
            return List.of();
        }
        Map<String, Integer> rawMap = new LinkedHashMap<>();
        Map<String, Integer> filteredMap = new LinkedHashMap<>();
        if (rawResults != null) {
            rawResults.forEach(result -> rawMap.put(result.getChannelName(), result.getDocuments() == null ? 0 : result.getDocuments().size()));
        }
        if (filteredResults != null) {
            filteredResults.forEach(result -> filteredMap.put(result.getChannelName(), result.getDocuments() == null ? 0 : result.getDocuments().size()));
        }
        LinkedHashSet<String> channelNames = new LinkedHashSet<>();
        channelNames.addAll(rawMap.keySet());
        channelNames.addAll(filteredMap.keySet());
        List<SubQuestionChannelTrace> traces = new ArrayList<>(channelNames.size());
        for (String channelName : channelNames) {
            traces.add(new SubQuestionChannelTrace(
                channelName,
                rawMap.getOrDefault(channelName, 0),
                filteredMap.getOrDefault(channelName, 0)
            ));
        }
        return traces;
    }

    /**
     * 记录通道执行观测数据。
     */
    private void recordChannelObservations(ConversationTraceRecorder traceRecorder,
                                           int subQuestionIndex,
                                           String subQuestion,
                                           List<RetrievalChannelResult> rawResults,
                                           List<RetrievalChannelResult> filteredResults,
                                           List<SubQuestionChannelTrace> channelTraces) {
        if (rawResults == null || rawResults.isEmpty()) {
            return;
        }

        List<ChannelExecutionView> executions = new ArrayList<>();
        for (RetrievalChannelResult rawResult : rawResults) {
            String channelName = rawResult.getChannelName();
            int recalledCount = rawResult.getDocuments() == null ? 0 : rawResult.getDocuments().size();

            RetrievalChannelResult filteredResult = filteredResults == null ? null :
                filteredResults.stream().filter(r -> channelName.equals(r.getChannelName())).findFirst().orElse(null);
            int acceptedCount = filteredResult == null || filteredResult.getDocuments() == null ? 0 : filteredResult.getDocuments().size();

            SubQuestionChannelTrace trace = channelTraces == null ? null :
                channelTraces.stream().filter(t -> channelName.equals(t.getChannelName())).findFirst().orElse(null);
            int finalSelectedCount = trace == null ? 0 : trace.getAcceptedCount();

            ChannelExecutionView execution = new ChannelExecutionView();
            execution.setId(traceRecorder.exchangeId());
            execution.setTraceId(traceRecorder.traceId());
            execution.setSubQuestionIndex(subQuestionIndex);
            execution.setSubQuestion(subQuestion);
            execution.setChannelType(channelName);
            execution.setExecutionState(1);
            execution.setRecalledCount(recalledCount);
            execution.setAcceptedCount(acceptedCount);
            execution.setFinalSelectedCount(finalSelectedCount);

            if (rawResult.getDocuments() != null && !rawResult.getDocuments().isEmpty()) {
                List<Double> scores = rawResult.getDocuments().stream()
                    .map(doc -> {
                        Object scoreObj = doc.getMetadata().get(DocumentKnowledgeMetadataKeys.SCORE);
                        if (scoreObj instanceof Number) {
                            return ((Number) scoreObj).doubleValue();
                        }
                        return 0.0;
                    })
                    .filter(score -> score > 0)
                    .toList();

                if (!scores.isEmpty()) {
                    execution.setAvgScore(java.math.BigDecimal.valueOf(scores.stream().mapToDouble(Double::doubleValue).average().orElse(0)));
                    execution.setMaxScore(java.math.BigDecimal.valueOf(scores.stream().mapToDouble(Double::doubleValue).max().orElse(0)));
                    execution.setMinScore(java.math.BigDecimal.valueOf(scores.stream().mapToDouble(Double::doubleValue).min().orElse(0)));
                }
            }

            executions.add(execution);
        }

        traceRecorder.recordChannelExecutions(executions);
    }

    /**
     * 记录检索结果观测数据。
     */
    private void recordRetrievalResultObservations(ConversationTraceRecorder traceRecorder,
                                                   int subQuestionIndex,
                                                   String subQuestion,
                                                   List<RetrievalChannelResult> rawResults,
                                                   List<RetrievalChannelResult> filteredResults,
                                                   List<Document> mergedCandidates,
                                                   List<Document> rerankedCandidates,
                                                   List<Document> finalDocuments) {
        List<RetrievalResultView> results = new ArrayList<>();
        Map<String, Integer> finalRankMap = new LinkedHashMap<>();
        if (finalDocuments != null) {
            for (int i = 0; i < finalDocuments.size(); i++) {
                String docId = finalDocuments.get(i).getId();
                if (docId != null) {
                    finalRankMap.put(docId, i + 1);
                }
            }
        }

        if (rawResults != null) {
            for (RetrievalChannelResult rawResult : rawResults) {
                String channelName = rawResult.getChannelName();
                List<Document> rawDocs = rawResult.getDocuments();
                if (rawDocs == null || rawDocs.isEmpty()) {
                    continue;
                }

                for (int i = 0; i < rawDocs.size(); i++) {
                    Document doc = rawDocs.get(i);
                    RetrievalResultView view = new RetrievalResultView();
                    view.setId(traceRecorder.exchangeId());
                    view.setTraceId(traceRecorder.traceId());
                    view.setSubQuestionIndex(subQuestionIndex);
                    view.setSubQuestion(subQuestion);
                    view.setChannelType(channelName);
                    view.setChannelRank(i + 1);

                    Object scoreObj = doc.getMetadata().get(DocumentKnowledgeMetadataKeys.SCORE);
                    if (scoreObj instanceof Number) {
                        view.setOriginalScore(java.math.BigDecimal.valueOf(((Number) scoreObj).doubleValue()));
                    }

                    Object rrfScoreObj = doc.getMetadata().get(DocumentKnowledgeMetadataKeys.RRF_SCORE);
                    if (rrfScoreObj instanceof Number) {
                        view.setRrfScore(java.math.BigDecimal.valueOf(((Number) rrfScoreObj).doubleValue()));
                    }

                    Object rerankScoreObj = doc.getMetadata().get("rerankScore");
                    if (rerankScoreObj instanceof Number) {
                        view.setRerankScore(java.math.BigDecimal.valueOf(((Number) rerankScoreObj).doubleValue()));
                    }

                    Object docIdObj = doc.getMetadata().get(DocumentKnowledgeMetadataKeys.DOCUMENT_ID);
                    if (docIdObj != null) {
                        view.setDocumentId(Long.parseLong(String.valueOf(docIdObj)));
                    }

                    Object docNameObj = doc.getMetadata().get(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME);
                    if (docNameObj != null) {
                        view.setDocumentName(String.valueOf(docNameObj));
                    }

                    Object chunkIdObj = doc.getMetadata().get(DocumentKnowledgeMetadataKeys.CHUNK_ID);
                    if (chunkIdObj != null) {
                        view.setChunkId(Long.parseLong(String.valueOf(chunkIdObj)));
                    }

                    Object chunkNoObj = doc.getMetadata().get(DocumentKnowledgeMetadataKeys.CHUNK_NO);
                    if (chunkNoObj != null) {
                        view.setChunkNo(Integer.parseInt(String.valueOf(chunkNoObj)));
                    }

                    Object sectionPathObj = doc.getMetadata().get(DocumentKnowledgeMetadataKeys.SECTION_PATH);
                    if (sectionPathObj != null) {
                        view.setSectionPath(String.valueOf(sectionPathObj));
                    }

                    String content = doc.getText();
                    if (content != null && !content.isEmpty()) {
                        view.setChunkTextPreview(content.length() > 500 ? content.substring(0, 500) : content);
                        view.setChunkCharCount(content.length());
                    }

                    boolean passedGate = filteredResults != null && filteredResults.stream()
                        .anyMatch(fr -> channelName.equals(fr.getChannelName()) &&
                            fr.getDocuments() != null &&
                            fr.getDocuments().stream().anyMatch(d -> Objects.equals(d.getId(), doc.getId())));
                    view.setGatePassed(passedGate);

                    boolean isSelected = doc.getId() != null && finalRankMap.containsKey(doc.getId());
                    view.setSelected(isSelected);

                    if (isSelected) {
                        view.setFinalRank(finalRankMap.get(doc.getId()));
                        view.setSelectionReason("已选入最终 Prompt");
                    } else if (!passedGate) {
                        // 记录具体的闸门过滤规则和阈值
                        Object scoreObj2 = doc.getMetadata().get(DocumentKnowledgeMetadataKeys.SCORE);
                        double score = scoreObj2 instanceof Number ? ((Number) scoreObj2).doubleValue() : 0.0;
                        if ("vector".equals(channelName)) {
                            view.setSelectionReason(String.format(
                                "向量闸门过滤：分数 %.4f < 阈值 %.4f",
                                score, properties.getMinVectorSimilarity()
                            ));
                        } else if ("keyword".equals(channelName)) {
                            view.setSelectionReason(String.format(
                                "关键词闸门过滤：分数 %.4f 低于相对阈值（floor=%.2f）",
                                score, properties.getKeywordRelativeScoreFloor()
                            ));
                        } else {
                            view.setSelectionReason("闸门过滤：分数 " + String.format("%.4f", score));
                        }
                    } else {
                        view.setSelectionReason("超出 finalTopK 限制（topK=" + properties.getFinalTopK() + "）");
                    }

                    results.add(view);
                }
            }
        }

        traceRecorder.recordRetrievalResults(results);
    }

    /**
     * RRF 融合过程中的临时容器。
     */
    private static class CandidateHolder {

        private final Document document;
        private final LinkedHashSet<String> channels = new LinkedHashSet<>();
        private double score;

        private CandidateHolder(Document document) {
            this.document = document;
        }
    }
}
