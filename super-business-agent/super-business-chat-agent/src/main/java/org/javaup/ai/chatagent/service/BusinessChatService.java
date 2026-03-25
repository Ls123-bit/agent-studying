package org.javaup.ai.chatagent.service;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import org.javaup.ai.chatagent.config.ChatAgentProperties;
import org.javaup.ai.chatagent.enums.ChatTurnStatus;
import org.javaup.ai.chatagent.model.ActionResponse;
import org.javaup.ai.chatagent.model.ChatRequest;
import org.javaup.ai.chatagent.model.ConversationSessionView;
import org.javaup.ai.chatagent.model.ConversationTurnView;
import org.javaup.ai.chatagent.model.SearchReference;
import org.javaup.ai.chatagent.support.ChatContextKeys;
import org.javaup.ai.chatagent.support.SinkEmitHelper;
import org.javaup.ai.chatagent.support.StreamEventWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class BusinessChatService {

    private static final Logger log = LoggerFactory.getLogger(BusinessChatService.class);

    private final ReactAgent businessChatReactAgent;
    private final ChatCheckpointManager checkpointManager;
    private final ChatAgentProperties chatAgentProperties;
    private final ConversationStore conversationStore;
    private final ChatTaskManager chatTaskManager;
    private final RecommendationService recommendationService;
    private final StreamEventWriter streamEventWriter;

    /**
     * 业务对话的总编排服务。
     *
     * <p>它不负责 ReAct 推理本身，ReAct 的轮次调度由 Spring AI Alibaba ReactAgent 完成；
     * 这里负责的是产品层编排：
     * 1. 创建/恢复会话；
     * 2. 把 ReactAgent 输出转换成前端需要的 SSE 协议；
     * 3. 记录 thinking/reference/recommend 等业务字段；
     * 4. 维护停止会话、查询会话、重置会话这些接口行为。</p>
     */
    public BusinessChatService(ReactAgent businessChatReactAgent,
                               ChatCheckpointManager checkpointManager,
                               ChatAgentProperties chatAgentProperties,
                               ConversationStore conversationStore,
                               ChatTaskManager chatTaskManager,
                               RecommendationService recommendationService,
                               StreamEventWriter streamEventWriter) {
        this.businessChatReactAgent = businessChatReactAgent;
        this.checkpointManager = checkpointManager;
        this.chatAgentProperties = chatAgentProperties;
        this.conversationStore = conversationStore;
        this.chatTaskManager = chatTaskManager;
        this.recommendationService = recommendationService;
        this.streamEventWriter = streamEventWriter;
    }

    /**
     * 发起流式对话。
     *
     * <p>主链路如下：</p>
     * <p>1. 校验参数并为本轮创建 turn。</p>
     * <p>2. 准备 SSE sink 和 RunnableConfig 上下文。</p>
     * <p>3. 调用 ReactAgent.stream(...)。</p>
     * <p>4. 把模型分片、工具思考、引用来源、推荐问题依次整理成前端事件。</p>
     * <p>5. 在结束或失败时把本轮完整结果持久化到 MySQL。</p>
     */
    public Flux<String> streamChat(ChatRequest request) {
        String question = normalizeQuestion(request.getQuestion());
        String conversationId = normalizeConversationId(request.getConversationId());
        if (chatTaskManager.hasRunningTask(conversationId)) {
            return Flux.just(streamEventWriter.error("该会话当前正在执行中，请稍后再试"));
        }

        ConversationTurnView turn = conversationStore.startTurn(conversationId, question);
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        RunnableConfig runnableConfig = buildSessionConfig(conversationId);

        List<String> thinkingSteps = new ArrayList<>();
        List<SearchReference> references = new ArrayList<>();
        Set<String> usedTools = ConcurrentHashMap.newKeySet();
        runnableConfig.context().put(ChatContextKeys.EVENT_SINK, sink);
        runnableConfig.context().put(ChatContextKeys.THINKING_STEPS, thinkingSteps);
        runnableConfig.context().put(ChatContextKeys.REFERENCES, references);
        runnableConfig.context().put(ChatContextKeys.USED_TOOLS, usedTools);

        ChatTaskManager.TaskInfo taskInfo = new ChatTaskManager.TaskInfo(
            conversationId,
            turn.getTurnId(),
            question,
            runnableConfig,
            sink,
            thinkingSteps,
            references,
            usedTools,
            System.currentTimeMillis()
        );

        if (!chatTaskManager.register(taskInfo)) {
            conversationStore.completeTurn(
                conversationId,
                turn.getTurnId(),
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                ChatTurnStatus.FAILED,
                "该会话当前正在执行中，请稍后再试",
                null,
                null
            );
            return Flux.just(streamEventWriter.error("该会话当前正在执行中，请稍后再试"));
        }

        try {
            Disposable disposable = businessChatReactAgent.stream(question, runnableConfig)
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(output -> handleStreamingOutput(taskInfo, output))
                .doOnError(error -> finishWithFailure(taskInfo, error))
                .doOnComplete(() -> finishSuccessfully(taskInfo))
                .subscribe();
            chatTaskManager.attachDisposable(conversationId, disposable);
        }
        catch (GraphRunnerException exception) {
            finishWithFailure(taskInfo, exception);
        }
        catch (RuntimeException exception) {
            finishWithFailure(taskInfo, exception);
        }

        return sink.asFlux()
            .doOnCancel(() -> stopConversation(conversationId, "客户端已取消请求"));
    }

    /**
     * 发起非流式对话。
     *
     * <p>这个接口适合后端内部直接调用或调试场景。
     * 与 streamChat 的差别只是输出方式不同：
     * ReAct、工具调用、引用聚合、会话落库这些能力保持一致。</p>
     */
    public ConversationTurnView chat(ChatRequest request) {
        String question = normalizeQuestion(request.getQuestion());
        String conversationId = normalizeConversationId(request.getConversationId());
        if (chatTaskManager.hasRunningTask(conversationId)) {
            throw new IllegalStateException("该会话当前正在执行中，请稍后再试");
        }

        ConversationTurnView turn = conversationStore.startTurn(conversationId, question);
        RunnableConfig runnableConfig = buildSessionConfig(conversationId);
        List<String> thinkingSteps = new ArrayList<>();
        List<SearchReference> references = new ArrayList<>();
        Set<String> usedTools = new LinkedHashSet<>();
        runnableConfig.context().put(ChatContextKeys.THINKING_STEPS, thinkingSteps);
        runnableConfig.context().put(ChatContextKeys.REFERENCES, references);
        runnableConfig.context().put(ChatContextKeys.USED_TOOLS, usedTools);

        long startTime = System.currentTimeMillis();
        try {
            AssistantMessage assistantMessage = businessChatReactAgent.call(question, runnableConfig);
            String answer = assistantMessage != null && StringUtils.hasText(assistantMessage.getText())
                ? assistantMessage.getText()
                : "";
            long elapsed = System.currentTimeMillis() - startTime;
            List<SearchReference> uniqueReferences = deduplicateReferences(references);
            List<String> recommendations = recommendationService.generateRecommendations(
                question,
                answer,
                recentTurns(conversationId)
            );
            conversationStore.completeTurn(
                conversationId,
                turn.getTurnId(),
                answer,
                thinkingSteps,
                uniqueReferences,
                recommendations,
                new ArrayList<>(usedTools),
                ChatTurnStatus.COMPLETED,
                "",
                elapsed,
                elapsed
            );
            return conversationStore.getSessionRecord(conversationId)
                .flatMap(record -> record.turns().stream().filter(item -> item.getTurnId() == turn.getTurnId()).findFirst())
                .orElseThrow(() -> new IllegalStateException("会话结果保存失败"));
        }
        catch (GraphRunnerException exception) {
            long elapsed = System.currentTimeMillis() - startTime;
            conversationStore.completeTurn(
                conversationId,
                turn.getTurnId(),
                "",
                thinkingSteps,
                deduplicateReferences(references),
                List.of(),
                new ArrayList<>(usedTools),
                ChatTurnStatus.FAILED,
                exception.getMessage(),
                null,
                elapsed
            );
            throw new IllegalStateException("智能对话调用失败", exception);
        }
    }

    public ActionResponse stopConversation(String conversationId) {
        return stopConversation(conversationId, "用户已停止生成");
    }

    /**
     * 主动停止当前会话。
     *
     * <p>顺序上先尝试中断 ReactAgent，再释放订阅，
     * 最后把当前已经拿到的正文、思考步骤、引用来源统一写回数据库，
     * 这样即使用户中途停止，之前已经生成的内容也不会丢失。</p>
     */
    public ActionResponse stopConversation(String conversationId, String reason) {
        Optional<ChatTaskManager.TaskInfo> taskInfoOptional = chatTaskManager.get(conversationId);
        if (taskInfoOptional.isEmpty()) {
            return new ActionResponse(false, "没有找到正在执行的会话");
        }

        ChatTaskManager.TaskInfo taskInfo = taskInfoOptional.get();
        if (!taskInfo.finalized().compareAndSet(false, true)) {
            return new ActionResponse(false, "会话已经结束");
        }

        try {
            businessChatReactAgent.interrupt(taskInfo.runnableConfig());
        }
        catch (RuntimeException exception) {
            log.debug("中断 ReactAgent 时出现异常，继续释放资源", exception);
        }

        Disposable disposable = taskInfo.disposable();
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
        }

        safeEmit(taskInfo.sink(), streamEventWriter.status("⏹ " + reason));
        safeComplete(taskInfo.sink());

        conversationStore.completeTurn(
            conversationId,
            taskInfo.turnId(),
            taskInfo.answerBuffer().toString(),
            List.copyOf(taskInfo.thinkingSteps()),
            deduplicateReferences(taskInfo.references()),
            List.of(),
            new ArrayList<>(taskInfo.usedTools()),
            ChatTurnStatus.STOPPED,
            reason,
            toNullable(taskInfo.firstResponseTimeMs().get()),
            System.currentTimeMillis() - taskInfo.startTime()
        );
        cleanup(taskInfo);
        return new ActionResponse(true, "已停止会话生成");
    }

    public ConversationSessionView getSession(String conversationId) {
        ConversationStore.SessionRecord sessionRecord = conversationStore.getSessionRecord(conversationId)
            .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + conversationId));
        return toSessionView(sessionRecord);
    }

    public List<ConversationSessionView> listSessions() {
        return conversationStore.listSessionRecords()
            .stream()
            .map(this::toSessionView)
            .toList();
    }

    public ActionResponse resetConversation(String conversationId) {
        stopConversation(conversationId, "会话被重置");
        int removed = checkpointManager.clearThread(conversationId);
        conversationStore.deleteSession(conversationId);
        return new ActionResponse(true, "会话已重置，移除 checkpoint 数量: " + removed);
    }

    /**
     * 只消费模型正文分片，不把工具/Hook 的其他节点事件直接暴露给前端。
     *
     * <p>工具阶段的“正在搜索”“搜索完成”这类提示由 TavilySearchTool 通过
     * RunnableConfig.context() 主动写入 thinking 事件，因此这里主要聚焦正文流。</p>
     */
    private void handleStreamingOutput(ChatTaskManager.TaskInfo taskInfo, NodeOutput output) {
        if (!(output instanceof StreamingOutput<?> streamingOutput)) {
            return;
        }

        if (!StringUtils.hasText(streamingOutput.chunk())) {
            return;
        }

        if (streamingOutput.getOutputType() == OutputType.AGENT_MODEL_STREAMING) {
            emitModelChunk(taskInfo, streamingOutput.chunk());
            return;
        }

        if (streamingOutput.getOutputType() == OutputType.AGENT_MODEL_FINISHED
            && taskInfo.answerBuffer().length() == 0) {
            emitModelChunk(taskInfo, streamingOutput.chunk());
        }
    }

    private void emitModelChunk(ChatTaskManager.TaskInfo taskInfo, String chunk) {
        taskInfo.answerBuffer().append(chunk);
        if (taskInfo.firstResponseTimeMs().get() == 0L) {
            taskInfo.firstResponseTimeMs().compareAndSet(0L, System.currentTimeMillis() - taskInfo.startTime());
        }
        safeEmit(taskInfo.sink(), streamEventWriter.text(chunk));
    }

    /**
     * 流式执行正常完成时的收尾逻辑。
     *
     * <p>这里不会再改 ReactAgent 的推理结果，而是只做产品层收尾：
     * 聚合引用、生成推荐问题、补发最终事件、写库并清理任务。</p>
     */
    private void finishSuccessfully(ChatTaskManager.TaskInfo taskInfo) {
        if (!taskInfo.finalized().compareAndSet(false, true)) {
            return;
        }

        String answer = taskInfo.answerBuffer().toString();
        List<SearchReference> uniqueReferences = deduplicateReferences(taskInfo.references());
        List<String> recommendations = recommendationService.generateRecommendations(
            taskInfo.question(),
            answer,
            recentTurns(taskInfo.conversationId())
        );

        if (!uniqueReferences.isEmpty()) {
            safeEmit(taskInfo.sink(), streamEventWriter.references(uniqueReferences));
        }
        if (!recommendations.isEmpty()) {
            safeEmit(taskInfo.sink(), streamEventWriter.recommendations(recommendations));
        }
        safeComplete(taskInfo.sink());

        conversationStore.completeTurn(
            taskInfo.conversationId(),
            taskInfo.turnId(),
            answer,
            List.copyOf(taskInfo.thinkingSteps()),
            uniqueReferences,
            recommendations,
            new ArrayList<>(taskInfo.usedTools()),
            ChatTurnStatus.COMPLETED,
            "",
            toNullable(taskInfo.firstResponseTimeMs().get()),
            System.currentTimeMillis() - taskInfo.startTime()
        );
        cleanup(taskInfo);
    }

    /**
     * 统一处理流式执行失败。
     *
     * <p>失败后仍然会保留已经生成的回答片段和 thinking steps，
     * 这样问题排查和前端回显都不会完全丢上下文。</p>
     */
    private void finishWithFailure(ChatTaskManager.TaskInfo taskInfo, Throwable error) {
        if (!taskInfo.finalized().compareAndSet(false, true)) {
            return;
        }

        String errorMessage = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
        safeEmit(taskInfo.sink(), streamEventWriter.error(errorMessage));
        safeComplete(taskInfo.sink());

        conversationStore.completeTurn(
            taskInfo.conversationId(),
            taskInfo.turnId(),
            taskInfo.answerBuffer().toString(),
            List.copyOf(taskInfo.thinkingSteps()),
            deduplicateReferences(taskInfo.references()),
            List.of(),
            new ArrayList<>(taskInfo.usedTools()),
            ChatTurnStatus.FAILED,
            errorMessage,
            toNullable(taskInfo.firstResponseTimeMs().get()),
            System.currentTimeMillis() - taskInfo.startTime()
        );
        cleanup(taskInfo);
    }

    private void cleanup(ChatTaskManager.TaskInfo taskInfo) {
        Disposable disposable = taskInfo.disposable();
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
        }
        chatTaskManager.remove(taskInfo.conversationId());
    }

    private List<SearchReference> deduplicateReferences(List<SearchReference> references) {
        Map<String, SearchReference> unique = new LinkedHashMap<>();
        for (SearchReference reference : references) {
            if (reference == null || !StringUtils.hasText(reference.getUrl())) {
                continue;
            }
            unique.putIfAbsent(reference.getUrl(), reference);
        }
        return new ArrayList<>(unique.values());
    }

    /**
     * 把数据库里的业务会话视图和 ReactAgent 的 checkpoint 信息合并成最终返回对象。
     *
     * <p>数据库负责存“业务上要展示的 turn 明细”，
     * checkpoint 负责存“模型继续对话所需的运行记忆”。
     * 两者合并后，接口既能返回完整的问答记录，也能返回当前线程的消息统计。</p>
     */
    private ConversationSessionView toSessionView(ConversationStore.SessionRecord sessionRecord) {
        RunnableConfig runnableConfig = RunnableConfig.builder()
            .threadId(sessionRecord.conversationId())
            .build();
        Map<String, Object> state = checkpointManager.get(runnableConfig)
            .map(Checkpoint::getState)
            .orElseGet(Map::of);
        Object messages = state.getOrDefault("messages", List.of());
        List<?> messageList = messages instanceof List<?> list ? list : List.of();

        return new ConversationSessionView(
            sessionRecord.conversationId(),
            sessionRecord.running(),
            checkpointManager.list(runnableConfig).size(),
            messageList.size(),
            latestMessage(messageList, MessageType.USER),
            latestMessage(messageList, MessageType.ASSISTANT),
            sessionRecord.createdAt(),
            sessionRecord.updatedAt(),
            sessionRecord.turns()
        );
    }

    private String latestMessage(List<?> messages, MessageType type) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            Object candidate = messages.get(index);
            if (candidate instanceof AbstractMessage message && message.getMessageType() == type) {
                return message.getText();
            }
        }
        return "";
    }

    private List<ConversationTurnView> recentTurns(String conversationId) {
        return conversationStore.getSessionRecord(conversationId)
            .map(ConversationStore.SessionRecord::turns)
            .orElseGet(List::of);
    }

    private RunnableConfig buildSessionConfig(String conversationId) {
        return RunnableConfig.builder()
            .threadId(conversationId)
            .build();
    }

    private Long toNullable(long value) {
        return value > 0 ? value : null;
    }

    private String normalizeQuestion(String question) {
        if (!StringUtils.hasText(question)) {
            throw new IllegalArgumentException("question 不能为空");
        }
        return question.trim();
    }

    private String normalizeConversationId(String conversationId) {
        if (StringUtils.hasText(conversationId)) {
            return conversationId.trim();
        }
        return chatAgentProperties.getDefaultConversationIdPrefix() + UUID.randomUUID().toString().replace("-", "");
    }

    private void safeEmit(Sinks.Many<String> sink, String payload) {
        SinkEmitHelper.emitNext(sink, payload);
    }

    private void safeComplete(Sinks.Many<String> sink) {
        SinkEmitHelper.emitComplete(sink);
    }
}
