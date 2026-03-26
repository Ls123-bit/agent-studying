package org.javaup.ai.chatagent.service;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import org.javaup.ai.chatagent.config.ChatAgentProperties;
import org.javaup.ai.chatagent.model.ActionResponse;
import org.javaup.ai.chatagent.model.ChatRequest;
import org.javaup.ai.chatagent.model.ConversationSessionView;
import org.javaup.ai.chatagent.model.ConversationTurnView;
import org.javaup.ai.chatagent.model.SearchReference;
import org.javaup.ai.chatagent.support.ChatContextKeys;
import org.javaup.ai.chatagent.support.SinkEmitHelper;
import org.javaup.ai.chatagent.support.StreamEventWriter;
import org.javaup.enums.ChatTurnStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
        /*
         * 先把输入规整成稳定的会话参数。
         * 这样后续无论是落库、查 checkpoint 还是做任务去重，都只面对规范化后的值。
         */
        String question = normalizeQuestion(request.getQuestion());
        String conversationId = normalizeConversationId(request.getConversationId());

        /*
         * 同一个 conversationId 在任意时刻只允许一条流式链路运行，
         * 避免多个 ReactAgent 同时写同一份会话数据。
         */
        if (chatTaskManager.hasRunningTask(conversationId)) {
            return Flux.just(streamEventWriter.error("该会话当前正在执行中，请稍后再试"));
        }

        /*
         * 先为本轮创建一条 RUNNING 状态的 turn，
         * 这样即使后面的模型调用还没结束，查询接口也能看到这一轮已经开始。
         */
        ConversationTurnView turn = conversationStore.startTurn(conversationId, question);
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        RunnableConfig runnableConfig = buildSessionConfig(conversationId);

        /*
         * 这些集合是本次执行的运行时上下文。
         * ReactAgent 本身只负责推理和工具调度，业务层需要自己准备容器来积累 thinking、引用和工具使用情况。
         */
        List<String> thinkingSteps = new ArrayList<>();
        List<SearchReference> references = new ArrayList<>();
        Set<String> usedTools = ConcurrentHashMap.newKeySet();

        /*
         * 把产品层需要的上下文挂到 RunnableConfig 里。
         * 工具执行阶段和流式输出阶段都可以从这里取到同一份对象，实现跨组件共享。
         */
        runnableConfig.context().put(ChatContextKeys.EVENT_SINK, sink);
        runnableConfig.context().put(ChatContextKeys.THINKING_STEPS, thinkingSteps);
        runnableConfig.context().put(ChatContextKeys.REFERENCES, references);
        runnableConfig.context().put(ChatContextKeys.USED_TOOLS, usedTools);

        /*
         * TaskInfo 是一次流式对话在 JVM 内的“执行现场”，
         * 用来串起订阅对象、累计答案、耗时指标和最终收尾状态。
         */
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

        /*
         * register 是真正的并发闸门。
         * 即使前面的 hasRunningTask 判断和这里之间存在极短的竞争窗口，
         * 也会由 putIfAbsent 再兜底一次，保证只有一个任务能注册成功。
         */
        if (!chatTaskManager.register(taskInfo)) {
            /*
             * 如果注册失败，说明并发任务已经占住这个会话。
             * 这里仍然要把刚刚插入的 RUNNING turn 及时改成 FAILED，避免数据库里残留脏状态。
             */
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
            /*
             * 从这里开始进入 Spring AI Alibaba ReactAgent 的内部 ReAct 循环。
             * 业务代码不再自己维护 scheduleRound，而是只订阅输出、处理收尾和异常。
             */
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

        /*
         * 把底层 sink 包装成对外返回的 Flux。
         * 当前端主动断开连接时，这里会反向触发 stopConversation，确保订阅和数据库状态一起收口。
         */
        return sink.asFlux()
            .doOnCancel(() -> stopConversation(conversationId, "客户端已取消请求"));
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

        /*
         * 只允许第一位进入 stop/finish 流程的线程执行真正的收尾。
         * 后续重复 stop 或 doOnError/doOnComplete 竞争进来时，会被这里短路掉。
         */
        if (!taskInfo.finalized().compareAndSet(false, true)) {
            return new ActionResponse(false, "会话已经结束");
        }

        try {
            /*
             * 先通知 ReactAgent 中断内部执行。
             * 即使中断失败，下面也会继续释放订阅并把当前已生成内容落库。
             */
            businessChatReactAgent.interrupt(taskInfo.runnableConfig());
        }
        catch (RuntimeException exception) {
            log.debug("中断 ReactAgent 时出现异常，继续释放资源", exception);
        }

        Disposable disposable = taskInfo.disposable();
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
        }

        /*
         * 给前端补一个停止状态事件，然后把已有内容按 STOPPED 状态收尾。
         * 这样用户中途停止后，页面和数据库看到的是同一份最终状态。
         */
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
        /*
         * 重置会话要同时清两类状态：
         * 先结束正在跑的任务，再删 ReactAgent checkpoint 和业务会话数据。
         */
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
        /*
         * ReactAgent 会吐出多种节点输出，这里只关心真正的流式正文事件。
         * 其他像工具执行、Hook 节点等元信息不直接转成最终回答文本。
         */
        if (!(output instanceof StreamingOutput<?> streamingOutput)) {
            return;
        }

        if (!StringUtils.hasText(streamingOutput.chunk())) {
            return;
        }

        /*
         * 正常情况下正文会以 AGENT_MODEL_STREAMING 持续输出。
         * 某些模型在结束时才一次性给内容，因此额外兼容 AGENT_MODEL_FINISHED 且 answerBuffer 为空的场景。
         */
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
        /*
         * 一份正文分片需要同时落到两个地方：
         * answerBuffer 用于最终持久化，sink 用于实时推送给前端。
         */
        taskInfo.answerBuffer().append(chunk);

        /*
         * 首字耗时只在第一次收到正文分片时记录一次，
         * 后续分片不再覆盖，保证统计口径稳定。
         */
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
        /*
         * 正常完成和失败/停止都会走到收尾逻辑，
         * finalized 保证只会有一个分支真正执行数据库写入和 sink 关闭。
         */
        if (!taskInfo.finalized().compareAndSet(false, true)) {
            return;
        }

        String answer = taskInfo.answerBuffer().toString();

        /*
         * 最终返回前统一整理业务增强数据：
         * 引用来源去重，推荐问题基于当前问答和最近几轮会话重新生成。
         */
        List<SearchReference> uniqueReferences = deduplicateReferences(taskInfo.references());
        List<String> recommendations = recommendationService.generateRecommendations(
            taskInfo.question(),
            answer,
            recentTurns(taskInfo.conversationId())
        );

        /*
         * reference 和 recommend 都不是模型正文的一部分，
         * 因此放在正文结束后作为独立事件补发给前端。
         */
        if (!uniqueReferences.isEmpty()) {
            safeEmit(taskInfo.sink(), streamEventWriter.references(uniqueReferences));
        }
        if (!recommendations.isEmpty()) {
            safeEmit(taskInfo.sink(), streamEventWriter.recommendations(recommendations));
        }
        safeComplete(taskInfo.sink());

        /*
         * 直到这里才真正把本轮的最终结果定稿到数据库，
         * 包括正文、thinking、引用、推荐问题和耗时指标。
         */
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
        /*
         * 和正常完成一样，失败分支也要抢收尾权，
         * 避免 doOnError、stopConversation、doOnComplete 互相重复写库。
         */
        if (!taskInfo.finalized().compareAndSet(false, true)) {
            return;
        }

        String errorMessage = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();

        /*
         * 先把错误事件推给前端，再关闭流。
         * 前端可以立刻感知失败，而数据库会保留失败前已经生成的正文和过程信息。
         */
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
        /*
         * cleanup 只负责释放 JVM 内的运行时资源，
         * 不再做任何业务判断，确保收尾路径足够简单稳定。
         */
        Disposable disposable = taskInfo.disposable();
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
        }
        chatTaskManager.remove(taskInfo.conversationId());
    }

    private List<SearchReference> deduplicateReferences(List<SearchReference> references) {
        Map<String, SearchReference> unique = new LinkedHashMap<>();

        /*
         * 这里按 url 去重，既能去掉同一工具多次返回的重复来源，
         * 也能避免不同轮次搜索把同一链接重复展示给前端。
         */
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
        /*
         * 会话详情既要读业务表，也要读 ReactAgent checkpoint。
         * 因此这里临时构造一个 threadId 相同的 RunnableConfig 来查询当前线程状态。
         */
        RunnableConfig runnableConfig = RunnableConfig.builder()
            .threadId(sessionRecord.conversationId())
            .build();

        /*
         * checkpoint 的 state 里保存着 ReactAgent 继续运行所需的消息上下文。
         * 这里主要提取 messages 数量和最近的用户/助手消息，补充给会话总览接口。
         */
        Map<String, Object> state = checkpointManager.get(runnableConfig)
            .map(Checkpoint::getState)
            .orElseGet(Map::of);
        Object messages = state.getOrDefault("messages", List.of());
        List<?> messageList = messages instanceof List<?> list ? list : List.of();

        /*
         * 最终对外返回的是“业务会话 + Agent 运行态摘要”的合并视图。
         */
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
        /*
         * threadId 是 ReactAgent 记忆恢复的关键索引。
         * 同一个 conversationId 反复调用，才能命中同一条 checkpoint 线程。
         */
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

        /*
         * 新会话没有传 conversationId 时，由服务端统一生成带前缀的线程号，
         * 方便后续排查日志、查询数据库和按业务前缀筛选会话。
         */
        return chatAgentProperties.getDefaultConversationIdPrefix() + UUID.randomUUID().toString().replace("-", "");
    }

    private void safeEmit(Sinks.Many<String> sink, String payload) {
        SinkEmitHelper.emitNext(sink, payload);
    }

    private void safeComplete(Sinks.Many<String> sink) {
        SinkEmitHelper.emitComplete(sink);
    }
}
