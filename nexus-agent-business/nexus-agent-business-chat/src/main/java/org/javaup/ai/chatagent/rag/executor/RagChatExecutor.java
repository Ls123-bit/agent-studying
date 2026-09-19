package org.javaup.ai.chatagent.rag.executor;

import cn.hutool.core.util.StrUtil;
import org.javaup.ai.chatagent.model.trace.ConversationTraceStageCode;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.model.ExecutionMode;
import org.javaup.ai.chatagent.rag.model.RagPromptAssemblyResult;
import org.javaup.ai.chatagent.rag.model.RagRetrievalContext;
import org.javaup.ai.chatagent.rag.service.RagPromptAssemblyService;
import org.javaup.ai.chatagent.rag.service.RagRetrievalEngine;
import org.javaup.ai.chatagent.rag.support.ExecutorEventSupport;
import org.javaup.ai.chatagent.service.ConversationTraceRecorder;
import org.javaup.ai.chatagent.service.ObservedChatModelService;
import org.javaup.ai.chatagent.service.TaskInfo;
import org.javaup.ai.chatagent.support.StreamEventWriter;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;




/*
RAG知识问答 模式：ExecutionMode.RETRIEVAL   知识问答执行器 改执行器负责标准的RAG路径 主流程为：
1. 根据执行计划发起检索
 * 2. 整理检索结果和引用
 * 3. 组装系统 Prompt / 用户 Prompt
 * 4. 调用模型基于证据流式生成答案

这是用的最多的执行器，走完整的“检索->组装->生产”流程
执行流程：
1.在 boundedElastic 调度器上调用 RagRetrievalEngine.retrieve(plan, traceRecorder)
2.用 RagPromptAssemblyService.assemble(plan, context) 组装系统提示词和用户提示词
3.调用 ObservedChatModelService.streamText() 流式生成回答

为什么"当前文档问答"会走到 RagChatExecutor？
把前面的信息串起来，整个分发链路就很清晰了：RagChatExecutor 的注册   RagChatExecutor 类上有 @Component 注解，并且 mode() 方法返回的是 ExecutionMode.RETRIEVAL：
Spring 启动时，ConversationExecutorRegistry 的构造函数会把 RagChatExecutor 按 RETRIEVAL 这个 key 注册到 executorMap 中。
 */
@Component
public class RagChatExecutor implements ConversationExecutor {

    private final RagRetrievalEngine ragRetrievalEngine;
    private final RagPromptAssemblyService ragPromptAssemblyService;
    private final StreamEventWriter streamEventWriter;
    private final ObservedChatModelService observedChatModelService;

    public RagChatExecutor(RagRetrievalEngine ragRetrievalEngine,
                           RagPromptAssemblyService ragPromptAssemblyService,
                           StreamEventWriter streamEventWriter,
                           ObservedChatModelService observedChatModelService) {
        this.ragRetrievalEngine = ragRetrievalEngine;
        this.ragPromptAssemblyService = ragPromptAssemblyService;
        this.streamEventWriter = streamEventWriter;
        this.observedChatModelService = observedChatModelService;
    }

    @Override
    public ExecutionMode mode() {
        return ExecutionMode.RETRIEVAL;
    }
    /**
     * 执行 RAG 知识问答主流程。
     *
     * @param taskInfo 当前任务
     * @return 模型输出的流式文本结果
     */
    @Override
    public Flux<String> execute(TaskInfo taskInfo) {//调用检索引擎 → 组装 Prompt → 流式生成回答
        ConversationExecutionPlan plan = taskInfo.executionPlan();
        // 先通知前端：当前正在进入知识检索规划阶段。 阶段一：发送thinking状态  这行代码会通过 SSE 向前端推送一个 thinking 事件，告诉用户"我正在思考"。这样用户就不会看到一片空白，体验会好很多。
        ExecutorEventSupport.publishThinking(taskInfo, streamEventWriter, "正在根据问题规划知识检索范围。");

        /*
        阶段二：启动检索阶段追踪
        这里启动了一个"阶段追踪句柄"。系统会记录这个检索阶段的开始时间、状态、输入输出等信息，最终落库到调试表中。这样后续可以在调试页面看到每个阶段的耗时和执行情况。
        为什么要追踪阶段？
        RAG 问答链路比较长，包括检索、Prompt 组装、模型回答等多个阶段。如果某次回答质量不好或者速度慢，我们需要知道是哪个阶段出了问题。阶段追踪就是为了解决这个问题——每个阶段都会记录开始时间、结束时间、输入输出、成功失败状态，方便后续排查。
         */
        ConversationTraceRecorder.StageHandle retrieveStage = taskInfo.traceRecorder() == null
            ? null
            : taskInfo.traceRecorder().startStage(ConversationTraceStageCode.RAG_RETRIEVE, mode().name(), "正在执行双通道混合检索。", null);

        /*
        阶段三：异步执行检索
        为什么要用 Mono.fromCallable？
        ragRetrievalEngine.retrieve() 是一个同步方法，它会阻塞当前线程直到检索完成。如果直接在响应线程上执行，会导致整个 SSE 流被阻塞，用户体验很差。
        Mono.fromCallable 把这个同步调用包装成一个响应式流，配合后面的 subscribeOn 可以把它调度到其他线程池执行。

        为什么要用 boundedElastic 线程池？
        boundedElastic 是 Reactor 提供的一个弹性线程池，专门用于执行阻塞 IO 操作。它的特点是：
        弹性扩容：根据任务量动态创建线程，最多可以创建 10 倍 CPU 核心数的线程
        自动回收：空闲线程会在 60 秒后自动回收
        有界队列：避免无限制创建线程导致 OOM
        检索操作涉及数据库查询、向量索引检索、HTTP 调用等阻塞 IO，非常适合用这个线程池。
         */
        // 检索通常涉及数据库、向量索引或外部 IO，因此切到弹性线程池执行，避免阻塞响应链路。
        return Mono.fromCallable(() -> ragRetrievalEngine.retrieve(plan, taskInfo.traceRecorder()))
            .subscribeOn(Schedulers.boundedElastic())
            .doOnError(error -> {//检索失败时的处理    如果检索过程中抛出异常，这里会把阶段状态标记为失败，并记录错误信息。这样在调试页面就能看到具体是哪个阶段失败了，失败原因是什么。
                if (taskInfo.traceRecorder() != null) {
                    // 检索阶段失败时，优先把阶段状态标记为失败，便于后续调试。
                    taskInfo.traceRecorder().failStage(retrieveStage, "RAG 检索失败。", error.getMessage(), null);
                }
            })
            .doOnSuccess(context -> {//检索成功时的埋点 将下面的信息记录下来  这些信息会落库到 conversation_trace_stage 表，供调试页面展示。
                if (taskInfo.traceRecorder() != null && context != null) {
                    // 检索成功后，把使用的通道、子问题、引用情况等埋点信息完整记录下来。
                    taskInfo.traceRecorder().completeStage(retrieveStage, "RAG 检索完成。", Map.of(
                        "retrievalQuestion", StrUtil.blankToDefault(context.getRetrievalQuestion(), ""),//实际用于检索的问题（可能经过改写）
                        "usedChannels", context.getUsedChannels() == null ? List.of() : context.getUsedChannels(),//使用了哪些检索通道（向量、关键词、rerank 等）
                        "retrievalNotes", context.getRetrievalNotes() == null ? List.of() : context.getRetrievalNotes(),//检索过程中的提示信息（比如"子问题1检索完成：vector=5，keyword=3，final=5"）
                        "referenceCount", context.flattenReferences().size(),//最终找到了多少条引用
                        "subQuestionCount", context.getSubQuestionEvidenceList() == null ? 0 : context.getSubQuestionEvidenceList().size(),//拆分了多少个子问题
                        "subQuestions", context.getSubQuestionEvidenceList() == null
                            ? List.of()
                            : context.getSubQuestionEvidenceList().stream().map(item -> Map.of(
                                "index", item.getSubQuestionIndex(),
                                "question", StrUtil.blankToDefault(item.getSubQuestion(), ""),
                                "referenceCount", item.getReferences() == null ? 0 : item.getReferences().size(),
                                "documentCount", item.getDocuments() == null ? 0 : item.getDocuments().size(),
                                "fusedCandidateCount", item.getFusedCandidateCount() == null ? 0 : item.getFusedCandidateCount(),
                                "parentCandidateCount", item.getParentCandidateCount() == null ? 0 : item.getParentCandidateCount(),
                                "rerankedCandidateCount", item.getRerankedCandidateCount() == null ? 0 : item.getRerankedCandidateCount(),
                                "channelTraces", item.getChannelTraces() == null
                                    ? List.of()
                                    : item.getChannelTraces().stream().map(trace -> Map.of(
                                        "channelName", StrUtil.blankToDefault(trace.getChannelName(), ""),
                                        "recalledCount", trace.getRecalledCount(),
                                        "acceptedCount", trace.getAcceptedCount()
                                    )).toList(),
                                "references", item.getReferences() == null
                                    ? List.of()
                                    : item.getReferences().stream().map(reference -> Map.of(
                                        "referenceId", StrUtil.blankToDefault(reference.getReferenceId(), ""),
                                        "documentName", StrUtil.blankToDefault(reference.getDocumentName(), reference.getTitle()),
                                        "sectionPath", StrUtil.blankToDefault(reference.getSectionPath(), ""),
                                        "channel", StrUtil.blankToDefault(reference.getChannel(), "")
                                    )).toList()
                            )).toList(),
                        "references", context.flattenReferences().stream().map(reference -> Map.of(
                            "referenceId", StrUtil.blankToDefault(reference.getReferenceId(), ""),
                            "documentName", StrUtil.blankToDefault(reference.getDocumentName(), reference.getTitle()),
                            "sectionPath", StrUtil.blankToDefault(reference.getSectionPath(), ""),
                            "channel", StrUtil.blankToDefault(reference.getChannel(), "")
                        )).toList()
                    ));
                }
            })

              /*
              检索完成后，会调用 streamFromRetrievalContext 方法继续处理。这个方法会做证据整理、Prompt 组装、模型回答等后续工作。
              flatMapMany 的作用是把 Mono<RagRetrievalContext> 转换成 Flux<String>，也就是把检索结果转换成流式文本输出。
               */
                // 检索完成后，继续后续流程  进入"证据整理 -> Prompt 组装 -> 模型回答"的后续链路。
            .flatMapMany(context -> streamFromRetrievalContext(taskInfo, plan, context));
    }

    private Flux<String> streamFromRetrievalContext(TaskInfo taskInfo,
                                                    ConversationExecutionPlan plan,
                                                    RagRetrievalContext context) {//拿到检索结果后，组装 Prompt 并调用模型

        context.getRetrievalNotes().forEach(note -> ExecutorEventSupport.publishThinking(taskInfo, streamEventWriter, note));

        taskInfo.usedTools().addAll(context.getUsedChannels());
        taskInfo.debugTrace().setRetrievalNotes(new ArrayList<>(context.getRetrievalNotes()));
        taskInfo.debugTrace().setUsedChannels(new ArrayList<>(context.getUsedChannels()));

        if (context.isEmpty()) {

            ExecutorEventSupport.publishThinking(taskInfo, streamEventWriter, "当前没有足够证据，直接返回无证据兜底回复。");
            return Flux.just(StrUtil.blankToDefault(plan.getNoEvidenceReply(), "当前没有足够证据支持明确回答。"));
        }

        taskInfo.references().addAll(context.flattenReferences());
        ExecutorEventSupport.publishThinking(taskInfo, streamEventWriter, "证据整理完成，正在基于证据生成回答。");

        ConversationTraceRecorder.StageHandle budgetStage = taskInfo.traceRecorder() == null
            ? null
            : taskInfo.traceRecorder().startStage(ConversationTraceStageCode.EVIDENCE_BUDGET, mode().name(), "正在组装证据与 Prompt 预算。", null);
        RagPromptAssemblyResult promptAssemblyResult = ragPromptAssemblyService.assemble(plan, context);
        String systemPrompt = promptAssemblyResult.getSystemPrompt();
        String userPrompt = promptAssemblyResult.getUserPrompt();
        taskInfo.debugTrace().setRagSystemPrompt(systemPrompt);
        taskInfo.debugTrace().setRagUserPrompt(userPrompt);
        if (taskInfo.traceRecorder() != null) {
            taskInfo.traceRecorder().completeStage(budgetStage, "证据预算与 Prompt 组装完成。", Map.of(
                "totalBudget", promptAssemblyResult.getTotalBudget(),
                "perSubQuestionBudget", promptAssemblyResult.getPerSubQuestionBudget(),
                "renderedReferenceCount", promptAssemblyResult.getRenderedReferenceCount(),
                "omittedReferenceCount", promptAssemblyResult.getOmittedReferenceCount(),
                "renderedReferenceDetails", promptAssemblyResult.getRenderedReferenceDetails() == null ? List.of() : promptAssemblyResult.getRenderedReferenceDetails(),
                "omittedReferenceDetails", promptAssemblyResult.getOmittedReferenceDetails() == null ? List.of() : promptAssemblyResult.getOmittedReferenceDetails(),
                "systemPrompt", StrUtil.blankToDefault(systemPrompt, ""),
                "userPrompt", StrUtil.blankToDefault(userPrompt, "")
            ));
        }

        ConversationTraceRecorder.StageHandle answerStage = taskInfo.traceRecorder() == null
            ? null
            : taskInfo.traceRecorder().startStage(ConversationTraceStageCode.ANSWER_GENERATE, mode().name(), "正在基于证据生成回答。", null);
        return observedChatModelService.streamText("rag_answer", systemPrompt, userPrompt, taskInfo.traceRecorder())
            .doOnComplete(() -> {
                if (taskInfo.traceRecorder() != null) {
                    taskInfo.traceRecorder().completeStage(answerStage, "答案生成完成。", Map.of(
                        "firstResponseTimeMs", taskInfo.firstResponseTimeMs().get(),
                        "answerLength", taskInfo.answerBuffer().length()
                    ));
                }
            })
            .doOnError(error -> {
                if (taskInfo.traceRecorder() != null) {
                    taskInfo.traceRecorder().failStage(answerStage, "答案生成失败。", error.getMessage(), null);
                }
            });
    }
}
