package org.javaup.ai.chatagent.service;

import cn.hutool.core.lang.UUID;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.alibaba.fastjson.JSON;
import org.javaup.lease.RedisLeaseManager;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.config.ChatAgentProperties;
import org.javaup.ai.chatagent.model.ActionResponse;
import org.javaup.ai.chatagent.model.ChatRequest;
import org.javaup.ai.chatagent.model.ConversationSessionView;
import org.javaup.ai.chatagent.model.ConversationTurnView;
import org.javaup.ai.chatagent.model.SearchReference;
import org.javaup.ai.chatagent.support.ChatContextKeys;
import org.javaup.ai.chatagent.support.SinkEmitHelper;
import org.javaup.ai.chatagent.support.StreamEventWriter;
import org.javaup.ai.chatagent.support.TimeSensitiveQueryHelper;
import org.javaup.enums.ChatTurnStatus;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
@Slf4j
@AllArgsConstructor
@Service
public class BusinessChatService {
    
    private static final ZoneId CHAT_ZONE_ID = ZoneId.of("Asia/Shanghai");
    private static final String CHAT_RUNNING_LEASE_PREFIX = "chat:running:";
    private static final Duration CHAT_RUNNING_LEASE_TTL = Duration.ofSeconds(30);
    private static final Duration CHAT_RUNNING_LEASE_RENEW_INTERVAL = Duration.ofSeconds(10);

    private final ReactAgent businessChatReactAgent;
    private final ChatCheckpointManager checkpointManager;
    private final ChatAgentProperties chatAgentProperties;
    private final ConversationStore conversationStore;
    private final ChatRuntimeRegistry chatRuntimeRegistry;
    private final RecommendationService recommendationService;
    private final StreamEventWriter streamEventWriter;
    private final RedisLeaseManager redisLeaseManager;
    

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
        log.info("======request内容：{}", JSON.toJSONString(request));
        String question = normalizeQuestion(request.getQuestion());
        String conversationId = normalizeConversationId(request.getConversationId());
        /*
         * Redis 租约是这条链路里唯一的“集群互斥真相来源”。
         *
         * 同一个 conversationId 在多实例部署下，
         * 任意时刻只能有一个实例持有执行资格。
         * 
         * leaseKey 代表“这个会话的集群级执行资格”，
         * leaseOwnerToken 代表“这一次具体流式任务是谁”。
         *
         * cleanup(...) 和续租都依赖 ownerToken 校验当前持有者身份。
         */
        String leaseKey = buildChatLeaseKey(conversationId);
        String leaseOwnerToken = java.util.UUID.randomUUID().toString();
        if (!redisLeaseManager.acquire(leaseKey, leaseOwnerToken, CHAT_RUNNING_LEASE_TTL)) {
            return Flux.just(streamEventWriter.error("该会话当前正在执行中，请稍后再试"));
        }

        String agentQuestion;
        ConversationTurnView turn = null;
        Sinks.Many<String> sink;
        RunnableConfig runnableConfig;
        TaskInfo taskInfo;

        try {
            LocalDate currentDate = LocalDate.now(CHAT_ZONE_ID);
            String currentDateText = formatCurrentDate(currentDate);
            boolean requiresCurrentDateAnchoring = TimeSensitiveQueryHelper.requiresCurrentDateAnchoring(question);
            boolean requiresFreshSearch = TimeSensitiveQueryHelper.requiresFreshSearch(question);
            agentQuestion = buildAgentQuestion(
                question,
                currentDateText,
                requiresCurrentDateAnchoring,
                requiresFreshSearch
            );

            /*
             * 先为本轮创建一条 RUNNING 状态的 turn，
             * 这样即使后面的模型调用还没结束，查询接口也能看到这一轮已经开始。
             */
            turn = conversationStore.startTurn(conversationId, question);
            sink = Sinks.many().unicast().onBackpressureBuffer();
            runnableConfig = buildSessionConfig(conversationId);

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
             * 把当前用户问题也放进运行时上下文。
             * 这样如果模型后续产出了空的 tavily_search arguments，
             * 工具拦截器还能回退到这句原始问题，自动补成 {"query":"..."}。
             */
            runnableConfig.context().put(ChatContextKeys.QUESTION, question);
            /*
             * 再额外放一份“当前绝对日期”，统一服务于所有相对时间问题，例如：
             * - 今天北京限号
             * - 当前美元汇率
             * - 最新黄金价格
             * - 本周票房
             *
             * 工具层会用这份日期把搜索 query 改写成带绝对日期的形式，
             * 主模型侧也会收到同样的日期提示，避免把“今天/最新”回答成旧日期。
             */
            runnableConfig.context().put(ChatContextKeys.CURRENT_DATE, currentDate.toString());
            runnableConfig.context().put(ChatContextKeys.CURRENT_DATE_TEXT, currentDateText);

            /*
             * TaskInfo 是一次流式对话在 JVM 内的“执行现场”，
             * 用来串起订阅对象、累计答案、耗时指标和最终收尾状态。
             */
            taskInfo = new TaskInfo(
                conversationId,
                turn.getTurnId(),
                question,
                runnableConfig,
                sink,
                leaseKey,
                leaseOwnerToken,
                thinkingSteps,
                references,
                usedTools,
                System.currentTimeMillis()
            );

            /*
             * 注册到本机运行态注册表。
             *
             * stopConversation(...)、cleanup(...) 和流式回调
             * 都通过 conversationId 找到同一份本机运行态。
             */
            if (!chatRuntimeRegistry.register(taskInfo)) {
                /*
                 * 如果本机登记失败，把刚刚插入的 RUNNING turn 及时改成 FAILED，
                 * 避免数据库里残留脏状态。
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
                releaseLeaseQuietly(leaseKey, leaseOwnerToken);
                return Flux.just(streamEventWriter.error("该会话当前正在执行中，请稍后再试"));
            }
        } catch (RuntimeException exception) {
            releaseLeaseQuietly(leaseKey, leaseOwnerToken);
            if (turn != null) {
                conversationStore.completeTurn(
                    conversationId,
                    turn.getTurnId(),
                    "",
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    ChatTurnStatus.FAILED,
                    buildErrorMessage(exception),
                    null,
                    null
                );
            }
            return Flux.just(streamEventWriter.error(buildErrorMessage(exception)));
        }

        try {
            /*
             * 这一整条 Reactor 链可以按“启动 -> 过程中不断收到事件 -> 最终结束”来理解。
             *
             * businessChatReactAgent.stream(question, runnableConfig) 返回的是 Flux<NodeOutput>：
             * - subscribe() 之前：只是把流程定义好，还没有真正开始执行；
             * - subscribe() 之后：ReactAgent 才会真正开始跑内部 ReAct 循环。
             *
             * 运行时序可以记成下面 4 句：
             * 1. 模型/工具/图执行过程中，每产出一条新的输出事件，就会触发一次 doOnNext(...)；
             * 2. 如果整个 Flux 中途抛异常，就触发一次 doOnError(...)；
             * 3. 如果整个 Flux 正常结束，就触发一次 doOnComplete(...)；
             * 4. doOnError 和 doOnComplete 二选一，不会同时发生。
             *
             * 用一个具体例子理解：
             * 用户问“帮我查一下杭州今天的天气”。
             * - 如果模型判断这个问题需要联网搜索，它会先在框架内部产出 tool call；
             * - 框架内部随后执行 TavilySearchTool.search(...)；
             * - 工具返回结果后，框架会再次把工具结果喂回模型，继续下一轮；
             * - 直到某一轮模型不再产出 tool call，而是开始产出正文分片，
             *   这些正文分片才会经由 doOnNext(...) -> handleStreamingOutput(...) 发给前端。
             *
             * 所以：
             * - “是否调工具、调完工具后是否继续下一轮” 这部分是 ReactAgent 框架内部完成的；
             * - 当前业务代码只负责消费框架吐出来的结果，并把其中有意义的内容发给前端。
             */
            Disposable disposable = businessChatReactAgent.stream(agentQuestion, runnableConfig)
                /*
                 * publishOn(boundedElastic) 的作用是把后面的回调切到更适合阻塞/收尾工作的线程池，
                 * 避免流式输出线程被数据库写入、日志、推荐问题生成这些操作拖住。
                 */
                .publishOn(Schedulers.boundedElastic())
                /*
                 * doOnNext：每收到一条 NodeOutput 就进一次。
                 * 注意这里是“每条输出事件一次”，不是“每轮对话一次”。
                 * 一个完整问题可能会经历很多次 doOnNext：
                 * - 模型正文第 1 段
                 * - 模型正文第 2 段
                 * - ...
                 */
                .doOnNext(output -> handleStreamingOutput(taskInfo, output))
                /*
                 * doOnError：整条流只要有一次未处理异常，就会走这里一次。
                 * 典型场景：
                 * - 工具调用 HTTP 失败
                 * - 模型网关异常
                 * - Graph Runtime 内部执行失败
                 */
                .doOnError(error -> finishWithFailure(taskInfo, error))
                /*
                 * doOnComplete：只有当整条流没有异常、并且 ReactAgent 认为整次执行已经结束时才会触发。
                 * 对当前业务来说，走到这里就意味着：
                 * - 模型已经不再继续下一轮
                 * - 不会再有更多正文分片
                 * - 可以开始做 reference/recommend/写库这些最终收尾动作了
                 */
                .doOnComplete(() -> finishSuccessfully(taskInfo))
                /*
                 * subscribe() 是真正的启动点。
                 * 没有这一步，上面整条链只是在“声明流程”，并不会真正执行。
                 *
                 * 返回值 Disposable 可以理解成“这次流式执行的遥控器”：
                 * 后面 stopConversation(...) 就是通过 dispose()/interrupt() 来中断它。
                 */
                .subscribe();

            /*
             * TaskInfo 已经提前注册进本机运行态注册表，
             * 所以这里直接回填同一个对象上的 Disposable 即可。
             *
             * 后续 stopConversation(...) 会先从 chatRuntimeRegistry 里按 conversationId
             * 找到这份 TaskInfo，再通过这里挂进去的 Disposable 主动中断流式订阅。
             */
            taskInfo.setDisposable(disposable);
            taskInfo.setLeaseRenewalDisposable(startLeaseRenewal(taskInfo));
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
        Optional<TaskInfo> taskInfoOptional = chatRuntimeRegistry.get(conversationId);
        if (taskInfoOptional.isEmpty()) {
            return new ActionResponse(false, "没有找到正在执行的会话");
        }

        TaskInfo taskInfo = taskInfoOptional.get();

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
     * 处理 ReactAgent 流式运行过程中推出来的单条节点输出。
     *
     * <p>这里最容易混淆的概念是 `NodeOutput`。</p>
     *
     * <p>`NodeOutput` 不是“整轮对话结果”，也不是“最终答案对象”，
     * 它更像是 Graph Runtime 在运行过程中吐出来的一条事件记录。</p>
     *
     * <p>因为 ReactAgent 底层不是简单调用一次模型，而是在执行一张图：
     * 模型节点、工具节点、Hook 节点、Graph 节点都可能产生输出。
     * 只要图里某个节点有新的输出事件，`businessChatReactAgent.stream(...)`
     * 这条 Flux 就会推出来一个 `NodeOutput`。</p>
     *
     * <p>所以这里的 `handleStreamingOutput(...)` 本质上是在做“事件过滤”：</p>
     * <p>1. 先判断这条 `NodeOutput` 是不是带流式内容语义的 `StreamingOutput`；</p>
     * <p>2. 再尝试从里面提取可展示的正文文本；</p>
     * <p>3. 最后只把真正代表模型正文的输出转换成前端 `text` 事件。</p>
     *
     * <p>也就是说，不是所有 `NodeOutput` 都应该直接展示给前端。
     * 有些只是工具节点、Hook 节点或图运行过程中的内部输出，
     * 当前业务代码只关心其中“模型正文分片”这一类。</p>
     *
     * 只消费模型正文分片，不把工具/Hook 的其他节点事件直接暴露给前端。
     *
     * <p>工具阶段的“正在搜索”“搜索完成”这类提示由 TavilySearchTool 通过
     * RunnableConfig.context() 主动写入 thinking 事件，因此这里主要聚焦正文流。</p>
     *
     * <p>这个方法在整体链路里的位置可以这样理解：</p>
     * <p>1. `streamChat(...)` 订阅 `businessChatReactAgent.stream(...)` 后，
     * ReactAgent 每产出一个 `NodeOutput`，都会进入这里。</p>
     * <p>2. 但 ReactAgent 的输出不只有“最终回答正文”，还可能包含工具节点、Hook 节点、
     * 结束节点等其他运行时产物，所以这里首先要做过滤。</p>
     * <p>3. 只有真正代表模型正文增量的输出，才会继续交给 `emitModelChunk(...)`
     * 写入 answerBuffer 并通过 SSE 发给前端。</p>
     *
     * <p>因此，这个方法不是 ReAct 调度器，它不负责决定“下一轮要不要继续”，
     * 只负责把框架已经调度好的结果，挑出正文部分转成前端能消费的流式文本。</p>
     */
    private void handleStreamingOutput(TaskInfo taskInfo, NodeOutput output) {
        /*
         * 第一步先过滤输出类型。
         * ReactAgent 底层基于 Graph Runtime 运行，一个 NodeOutput 既可能来自模型节点，
         * 也可能来自工具节点、图执行节点或者其他内部节点。
         * 只有 StreamingOutput 才有“当前这一小段流式内容”的语义。
         *
         * 可以把 NodeOutput 想成“框架吐出来的一条运行事件”。
         * 例如：
         * - 模型开始流式输出一段正文
         * - 某个节点结束
         * - 工具节点输出了某些内容
         *
         * 而当前业务前端真正想展示的是“回答正文”，
         * 所以这里先把不属于流式文本语义的事件全部过滤掉。
         */
        if (!(output instanceof StreamingOutput<?> streamingOutput)) {
            return;
        }

        /*
         * 第二步把这次 StreamingOutput 里的正文文本抽出来。
         *
         * 这里要特别注意：
         * 1. 这个方法只做“取文本”，不做业务判断；
         * 2. 如果当前输出本身没有可展示的正文，例如只是工具相关元信息，
         *    extractStreamingText(...) 会返回空字符串；
         * 3. 返回空字符串时直接跳过，不会往前端发无意义事件。
         *
         * 例如：
         * - 如果当前事件只是“工具阶段结束”，通常这里拿不到正文；
         * - 如果当前事件携带的是模型真正生成的回答文本，这里才能提取出 content。
         */
        String content = extractStreamingText(streamingOutput);
        if (!StringUtils.hasText(content)) {
            return;
        }

        /*
         * 第三步根据 OutputType 决定“这段正文现在要不要立刻下发”。
         *
         * 这里最容易混淆的点是：不是所有带文本的 StreamingOutput 都应该直接当正文分片输出。
         *
         * 当前逻辑只接两种情况：
         * 1. AGENT_MODEL_STREAMING
         *    这是最标准的流式正文分片，模型边生成边吐文本，每来一段就立刻发给前端。
         *    例如模型依次吐出：
         *    “杭州今天”
         *    “多云转小雨”
         *    “，最高温 28 度”
         *    那每一段都会进入这里并立即发给前端。
         * 2. AGENT_MODEL_FINISHED 且 answerBuffer 仍为空
         *    这是兼容某些模型或某些底层实现“前面没有持续流式分片，只在结束时一次性返回整段文本”的场景。
         *    例如某些模型前面一个 chunk 都没吐，结束时才一次性返回
         *    “杭州今天多云转小雨，最高温 28 度”，
         *    那就会走这个分支补发整段正文。
         *
         * 为什么 finished 分支要额外判断 answerBuffer 为空：
         * - 如果前面已经走过 streaming 分支，正文其实早就发过了；
         * - 这时 finished 再带一份完整文本，很可能只是结束态的重复内容；
         * - 如果不判断，就会把整段答案重复发给前端一遍。
         */
        if (streamingOutput.getOutputType() == OutputType.AGENT_MODEL_STREAMING) {
            emitModelChunk(taskInfo, content);
            return;
        }

        if (streamingOutput.getOutputType() == OutputType.AGENT_MODEL_FINISHED
            && taskInfo.answerBuffer().isEmpty()) {
            emitModelChunk(taskInfo, content);
        }
    }

    private String extractStreamingText(StreamingOutput<?> streamingOutput) {
        /*
         * 这个辅助方法只负责一件事：把 StreamingOutput 尽可能稳定地转成字符串正文。
         *
         * 读取顺序是有意设计的：
         * 1. 先读 message()
         *    这是当前框架推荐的读取方式，也是最符合“聊天消息”语义的一层。
         * 2. 再读 originData 里是不是 Message
         *    有些输出会把底层原始对象放在 originData 中，这里做一层兼容。
         * 3. 最后兜底 originData 是不是 String
         *    用来兼容某些更原始的流式输出形态。
         *
         * 如果三层都读不到文本，就返回空字符串，让上层安全跳过。
         */
        Message message = streamingOutput.message();
        if (message != null && StringUtils.hasText(message.getText())) {
            return message.getText();
        }

        Object originData = streamingOutput.getOriginData();

        /*
         * 第二优先级：originData 本身也是 Message。
         * 这说明框架把更原始的消息对象挂到了 originData，而不是直接放在 message() 上。
         */
        if (originData instanceof Message originMessage && StringUtils.hasText(originMessage.getText())) {
            return originMessage.getText();
        }

        /*
         * 第三优先级：originData 已经是字符串。
         * 这种场景语义最弱，所以放在最后兜底。
         */
        if (originData instanceof String text && StringUtils.hasText(text)) {
            return text;
        }
        return "";
    }

    private void emitModelChunk(TaskInfo taskInfo, String chunk) {
        /*
         * 这个方法是“正文分片的统一出口”。
         * 只要某段文本已经被判定为应该展示给前端，就一定会走到这里。
         *
         * 这里同时做三件事：
         * 1. 把 chunk 追加到 answerBuffer，保证后面落库时能拿到完整答案；
         * 2. 如果这是第一段正文，顺手记录首字耗时；
         * 3. 把这段 chunk 立刻通过 SSE 发给前端。
         */
        taskInfo.answerBuffer().append(chunk);

        /*
         * 首字耗时只应该记录一次。
         * 因为我们要统计的是“从请求开始到前端第一次看到正文”的时间，
         * 而不是最后一次分片到来的时间。
         */
        if (taskInfo.firstResponseTimeMs().get() == 0L) {
            taskInfo.firstResponseTimeMs().compareAndSet(0L, System.currentTimeMillis() - taskInfo.startTime());
        }

        /*
         * 到这里才真正把这一小段正文推给前端。
         * streamEventWriter.text(chunk) 会把这段正文包装成类似下面这样的 JSON 字符串：
         * {"type":"text","content":"杭州今天多云","timestamp":"..."}
         *
         * safeEmit(...) 再把这条 JSON 丢进 sink。
         * 而 streamChat(...) 最终 return 的其实就是 sink.asFlux()，
         * 所以前端才能实时收到这条 text 事件。
         */
        safeEmit(taskInfo.sink(), streamEventWriter.text(chunk));
    }

    /**
     * 流式执行正常完成时的收尾逻辑。
     *
     * <p>这里不会再改 ReactAgent 的推理结果，而是只做产品层收尾：
     * 聚合引用、生成推荐问题、补发最终事件、写库并清理任务。</p>
     */
    private void finishSuccessfully(TaskInfo taskInfo) {
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
     *
     * <p>这个方法会在两类场景下进入：</p>
     * <p>1. `businessChatReactAgent.stream(...)` 订阅之后，流式过程中抛出异常，
     *    通过 `doOnError(...)` 进入这里。</p>
     * <p>2. `streamChat(...)` 在刚启动流式调用时就直接抛出异常，
     *    通过外层 `catch` 进入这里。</p>
     *
     * <p>因此，它不是单纯“打印个错误日志”，而是整条失败收尾链路的统一出口。</p>
     */
    private void finishWithFailure(TaskInfo taskInfo, Throwable error) {
        /*
         * 第一步先抢“最终收尾权”。
         *
         * 因为一条流式链路里可能同时存在多个结束入口：
         * - doOnError
         * - stopConversation
         * - doOnComplete
         *
         * 如果不先用 finalized 抢锁，同一轮对话就可能被重复发错误、重复写库、重复 cleanup。
         */
        if (!taskInfo.finalized().compareAndSet(false, true)) {
            return;
        }

        /*
         * 第二步把异常对象转换成更适合记录和回传的错误文本。
         * 这里不会直接把 Throwable 原样丢给前端，而是统一提炼成字符串消息。
         */
        String errorMessage = buildErrorMessage(error);

        /*
         * 第三步先记完整日志。
         * 日志里带上 conversationId 和 turnId，是为了后续能从数据库记录和运行日志互相对上。
         */
        log.error("会话执行失败, conversationId={}, turnId={}, error={}",
            taskInfo.conversationId(),
            taskInfo.turnId(),
            errorMessage,
            error);

        /*
         * 第四步先把错误事件推给前端，再关闭流。
         *
         * 顺序不能反过来：
         * - 如果先 close，再 emit error，前端可能根本收不到错误提示；
         * - 先 emit error，再 complete，前端才能稳定感知“这次失败是怎么失败的”。
         *
         * 这两句配合起来的实际效果是：
         * 1. streamEventWriter.error(errorMessage) 先把错误包装成 JSON，
         *    例如 {"type":"error","content":"400 from POST ...","timestamp":"..."}
         * 2. safeEmit(...) 把这条错误事件送进 sink，前端立刻能收到
         * 3. safeComplete(...) 再告诉 sink：这条 SSE 流已经结束，后面不会再有任何事件了
         */
        safeEmit(taskInfo.sink(), streamEventWriter.error(errorMessage));
        safeComplete(taskInfo.sink());

        /*
         * 第五步把“失败时已经生成出来的现场”完整落库。
         *
         * 这里保存的不是空数据，而是尽量保留失败前已经拿到的一切：
         * - answerBuffer：失败前已经生成出来的正文片段
         * - thinkingSteps：过程提示
         * - references：已经搜到的引用来源
         * - usedTools：本轮已经调用过的工具
         *
         * 这样即使失败了，会话详情也仍然是可排查、可回显的。
         */
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

        /*
         * 最后一步才释放 JVM 内存态资源。
         * 到这一步以后，这条流式任务在运行时层面就算彻底结束了。
         */
        cleanup(taskInfo);
    }

    private String buildErrorMessage(Throwable error) {
        /*
         * 这个方法的目标不是“拿最原始的异常文本”，
         * 而是“尽量提炼出对排查最有帮助的错误信息”。
         *
         * 之所以要沿着 cause 链往下找，是因为真正有用的信息经常包在最里面那层异常里，
         * 尤其是 HTTP 调用失败时，外层异常往往只有一层统一包装。
         */
        Throwable current = error;
        while (current != null) {
            /*
             * 如果底层是 WebClientResponseException，说明这是一次明确的 HTTP 调用失败。
             * 这种情况下最有价值的信息不是通用异常名，而是：
             * - 状态码
             * - 请求方法
             * - 请求地址
             * - 响应体
             */
            if (current instanceof WebClientResponseException responseException) {
                String responseBody = responseException.getResponseBodyAsString();
                if (StringUtils.hasText(responseBody)) {
                    return responseException.getStatusCode()
                        + " from "
                        + responseException.getRequest().getMethod()
                        + " "
                        + responseException.getRequest().getURI()
                        + " | responseBody="
                        + responseBody;
                }

                /*
                 * 有些服务端不会返回可读 response body，
                 * 这时至少保留框架拼好的异常消息。
                 */
                return responseException.getMessage();
            }
            current = current.getCause();
        }

        /*
         * 如果整条 cause 链里都没有更具体的 HTTP 异常，
         * 就退回到最基础的 message / class name。
         */
        return error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
    }

    private void cleanup(TaskInfo taskInfo) {
        /*
         * cleanup 故意保持得很“傻瓜化”，只做资源释放，不做业务判断。
         *
         * 原因是 finishSuccessfully / finishWithFailure / stopConversation
         * 这几个入口已经在前面做完了状态判定、事件发送和数据库落库。
         * cleanup 这里越简单，收尾阶段越不容易因为分支过多而出错。
         */
        Disposable disposable = taskInfo.disposable();
        Disposable leaseRenewalDisposable = taskInfo.leaseRenewalDisposable();

        if (leaseRenewalDisposable != null && !leaseRenewalDisposable.isDisposed()) {
            leaseRenewalDisposable.dispose();
        }

        /*
         * 如果订阅还活着，就主动 dispose。
         * 这相当于告诉 Reactor：这条流式链路到这里彻底结束，不要再继续往下推事件了。
         */
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
        }

        /*
         * 最后释放 Redis 租约。
         *
         * 这里不是简单 DEL，而是通过 ownerToken 做 compare-and-delete：
         * 只有这条任务仍然是当前 owner 时，才会真正删除租约 key。
         */
        releaseLeaseQuietly(taskInfo.leaseKey(), taskInfo.leaseOwnerToken());

        /*
         * 最后把这条任务从本机运行态注册表移除。
         * 移除之后，同一个 conversationId 才允许下一次重新发起对话。
         */
        chatRuntimeRegistry.remove(taskInfo.conversationId());
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

    private Disposable startLeaseRenewal(TaskInfo taskInfo) {
        /*
         * 流式回答可能持续几十秒甚至更久，所以租约不能只在开始时写一次 TTL。
         *
         * 这里用一个本机定时 Flux 周期续租：
         * - 续租成功：说明当前实例仍然持有执行资格；
         * - 续租失败：说明租约已经丢失，此时必须尽快停止本地流，避免继续输出或写库。
         */
        return Flux.interval(CHAT_RUNNING_LEASE_RENEW_INTERVAL, CHAT_RUNNING_LEASE_RENEW_INTERVAL)
            .subscribe(ignored -> renewLeaseOrStop(taskInfo), error ->
                log.warn("租约续期任务出现异常, conversationId={}, turnId={}",
                    taskInfo.conversationId(),
                    taskInfo.turnId(),
                    error)
            );
    }

    private void renewLeaseOrStop(TaskInfo taskInfo) {
        /*
         * renew 返回 false 有两种常见含义：
         * 1. key 已经过期；
         * 2. key 还在，但 ownerToken 已经不是自己，说明租约被别人接管了。
         *
         * 这两种场景都意味着当前实例已经失去继续执行这条会话的资格，
         * 所以这里必须主动 stop，而不是继续向下跑。
         */
        boolean renewed = redisLeaseManager.renew(
            taskInfo.leaseKey(),
            taskInfo.leaseOwnerToken(),
            CHAT_RUNNING_LEASE_TTL
        );
        if (renewed) {
            return;
        }

        log.warn("会话租约续期失败，准备停止当前会话, conversationId={}, turnId={}",
            taskInfo.conversationId(),
            taskInfo.turnId());
        Disposable leaseRenewalDisposable = taskInfo.leaseRenewalDisposable();
        if (leaseRenewalDisposable != null && !leaseRenewalDisposable.isDisposed()) {
            leaseRenewalDisposable.dispose();
        }
        stopConversation(taskInfo.conversationId(), "会话租约已失效，已停止生成");
    }

    private void releaseLeaseQuietly(String leaseKey, String leaseOwnerToken) {
        try {
            redisLeaseManager.release(leaseKey, leaseOwnerToken);
        }
        catch (RuntimeException exception) {
            log.warn("释放会话租约时出现异常, leaseKey={}", leaseKey, exception);
        }
    }

    private String buildChatLeaseKey(String conversationId) {
        /*
         * 所有会话租约统一放在 chat:running:* 命名空间下，
         * 便于排查 Redis 时按业务前缀筛选。
         */
        return CHAT_RUNNING_LEASE_PREFIX + conversationId;
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

    private String buildAgentQuestion(String question,
                                      String currentDateText,
                                      boolean requiresCurrentDateAnchoring,
                                      boolean requiresFreshSearch) {
        /*
         * 这里不直接改写前端展示和数据库里保存的原始 question，
         * 而是只给 Agent 追加一段运行时上下文。
         *
         * 这样用户看到的仍然是自己原始输入的“查一下北京的天气”，
         * 但模型在推理时会额外收到“今天是 2026-03-28（星期六）”这样的绝对日期锚点。
         *
         * 这里分两层提示：
         * 1. requiresCurrentDateAnchoring
         *    说明问题里存在“今天/现在/本周/本月/今年”这类相对时间语义，
         *    或者主题本身就是强时效事实，需要始终以当前日期为准来解释问题。
         * 2. requiresFreshSearch
         *    说明问题不只是要理解“今天”，还需要联网核实最新事实，
         *    例如天气、汇率、股价、限号、新闻、票房等。
         */
        StringBuilder builder = new StringBuilder();
        builder.append("系统时间信息：\n");
        builder.append("当前日期是 ").append(currentDateText).append("，时区为 Asia/Shanghai。\n");

        if (requiresCurrentDateAnchoring) {
            builder.append("当前问题包含相对时间或强时效语义。");
            builder.append("当用户提到“今天、明天、昨天、现在、当前、最新、本周、本月、今年”等表达时，");
            builder.append("必须以这个日期为准，不要把搜索结果里的旧日期误当成今天。\n");
        }
        else {
            builder.append("当用户提到“今天、明天、昨天、现在、当前、最新”等相对时间时，必须以这个日期为准。\n");
        }

        if (requiresFreshSearch) {
            builder.append("当前问题需要核实最新外部事实，回答前必须优先调用联网搜索工具。\n");
            builder.append("如果搜索结果里的日期与当前日期不一致，必须明确说明来源日期，不要把旧日期说成今天。\n");
            builder.append("如果无法找到与当前日期匹配的可靠结果，要明确说明不确定性，不要编造最新信息。\n");
        }

        builder.append("\n用户问题：\n");
        builder.append(question);
        return builder.toString();
    }

    private String formatCurrentDate(LocalDate currentDate) {
        return currentDate + "（" + chineseWeekday(currentDate.getDayOfWeek()) + "）";
    }

    private String chineseWeekday(DayOfWeek dayOfWeek) {
        return switch (dayOfWeek) {
            case MONDAY -> "星期一";
            case TUESDAY -> "星期二";
            case WEDNESDAY -> "星期三";
            case THURSDAY -> "星期四";
            case FRIDAY -> "星期五";
            case SATURDAY -> "星期六";
            case SUNDAY -> "星期日";
        };
    }

    private void safeEmit(Sinks.Many<String> sink, String payload) {
        /*
         * 这只是业务层的薄封装。
         * 它真正做的事情是：把一条已经序列化好的 JSON 事件写进 sink。
         * 只要 sink 还没结束，前端就能从返回的 Flux<String> 里收到这条事件。
         */
        SinkEmitHelper.emitNext(sink, payload);
    }

    private void safeComplete(Sinks.Many<String> sink) {
        /*
         * 这一步不是“发送一个 complete 文本”，而是结束 Reactor 流本身。
         * 一旦调用成功，前端的 SSE 连接在业务语义上就算收尾完成了。
         */
        SinkEmitHelper.emitComplete(sink);
    }
}
