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

        /*
        等待所有通道完成并过滤空结果
        这里先检查是否有可用的通道。如果没有，直接返回空证据。
        然后等待所有通道完成（CompletableFuture::join），并过滤掉空结果。这里保留的是原始结果，也就是通道直接召回的候选，还没有经过任何过滤。
         */
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



        /*
        应用证据闸门过滤
         */
        // 对不同通道应用对应的证据闸门，过滤掉分数过低的候选文档。 这里调用 applyEvidenceGate 方法，对每个通道的结果应用证据闸门
        List<RetrievalChannelResult> channelResults = rawChannelResults.stream()
            .map(this::applyEvidenceGate)
            .toList();
        /*
        构建通道轨迹
        List<SubQuestionChannelTrace> channelTraces = buildChannelTraces(rawChannelResults, channelResults);
         */
        // 构建每个通道"召回数 / 通过闸门数"的观察轨迹，供调试界面展示。
        List<SubQuestionChannelTrace> channelTraces = buildChannelTraces(rawChannelResults, channelResults);
        channelResults.stream()
            .filter(result -> !result.getDocuments().isEmpty())
                // 只要某个通道最终贡献了有效候选，就把它标记为"本轮实际使用过"。
            .forEach(result -> markUsedChannel(usedChannels, result.getChannelName()));

        // 用 RRF 融合多个通道的候选，减少单一通道偏差，并让多通道共同命中的文档获得更高优先级。
        List<Document> mergedCandidates = fuseByRrf(channelResults);

        /*
        父块提升

         */
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

    /**
     * 对单个通道结果应用证据闸门。
     * 不同通道采用不同过滤策略，例如向量通道基于最小相似度，关键词通道基于相对分数下限。
     *
     * @param result 通道原始结果
     * @return 过滤后的通道结果
     * 什么是证据闸门？
     * 证据闸门（Evidence Gate）是一个过滤机制，用于过滤掉分数过低的候选文档。不同通道使用不同的过滤策略：
     */
    private RetrievalChannelResult applyEvidenceGate(RetrievalChannelResult result) {
        if (result == null || result.getDocuments() == null || result.getDocuments().isEmpty()) {//证据门控：向量通道有相似度最低阈值，关键词通道有相对分数地板
            return result;
        }
        // 按通道类型选择不同的过滤策略，避免不同分数体系混用同一阈值。
        List<Document> documents = switch (result.getChannelName()) {
            case "vector" -> filterVectorCandidates(result.getDocuments());
            case "keyword" -> filterKeywordCandidates(result.getDocuments());
            default -> result.getDocuments();
        };
        return new RetrievalChannelResult(result.getChannelName(), documents);
    }


    /**向量通道：绝对阈值过滤
     * 过滤向量检索候选。
     *向量检索的分数是相似度（0-1 之间），可以直接用绝对阈值过滤。比如设置 minVectorSimilarity = 0.7，只保留相似度 >= 0.7 的文档。
     * @param documents 原始候选
     * @return 满足最小相似度阈值的候选
     */
    private List<Document> filterVectorCandidates(List<Document> documents) {//向量结果过滤（低于阈值的丢掉）
        return documents.stream()

            .filter(document -> {
                // 向量通道直接按绝对相似度阈值过滤，屏蔽明显低相关候选。
                Double score = resolveScore(document);
                return score != null && score >= properties.getMinVectorSimilarity();
            })
            .toList();
    }

    /**关键词通道：相对阈值过滤
     * 过滤关键词检索候选。
     * 关键词通道不采用绝对阈值，而是相对于当前通道 topScore 计算一个接受下限。
     *
     * @param documents 原始候选
     * @return 满足相对阈值的候选
     * ————————————————————————————————————————————————————————————————————————
     * 关键词检索的分数是 BM25 分数，不同问题的分数范围差异很大。所以不能用绝对阈值，而是用相对阈值：
     * 1.找到当前通道的最高分 topScore
     * 2.计算接受下限：acceptedFloor = topScore * keywordRelativeScoreFloor
     * 3.只保留分数 >= acceptedFloor 的文档
     * 比如 keywordRelativeScoreFloor = 0.5，如果最高分是 10，那么只保留分数 >= 5 的文档。
     */
    private List<Document> filterKeywordCandidates(List<Document> documents) {//关键词结果过滤（相对分数太低的丢掉）
        Double topScore = documents.stream()
            .map(this::resolveScore)
            .filter(Objects::nonNull)
            .max(Double::compareTo)
            .orElse(null);
        if (topScore == null || topScore <= 0D) {
            // 如果没有可用分数，就不做过滤，避免误伤所有候选。
            return documents;
        }
        // 关键词通道采用相对阈值，防止不同问题下绝对分数分布波动过大。
        double acceptedFloor = topScore * Math.max(0D, properties.getKeywordRelativeScoreFloor());
        return documents.stream()
            .filter(document -> {
                Double score = resolveScore(document);
                return score != null && score >= acceptedFloor;
            })
            .toList();
    }

    /**融合主方法
     * 使用 RRF（Reciprocal Rank Fusion）融合多个通道候选。
     *
     * @param channelResults 各通道过滤后的候选结果
     * @return 融合并排序后的候选列表
     */
    /*
    这个方法分三步：
    第一步：遍历每个通道，累加 RRF 分数
    对每个通道调用 accumulateRrf，把通道内的排名转化为 RRF 分数，累加到对应文档的 CandidateHolder 上。如果同一个文档在多个通道都出现了，它的分数会被多次累加。
    第二步：按 RRF 总分降序排列，取 Top K
    properties.getCandidateTopK() 默认是 10，也就是融合后只保留分数最高的 10 个候选。
    第三步：把融合结果写回 metadata
    对每个入选的候选文档，把以下信息写入 metadata：
    SCORE：RRF 融合分数（覆盖掉之前通道的原始分数）
    RRF_SCORE：同样是 RRF 分数（单独存一份，方便后续对比）
    CHANNEL：来源通道标记。如果文档被多个通道命中，标记为 "hybrid"；如果只被一个通道命中，就标记那个通道的名字
     */
    private List<Document> fuseByRrf(List<RetrievalChannelResult> channelResults) {//RRF 融合：把多通道结果合并成一个排序列表
        Map<String, CandidateHolder> holders = new LinkedHashMap<>();

        for (RetrievalChannelResult retrievalChannelResult : channelResults) {
            // 每个通道的排名都会转化为 RRF 分数并累加到同一文档上。
            accumulateRrf(retrievalChannelResult, holders);
        }

        return holders.values().stream()
            .sorted((left, right) -> Double.compare(right.score, left.score))
            .limit(properties.getCandidateTopK())
            .map(holder -> {
                // 把融合后的分数和来源通道写回 metadata，方便后续 rerank、观测和调试页面复用。
                holder.document.getMetadata().put(DocumentKnowledgeMetadataKeys.SCORE, holder.score);
                holder.document.getMetadata().put(DocumentKnowledgeMetadataKeys.RRF_SCORE, holder.score);
                holder.document.getMetadata().put(DocumentKnowledgeMetadataKeys.CHANNEL,
                    holder.channels.size() > 1 ? "hybrid" : holder.channels.iterator().next());
                return holder.document;
            })
            .toList();
    }


    /*
    accumulateRrf：RRF 分数累加
    private static final int RRF_K = 60;
    累加单个通道对 RRF 融合结果的贡献。
    * @param channelResult 单个通道结果
     * @param holders 融合中的候选容器
     ____________________________________________
     这是 RRF 的核心计算逻辑。遍历通道内的每个文档，根据它在通道中的排名计算 RRF 分数，然后累加到 holders 中对应的 CandidateHolder 上。
    computeIfAbsent 保证了：如果这个文档第一次出现，就创建一个新的 holder；如果已经存在（被其他通道命中过），就直接拿到已有的 holder 继续累加。
     */
    private void accumulateRrf(RetrievalChannelResult channelResult, Map<String, CandidateHolder> holders) {//累加单个通道的 RRF 分数
        // 通道结果已经经过闸门过滤，这里的 documents 都是可参与融合的候选。
        List<Document> documents = channelResult.getDocuments();
        for (int rank = 0; rank < documents.size(); rank++) {
            Document document = documents.get(rank);
            // documentId 是跨通道去重和累加分数的唯一键。
            String documentId = document.getId();
            // RRF 分数按排名倒数计算：排名越靠前，贡献越大。
            double rrfScore = 1D / (RRF_K + rank + 1);
            // 第一次遇到该文档时创建 holder，后续跨通道命中会复用同一个 holder。
            CandidateHolder holder = holders.computeIfAbsent(documentId, ignored -> new CandidateHolder(document));
            holder.score += rrfScore;
            // 记录该文档来自哪些通道，后面会转成 metadata 中的 channel/hybrid 标识。
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


    /*
    resolveScore：分数解析方法
    上面两个过滤方法都调用了 resolveScore 来获取文档的分数，
    这个方法的逻辑很简单，但设计上有个小巧思：两级分数来源。
1.优先从 metadata 中取分数：各检索通道（向量、关键词）在返回结果时，会把原始分数写入 document.getMetadata().put("score", xxx)。这个分数是通道自己计算的，比如向量通道放的是余弦相似度，关键词通道放的是 BM25 分数。
2.兜底用 Spring AI 自带的 score：如果 metadata 里没有分数（比如某些特殊场景），就回退到 document.getScore()，这是 Spring AI 框架的 Document 对象自带的分数字段。
     */
    private Double resolveScore(Document document) {
        // document 为空时没有可解析分数。
        if (document == null) {
            return null;
        }

        // 优先读取业务 metadata 中的 SCORE，因为各检索通道通常会把原始分数放在这里。
        Object metadataScore = document.getMetadata().get(DocumentKnowledgeMetadataKeys.SCORE);
        if (metadataScore instanceof Number number) {
            return number.doubleValue();
        }
        // metadata 没有分数时，回退到 Spring AI Document 自带的 score。
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


    /**
     * 构造每个通道的召回数量和闸门后采纳数量。
     * ————————————————————————————————————————————————————————————————————
     * 这个方法的思路很清晰，分三步走：
     * 第一步：分别统计原始召回数和闸门通过数
     * 用两个 LinkedHashMap 分别记录：
     * rawMap：每个通道在证据闸门过滤之前召回了多少文档
     * filteredMap：每个通道在证据闸门过滤之后还剩多少文档
     * 第二步：合并所有通道名
     * 用 LinkedHashSet 把两边的通道名合并起来，保持插入顺序。为什么要合并？因为理论上可能存在某个通道只在 raw 里有结果但 filtered 里没有（全被过滤掉了），或者反过来。
     * 第三步：逐通道生成 trace 对象
     * 遍历所有通道名，从两个 Map 中取对应的数量，缺失的按 0 处理，最终生成 SubQuestionChannelTrace 列表。
     */
    private List<SubQuestionChannelTrace> buildChannelTraces(List<RetrievalChannelResult> rawResults,
                                                             List<RetrievalChannelResult> filteredResults) {
        // 原始结果和过滤结果都为空时，没有可记录的通道 trace。
        if ((rawResults == null || rawResults.isEmpty()) && (filteredResults == null || filteredResults.isEmpty())) {
            return List.of();
        }
        // rawMap 记录通道原始召回数量。
        Map<String, Integer> rawMap = new LinkedHashMap<>();
        // filteredMap 记录通道闸门过滤后的采纳数量。
        Map<String, Integer> filteredMap = new LinkedHashMap<>();
        if (rawResults != null) {
            rawResults.forEach(result -> rawMap.put(result.getChannelName(), result.getDocuments() == null ? 0 : result.getDocuments().size()));
        }
        if (filteredResults != null) {
            filteredResults.forEach(result -> filteredMap.put(result.getChannelName(), result.getDocuments() == null ? 0 : result.getDocuments().size()));
        }
        // 使用 LinkedHashSet 保持通道出现顺序，同时合并 raw/filtered 两边的通道名。
        LinkedHashSet<String> channelNames = new LinkedHashSet<>();
        channelNames.addAll(rawMap.keySet());
        channelNames.addAll(filteredMap.keySet());
        // 逐通道生成 trace 对象，缺失的一侧按 0 处理。
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



    /*
    CandidateHolder：融合过程中的临时容器
    在看融合逻辑之前，先看一下用来暂存融合结果的内部类：
    每个 CandidateHolder 对应一个候选文档，它记录了三个信息：
    document：原始文档对象
    channels：这个文档被哪些通道命中了（用 LinkedHashSet 去重且保持顺序）
    score：跨通道累加后的 RRF 总分
     */
    private static class CandidateHolder {//内部类CandidateHolder
        // 原始 Document 对象会在融合后继续向后传递，并写入融合分等 metadata。
        private final Document document;//文档快
        // 记录该候选来自哪些通道，用于判断是单通道命中还是 hybrid 命中。
        private final LinkedHashSet<String> channels = new LinkedHashSet<>();//来自那些通道
        // 该候选跨通道累加后的 RRF 分数。
        private double score;//RRF 累积分数

        private CandidateHolder(Document document) {//每个 CandidateHolder 对应一个候选文档，它记录了三个信息：
            // holder 创建时绑定候选文档，后续只累加分数和通道来源。
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
