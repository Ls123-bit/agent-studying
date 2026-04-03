package org.javaup.ai.chatagent.service;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.StrUtil;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.alibaba.fastjson.JSON;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.config.ChatAgentProperties;
import org.javaup.ai.chatagent.dto.ChatRequestDto;
import org.javaup.ai.chatagent.model.ConversationExchangeView;
import org.javaup.ai.chatagent.model.ConversationSessionView;
import org.javaup.ai.chatagent.model.SearchReference;
import org.javaup.ai.chatagent.model.debug.ChatDebugTrace;
import org.javaup.ai.chatagent.rag.executor.ConversationExecutor;
import org.javaup.ai.chatagent.rag.executor.ConversationExecutorRegistry;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.service.ChatPreparationOrchestrator;
import org.javaup.ai.chatagent.support.ChatContextKeys;
import org.javaup.ai.chatagent.support.SinkEmitHelper;
import org.javaup.ai.chatagent.support.StreamEventWriter;
import org.javaup.ai.chatagent.vo.ConversationResetVo;
import org.javaup.ai.chatagent.vo.ConversationSessionListVo;
import org.javaup.ai.chatagent.vo.ConversationStopVo;
import org.javaup.enums.ChatTurnStatus;
import org.javaup.lease.RedisLeaseManager;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.DayOfWeek;
import java.time.Duration;
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
    private final ConversationArchiveStore conversationArchiveStore;
    private final ChatRuntimeRegistry chatRuntimeRegistry;
    private final RecommendationService recommendationService;
    private final StreamEventWriter streamEventWriter;
    private final RedisLeaseManager redisLeaseManager;
    private final ChatPreparationOrchestrator chatPreparationOrchestrator;
    private final ConversationExecutorRegistry conversationExecutorRegistry;
    

    /**
     * 发起流式对话。
     *
     * <p>这里不再采用“进方法就立刻 subscribe”那种线性写法，
     * 而是拆成三段：</p>
     * <p>1. 规划启动参数和集群租约。</p>
     * <p>2. 预创建业务 turn 与 JVM 运行态。</p>
     * <p>3. 等前端真正订阅时，再把 Agent 执行桥接到 SSE 通道。</p>
     */
    public Flux<String> openConversationStream(ChatRequestDto request) {
        /*
         * 用 Flux.defer 的关键点是“把真正的启动动作延迟到订阅时”。
         *
         * 如果这里直接在方法体里创建 turn、抢租约、启动 agent，
         * 那控制器一返回就已经把后端资源占上了；
         * 而 defer 会把这些动作推迟到 WebFlux 真正建立 SSE 订阅时再执行，
         * 生命周期会更贴近真实客户端连接。
         */
        /*
         * 这里不是“为了写法优雅”才用 defer，而是为了确保：
         * 1. 每次新的 SSE 订阅都会独立走一轮启动流程
         * 2. 没有订阅时，不会提前创建 turn、抢租约、注册运行态
         */
        return Flux.defer(() -> openDeferredConversationStream(request));
    }

    private Flux<String> openDeferredConversationStream(ChatRequestDto request) {
        // 先把原始请求打日志，方便后面按 conversationId / question 排查一次对话的启动现场。
        log.info("======request内容：{}", JSON.toJSONString(request));
        /*
         * launchPlan 是本次对话启动前的“静态蓝图”：
         * 里面只放启动阶段就能确定的信息，例如 question、conversationId、租约信息、日期锚点等。
         *
         * 这样做的目的，是把“参数规划”和“运行态构建”拆开，
         * 让主流程不再像以前那样所有东西都堆在一个大方法里线性推进。
         */
        StreamLaunchPlan launchPlan = buildLaunchPlan(request);
        /*
         * 先抢租约再做任何持久化动作，是为了避免：
         * 1. 后面已经知道这轮不能执行
         * 2. 却仍然落了一条新的业务轮次
         *
         * 当前顺序保证了“没有执行资格就没有启动痕迹”。
         */
        if (!claimConversationLease(launchPlan)) {
            // 租约抢不到就直接短路，说明这条会话当前已经在别的实例或别的请求里运行了。
            return rejectionFlux("该会话当前正在执行中，请稍后再试");
        }

        /*
         * bootstrapConversation(...) 只负责把业务 turn 和 JVM 运行态准备好，
         * 还不会真正触发 ReactAgent 执行。
         *
         * 这一步完成后，返回的 outbound Flux 才会在前端真正订阅时进入 activateGeneration(...)。
         */
        BootstrapResult bootstrapResult = bootstrapConversation(launchPlan);
        /*
         * bootstrap 失败和租约失败的区别在于：
         * - 租约失败：根本没有执行资格，直接拒绝
         * - bootstrap 失败：已经开始准备本轮会话，但在创建运行态或落库时出错
         *
         * 两者对前端来说都表现成错误事件，但问题定位意义完全不同。
         */
        if (StrUtil.isNotBlank(bootstrapResult.getRejectionMessage())) {
            // bootstrap 失败时，仍然统一转成 SSE 错误事件返回给前端。
            return rejectionFlux(bootstrapResult.getRejectionMessage());
        }
        // 只有当启动准备阶段完全成功时，才把真正可订阅的对外流交给 WebFlux。
        return bootstrapResult.getOutbound();
    }

    private BootstrapResult bootstrapConversation(StreamLaunchPlan launchPlan) {
        // turn 先置空，是为了在启动中途异常时判断是否需要把已经落库的 RUNNING turn 回写成 FAILED。
        ConversationExchangeView exchangeView = null;
        try {
            /*
             * 先落一条 RUNNING 的业务轮次，让“这轮对话已经开始”成为数据库里的显式事实。
             *
             * 这样做的好处是：
             * 1. 前端刷新会话详情时，即使模型还没开始吐正文，也能看到这一轮已经存在；
             * 2. 后面如果启动失败，可以明确把这条 turn 改成 FAILED，而不是完全没有痕迹。
             */
            exchangeView = conversationArchiveStore.startExchange(launchPlan.getConversationId(), launchPlan.getQuestion());
            // TaskInfo 是“本次执行的 JVM 现场”，后面 stop/finish/续租都会围绕它协作。
            TaskInfo taskInfo = createTaskInfo(launchPlan, exchangeView);
            /*
             * register(taskInfo) 是本机最后一道互斥防线。
             * 只有当 Redis 租约和 JVM 运行态注册都成功时，这轮对话才真正算“可以开始执行”。
             */
            if (!chatRuntimeRegistry.register(taskInfo)) {
                /*
                 * 这里的失败不是集群级冲突，而是“当前 JVM 已经有同 conversationId 的执行现场”。
                 *
                 * 因为前面 Redis 租约只保证集群互斥，
                 * 本机仍然需要再防一层，避免由于重复订阅或并发重入导致同进程出现两份 TaskInfo。
                 */
                failBootstrappedExchange(launchPlan.getConversationId(), exchangeView.getExchangeId(), "该会话当前正在执行中，请稍后再试");
                // 本机注册失败时别忘了把前面抢到的 Redis 租约放掉，否则这条会话会被白白锁住。
                releaseLeaseQuietly(launchPlan.getLeaseKey(), launchPlan.getLeaseOwnerToken());
                return BootstrapResult.rejected("该会话当前正在执行中，请稍后再试");
            }

            /*
             * 这里只是把“客户端通道”和“未来将要启动的 agent 执行”绑定起来。
             * 真正启动 agent 的时机被放进了 doOnSubscribe(...)，也就是前端真的连上来时。
             */
            return BootstrapResult.ready(bindClientChannel(taskInfo));
        }
        catch (RuntimeException exception) {
            /*
             * 启动阶段一旦抛异常，必须先释放租约。
             * 否则这次失败虽然没有真正跑起来，但会把后续同会话请求一起挡住。
             */
            // 只要启动准备阶段抛异常，就先释放租约，避免这次失败把后续正常请求也挡住。
            releaseLeaseQuietly(launchPlan.getLeaseKey(), launchPlan.getLeaseOwnerToken());
            if (exchangeView != null) {
                // 只有 exchange 已经创建成功时，才需要把数据库里那条 RUNNING 记录回写成 FAILED。
                failBootstrappedExchange(launchPlan.getConversationId(), exchangeView.getExchangeId(), buildErrorMessage(exception));
            }
            return BootstrapResult.rejected(buildErrorMessage(exception));
        }
    }

    private TaskInfo createTaskInfo(StreamLaunchPlan launchPlan, ConversationExchangeView exchangeView) {
        // sink 是这次 SSE 对话对外发事件的唯一出口，所有 text/status/error 最终都会写到这里。
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        // runnableConfig 里最关键的是 threadId；同一个 conversationId 才能命中同一条 agent 记忆线程。
        RunnableConfig runnableConfig = buildSessionConfig(launchPlan.getConversationId());
        // 这几个集合分别累计“过程提示、引用来源、工具轨迹”，供工具拦截器和收尾逻辑共用。
        List<String> thinkingSteps = new ArrayList<>();
        List<SearchReference> references = new ArrayList<>();
        Set<String> usedTools = ConcurrentHashMap.newKeySet();

        /*
         * 这里把产品层关心的“过程态容器”统一挂进 RunnableConfig.context()：
         * - 工具执行阶段可以往里面写 thinking/reference/tool
         * - 流式回调阶段可以从里面继续读取同一份对象
         *
         * 这样不同组件之间共享的是同一份运行态，而不是靠方法返回值层层往外传。
         */
        runnableConfig.context().put(ChatContextKeys.EVENT_SINK, sink);
        runnableConfig.context().put(ChatContextKeys.THINKING_STEPS, thinkingSteps);
        runnableConfig.context().put(ChatContextKeys.REFERENCES, references);
        runnableConfig.context().put(ChatContextKeys.USED_TOOLS, usedTools);
        // 原始 question 单独放进 context，工具层就算拿不到标准参数也能回退到用户原问题。
        runnableConfig.context().put(ChatContextKeys.QUESTION, launchPlan.getQuestion());
        // 当前日期和格式化日期都挂进去，统一服务于“今天/最新/本周”这类时效问题。
        runnableConfig.context().put(ChatContextKeys.CURRENT_DATE, launchPlan.getCurrentDate().toString());
        runnableConfig.context().put(ChatContextKeys.CURRENT_DATE_TEXT, launchPlan.getCurrentDateText());

        /*
         * debugTrace 是“这轮回答为什么会这样执行”的结构化快照。
         * 后续无论走澄清、RAG 还是 Agent，都在这份对象上继续补充运行时信息，
         * 最终统一落进会话归档，供后台观测页回放。
         */
        ChatDebugTrace debugTrace = initializeDebugTrace(launchPlan.getExecutionPlan());

        /*
         * 这里一次性把：
         * - 业务主键
         * - 执行计划
         * - 调试轨迹
         * - SSE sink
         * - 共享上下文容器
         * 都打进 TaskInfo，目的是让后面任何一个执行器都只需要拿 TaskInfo 就能工作。
         */
        // 这里把启动期准备好的所有关键对象一次性封装进 TaskInfo，后面流程就都只传 TaskInfo 了。
        return new TaskInfo(
            launchPlan.getConversationId(),
            exchangeView.getExchangeId(),
            launchPlan.getQuestion(),
            launchPlan.getExecutionPlan(),
            debugTrace,
            runnableConfig,
            sink,
            launchPlan.getLeaseKey(),
            launchPlan.getLeaseOwnerToken(),
            thinkingSteps,
            references,
            usedTools,
            System.currentTimeMillis()
        );
    }

    private Flux<String> bindClientChannel(TaskInfo taskInfo) {
        // 对外暴露给 controller 的其实就是这个 sink 对应的 Flux<String>。
        return taskInfo.sink().asFlux()
            /*
             * 这里是这次重构里最关键的“形态变化”之一：
             * 不是 service 方法一进来就立刻 subscribe，
             * 而是把启动动作绑定到客户端真正订阅 SSE 的那一刻。
             *
             * 这样整个链路变成：
             * 控制器返回 Flux -> WebFlux 建立 SSE 订阅 -> doOnSubscribe 触发 -> agent 真正启动。
             */
            .doOnSubscribe(ignored -> activateGeneration(taskInfo))
            /*
             * 当前端主动断开浏览器连接时，这里会反向触发 stopConversation。
             * 这样可以保证：
             * 1. 内存里的订阅被释放；
             * 2. 数据库里的 turn 被收口成 STOPPED；
             * 3. Redis 租约被及时释放。
             */
            .doOnCancel(() -> stopConversation(taskInfo.conversationId(), "客户端已取消请求"));
    }

    private void activateGeneration(TaskInfo taskInfo) {
        try {
            /*
             * buildConversationExecution(...) 只是组装一条“怎样执行”的 Flux，
             * subscribe() 才是这次对话真正开始跑的瞬间。
             *
             * 这里把 Disposable 保存进 TaskInfo，
             * 后续 stopConversation(...) 才能通过 dispose()/interrupt() 精确中断它。
             */
            Disposable disposable = buildConversationExecution(taskInfo).subscribe();
            // 记住这次订阅的 Disposable，后续 stopConversation 才能精确 dispose 掉当前这轮执行。
            taskInfo.setDisposable(disposable);
            /*
             * Redis 租约初次获取只有 30 秒，
             * 流式回答一旦更长，就必须在启动成功后立刻开启续租。
             */
            taskInfo.setLeaseRenewalDisposable(startLeaseRenewal(taskInfo));
        }
        catch (RuntimeException exception) {
            /*
             * activateGeneration 属于“订阅瞬间”的启动阶段。
             * 这里抛异常时，说明还没进入稳定的正文输出阶段，因此直接统一按 FAILED 收尾最稳。
             */
            // 其他运行时异常也不要往外抛，统一收口成 FAILED，保证前端和数据库状态一致。
            finishWithFailure(taskInfo, exception);
        }
    }

    /**
     * 根据前置编排得到的执行模式，选择具体执行器。
     *
     * <p>这里的关键设计是：
     * BusinessChatService 继续统一管理“会话生命周期、SSE 通道、收尾归档”，
     * 但不再自己关心“知识问答”和“ReactAgent”之间的具体差异。</p>
     */
    private Flux<String> buildConversationExecution(TaskInfo taskInfo) {
        /*
         * 执行器选择完全取决于前置编排已经产出的 executionPlan。
         * 这里不再做任何 if/else 猜测，保证“编排”和“执行”职责彻底分离。
         */
        ConversationExecutor executor = conversationExecutorRegistry.get(taskInfo.executionPlan().getMode());
        return executor.execute(taskInfo)
            .publishOn(Schedulers.boundedElastic())
            /*
             * 执行器只负责吐正文分片，真正如何把分片写入 answerBuffer、发给前端，
             * 仍然统一由 BusinessChatService 自己掌控。
             */
            .doOnNext(chunk -> emitModelChunk(taskInfo, chunk))
            .doOnError(error -> finishWithFailure(taskInfo, error))
            .doOnComplete(() -> finishSuccessfully(taskInfo));
    }

    private Flux<NodeOutput> buildAgentExecution(TaskInfo taskInfo, String agentQuestion) throws GraphRunnerException {
        // 这里真正调用的是 ReactAgent 的流式接口，它返回的还是“底层节点事件流”，不是直接给前端的文本流。
        return businessChatReactAgent.stream(agentQuestion, taskInfo.runnableConfig())
                /*
                 * publishOn(boundedElastic) 的作用是把后面的回调切到更适合阻塞/收尾工作的线程池，
                 * 避免流式输出线程被数据库写入、日志、推荐问题生成这些操作拖住。
                 */
                .publishOn(Schedulers.boundedElastic())
                // 每来一条底层节点事件，就尝试从中提取可展示的正文分片。
                .doOnNext(output -> handleStreamingOutput(taskInfo, output))
                // 整条流一旦抛错，只让 finishWithFailure 做一次统一收尾。
                .doOnError(error -> finishWithFailure(taskInfo, error))
                // 正常结束时，也统一交给 finishSuccessfully 做唯一一次收尾。
                .doOnComplete(() -> finishSuccessfully(taskInfo));
    }

    private StreamLaunchPlan buildLaunchPlan(ChatRequestDto request) {
        // 先把 question 做非空和 trim 规范化，避免后面上下文里到处处理空白字符串。
        String question = normalizeQuestion(request.getQuestion());
        // 新会话没传 conversationId 时，这里会按统一规则自动生成一个。
        String conversationId = normalizeConversationId(request.getConversationId());
        // 当前日期是所有时效性问题的统一锚点。
        LocalDate currentDate = LocalDate.now(CHAT_ZONE_ID);
        String currentDateText = formatCurrentDate(currentDate);
        /*
         * 先拿最近几轮历史，再进入前置编排器。
         * 这样路由、改写、歧义澄清都能拿到真实上下文，而不是只看用户这一句话。
         */
        List<ConversationExchangeView> history = recentExchanges(conversationId);
        /*
         * executionPlan 是“当前这轮对话准备怎么处理”的定稿。
         * 到这一步为止，系统已经知道：
         * - 走哪种执行模式
         * - 要不要改写
         * - 是否需要澄清
         * - 要查哪些文档
         */
        ConversationExecutionPlan executionPlan = chatPreparationOrchestrator.prepare(question, history, currentDate, currentDateText);

        /*
         * agentQuestion 是给 ReactAgent 路径使用的增强版问题，不会直接回显给前端。
         * 即使当前轮最终不走 ReactAgent，也保留这份增强结果，便于未来切换执行模式时复用。
         */
        String agentQuestion = buildAgentQuestion(
            question,
            currentDateText,
            executionPlan.isRequiresCurrentDateAnchoring(),
            executionPlan.isRequiresFreshSearch()
        );
        /*
         * agentQuestion 属于执行计划的一部分，但它依赖当前方法里的日期增强逻辑来生成。
         * 所以先由 buildLaunchPlan 算出来，再回写到 executionPlan 中。
         */
        executionPlan.setAgentQuestion(agentQuestion);

        /*
         * 这里刻意把“原始 question”和“给 agent 的增强 question”分开保存。
         *
         * conversationArchiveStore 落库和前端展示用的仍然是用户原始输入，
         * 只有真正喂给模型的 agentQuestion 才会额外加上日期锚点和联网要求，
         * 这样既保留用户原话，也保证时效问题处理更稳。
         */
        return new StreamLaunchPlan(
            question,
            conversationId,
            // agentQuestion 和原始 question 分开保存，后面谁该落库、谁该喂模型会非常清楚。
            agentQuestion,
            executionPlan,
            // leaseKey 是这条会话在 Redis 里的锁名。
            buildChatLeaseKey(conversationId),
            // ownerToken 用来标识“这次具体执行是谁持有了这把锁”。
            UUID.randomUUID().toString(),
            currentDate,
            currentDateText
        );
    }

    private boolean claimConversationLease(StreamLaunchPlan launchPlan) {
        /*
         * Redis 租约是集群层面的“唯一执行凭证”。
         *
         * 同一个 conversationId 在多实例场景下，
         * 无论请求被打到哪台机器，只要 lease 抢不到，就不能继续往下启动，
         * 从根上避免一条会话在多实例同时生成。
         */
        return redisLeaseManager.acquire(
            launchPlan.getLeaseKey(),
            launchPlan.getLeaseOwnerToken(),
            CHAT_RUNNING_LEASE_TTL
        );
    }

    private void failBootstrappedExchange(String conversationId, long exchangeId, String errorMessage) {
        /*
         * 这里属于“启动期失败收口”分支。
         * 当前轮可能已经在数据库里创建了 RUNNING 记录，但执行器还没真正跑起来，
         * 所以要把它就地改成 FAILED，避免会话详情里残留悬空运行态。
         */
        // 启动阶段失败时，这里把刚创建的 RUNNING exchange 原地收口成 FAILED，避免数据库留下悬空运行态。
        conversationArchiveStore.completeExchange(
            conversationId,
            exchangeId,
            "",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null,
            ChatTurnStatus.FAILED,
            errorMessage,
            null,
            null
        );
    }

    private Flux<String> rejectionFlux(String message) {
        /*
         * 即使是拒绝态，也仍然返回标准 SSE 事件字符串，
         * 这样前端的流式消费逻辑不用额外分支处理“这是 SSE 还是普通 JSON 错误”。
         */
        /*
         * 这里不返回普通 JSON 响应，是为了让前端始终用同一套流式解析器处理成功态和失败态。
         */
        return Flux.just(streamEventWriter.error(message));
    }

    public ConversationStopVo stopConversation(String conversationId) {
        return stopConversation(conversationId, "用户已停止生成");
    }

    /**
     * 主动停止当前会话。
     *
     * <p>顺序上先尝试中断 ReactAgent，再释放订阅，
     * 最后把当前已经拿到的正文、思考步骤、引用来源统一写回数据库，
     * 这样即使用户中途停止，之前已经生成的内容也不会丢失。</p>
     */
    public ConversationStopVo stopConversation(String conversationId, String reason) {
        Optional<TaskInfo> taskInfoOptional = chatRuntimeRegistry.get(conversationId);
        if (taskInfoOptional.isEmpty()) {
            return new ConversationStopVo(conversationId, false, "没有找到正在执行的会话");
        }

        TaskInfo taskInfo = taskInfoOptional.get();

        /*
         * 只允许第一位进入 stop/finish 流程的线程执行真正的收尾。
         * 后续重复 stop 或 doOnError/doOnComplete 竞争进来时，会被这里短路掉。
         */
        if (!taskInfo.finalized().compareAndSet(false, true)) {
            return new ConversationStopVo(conversationId, false, "会话已经结束");
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
            /*
             * dispose() 是 Reactor 订阅层的硬停止；
             * 即使上面的 agent interrupt 因为底层实现原因没有完全生效，
             * 这里也会把当前 JVM 里的订阅先停下来，防止继续向 sink 推送数据。
             */
            disposable.dispose();
        }

        /*
         * 给前端补一个停止状态事件，然后把已有内容按 STOPPED 状态收尾。
         * 这样用户中途停止后，页面和数据库看到的是同一份最终状态。
         */
        safeEmit(taskInfo.sink(), streamEventWriter.status("⏹ " + reason));
        safeComplete(taskInfo.sink());

        conversationArchiveStore.completeExchange(
            conversationId,
            taskInfo.exchangeId(),
            taskInfo.answerBuffer().toString(),
            List.copyOf(taskInfo.thinkingSteps()),
            deduplicateReferences(taskInfo.references()),
            List.of(),
            new ArrayList<>(taskInfo.usedTools()),
            taskInfo.debugTrace(),
            ChatTurnStatus.STOPPED,
            reason,
            toNullable(taskInfo.firstResponseTimeMs().get()),
            System.currentTimeMillis() - taskInfo.startTime()
        );
        cleanup(taskInfo);
        return new ConversationStopVo(conversationId, true, "已停止会话生成");
    }

    public ConversationSessionView getSession(String conversationId) {
        ConversationArchiveStore.ConversationArchiveRecord archiveRecord = conversationArchiveStore.getSessionRecord(conversationId)
            .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + conversationId));
        return toSessionView(archiveRecord);
    }

    public ConversationSessionListVo listSessions() {
        List<ConversationSessionView> sessions = conversationArchiveStore.listSessionRecords()
            .stream()
            .map(this::toSessionView)
            .toList();
        return new ConversationSessionListVo(sessions);
    }

    public ConversationResetVo resetConversation(String conversationId) {
        /*
         * 这里先尝试收口运行中的流，再清理业务归档，最后移除 Agent checkpoint。
         * 这样 reset 更像一次“会话归档撤场”，而不是单纯的 delete。
         */
        ConversationStopVo stopResult = stopConversation(conversationId, "会话被重置");
        /*
         * deleteSession(...) 现在返回明确的删除统计，
         * 是为了让 reset 接口能把“删了多少业务记录”直接反馈给前端或日志，而不是只回一个模糊成功文案。
         */
        ConversationArchiveStore.ConversationRemovalResult removalResult = conversationArchiveStore.deleteSession(conversationId);
        int removedCheckpointCount = checkpointManager.clearThread(conversationId);
        return new ConversationResetVo(
            conversationId,
            stopResult.isStopped(),
            removalResult.removedDialogueCount(),
            removalResult.removedExchangeCount(),
            removedCheckpointCount,
            "会话已重置"
        );
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
     * <p>1. `openConversationStream(...)` 完成启动绑定后，
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
            /*
             * 这里直接 return 的意思不是“出错了”，
             * 而是“这条事件对前端正文展示没有价值”。
             *
             * 换句话说，NodeOutput 的来源很多，
             * 当前业务只把其中“带流式正文语义的事件”继续往下处理；
             * 其他事件就到这里被温和忽略。
             */
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
        if (StrUtil.isBlank(content)) {
            /*
             * 空字符串同样不是异常，而是“当前这一帧没有可展示正文”。
             *
             * 常见场景包括：
             * 1. 这一帧只带了一些底层运行元信息；
             * 2. 这一帧属于工具或节点状态变化，但没有正文；
             * 3. 某些模型在边界帧里给了空文本。
             *
             * 当前业务约定是：只要没有可展示正文，就不往前端发 text 事件。
             */
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
            /*
             * 这是最理想、也是最常见的路径：
             * 模型每生成一点正文，就立刻进来一次，
             * 所以前端能看到真正的“边生成边展示”效果。
             */
            emitModelChunk(taskInfo, content);
            return;
        }

        if (streamingOutput.getOutputType() == OutputType.AGENT_MODEL_FINISHED
            && taskInfo.answerBuffer().isEmpty()) {
            /*
             * 这条分支专门兜底“只在结束时一次性给整段答案”的模型实现。
             *
             * `answerBuffer().isEmpty()` 是关键保护条件：
             * - 空：说明前面确实没有发过任何正文，可以把 finished 里的完整文本补发出去；
             * - 非空：说明前面已经按 streaming 分片发过了，这里再发就会重复。
             */
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
        if (message != null && StrUtil.isNotBlank(message.getText())) {
            /*
             * 命中这里时，说明框架已经把这次流式帧整理成了标准聊天消息。
             * 这是最优先、语义最清晰的一层读取方式。
             */
            return message.getText();
        }

        Object originData = streamingOutput.getOriginData();

        /*
         * 第二优先级：originData 本身也是 Message。
         * 这说明框架把更原始的消息对象挂到了 originData，而不是直接放在 message() 上。
         */
        if (originData instanceof Message originMessage && StrUtil.isNotBlank(originMessage.getText())) {
            /*
             * 有些实现不会把消息直接放在 message() 上，
             * 而是保留在 originData 里。
             * 这里再兼容一层，尽量别因为底层封装差异漏掉正文。
             */
            return originMessage.getText();
        }

        /*
         * 第三优先级：originData 已经是字符串。
         * 这种场景语义最弱，所以放在最后兜底。
         */
        if (originData instanceof String text && StrUtil.isNotBlank(text)) {
            /*
             * 这是最弱的一层兜底：
             * 当前帧只给了一个原始字符串，但只要确实有内容，我们仍然把它当正文接住。
             */
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
        /*
         * 一定要先 append 再 emit。
         *
         * 这样做有两个直接好处：
         * 1. 如果紧接着发生 stop/failure，数据库收尾时已经能拿到最新正文；
         * 2. `answerBuffer().isEmpty()` 之类的判断会立刻反映“正文已经开始产生”这一事实。
         */
        taskInfo.answerBuffer().append(chunk);

        /*
         * 首字耗时只应该记录一次。
         * 因为我们要统计的是“从请求开始到前端第一次看到正文”的时间，
         * 而不是最后一次分片到来的时间。
         */
        if (taskInfo.firstResponseTimeMs().get() == 0L) {
            /*
             * compareAndSet 是为了防止极端并发下首字耗时被重复覆盖。
             * 哪怕同一时刻有两帧几乎同时到达，也只会有第一帧成功写入首字耗时。
             */
            taskInfo.firstResponseTimeMs().compareAndSet(0L, System.currentTimeMillis() - taskInfo.startTime());
        }

        /*
         * 到这里才真正把这一小段正文推给前端。
         * streamEventWriter.text(chunk) 会把这段正文包装成类似下面这样的 JSON 字符串：
         * {"type":"text","content":"杭州今天多云","timestamp":"..."}
         *
         * safeEmit(...) 再把这条 JSON 丢进 sink。
         * 而当前对外返回给前端的其实就是 sink.asFlux()，
         * 所以前端才能实时收到这条 text 事件。
         *
         * 可以把这里理解成：
         * “数据库未来要保存的完整答案” 和 “前端眼前正在展示的增量答案”
         * 从这一行开始被同步推进。
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
         *
         * 例如：
         * - 模型正常走完 doOnComplete(...) 会进来；
         * - 如果与此同时客户端刚好断开，stopConversation(...) 也可能想进来；
         * - compareAndSet(false, true) 能保证只有第一位到达终点的线程真正执行收尾。
         */
        if (!taskInfo.finalized().compareAndSet(false, true)) {
            return;
        }

        /*
         * answerBuffer 里此时已经累计了整轮回答的正文内容。
         * 这里把它取成字符串快照，是为了后面生成推荐问题和写库时都基于同一份定稿文本。
         */
        String answer = taskInfo.answerBuffer().toString();

        /*
         * 最终返回前统一整理业务增强数据：
         * 引用来源去重，推荐问题基于当前问答和最近几轮会话重新生成。
         *
         * 这里刻意把“正文生成”和“增强信息补发”分成两个阶段：
         * 正文先实时流给用户看；
         * 引用和推荐问题等到最终完成时再一次性整理，避免中途频繁回写前端。
         */
        List<SearchReference> uniqueReferences = deduplicateReferences(taskInfo.references());
        List<String> recommendations = recommendationService.generateRecommendations(
            taskInfo.question(),
            answer,
            recentExchanges(taskInfo.conversationId())
        );

        /*
         * reference 和 recommend 都不是模型正文的一部分，
         * 因此放在正文结束后作为独立事件补发给前端。
         *
         * 这样前端渲染层可以明确区分：
         * - `text` 事件只负责累加正文；
         * - `reference` / `recommend` 事件只负责刷新增强区块。
         */
        if (!uniqueReferences.isEmpty()) {
            safeEmit(taskInfo.sink(), streamEventWriter.references(uniqueReferences));
        }
        if (!recommendations.isEmpty()) {
            safeEmit(taskInfo.sink(), streamEventWriter.recommendations(recommendations));
        }
        /*
         * 注意 complete 一定在补发增强事件之后执行。
         * 否则前端可能只拿到正文，拿不到最后的引用和推荐问题。
         */
        safeComplete(taskInfo.sink());

        /*
         * 直到这里才真正把本轮的最终结果定稿到数据库，
         * 包括正文、thinking、引用、推荐问题和耗时指标。
         */
        conversationArchiveStore.completeExchange(
            taskInfo.conversationId(),
            taskInfo.exchangeId(),
            answer,
            List.copyOf(taskInfo.thinkingSteps()),
            uniqueReferences,
            recommendations,
            new ArrayList<>(taskInfo.usedTools()),
            taskInfo.debugTrace(),
            ChatTurnStatus.COMPLETED,
            "",
            toNullable(taskInfo.firstResponseTimeMs().get()),
            System.currentTimeMillis() - taskInfo.startTime()
        );
        /*
         * 数据库定稿成功后，才真正释放运行态资源。
         * 这样可以保证“前端已结束”和“数据库已落定”尽量保持同一收尾点。
         */
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
     * <p>2. 流式会话在刚完成启动绑定时就直接抛出异常，
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
         *
         * 这样做有两个目的：
         * 1. 给前端一个稳定、可展示的错误文本；
         * 2. 把真正适合排查的完整异常栈留在服务端日志里。
         */
        String errorMessage = buildErrorMessage(error);

        /*
         * 第三步先记完整日志。
         * 日志里带上 conversationId 和 exchangeId，是为了后续能从数据库记录和运行日志互相对上。
         */
        log.error("会话执行失败, conversationId={}, exchangeId={}, error={}",
            taskInfo.conversationId(),
            taskInfo.exchangeId(),
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
        conversationArchiveStore.completeExchange(
            taskInfo.conversationId(),
            taskInfo.exchangeId(),
            taskInfo.answerBuffer().toString(),
            List.copyOf(taskInfo.thinkingSteps()),
            deduplicateReferences(taskInfo.references()),
            List.of(),
            new ArrayList<>(taskInfo.usedTools()),
            taskInfo.debugTrace(),
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
                if (StrUtil.isNotBlank(responseBody)) {
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
            /*
             * 续租定时任务一定要先停。
             * 否则主流程已经结束后，后台线程还会继续给已经完成的会话续租，制造脏状态。
             */
            leaseRenewalDisposable.dispose();
        }

        /*
         * 如果订阅还活着，就主动 dispose。
         * 这相当于告诉 Reactor：这条流式链路到这里彻底结束，不要再继续往下推事件了。
         */
        if (disposable != null && !disposable.isDisposed()) {
            /*
             * 这里不关心当前是正常完成、失败还是主动停止，
             * 统一按“如果还活着就关掉”的原则处理，保持 cleanup 的职责单一。
             */
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
         * 旧版本只按 url 去重，已经不适合文档引用场景。
         * 现在统一委托给引用对象自己的 uniqueKey()，
         * 这样网页来源按 URL 去重，文档来源按 chunkId 去重，
         * 后续再加工具来源时也不用回头改这里。
         */
        for (SearchReference reference : references) {
            if (reference == null) {
                continue;
            }
            unique.putIfAbsent(reference.uniqueKey(), reference);
        }
        return new ArrayList<>(unique.values());
    }

    /**
     * 从执行计划初始化一份调试轨迹。
     *
     * <p>这里先把编排阶段已经确定的信息写进去；
     * 后续执行器再继续补充检索轨迹、Prompt 和通道使用情况。</p>
     */
    private ChatDebugTrace initializeDebugTrace(org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan executionPlan) {
        if (executionPlan == null) {
            return ChatDebugTrace.builder().build();
        }
        return ChatDebugTrace.builder()
            /*
             * 这两个字段是调试轨迹里最先看的入口信息：
             * - routeType 解释“这轮为什么走到知识问答 / 澄清 / 开放式对话”
             * - executionMode 解释“最终由哪个执行器真正执行”
             */
            .routeType(executionPlan.getRouteType() == null ? "" : executionPlan.getRouteType().name())
            .executionMode(executionPlan.getMode() == null ? "" : executionPlan.getMode().name())
            /*
             * 这三份问题快照各自代表不同语义层级：
             * - originalQuestion: 用户原话
             * - rewrittenQuestion: 检索理解后的问题
             * - agentQuestion: 给 Agent 路径使用的增强版问题
             *
             * 三者一起保留，后面排查“为什么检索到了这些结果”时才有足够上下文。
             */
            .originalQuestion(executionPlan.getOriginalQuestion())
            .rewrittenQuestion(executionPlan.getRewrittenQuestion())
            .agentQuestion(executionPlan.getAgentQuestion())
            /*
             * historySummary 和 currentDateText 反映的是“前置编排当时看到的上下文”。
             * 如果不把它们记下来，后面就只能看到结论，却看不到结论形成时依赖了什么背景。
             */
            .historySummary(executionPlan.getHistorySummary())
            .currentDateText(executionPlan.getCurrentDateText())
            .requiresFreshSearch(executionPlan.isRequiresFreshSearch())
            .requiresCurrentDateAnchoring(executionPlan.isRequiresCurrentDateAnchoring())
            .clarifyPrompt(executionPlan.getClarifyPrompt())
            /*
             * 这里显式做列表拷贝，而不是直接复用 executionPlan 里的引用，
             * 是为了让 debugTrace 保留“初始化当下”的快照，不受后续 plan 对象变化影响。
             */
            .subQuestions(executionPlan.getSubQuestions() == null ? List.of() : new ArrayList<>(executionPlan.getSubQuestions()))
            .scopeOptions(executionPlan.getScopeOptions() == null ? List.of() : new ArrayList<>(executionPlan.getScopeOptions()))
            .selectedDocumentIds(executionPlan.getSelectedDocumentIds() == null ? List.of() : new ArrayList<>(executionPlan.getSelectedDocumentIds()))
            .selectedTaskIds(executionPlan.getSelectedTaskIds() == null ? List.of() : new ArrayList<>(executionPlan.getSelectedTaskIds()))
            /*
             * retrievalNotes / usedChannels 会在执行期不断被补充，
             * 所以初始化时先准备空容器，保持调试轨迹对象始终结构完整。
             */
            .retrievalNotes(new ArrayList<>())
            .usedChannels(new ArrayList<>())
            .noEvidenceReply(executionPlan.getNoEvidenceReply())
            .build();
    }

    /**
     * 把数据库里的业务会话视图和 ReactAgent 的 checkpoint 信息合并成最终返回对象。
     *
     * <p>数据库负责存“业务上要展示的 turn 明细”，
     * checkpoint 负责存“模型继续对话所需的运行记忆”。
     * 两者合并后，接口既能返回完整的问答记录，也能返回当前线程的消息统计。</p>
     */
    private ConversationSessionView toSessionView(ConversationArchiveStore.ConversationArchiveRecord archiveRecord) {
        /*
         * 会话详情既要读业务表，也要读 ReactAgent checkpoint。
         * 因此这里临时构造一个 threadId 相同的 RunnableConfig 来查询当前线程状态。
         *
         * 这里不直接把 checkpoint 表暴露给 controller，
         * 而是在 service 层完成“业务归档 + Agent 运行摘要”的拼装，
         * 让上层始终只面对一个稳定的 SessionView。
         */
        RunnableConfig runnableConfig = RunnableConfig.builder()
            .threadId(archiveRecord.conversationId())
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
         *
         * 其中：
         * - exchanges 来自业务归档表，负责页面完整历史展示；
         * - checkpointCount / messageCount / latestUserMessage / latestAssistantMessage
         *   来自 Graph checkpoint，负责补足当前线程记忆态信息。
         */
        return new ConversationSessionView(
            archiveRecord.conversationId(),
            archiveRecord.running(),
            /*
             * checkpoint 数量来自 Graph 层，而不是业务会话表。
             * 它反映的是“这条 Agent 线程目前保存了多少个运行快照”。 
             */
            checkpointManager.list(runnableConfig).size(),
            /*
             * messageCount 统计的是 checkpoint 里当前消息上下文的条数。
             * 它和业务上有多少轮 exchange 不是一回事，但能帮助判断 Agent 线程记忆是否正常积累。
             */
            messageList.size(),
            latestMessage(messageList, MessageType.USER),
            latestMessage(messageList, MessageType.ASSISTANT),
            archiveRecord.createdAt(),
            archiveRecord.updatedAt(),
            archiveRecord.exchanges()
        );
    }

    private String latestMessage(List<?> messages, MessageType type) {
        /*
         * 这里从后往前扫，是因为我们只关心“最近一条”某种类型的消息。
         * 倒序扫描能在最常见场景下更快命中，不需要遍历完整个列表。
         */
        for (int index = messages.size() - 1; index >= 0; index--) {
            Object candidate = messages.get(index);
            if (candidate instanceof AbstractMessage message && message.getMessageType() == type) {
                return message.getText();
            }
        }
        return "";
    }

    private List<ConversationExchangeView> recentExchanges(String conversationId) {
        /*
         * 这里把“读取最近几轮历史”抽成一个极薄方法，是为了把仓储细节隔离在 service 层内部。
         * 上层编排器只关心“我拿到一组 exchange 视图”，不需要知道底层是怎么查数据库的。
         */
        return conversationArchiveStore.getSessionRecord(conversationId)
            .map(ConversationArchiveStore.ConversationArchiveRecord::exchanges)
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
            /*
             * 每次 tick 都重新校验“我是不是仍然是这条会话的合法 owner”。
             * 一旦续租失败，就说明当前实例已经不应该继续输出，必须主动 stop。
             */
            .subscribe(ignored -> renewLeaseOrStop(taskInfo), error ->
                log.warn("租约续期任务出现异常, conversationId={}, exchangeId={}",
                    taskInfo.conversationId(),
                    taskInfo.exchangeId(),
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
            /*
             * 续租成功时，不要额外发事件、不用记业务日志，静默继续执行即可。
             * 因为续租本质上只是后台保活动作，不应该打扰用户看到的对话过程。
             */
            return;
        }

        /*
         * 一旦续租失败，就立刻停止续租定时任务本身，
         * 避免 stopConversation(...) 还没收尾完成前，后台又继续重复触发续租失败日志。
         */
        log.warn("会话租约续期失败，准备停止当前会话, conversationId={}, exchangeId={}",
            taskInfo.conversationId(),
            taskInfo.exchangeId());
        Disposable leaseRenewalDisposable = taskInfo.leaseRenewalDisposable();
        if (leaseRenewalDisposable != null && !leaseRenewalDisposable.isDisposed()) {
            leaseRenewalDisposable.dispose();
        }
        stopConversation(taskInfo.conversationId(), "会话租约已失效，已停止生成");
    }

    private void releaseLeaseQuietly(String leaseKey, String leaseOwnerToken) {
        try {
            /*
             * release 不是无脑删 key，而是带 ownerToken 校验的 compare-and-release。
             * 只有当前任务仍然持有租约时，才允许真正释放，避免误删别人接管后的租约。
             */
            redisLeaseManager.release(leaseKey, leaseOwnerToken);
        }
        catch (RuntimeException exception) {
            /*
             * 释放租约属于“收尾阶段的最好努力”动作。
             * 这里记 warn 而不是继续抛出异常，是为了避免一个租约释放失败把整轮收尾再打断一次。
             */
            log.warn("释放会话租约时出现异常, leaseKey={}", leaseKey, exception);
        }
    }

    private String buildChatLeaseKey(String conversationId) {
        /*
         * 这里的 `chat:running:` 只是 Redis key 的命名空间，
         * 用来把“会话运行租约”这类 key 和其他业务 key 分开，便于排查与批量观察。
         *
         * 它和 conversationId 本身的取值规则无关；
         * 当前 conversationId 已经不再追加任何业务前缀。
         */
        return CHAT_RUNNING_LEASE_PREFIX + conversationId;
    }

    private Long toNullable(long value) {
        /*
         * 这里专门把 <=0 的耗时指标转成 null，
         * 是为了区分：
         * - null: 没采到 / 不适用
         * - 正数: 有效耗时
         */
        return value > 0 ? value : null;
    }

    private String normalizeQuestion(String question) {
        if (StrUtil.isBlank(question)) {
            throw new IllegalArgumentException("question 不能为空");
        }
        /*
         * 这里故意只做 trim，而不做更激进的文本清洗。
         * 更深层的语义处理应该交给 rewrite / route 阶段，而不是在最入口就偷偷改用户问题。
         */
        return question.trim();
    }

    private String normalizeConversationId(String conversationId) {
        if (StrUtil.isNotBlank(conversationId)) {
            /*
             * 老会话继续追问时，服务端必须保留原 conversationId，
             * 否则业务归档和 Agent checkpoint 都会断开，变成一条新线程。
             */
            return conversationId.trim();
        }

        /*
         * 新会话没有传 conversationId 时，由服务端直接生成一个纯 UUID 风格线程号。
         *
         */
        return UUID.randomUUID().toString().replace("-", "");
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
        /*
         * 当前实现直接构造一段“系统时间信息 + 用户问题”的增强文本，
         * 而不是去改写原 question 本身。
         * 这样数据库里保存的仍然是用户原话，只有 Agent 看到的是增强版问题。
         */
        StringBuilder builder = new StringBuilder();
        builder.append("系统时间信息：\n");
        builder.append("当前日期是 ").append(currentDateText).append("，时区为 Asia/Shanghai。\n");

        if (requiresCurrentDateAnchoring) {
            /*
             * 已经明确检测到问题带有相对时间语义时，这里会用更强语气约束 Agent，
             * 避免它把搜索结果里的旧日期误读成今天。
             */
            builder.append("当前问题包含相对时间或强时效语义。");
            builder.append("当用户提到“今天、明天、昨天、现在、当前、最新、本周、本月、今年”等表达时，");
            builder.append("必须以这个日期为准，不要把搜索结果里的旧日期误当成今天。\n");
        } else {
            /*
             * 即使当前问题不明显要求日期锚定，也仍然补一条弱提示。
             * 这是为了让 Agent 在碰到潜在相对时间表达时，默认知道应该参考当前日期。
             */
            builder.append("当用户提到“今天、明天、昨天、现在、当前、最新”等相对时间时，必须以这个日期为准。\n");
        }

        if (requiresFreshSearch) {
            /*
             * 一旦当前问题具备强时效性，这里直接明确要求优先联网核实。
             * 这不是最终答案，而是给 Agent 的执行偏好约束。
             */
            builder.append("当前问题需要核实最新外部事实，回答前必须优先调用联网搜索工具。\n");
            builder.append("如果搜索结果里的日期与当前日期不一致，必须明确说明来源日期，不要把旧日期说成今天。\n");
            builder.append("如果无法找到与当前日期匹配的可靠结果，要明确说明不确定性，不要编造最新信息。\n");
        }

        /*
         * 最后再把用户原问题拼进去。
         * 这样增强信息始终是“系统补充上下文”，而不是把用户原话覆盖掉。
         */
        builder.append("\n用户问题：\n");
        builder.append(question);
        return builder.toString();
    }

    private String formatCurrentDate(LocalDate currentDate) {
        /*
         * 这里返回给模型看的不是 ISO 日期，而是“日期 + 星期”的自然语言格式。
         * 这样模型在处理“本周几”“今天周末吗”这类语义时会更稳。
         */
        return currentDate + "（" + chineseWeekday(currentDate.getDayOfWeek()) + "）";
    }

    private String chineseWeekday(DayOfWeek dayOfWeek) {
        /*
         * 这里显式做星期映射，而不是依赖某个 locale 格式化结果，
         * 这样不同运行环境下输出口径始终一致。
         */
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
        /*
         * 这里自己不做 try-catch 和同步控制，
         * 统一交给 SinkEmitHelper，避免不同调用点各自实现一套发送语义。
         */
        SinkEmitHelper.emitNext(sink, payload);
    }

    private void safeComplete(Sinks.Many<String> sink) {
        /*
         * 这一步不是“发送一个 complete 文本”，而是结束 Reactor 流本身。
         * 一旦调用成功，前端的 SSE 连接在业务语义上就算收尾完成了。
         */
        /*
         * 和 safeEmit 一样，complete 的并发安全和终态处理都集中交给 helper，
         * 这样业务层只保留“什么时候应该结束流”的语义判断。
         */
        SinkEmitHelper.emitComplete(sink);
    }

}
