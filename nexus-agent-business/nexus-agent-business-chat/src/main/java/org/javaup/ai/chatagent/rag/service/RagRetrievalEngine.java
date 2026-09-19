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
        /*
        Rerank重排序
         */
        // 对父块候选再做 rerank，把与当前子问题最相关的内容排到更前面。
        List<Document> rerankedCandidates = applyRerank(subQuestion, parentCandidates, usedChannels);

        /*
        最终裁剪
        经过前面的层层筛选，候选文档已经比较精准了。但为了控制 Prompt 的长度，最终只保留 topK 个文档（通常是 5-10 个）。
         */
        // 最终只保留 topK 证据，控制后续 Prompt 的上下文体积。  从 Rerank 排好序的候选列表里，只取前 finalTopK 条。
        List<Document> finalDocuments = rerankedCandidates.stream()
            .limit(properties.getFinalTopK())
            .toList();


        /*检索摘要生成  裁剪完之后，需要生成一条人类可读的检索摘要，方便调试和日志追踪：
        记录检索摘要 写入子问题检索摘要，便于前端或日志快速查看各通道命中情况。
        这里会生成一条摘要信息，比如：
        子问题1检索完成：vector=5，keyword=3，final=5
        表示向量通道召回了 5 个文档，关键词通道召回了 3 个文档，最终保留了 5 个文档。
         */
        // 写入子问题检索摘要，便于前端或日志快速查看各通道命中情况。   summarizeChannelResults 方法来生成各通道的数量统计
        notes.add("子问题" + subQuestionIndex + "检索完成："
            + summarizeChannelResults(channelResults)
            + "，final=" + finalDocuments.size());


        /*
        记录检索观测数据
        这里会把详细的检索观测数据记录到数据库，包括：
        通道级观测：每个通道的召回数、通过闸门数、最终入选数、分数统计
        文档级观测：每个候选文档的通道排名、原始分数、RRF 分数、rerank 分数、是否通过闸门、是否入选最终 Prompt
        这些数据会落库到 channel_execution 和 retrieval_result 表，供调试页面展示。
         */
        /*
        这段代码有几个设计要点：
    1. 条件触发：只有 traceRecorder != null 时才记录。生产环境可以通过不传 traceRecorder 来关闭观测，避免性能开销。
    2. 两层观测：
    recordChannelObservations：通道级——每个通道召回了多少、采纳了多少、分数分布如何
    recordRetrievalResultObservations：文档级——每个候选文档的完整生命周期（从哪个通道来、分数多少、是否过闸门、最终排名多少）
    3. 异常隔离：整个 trace 记录包在 try-catch 里。观测数据是"锦上添花"的调试信息，绝对不能因为记录失败而影响主检索流程。即使 traceRecorder 内部抛异常，也只是打个 warn 日志，主流程照常返回结果。
    设计思路
    这是一种典型的"旁路观测"模式：主流程和观测流程解耦，观测失败不影响业务。类似于 APM（应用性能监控）的 agent 设计——即使监控挂了，业务也不能挂。
         */
        if (traceRecorder != null) {// 开启 trace 时，额外记录通道执行和候选文档入选/过滤原因。
            try {
                /*
                Trace 观测记录的触发
                FinalTopK 裁剪和摘要生成完成后，如果当前对话开启了 trace 记录（traceRecorder != null），就会触发两个观测记录方法：
                 */
                // 记录通道执行情况与候选文档选择情况，供后续调试页回放完整检索过程。  // 记录通道级观测：召回数、采纳数、分数统计等。
                recordChannelObservations(traceRecorder, subQuestionIndex, subQuestion,
                    rawChannelResults, channelResults, channelTraces);
                // 记录文档级观测：每个召回块的分数、排名、是否过闸门、是否进入最终 Prompt。
                recordRetrievalResultObservations(traceRecorder, subQuestionIndex, subQuestion,
                    rawChannelResults, channelResults, mergedCandidates, rerankedCandidates, finalDocuments);
            } catch (RuntimeException exception) {
                // 观测记录失败不能影响主检索结果，因此只打印告警。
                log.warn("记录检索观测数据失败, subQuestionIndex={}", subQuestionIndex, exception);
            }
        }

        /*
        返回证据对象
        最后返回一个 SubQuestionEvidence 对象，包含：
        subQuestionIndex：子问题序号
        subQuestion：子问题文本
        finalDocuments：最终入选的文档列表
        references：引用列表（这里是空的，后续会填充）
        channelTraces：通道轨迹（每个通道的召回数和通过闸门数）
        fusedCandidateCount：RRF 融合后的候选数
        parentCandidateCount：父块提升后的候选数
        rerankedCandidateCount：rerank 后的候选数
        SubQuestionEvidence 的封装与返回
所有观测数据记录完毕后，retrieveSingleSubQuestion 方法的最后一步是把结果封装成 SubQuestionEvidence 对象返回：
 将最终证据、通道 trace 和各阶段候选数量封装成子问题证据对象。
         */
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



    /**
     * 对候选文档执行 rerank。
     *
     * @param subQuestion 当前子问题
     * @param candidates 待重排候选
     * @param usedChannels 已使用通道集合
     * @return 重排后的候选列表
     * 这个方法本身很简单：先判断是否开启了 rerank，如果开启了就调用 rerankPostProcessor.process 执行实际的重排序。同时把 rerank 标记为"已使用通道"，方便调试页面展示。
     * 关键逻辑在 rerankPostProcessor.process 里面，它的实现类是 HttpDocumentRerankPostProcessor。
     */
    private List<Document> applyRerank(String subQuestion,
                                       List<Document> candidates,
                                       List<String> usedChannels) {//调用外部 Rerank 模型二次排序
        if (!properties.isRerankEnabled() || candidates.isEmpty()) {
            // 未开启 rerank 或没有候选时，直接返回原顺序。
            return candidates;
        }
        // rerank 本身也视作一种"通道 / 能力"使用，需要在调试和落库中体现。
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
    /**
     * 汇总每个检索通道闸门后的候选数量，生成一条简短检索备注。
     * 逻辑很简单：遍历每个通道的过滤后结果，把通道名和文档数量拼成 通道名=数量 的格式，用中文逗号连接。
     * 最终生成的 notes 大概长这样：
     * 子问题1检索完成：vector=3，keyword=2，final=4
     * 这条信息会出现在对话的调试面板里，一眼就能看出每个通道贡献了多少证据，最终选了几条。
     */
    private String summarizeChannelResults(List<RetrievalChannelResult> channelResults) {
        // 没有任何通道时，给出更明确的提示文案。
        if (channelResults.isEmpty()) {
            return "没有启用任何检索通道";
        }
        // 格式示例：vector=3，keyword=2，便于用户或调试者快速看懂。
        return channelResults.stream()
            .map(result -> result.getChannelName() + "=" + result.getDocuments().size())
            .reduce((left, right) -> left + "，" + right)
            .orElse("没有检索结果");
    }


    /**通道轨迹构建
     * 在前面的流程中（RRF 融合阶段），系统已经调用了 buildChannelTraces 方法来构建通道轨迹。这里我们补充讲解一下这个方法，因为它生成的 channelTraces 会在后面的 trace 记录和最终返回值中用到：
     * 构造每个通道的召回数量和闸门后采纳数量。                                     这个方法做的事情是：把每个通道的"原始召回数"和"闸门后采纳数"配对记录下来。
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

    /**
     * 记录通道级检索观测数据。
     *
     * <p>该方法把每个通道的原始召回数、闸门后采纳数、最终选择数以及分数统计写入 trace，
     * 用来回答"哪个通道召回了多少，为什么后面剩这么少"。</p>
     */
    private void recordChannelObservations(ConversationTraceRecorder traceRecorder,
                                           int subQuestionIndex,
                                           String subQuestion,
                                           List<RetrievalChannelResult> rawResults,
                                           List<RetrievalChannelResult> filteredResults,
                                           List<SubQuestionChannelTrace> channelTraces) {
        /*前置校验
        没有原始通道结果时，说明没有可记录的通道执行。
        没有原始通道结果就直接返回。这种情况一般不会出现（前面的流程至少会执行一个通道），但作为防御性编程还是加了判断。
        */
        if (rawResults == null || rawResults.isEmpty()) {
            return;
        }
        // 收集每个通道对应的一条 ChannelExecutionView，最后批量写入 traceRecorder。
        List<ChannelExecutionView> executions = new ArrayList<>();
        /*
        遍历每个通道，组装观测数据
        方法的核心是一个 for 循环，遍历每个通道的原始结果，为每个通道生成一条 ChannelExecutionView 记录。
         */
        for (RetrievalChannelResult rawResult : rawResults) {

            //第一步：获取原始召回数量  通道名用于后续关联过滤结果和通道 trace。
            String channelName = rawResult.getChannelName();
            // recalledCount 是通道原始返回数量，代表召回阶段的广度。 recalledCount 代表这个通道在闸门过滤之前召回了多少文档。比如向量通道配置了 topK=10，那这里最多就是 10。
            int recalledCount = rawResult.getDocuments() == null ? 0 : rawResult.getDocuments().size();



            //第二步：获取闸门后采纳数量  在过滤后的结果中找到同名通道，用于计算证据闸门后的采纳数量。 从过滤后的结果列表中，找到同名通道的结果，拿到闸门后剩余的文档数量。如果向量通道召回了 10 条，闸门过滤掉 4 条低分的，那 acceptedCount 就是 6。
            RetrievalChannelResult filteredResult = filteredResults == null ? null :
                filteredResults.stream().filter(r -> channelName.equals(r.getChannelName())).findFirst().orElse(null);
            int acceptedCount = filteredResult == null || filteredResult.getDocuments() == null ? 0 : filteredResult.getDocuments().size();


            //第三步：获取最终选择数量  channelTraces 里也保存了召回/采纳关系，这里用于补 finalSelectedCount 字段。 从之前构建好的 channelTraces 中取出该通道的最终选择数量。当前实现里这个值和 acceptedCount 是对齐的。
            SubQuestionChannelTrace trace = channelTraces == null ? null :
                channelTraces.stream().filter(t -> channelName.equals(t.getChannelName())).findFirst().orElse(null);
            // 当前实现里 finalSelectedCount 与闸门后 acceptedCount 对齐，表示通过当前通道过滤的数量。
            int finalSelectedCount = trace == null ? 0 : trace.getAcceptedCount();


            //第四步：组装 ChannelExecutionView 对象  组装通道执行观测对象，关联当前 trace、子问题和通道类型。 把前面收集到的数据填入视图对象。executionState=1 表示通道执行成功。
            ChannelExecutionView execution = new ChannelExecutionView();
            execution.setId(traceRecorder.exchangeId());
            execution.setTraceId(traceRecorder.traceId());
            execution.setSubQuestionIndex(subQuestionIndex);
            execution.setSubQuestion(subQuestion);
            execution.setChannelType(channelName);
            // executionState=1 表示本通道执行完成；失败通道在 exceptionally 中会变成空结果。
            execution.setExecutionState(1);
            execution.setRecalledCount(recalledCount);
            execution.setAcceptedCount(acceptedCount);
            execution.setFinalSelectedCount(finalSelectedCount);


            //第五步：计算分数统计   如果通道原始结果有分数，计算平均分、最高分、最低分，便于观察召回质量。
            /*
            这段代码从通道的原始召回文档中提取分数，计算平均分、最高分、最低分。注意两个细节：
            只统计正分数：.filter(score -> score > 0) 过滤掉了没有分数（默认 0）的文档，避免拉低统计值
            有有效分数才写入：如果所有文档都没有分数，就不设置统计字段，避免前端展示误导性的 0 值
             */
            if (rawResult.getDocuments() != null && !rawResult.getDocuments().isEmpty()) {
                List<Double> scores = rawResult.getDocuments().stream()
                    .map(doc -> {
                        // 分数来自统一 metadata key；没有分数时按 0 处理，后面会过滤掉。
                        Object scoreObj = doc.getMetadata().get(DocumentKnowledgeMetadataKeys.SCORE);
                        if (scoreObj instanceof Number) {
                            return ((Number) scoreObj).doubleValue();
                        }
                        return 0.0;
                    })
                        // 只统计正分数，避免空分数或默认 0 拉低统计值。
                    .filter(score -> score > 0)
                    .toList();
                // 有有效分数时才写入 BigDecimal 统计，避免空集合导致误导性 0 值。
                if (!scores.isEmpty()) {
                    execution.setAvgScore(BigDecimal.valueOf(scores.stream().mapToDouble(Double::doubleValue).average().orElse(0)));
                    execution.setMaxScore(BigDecimal.valueOf(scores.stream().mapToDouble(Double::doubleValue).max().orElse(0)));
                    execution.setMinScore(BigDecimal.valueOf(scores.stream().mapToDouble(Double::doubleValue).min().orElse(0)));
                }
            }
            // 当前通道观测对象组装完成，加入批量写入列表。
            executions.add(execution);
        }
        // 批量写入通道执行观测，持久化细节由 traceRecorder 封装。 所有通道的观测数据收集完毕后，一次性批量写入。具体的持久化方式（写数据库、写日志、发消息队列）由 traceRecorder 内部封装，这里不关心。
        traceRecorder.recordChannelExecutions(executions);
    }




    /**
     * 记录文档级检索观测数据。
     *
     * <p>该方法会为每个原始召回文档记录通道排名、原始分数、RRF 分数、rerank 分数、
     * 是否通过证据闸门、是否进入最终 Prompt，以及未入选原因。</p>
     */
    private void recordRetrievalResultObservations(ConversationTraceRecorder traceRecorder,
                                                   int subQuestionIndex,
                                                   String subQuestion,
                                                   List<RetrievalChannelResult> rawResults,
                                                   List<RetrievalChannelResult> filteredResults,
                                                   List<Document> mergedCandidates,
                                                   List<Document> rerankedCandidates,
                                                   List<Document> finalDocuments) {
        // 收集所有文档级观测记录，最后统一写入 traceRecorder。
        List<RetrievalResultView> results = new ArrayList<>();
       /*
       构建finalRankMap
       方法一开始就构建了一个 finalRankMap，key 是文档 ID，value 是最终排名（从 1 开始）。这个 map 的作用是：后面遍历每个候选文档时，能快速判断它是否被选入了最终 Prompt，以及排在第几位。
        为什么用 LinkedHashMap？
        LinkedHashMap 保持插入顺序。虽然这里主要用它做 key 查找，但保持顺序有助于调试时按排名顺序遍历。
        */
        // finalRankMap 用于快速判断某个 doc 是否被选入最终 Prompt，以及它的最终排名。
        Map<String, Integer> finalRankMap = new LinkedHashMap<>();
        if (finalDocuments != null) {
            for (int i = 0; i < finalDocuments.size(); i++) {
                // docId 是跨阶段关联同一个候选块的主键。
                String docId = finalDocuments.get(i).getId();
                if (docId != null) {
                    // docId 是跨阶段关联同一个候选块的主键。
                    finalRankMap.put(docId, i + 1);
                }
            }
        }

        // 原始召回结果是文档级观测的起点；没有 rawResults 就没有可解释的候选来源。
        if (rawResults != null) {
            for (RetrievalChannelResult rawResult : rawResults) {
                // 每个候选都要保留它来自哪个通道。
                String channelName = rawResult.getChannelName();
                List<Document> rawDocs = rawResult.getDocuments();
                // 当前通道没有候选时跳过，不生成空观测记录。
                if (rawDocs == null || rawDocs.isEmpty()) {
                    continue;
                }

                // 逐个原始候选生成 RetrievalResultView，保留它在通道内的原始排名。
                for (int i = 0; i < rawDocs.size(); i++) {
                    Document doc = rawDocs.get(i);
                    RetrievalResultView view = new RetrievalResultView();
                    // exchangeId/traceId 用于把观测数据关联回本轮对话 trace。
                    view.setId(traceRecorder.exchangeId());
                    view.setTraceId(traceRecorder.traceId());
                    view.setSubQuestionIndex(subQuestionIndex);
                    view.setSubQuestion(subQuestion);
                    view.setChannelType(channelName);
                    // channelRank 表示该候选在原始通道结果中的排名，排名从 1 开始。
                    view.setChannelRank(i + 1);


                    /*遍历原始召回文档
                    接下来是双层循环：外层遍历每个通道，内层遍历该通道的每个候选文档。
                    对于每个文档，需要记录以下几类信息：原始召回分数 RRF融合分数 Rerank分数

                     */
                    // 原始召回分数通常来自向量相似度、关键词匹配分或结构通道自定义分。
                    Object scoreObj = doc.getMetadata().get(DocumentKnowledgeMetadataKeys.SCORE);
                    if (scoreObj instanceof Number) {
                        view.setOriginalScore(BigDecimal.valueOf(((Number) scoreObj).doubleValue()));
                    }
                    // RRF 分数由融合阶段写回 metadata；原始召回阶段可能尚未包含该字段。
                    Object rrfScoreObj = doc.getMetadata().get(DocumentKnowledgeMetadataKeys.RRF_SCORE);
                    if (rrfScoreObj instanceof Number) {
                        view.setRrfScore(BigDecimal.valueOf(((Number) rrfScoreObj).doubleValue()));
                    }
                    // rerankScore 由 rerank 处理器写入，用于解释二次重排后的相关性。
                    Object rerankScoreObj = doc.getMetadata().get("rerankScore");
                    if (rerankScoreObj instanceof Number) {
                        view.setRerankScore(BigDecimal.valueOf(((Number) rerankScoreObj).doubleValue()));
                    }

                    /*
                    文档来源信息提取 这些字段帮助定位证据的来源：来自哪个文档、哪个切块、哪个章节。前端调试页面可以据此展示"这条证据来自《xxx文档》第 3 个切块，章节路径是 第一章 > 1.2 配置说明"。
                     */
                    // 以下字段从 metadata 提取文档和 chunk 信息，用于前端定位证据来源。
                    Object docIdObj = doc.getMetadata().get(DocumentKnowledgeMetadataKeys.DOCUMENT_ID);
                    if (docIdObj != null) {
                        view.setDocumentId(Long.parseLong(String.valueOf(docIdObj)));
                    }
                    // 文档名称用于在 trace 或引用列表中直接展示。
                    Object docNameObj = doc.getMetadata().get(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME);
                    if (docNameObj != null) {
                        view.setDocumentName(String.valueOf(docNameObj));
                    }
                    // chunkId 是知识库切块的唯一标识。
                    Object chunkIdObj = doc.getMetadata().get(DocumentKnowledgeMetadataKeys.CHUNK_ID);
                    if (chunkIdObj != null) {
                        view.setChunkId(Long.parseLong(String.valueOf(chunkIdObj)));
                    }
                    // chunkNo 是文档内的切块序号，便于按原文顺序回溯。
                    Object chunkNoObj = doc.getMetadata().get(DocumentKnowledgeMetadataKeys.CHUNK_NO);
                    if (chunkNoObj != null) {
                        view.setChunkNo(Integer.parseInt(String.valueOf(chunkNoObj)));
                    }
                    // sectionPath 记录章节路径，帮助判断证据是否来自正确章节。
                    Object sectionPathObj = doc.getMetadata().get(DocumentKnowledgeMetadataKeys.SECTION_PATH);
                    if (sectionPathObj != null) {
                        view.setSectionPath(String.valueOf(sectionPathObj));
                    }

                    /*
                    文本预览 chunk 文本可能很长（父块提升后可能有几千字），trace 里只存前 500 字作为预览，同时记录完整字符数。这样前端既能展示内容摘要，又能知道完整长度。
                     */
                    // chunk 文本可能很长，trace 里只存前 500 字预览，同时记录完整字符数。
                    String content = doc.getText();
                    if (content != null && !content.isEmpty()) {
                        view.setChunkTextPreview(content.length() > 500 ? content.substring(0, 500) : content);
                        view.setChunkCharCount(content.length());
                    }

                    /*
                    入选/淘汰原因判定  这是整个方法最核心的逻辑——为每个候选文档标注它的"命运"和原因：
                    先判断两个布尔值：
                        passedGate：是否通过了证据闸门（在过滤后的同通道结果中能找到相同 ID 的文档）
                        isSelected：是否进入了最终 Prompt（在 finalRankMap 中能找到）
                     */
                    // 判断该候选是否通过证据闸门：同通道过滤结果中存在相同 docId 即视为通过。
                    boolean passedGate = filteredResults != null && filteredResults.stream()
                        .anyMatch(fr -> channelName.equals(fr.getChannelName()) &&
                            fr.getDocuments() != null &&
                            fr.getDocuments().stream().anyMatch(d -> Objects.equals(d.getId(), doc.getId())));
                    view.setGatePassed(passedGate);
                    // 判断该候选是否进入最终 Prompt：最终文档集合中有同一 docId 即视为选中。
                    boolean isSelected = doc.getId() != null && finalRankMap.containsKey(doc.getId());
                    view.setSelected(isSelected);
                    /*
                    然后根据这两个布尔值的组合，生成不同的 selectionReason：
                     */
                    // 入选时记录最终排名和正向原因。
                    if (isSelected) {
                        view.setFinalRank(finalRankMap.get(doc.getId()));
                        view.setSelectionReason("已选入最终 Prompt");
                    } else if (!passedGate) {
                        // 未通过闸门时，根据通道类型生成更具体的过滤原因。
                        Object scoreObj2 = doc.getMetadata().get(DocumentKnowledgeMetadataKeys.SCORE);
                        double score = scoreObj2 instanceof Number ? ((Number) scoreObj2).doubleValue() : 0.0;
                        if ("vector".equals(channelName)) {
                            // 向量通道用绝对相似度阈值解释过滤原因。
                            view.setSelectionReason(String.format(
                                "向量闸门过滤：分数 %.4f < 阈值 %.4f",
                                score, properties.getMinVectorSimilarity()
                            ));
                        } else if ("keyword".equals(channelName)) {
                            // 关键词通道用相对最高分阈值解释过滤原因。
                            view.setSelectionReason(String.format(
                                "关键词闸门过滤：分数 %.4f 低于相对阈值（floor=%.2f）",
                                score, properties.getKeywordRelativeScoreFloor()
                            ));
                        } else {
                            // 其他通道没有专用阈值文案时，保留通用过滤分数说明。
                            view.setSelectionReason("闸门过滤：分数 " + String.format("%.4f", score));
                        }
                    } else {
                        // 通过闸门但未进入最终 Prompt，通常是排序后超出 finalTopK。
                        view.setSelectionReason("超出 finalTopK 限制（topK=" + properties.getFinalTopK() + "）");
                    }
                    // 单条文档观测记录完成，加入待写入列表。
                    results.add(view);
                }
            }
        }
        // 批量写入文档级检索结果观测。
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
