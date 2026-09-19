package org.javaup.ai.chatagent.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.model.ChannelExecutionView;
import org.javaup.ai.chatagent.model.RetrievalResultView;
import org.javaup.ai.chatagent.model.SearchReference;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.model.RagRetrievalContext;
import org.javaup.ai.chatagent.rag.model.SubQuestionChannelTrace;
import org.javaup.ai.chatagent.rag.model.SubQuestionEvidence;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/*
检索引擎总入口
 */
/**
 * RAG 检索引擎。
 * 该组件负责把编排阶段产出的检索问题真正落到多通道检索执行上，并完成：
 * 1. 子问题拆分后的并发检索
 * 2. 通道级超时隔离与降级
 * 3. 向量 / 关键词结果闸门过滤
 * 4. 多通道 RRF 融合
 * 5. 父块提升与 rerank
 * 6. 最终证据裁剪、引用编号和检索观测记录
 */
@Slf4j
@Service
public class RagRetrievalEngine {

    private static final int RRF_K = 60;
/*
从依赖注入可以看出，这个引擎需要：
retrievalChannels：所有可用的检索通道（向量检索、关键词检索等）
properties：RAG 配置参数（超时时间、topK、阈值等）
rerankPostProcessor：重排序处理器
documentKnowledgeService：文档知识服务（用于父块提升）
executorService：线程池（用于并发检索）
 */
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
     * 执行整轮 RAG 检索。
     * 这是知识问答链路中的核心入口，会把总检索问题拆成多个子问题并发执行，
     * 然后收集每个子问题的证据、引用、使用通道和调试备注，最终汇总成统一的检索上下文。
     *
     * @param plan 编排阶段产出的执行计划
     * @param traceRecorder 会话追踪记录器，可为空
     * @return 汇总后的检索上下文
     */
    public RagRetrievalContext retrieve(ConversationExecutionPlan plan, ConversationTraceRecorder traceRecorder) {//检索主入口，返回完整的检索上下文
       /*
       步骤一：初始化检索上下文
       这里创建了一个 RagRetrievalContext 对象，用于存储整轮检索的结果。注意 usedChannels 和 retrievalNotes 使用了 Collections.synchronizedList，因为后续会有多个线程并发写入。
        */
        RagRetrievalContext context = new RagRetrievalContext();
        // 记录本轮实际用于检索的问题文本，后续调试页面和埋点会直接使用这里的值。
        context.setRetrievalQuestion(plan.getRetrievalQuestion());
        // usedChannels / retrievalNotes 在子问题并发检索过程中会被多个线程写入，因此使用同步列表。
        context.setUsedChannels(Collections.synchronizedList(new ArrayList<>()));
        context.setRetrievalNotes(Collections.synchronizedList(new ArrayList<>()));

        /*
        步骤二：准备子问题列表
        在编排阶段，系统可能会把用户的复杂问题拆分成多个子问题。比如用户问"Java 和 Python 的区别是什么？"，可能会拆成：
        子问题1：Java 的特点是什么？
        子问题2：Python 的特点是什么？
        如果没有拆分，就直接用原问题检索。
         */
        // 如果编排阶段没有拆子问题，则退化为只检索总问题本身。
        List<String> subQuestions = plan.getRetrievalSubQuestions() == null || plan.getRetrievalSubQuestions().isEmpty()
            ? List.of(plan.getRetrievalQuestion())
            : plan.getRetrievalSubQuestions();


        /*
        步骤三:并发检索所有问题
        这段代码是整个检索引擎的核心，我们逐步分析：
        为什么要并发检索？
        如果有 3 个子问题，每个子问题检索需要 500ms，串行执行就需要 1500ms。并发执行的话，理论上只需要 500ms 左右，大大提升了响应速度。
         */
        List<CompletableFuture<SubQuestionEvidence>> futures = new ArrayList<>();
        for (int index = 0; index < subQuestions.size(); index++) {
            final int subQuestionIndex = index + 1;
            final String subQuestion = subQuestions.get(index);

            // 每个子问题独立并发检索，彼此隔离，这样某个子问题超时不会拖垮整轮检索。
            futures.add(CompletableFuture.supplyAsync(//supplyAsync 会把 retrieveSingleSubQuestion 方法提交到线程池异步执行，立即返回一个 CompletableFuture 对象。这样多个子问题就可以同时在不同线程上执行。
                    () -> retrieveSingleSubQuestion(subQuestionIndex, subQuestion, plan, context.getUsedChannels(), context.getRetrievalNotes(), traceRecorder),
                    executorService
                )
                    // 为每个子问题单独设置超时，避免极端慢查询卡住整轮回答。 设置超时：如果某个子问题的检索特别慢（比如向量索引卡住了），不设置超时的话会一直等待，导致整个回答都卡住。设置超时后，超过时间就会抛出 TimeoutException，然后被 exceptionally 捕获并降级处理。
                .orTimeout(Math.max(properties.getSubQuestionTimeoutMs(), 1L), TimeUnit.MILLISECONDS)
                .exceptionally(throwable -> {


                    /*
                    降级处理
                    这里体现了局部失败、整体降级的设计思想：
                    如果子问题1检索失败了，不应该导致整个回答都失败
                    而是应该用子问题2、3的检索结果继续回答
                    只是在 retrievalNotes 里记录一下"子问题1检索失败或超时，已自动忽略"
                    这样即使部分检索失败，用户仍然能得到一个基于部分证据的回答，而不是直接报错。
                     */
                    // 超时、线程池封装异常等都先展开到根因，方便日志诊断。
                    Throwable rootCause = unwrapThrowable(throwable);
                    log.warn("子问题检索失败: subQuestionIndex={}, subQuestion='{}', exceptionType={}, message={}",
                        subQuestionIndex,
                        subQuestion,
                        rootCause == null ? "" : rootCause.getClass().getName(),
                        rootCause == null ? "" : rootCause.getMessage(),
                        throwable);
                    context.getRetrievalNotes().add("子问题" + subQuestionIndex + "检索失败或超时，已自动忽略。");
                    // 单个子问题失败时不抛出整轮异常，而是返回一个空证据对象，体现"局部失败、整体降级"策略。
                    return new SubQuestionEvidence(subQuestionIndex, subQuestion, List.of(), new ArrayList<>(), List.of(), 0, 0, 0);
                }));
        }


        /*
        步骤四：等待所有子问题完成
        CompletableFuture.join() 会阻塞当前线程，直到异步任务完成。因为前面已经用 exceptionally 处理了异常，所以这里不会抛出异常，每个 Future 要么返回正常结果，要么返回降级后的空证据对象。
         */
        // join 会等待每个子问题都回到"成功结果或降级结果"状态，这里不会再向外抛单个子问题异常。
        List<SubQuestionEvidence> evidenceList = futures.stream()
            .map(CompletableFuture::join)
            .toList();
        /*
        步骤五：统计检索命中率
        这里统计了有多少个子问题真正检索到了文档。如果 acceptedCount 很低，说明检索质量不好，可能需要调整检索策略或阈值。
         */
        // 统计有多少个子问题真正取回了文档证据，便于日志观察检索命中率。
        int acceptedCount = (int) evidenceList.stream().filter(item -> item.getDocuments() != null && !item.getDocuments().isEmpty()).count();
        log.info("RAG 检索完成: retrievalQuestion='{}', originalSubQuestionCount={}, acceptedSubQuestionCount={}, notes={}",
            plan.getRetrievalQuestion(),
            evidenceList.size(),
            acceptedCount,
            context.getRetrievalNotes());
        /*
        步骤六：分配引用编号
        最后调用 assignReferenceIds 方法，为所有引用分配连续的编号（1、2、3...）。这样在答案中就可以用 [1]、[2] 这样的标记来引用具体的文档。
         */
        // 统一为所有子问题下的引用分配稳定、连续的 referenceId，便于前端展示和答案内引用映射。
        assignReferenceIds(evidenceList);
        context.setSubQuestionEvidenceList(evidenceList);
        return context;
    }

    /**
     * 检索单个子问题。
     * 该方法会并发调度所有可用检索通道，然后执行闸门过滤、RRF 融合、父块提升、rerank 和最终裁剪。
     *
     * @param subQuestionIndex 子问题序号，从 1 开始
     * @param subQuestion 子问题文本
     * @param plan 执行计划
     * @param usedChannels 全局已使用通道集合
     * @param notes 全局检索备注
     * @param traceRecorder 检索追踪器
     * @return 当前子问题对应的证据对象
     */
    private SubQuestionEvidence retrieveSingleSubQuestion(int subQuestionIndex,//子问题序号（从 1 开始），用于日志和调试
                                                          String subQuestion,//子问题文本，比如"Java 的特点是什么？"
                                                          ConversationExecutionPlan plan,//执行计划，包含检索范围、文档 ID 等信息
                                                          List<String> usedChannels,//全局已使用通道集合（同步列表），用于记录哪些通道被使用了
                                                          List<String> notes,//全局检索备注（同步列表），用于记录检索过程中的提示信息

                                                          ConversationTraceRecorder traceRecorder) {//单个子问题的完整检索流程     检索追踪器，用于记录详细的检索观测数据
        /*
        步骤一：并发调度所有检索通道
         */
        // 针对每个子问题，把所有"支持当前 plan 的通道"并发跑起来，最大化利用不同召回能力。
        List<CompletableFuture<RetrievalChannelResult>> futures = retrievalChannels.stream()

            .filter(channel -> channel.supports(plan))
            .map(channel -> CompletableFuture.supplyAsync(() -> channel.retrieve(subQuestion, plan), executorService)
                    // 每个通道同样有独立超时，避免某一路检索长期阻塞。
                .orTimeout(Math.max(properties.getChannelTimeoutMs(), 1L), TimeUnit.MILLISECONDS)
                .exceptionally(throwable -> {

                    // 通道异常时记录根因并自动降级为空结果，避免单通道失败拖垮当前子问题。
                    Throwable rootCause = unwrapThrowable(throwable);
                    log.warn("检索通道失败: subQuestionIndex={}, subQuestion='{}', channel='{}', exceptionType={}, message={}",
                        subQuestionIndex,
                        subQuestion,
                        channel.channelName(),
                        rootCause == null ? "" : rootCause.getClass().getName(),
                        rootCause == null ? "" : rootCause.getMessage(),
                        throwable);
                    notes.add("子问题" + subQuestionIndex + "通道[" + channel.channelName() + "]检索失败或超时，已自动降级。");
                    return new RetrievalChannelResult(channel.channelName(), List.of());
                }))
            .toList();
        if (futures.isEmpty()) {
            // 如果没有任何通道支持当前计划，直接返回空证据，并写入提示说明。
            notes.add("子问题" + subQuestionIndex + "没有可用的检索通道。");
            return new SubQuestionEvidence(subQuestionIndex, subQuestion, List.of(), new ArrayList<>(), List.of(), 0, 0, 0);
        }

        // 原始结果保留的是通道直接召回的候选，用于后续观测记录和过滤前后对比。
        List<RetrievalChannelResult> rawChannelResults = futures.stream()
            .map(CompletableFuture::join)
            .filter(result -> result.getDocuments() != null)
            .toList();
        // 对不同通道应用对应的证据闸门，过滤掉分数过低的候选文档。
        List<RetrievalChannelResult> channelResults = rawChannelResults.stream()
            .map(this::applyEvidenceGate)
            .toList();
        // 构建每个通道"召回数 / 通过闸门数"的观察轨迹，供调试界面展示。
        List<SubQuestionChannelTrace> channelTraces = buildChannelTraces(rawChannelResults, channelResults);

        channelResults.stream()
            .filter(result -> !result.getDocuments().isEmpty())
                // 只要某个通道最终贡献了有效候选，就把它标记为"本轮实际使用过"。
            .forEach(result -> markUsedChannel(usedChannels, result.getChannelName()));

        // 用 RRF 融合多个通道的候选，减少单一通道偏差，并让多通道共同命中的文档获得更高优先级。
        List<Document> mergedCandidates = fuseByRrf(channelResults);
        // 将分块级证据尽量提升回父块，避免最终回答只看到过短的孤立片段。
        List<Document> parentCandidates = documentKnowledgeService.elevateToParentBlocks(
            mergedCandidates,
            properties.getParentEvidenceMaxChars()
        );
        // 对父块候选再做 rerank，把与当前子问题最相关的内容排到更前面。
        List<Document> rerankedCandidates = applyRerank(subQuestion, parentCandidates, usedChannels);

        // 最终只保留 topK 证据，控制后续 Prompt 的上下文体积。
        List<Document> finalDocuments = rerankedCandidates.stream()
            .limit(properties.getFinalTopK())
            .toList();

        // 写入子问题检索摘要，便于前端或日志快速查看各通道命中情况。
        notes.add("子问题" + subQuestionIndex + "检索完成："
            + summarizeChannelResults(channelResults)
            + "，final=" + finalDocuments.size());

        if (traceRecorder != null) {
            try {
                // 记录通道执行情况与候选文档选择情况，供后续调试页回放完整检索过程。
                recordChannelObservations(traceRecorder, subQuestionIndex, subQuestion,
                    rawChannelResults, channelResults, channelTraces);
                recordRetrievalResultObservations(traceRecorder, subQuestionIndex, subQuestion,
                    rawChannelResults, channelResults, mergedCandidates, rerankedCandidates, finalDocuments);
            } catch (RuntimeException exception) {
                log.warn("记录检索观测数据失败, subQuestionIndex={}", subQuestionIndex, exception);
            }
        }
        // 当前子问题的最终输出是"最终文档 + 引用占位 + 通道轨迹 + 过程统计"。
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
        if (result == null || result.getDocuments() == null || result.getDocuments().isEmpty()) {//证据门控：向量通道有相似度最低阈值，关键词通道有相对分数地板
            return result;
        }

        List<Document> documents = switch (result.getChannelName()) {
            case "vector" -> filterVectorCandidates(result.getDocuments());
            case "keyword" -> filterKeywordCandidates(result.getDocuments());
            default -> result.getDocuments();
        };
        return new RetrievalChannelResult(result.getChannelName(), documents);
    }

    private List<Document> filterVectorCandidates(List<Document> documents) {//向量结果过滤（低于阈值的丢掉）
        return documents.stream()

            .filter(document -> {
                Double score = resolveScore(document);
                return score != null && score >= properties.getMinVectorSimilarity();
            })
            .toList();
    }

    private List<Document> filterKeywordCandidates(List<Document> documents) {//关键词结果过滤（相对分数太低的丢掉）
        Double topScore = documents.stream()
            .map(this::resolveScore)
            .filter(Objects::nonNull)
            .max(Double::compareTo)
            .orElse(null);
        if (topScore == null || topScore <= 0D) {
            return documents;
        }

        double acceptedFloor = topScore * Math.max(0D, properties.getKeywordRelativeScoreFloor());
        return documents.stream()
            .filter(document -> {
                Double score = resolveScore(document);
                return score != null && score >= acceptedFloor;
            })
            .toList();
    }

    private List<Document> fuseByRrf(List<RetrievalChannelResult> channelResults) {//RRF 融合：把多通道结果合并成一个排序列表
        Map<String, CandidateHolder> holders = new LinkedHashMap<>();

        for (RetrievalChannelResult retrievalChannelResult : channelResults) {
            accumulateRrf(retrievalChannelResult, holders);
        }

        return holders.values().stream()
            .sorted((left, right) -> Double.compare(right.score, left.score))
            .limit(properties.getCandidateTopK())
            .map(holder -> {

                holder.document.getMetadata().put(DocumentKnowledgeMetadataKeys.SCORE, holder.score);
                holder.document.getMetadata().put(DocumentKnowledgeMetadataKeys.RRF_SCORE, holder.score);
                holder.document.getMetadata().put(DocumentKnowledgeMetadataKeys.CHANNEL,
                    holder.channels.size() > 1 ? "hybrid" : holder.channels.iterator().next());
                return holder.document;
            })
            .toList();
    }

    private void accumulateRrf(RetrievalChannelResult channelResult, Map<String, CandidateHolder> holders) {//累加单个通道的 RRF 分数
        List<Document> documents = channelResult.getDocuments();
        for (int rank = 0; rank < documents.size(); rank++) {
            Document document = documents.get(rank);
            String documentId = document.getId();

            double rrfScore = 1D / (RRF_K + rank + 1);
            CandidateHolder holder = holders.computeIfAbsent(documentId, ignored -> new CandidateHolder(document));
            holder.score += rrfScore;
            holder.channels.add(channelResult.getChannelName());
        }
    }

    private List<Document> applyRerank(String subQuestion,
                                       List<Document> candidates,
                                       List<String> usedChannels) {//调用外部 Rerank 模型二次排序
        if (!properties.isRerankEnabled() || candidates.isEmpty()) {
            return candidates;
        }

        markUsedChannel(usedChannels, RetrievalChannelEnum.RERANK.getName());
        return rerankPostProcessor.process(new Query(subQuestion), candidates);
    }

    private void assignReferenceIds(List<SubQuestionEvidence> evidenceList) {//跨子问题分配连续的引用编号
        final int[] referenceNumber = {1};
        Map<String, String> assignedIds = new LinkedHashMap<>();
        for (SubQuestionEvidence evidence : evidenceList) {
            List<SearchReference> references = new ArrayList<>();
            for (Document document : evidence.getDocuments()) {

                SearchReference reference = SearchReferenceMapper.fromDocument(
                    document,
                    evidence.getSubQuestionIndex(),
                    evidence.getSubQuestion(),
                    0
                );
                String uniqueKey = reference.uniqueKey();

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

        Object metadataScore = document.getMetadata().get(DocumentKnowledgeMetadataKeys.SCORE);
        if (metadataScore instanceof Number number) {
            return number.doubleValue();
        }
        return document.getScore();
    }

    private void markUsedChannel(List<String> usedChannels, String channel) {

        if (!usedChannels.contains(channel)) {
            usedChannels.add(channel);
        }
    }

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
                    execution.setAvgScore(BigDecimal.valueOf(scores.stream().mapToDouble(Double::doubleValue).average().orElse(0)));
                    execution.setMaxScore(BigDecimal.valueOf(scores.stream().mapToDouble(Double::doubleValue).max().orElse(0)));
                    execution.setMinScore(BigDecimal.valueOf(scores.stream().mapToDouble(Double::doubleValue).min().orElse(0)));
                }
            }

            executions.add(execution);
        }

        traceRecorder.recordChannelExecutions(executions);
    }

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
                        view.setOriginalScore(BigDecimal.valueOf(((Number) scoreObj).doubleValue()));
                    }

                    Object rrfScoreObj = doc.getMetadata().get(DocumentKnowledgeMetadataKeys.RRF_SCORE);
                    if (rrfScoreObj instanceof Number) {
                        view.setRrfScore(BigDecimal.valueOf(((Number) rrfScoreObj).doubleValue()));
                    }

                    Object rerankScoreObj = doc.getMetadata().get("rerankScore");
                    if (rerankScoreObj instanceof Number) {
                        view.setRerankScore(BigDecimal.valueOf(((Number) rerankScoreObj).doubleValue()));
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

    private static class CandidateHolder {//内部类CandidateHolder

        private final Document document;//文档快
        private final LinkedHashSet<String> channels = new LinkedHashSet<>();//来自那些通道
        private double score;//RRF 累积分数

        private CandidateHolder(Document document) {
            this.document = document;
        }
    }

    private Throwable unwrapThrowable(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        Throwable current = throwable;
        while (current.getCause() != null
            && current.getCause() != current
            && (current instanceof CompletionException
            || current instanceof ExecutionException
            || current instanceof TimeoutException)) {
            current = current.getCause();
        }
        return current;
    }
}
