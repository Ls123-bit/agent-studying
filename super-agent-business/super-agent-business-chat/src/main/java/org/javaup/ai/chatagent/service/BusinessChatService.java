package org.javaup.ai.chatagent.service;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.StrUtil;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.fastjson.JSON;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.config.ChatAgentProperties;
import org.javaup.ai.chatagent.dto.ChatRequestDto;
import org.javaup.ai.chatagent.dto.ConversationSessionListQueryDto;
import org.javaup.ai.chatagent.model.ConversationExchangeView;
import org.javaup.ai.chatagent.model.KnowledgeDocumentOptionView;
import org.javaup.ai.chatagent.model.ConversationMemorySummaryView;
import org.javaup.ai.chatagent.model.ConversationSessionView;
import org.javaup.ai.chatagent.model.SearchReference;
import org.javaup.ai.chatagent.model.debug.ChatDebugTrace;
import org.javaup.ai.chatagent.rag.executor.ConversationExecutor;
import org.javaup.ai.chatagent.rag.executor.ConversationExecutorRegistry;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.manage.model.KnowledgeDocumentDescriptor;
import org.javaup.ai.manage.service.DocumentKnowledgeService;
import org.javaup.ai.chatagent.rag.service.ChatPreparationOrchestrator;
import org.javaup.ai.chatagent.support.ChatContextKeys;
import org.javaup.ai.chatagent.support.SinkEmitHelper;
import org.javaup.ai.chatagent.support.StreamEventMetadata;
import org.javaup.ai.chatagent.support.StreamEventWriter;
import org.javaup.ai.chatagent.vo.ConversationResetVo;
import org.javaup.ai.chatagent.vo.ConversationSessionListVo;
import org.javaup.ai.chatagent.vo.ConversationStopVo;
import org.javaup.enums.ChatTurnStatus;
import org.javaup.enums.ChatQueryMode;
import org.javaup.lease.RedisLeaseManager;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
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
    private final ConversationMemoryService conversationMemoryService;
    private final DocumentKnowledgeService documentKnowledgeService;
    

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
        StreamLaunchPlan launchPlan = null;
        boolean leaseClaimed = false;
        try {
            /*
             * launchPlan 现在只保留启动阶段就能确定的轻量信息：
             * question、conversationId、租约信息、日期锚点。
             *
             * 真正重的“历史摘要 / 改写 / 文档检索规划”被下沉到订阅后的执行阶段，
             * 避免在建立 SSE 通道之前就把耗时工作全部做完。
             */
            launchPlan = buildLaunchPlan(request);
            /*
             * 先抢租约再落库，避免同一会话并发请求都把重活做完后，
             * 最后才发现只有一个请求真正能执行。
             */
            leaseClaimed = claimConversationLease(launchPlan);
            if (!leaseClaimed) {
                return rejectionFlux("该会话当前正在执行中，请稍后再试", launchPlan.getConversationId(), null);
            }

            BootstrapResult bootstrapResult = bootstrapConversation(launchPlan);
            if (StrUtil.isNotBlank(bootstrapResult.getRejectionMessage())) {
                return rejectionFlux(bootstrapResult.getRejectionMessage(), launchPlan.getConversationId(), null);
            }
            return bootstrapResult.getOutbound();
        }
        catch (RuntimeException exception) {
            log.error("会话启动失败, conversationId={}, question={}",
                launchPlan == null ? "" : launchPlan.getConversationId(),
                request.getQuestion(),
                exception);
            if (leaseClaimed && launchPlan != null) {
                releaseLeaseQuietly(launchPlan.getLeaseKey(), launchPlan.getLeaseOwnerToken());
            }
            return rejectionFlux(
                buildErrorMessage(exception),
                launchPlan == null ? null : launchPlan.getConversationId(),
                null
            );
        }
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
            exchangeView = conversationArchiveStore.startExchange(
                launchPlan.getConversationId(),
                launchPlan.getQuestion(),
                launchPlan.getChatMode(),
                launchPlan.getSelectedDocumentId(),
                launchPlan.getSelectedDocumentName()
            );
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
        List<String> thinkingSteps = Collections.synchronizedList(new ArrayList<>());
        List<SearchReference> references = Collections.synchronizedList(new ArrayList<>());
        Set<String> usedTools = ConcurrentHashMap.newKeySet();
        StreamEventMetadata eventMetadata = new StreamEventMetadata(
            launchPlan.getConversationId(),
            exchangeView.getExchangeId()
        );
        /*
         * eventMetadata 看起来只是两个简单字段，但它承担的是“把所有流式事件稳定定位到当前 turn”的职责。
         * 一旦后端支持服务端生成 conversationId，或者前端并行打开多个对话窗口，
         * 这份元数据就能避免事件落错会话、落错消息卡片。
         */

        /*
         * 这里把产品层关心的“过程态容器”统一挂进 RunnableConfig.context()：
         * - 工具执行阶段可以往里面写 thinking/reference/tool
         * - 流式回调阶段可以从里面继续读取同一份对象
         *
         * 这样不同组件之间共享的是同一份运行态，而不是靠方法返回值层层往外传。
         */
        runnableConfig.context().put(ChatContextKeys.EVENT_SINK, sink);
        runnableConfig.context().put(ChatContextKeys.EVENT_METADATA, eventMetadata);
        runnableConfig.context().put(ChatContextKeys.THINKING_STEPS, thinkingSteps);
        runnableConfig.context().put(ChatContextKeys.REFERENCES, references);
        runnableConfig.context().put(ChatContextKeys.USED_TOOLS, usedTools);
        // 原始 question 单独放进 context，工具层就算拿不到标准参数也能回退到用户原问题。
        runnableConfig.context().put(ChatContextKeys.QUESTION, launchPlan.getQuestion());
        /*
         * chatMode 也放进共享上下文，是为了让工具层、调试页或未来新增的执行组件
         * 随时都能读到“本轮是文档问答还是开放式提问”。
         *
         * 这样后面的扩展能力不需要再反查 request DTO 或 launchPlan。
         */
        runnableConfig.context().put(ChatContextKeys.CHAT_MODE, launchPlan.getChatMode().name());
        // 当前日期和格式化日期都挂进去，统一服务于“今天/最新/本周”这类时效问题。
        runnableConfig.context().put(ChatContextKeys.CURRENT_DATE, launchPlan.getCurrentDate().toString());
        runnableConfig.context().put(ChatContextKeys.CURRENT_DATE_TEXT, launchPlan.getCurrentDateText());
        /*
         * RunnableConfig.context() 底层实现并不保证接受 null value。
         * 对开放式提问来说，当前文档相关字段本来就可能为空；
         * 如果这里仍然无条件 put(null)，就会在启动阶段直接抛 NPE。
         *
         * 因此文档上下文字段统一改成“有值才写入”：
         * - DOCUMENT 模式会完整写入 documentId / documentName / taskId
         * - OPEN_CHAT 模式则干净地不写这些键
         */
        putContextIfNotNull(runnableConfig, ChatContextKeys.SELECTED_DOCUMENT_ID, launchPlan.getSelectedDocumentId());
        putContextIfNotBlank(runnableConfig, ChatContextKeys.SELECTED_DOCUMENT_NAME, launchPlan.getSelectedDocumentName());
        putContextIfNotNull(runnableConfig, ChatContextKeys.SELECTED_TASK_ID, launchPlan.getSelectedTaskId());

        /*
         * debugTrace 是“这轮回答为什么会这样执行”的结构化快照。
         * 后续无论走文档 RAG 还是开放式 Agent，都在这份对象上继续补充运行时信息，
         * 最终统一落进会话归档，供后台观测页回放。
         */
        ChatDebugTrace debugTrace = initializeDebugTrace(null);
        runnableConfig.context().put(ChatContextKeys.DEBUG_TRACE, debugTrace);

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
            launchPlan.getChatMode(),
            launchPlan.getSelectedDocumentId(),
            launchPlan.getSelectedDocumentName(),
            launchPlan.getSelectedTaskId(),
            launchPlan.getCurrentDate(),
            launchPlan.getCurrentDateText(),
            null,
            debugTrace,
            runnableConfig,
            sink,
            eventMetadata,
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
            .doOnCancel(() -> stopTask(taskInfo, "客户端已取消请求"));
    }

    private void activateGeneration(TaskInfo taskInfo) {
        try {
            if (taskInfo.finalized().get()) {
                return;
            }
            /*
             * 续租任务要先于真正执行流启动。
             * 这是为了覆盖“执行器同步完成得非常快”以及“订阅刚建立就遇到异常”的极端窗口，
             * 避免“主流程已经结束，续租任务却晚到并误停下一轮会话”的时序问题。
             */
            Disposable leaseRenewalDisposable = startLeaseRenewal(taskInfo);
            taskInfo.setLeaseRenewalDisposable(leaseRenewalDisposable);
            if (taskInfo.finalized().get() && !leaseRenewalDisposable.isDisposed()) {
                leaseRenewalDisposable.dispose();
                return;
            }
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
            if (taskInfo.finalized().get() && !disposable.isDisposed()) {
                disposable.dispose();
            }
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
        return Flux.defer(() -> {
                /*
                 * 进入真正执行前，先立即给前端一个过程事件。
                 * 这样历史摘要自愈、问题改写等重前置动作不再表现成“完全静默等待”。
                 */
                safeEmit(taskInfo.sink(), streamEventWriter.thinking("正在分析问题上下文。", taskInfo.eventMetadata()));
                return Mono.fromCallable(() -> prepareExecutionPlan(taskInfo))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMapMany(plan -> {
                        /*
                         * 执行器选择完全取决于前置编排阶段刚刚产出的 executionPlan。
                         * 这样既保留了“执行器只看 plan”的职责边界，也让前置异常自然走进统一失败收口。
                         */
                        ConversationExecutor executor = conversationExecutorRegistry.get(plan.getMode());
                        return executor.execute(taskInfo);
                    });
            })
            .publishOn(Schedulers.boundedElastic())
            /*
             * 执行器只负责吐正文分片，真正如何把分片写入 answerBuffer、发给前端，
             * 仍然统一由 BusinessChatService 自己掌控。
             */
            .doOnNext(chunk -> emitModelChunk(taskInfo, chunk))
            .doOnError(error -> finishWithFailure(taskInfo, error))
            .doOnComplete(() -> finishSuccessfully(taskInfo));
    }

    private StreamLaunchPlan buildLaunchPlan(ChatRequestDto request) {
        // 先把 question 做非空和 trim 规范化，避免后面上下文里到处处理空白字符串。
        String question = normalizeQuestion(request.getQuestion());
        // 新会话没传 conversationId 时，这里会按统一规则自动生成一个。
        String conversationId = normalizeConversationId(request.getConversationId());
        ChatQueryMode chatMode = request.getChatMode();
        /*
         * 这里把“模式校验”和“文档解析”放在启动蓝图阶段一次性做完，
         * 后面的执行链路就不需要继续猜参数组合是否合法。
         *
         * 现在的契约非常明确：
         * - DOCUMENT: 必须带 selectedDocumentId
         * - OPEN_CHAT: 不允许带 selectedDocumentId
         */
        KnowledgeDocumentDescriptor selectedDocument = resolveSelectedDocument(chatMode, request.getSelectedDocumentId());
        // 当前日期是所有时效性问题的统一锚点。
        LocalDate currentDate = LocalDate.now(CHAT_ZONE_ID);
        String currentDateText = formatCurrentDate(currentDate);
        return new StreamLaunchPlan(
            question,
            conversationId,
            chatMode,
            selectedDocument == null ? null : selectedDocument.getDocumentId(),
            selectedDocument == null ? "" : selectedDocument.getDocumentName(),
            selectedDocument == null ? null : selectedDocument.getLastIndexTaskId(),
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
        return rejectionFlux(message, null, null);
    }

    private Flux<String> rejectionFlux(String message, String conversationId, Long exchangeId) {
        /*
         * 即使是拒绝态，也仍然返回标准 SSE 事件字符串，
         * 这样前端的流式消费逻辑不用额外分支处理“这是 SSE 还是普通 JSON 错误”。
         */
        /*
         * 这里不返回普通 JSON 响应，是为了让前端始终用同一套流式解析器处理成功态和失败态。
         */
        return Flux.just(streamEventWriter.error(message, new StreamEventMetadata(conversationId, exchangeId)));
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
        return stopTask(taskInfoOptional.get(), reason);
    }

    private ConversationStopVo stopTask(TaskInfo taskInfo, String reason) {
        /*
         * 只允许第一位进入 stop/finish 流程的线程执行真正的收尾。
         * 后续重复 stop 或 doOnError/doOnComplete 竞争进来时，会被这里短路掉。
         */
        if (!taskInfo.finalized().compareAndSet(false, true)) {
            return new ConversationStopVo(taskInfo.conversationId(), false, "会话已经结束");
        }

        Optional<TaskInfo> currentTask = chatRuntimeRegistry.get(taskInfo.conversationId());
        if (currentTask.isPresent() && currentTask.get() != taskInfo) {
            /*
             * 这里按 TaskInfo 身份做二次确认，是为了防止“旧任务”误停掉“新任务”。
             * 同一个 conversationId 在高并发下可能已经被新的执行接管，
             * 这时即使旧任务还握着自己的引用，也只能结束自己，绝不能越权收掉当前活跃任务。
             */
            return new ConversationStopVo(taskInfo.conversationId(), false, "会话已由新的执行接管");
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
        String responseMessage = "已停止会话生成";
        try {
            safeEmit(taskInfo.sink(), streamEventWriter.status("⏹ " + reason, taskInfo.eventMetadata()));
        }
        catch (RuntimeException exception) {
            log.warn("发送停止事件失败, conversationId={}, exchangeId={}", taskInfo.conversationId(), taskInfo.exchangeId(), exception);
            responseMessage = "会话已停止，停止事件发送失败";
        }
        finally {
            try {
                safeComplete(taskInfo.sink());
            }
            catch (RuntimeException exception) {
                log.warn("关闭停止中的 SSE 流失败, conversationId={}, exchangeId={}", taskInfo.conversationId(), taskInfo.exchangeId(), exception);
            }
            try {
                conversationArchiveStore.completeExchange(
                    taskInfo.conversationId(),
                    taskInfo.exchangeId(),
                    taskInfo.answerBuffer().toString(),
                    snapshotStringList(taskInfo.thinkingSteps()),
                    deduplicateReferences(snapshotReferenceList(taskInfo.references())),
                    List.of(),
                    snapshotUsedTools(taskInfo.usedTools()),
                    taskInfo.debugTrace(),
                    ChatTurnStatus.STOPPED,
                    reason,
                    toNullable(taskInfo.firstResponseTimeMs().get()),
                    System.currentTimeMillis() - taskInfo.startTime()
                );
            }
            catch (RuntimeException exception) {
                log.error("停止会话落库失败, conversationId={}, exchangeId={}", taskInfo.conversationId(), taskInfo.exchangeId(), exception);
                responseMessage = "会话已停止，收尾落库失败";
            }
            finally {
                safeRefreshConversationSummary(taskInfo.conversationId());
                cleanup(taskInfo);
            }
        }
        return new ConversationStopVo(taskInfo.conversationId(), true, responseMessage);
    }

    public ConversationSessionView getSession(String conversationId) {
        ConversationArchiveStore.ConversationArchiveRecord archiveRecord = conversationArchiveStore.getSessionRecord(conversationId)
            .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + conversationId));
        return overlayRuntimeSnapshot(toSessionView(archiveRecord, true, true));
    }

    public ConversationSessionListVo listSessions(ConversationSessionListQueryDto dto) {
        int pageNo = parsePositiveInt(dto == null ? null : dto.getPageNo(), 1);
        int pageSize = parsePositiveInt(dto == null ? null : dto.getPageSize(), 20);
        String keyword = normalizeOptionalText(dto == null ? null : dto.getKeyword());
        ChatQueryMode chatMode = parseOptionalChatMode(dto == null ? null : dto.getChatMode());
        ChatTurnStatus turnStatus = parseOptionalTurnStatus(dto == null ? null : dto.getTurnStatus());

        ConversationArchiveStore.ConversationArchivePage archivePage = conversationArchiveStore.listSessionRecordPage(
            pageNo,
            pageSize,
            keyword,
            chatMode,
            turnStatus
        );
        List<ConversationSessionView> sessions = archivePage.records()
            .stream()
            .map(record -> toSessionView(record, false, false))
            .toList();

        long totalPages = archivePage.totalSize() <= 0
            ? 0
            : (archivePage.totalSize() + archivePage.pageSize() - 1) / archivePage.pageSize();
        return new ConversationSessionListVo(
            archivePage.pageNo(),
            archivePage.pageSize(),
            archivePage.totalSize(),
            totalPages,
            sessions
        );
    }

    public List<KnowledgeDocumentOptionView> listKnowledgeDocumentOptions() {
        return documentKnowledgeService.listRetrievableDocuments().stream()
            .map(this::toKnowledgeDocumentOptionView)
            .toList();
    }

    /**
     * 手动重建某条会话的长期摘要。
     *
     * <p>这个入口主要给后台观测页和教学演示使用，
     * 方便在不发起新一轮对话的前提下，直接触发一次摘要重算。</p>
     */
    public ConversationMemorySummaryView rebuildConversationSummary(String conversationId) {
        return conversationMemoryService.rebuildConversationSummary(conversationId);
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
        /*
         * reset 不只是删业务问答记录，还要把长期摘要快照一起清掉，
         * 否则后面同 conversationId 重建会话时会读到旧记忆。
         */
        conversationMemoryService.deleteConversationSummary(conversationId);
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
        safeEmit(taskInfo.sink(), streamEventWriter.text(chunk, taskInfo.eventMetadata()));
    }

    /**
     * 流式执行正常完成时的收尾逻辑。
     *
     * <p>这里不会再改 ReactAgent 的推理结果，而是只做产品层收尾：
     * 聚合引用、生成推荐问题、补发最终事件、写库并清理任务。</p>
     */
    private void finishSuccessfully(TaskInfo taskInfo) {
        if (!taskInfo.finalized().compareAndSet(false, true)) {
            return;
        }

        String answer = taskInfo.answerBuffer().toString();
        List<SearchReference> uniqueReferences = deduplicateReferences(snapshotReferenceList(taskInfo.references()));
        List<String> recommendations = recommendationService.generateRecommendations(
            taskInfo.question(),
            answer,
            historicalRecentExchanges(taskInfo)
        );

        try {
            if (!uniqueReferences.isEmpty()) {
                safeEmit(taskInfo.sink(), streamEventWriter.references(uniqueReferences, taskInfo.eventMetadata()));
            }
            if (!recommendations.isEmpty()) {
                safeEmit(taskInfo.sink(), streamEventWriter.recommendations(recommendations, taskInfo.eventMetadata()));
            }
        }
        catch (RuntimeException exception) {
            log.warn("补发引用或推荐事件失败, conversationId={}, exchangeId={}", taskInfo.conversationId(), taskInfo.exchangeId(), exception);
        }
        finally {
            try {
                safeComplete(taskInfo.sink());
            }
            catch (RuntimeException exception) {
                log.warn("关闭成功完成的 SSE 流失败, conversationId={}, exchangeId={}", taskInfo.conversationId(), taskInfo.exchangeId(), exception);
            }
            try {
                conversationArchiveStore.completeExchange(
                    taskInfo.conversationId(),
                    taskInfo.exchangeId(),
                    answer,
                    snapshotStringList(taskInfo.thinkingSteps()),
                    uniqueReferences,
                    recommendations,
                    snapshotUsedTools(taskInfo.usedTools()),
                    taskInfo.debugTrace(),
                    ChatTurnStatus.COMPLETED,
                    "",
                    toNullable(taskInfo.firstResponseTimeMs().get()),
                    System.currentTimeMillis() - taskInfo.startTime()
                );
            }
            catch (RuntimeException exception) {
                log.error("成功会话收尾落库失败, conversationId={}, exchangeId={}", taskInfo.conversationId(), taskInfo.exchangeId(), exception);
            }
            finally {
                safeRefreshConversationSummary(taskInfo.conversationId());
                cleanup(taskInfo);
            }
        }
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
        try {
            safeEmit(taskInfo.sink(), streamEventWriter.error(errorMessage, taskInfo.eventMetadata()));
        }
        catch (RuntimeException exception) {
            log.warn("发送失败事件失败, conversationId={}, exchangeId={}", taskInfo.conversationId(), taskInfo.exchangeId(), exception);
        }
        finally {
            try {
                safeComplete(taskInfo.sink());
            }
            catch (RuntimeException exception) {
                log.warn("关闭失败中的 SSE 流失败, conversationId={}, exchangeId={}", taskInfo.conversationId(), taskInfo.exchangeId(), exception);
            }
            try {
                conversationArchiveStore.completeExchange(
                    taskInfo.conversationId(),
                    taskInfo.exchangeId(),
                    taskInfo.answerBuffer().toString(),
                    snapshotStringList(taskInfo.thinkingSteps()),
                    deduplicateReferences(snapshotReferenceList(taskInfo.references())),
                    List.of(),
                    snapshotUsedTools(taskInfo.usedTools()),
                    taskInfo.debugTrace(),
                    ChatTurnStatus.FAILED,
                    errorMessage,
                    toNullable(taskInfo.firstResponseTimeMs().get()),
                    System.currentTimeMillis() - taskInfo.startTime()
                );
            }
            catch (RuntimeException exception) {
                log.error("失败会话收尾落库失败, conversationId={}, exchangeId={}", taskInfo.conversationId(), taskInfo.exchangeId(), exception);
            }
            finally {
                safeRefreshConversationSummary(taskInfo.conversationId());
                cleanup(taskInfo);
            }
        }
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
        chatRuntimeRegistry.remove(taskInfo.conversationId(), taskInfo);
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
    private ChatDebugTrace initializeDebugTrace(ConversationExecutionPlan executionPlan) {
        if (executionPlan == null) {
            return ChatDebugTrace.builder()
                .retrievalNotes(Collections.synchronizedList(new ArrayList<>()))
                .usedChannels(Collections.synchronizedList(new ArrayList<>()))
                .build();
        }
        return ChatDebugTrace.builder()
            /*
             * chatMode 和 executionMode 一起构成当前教学项目里最核心的两层解释：
             * - chatMode: 用户在前端选的是“当前文档问答”还是“开放式提问”
             * - executionMode: 后端最终由哪个执行器真正执行
             */
            .executionMode(executionPlan.getMode() == null ? "" : executionPlan.getMode().name())
            .chatMode(executionPlan.getChatMode())
            /*
             * 这三份问题快照各自代表不同语义层级：
             * - originalQuestion: 用户原话
             * - rewriteQuestion: rewrite 阶段给出的独立问题
             * - retrievalQuestion: 最终真正拿去检索的问题计划
             * - agentQuestion: 给 Agent 路径使用的增强版问题
             *
             * 三者一起保留，后面排查“为什么检索到了这些结果”时才有足够上下文。
             */
            .originalQuestion(executionPlan.getOriginalQuestion())
            .rewriteQuestion(executionPlan.getRewriteQuestion())
            .rewriteSubQuestions(executionPlan.getRewriteSubQuestions() == null ? List.of() : new ArrayList<>(executionPlan.getRewriteSubQuestions()))
            .retrievalQuestion(executionPlan.getRetrievalQuestion())
            .agentQuestion(executionPlan.getAgentQuestion())
            .intentResolution(executionPlan.getIntentResolution())
            /*
             * historySummary 和 currentDateText 反映的是“前置编排当时看到的上下文”。
             * 如果不把它们记下来，后面就只能看到结论，却看不到结论形成时依赖了什么背景。
             */
            .historySummary(executionPlan.getHistorySummary())
            .longTermSummary(executionPlan.getLongTermSummary())
            .recentHistoryTranscript(executionPlan.getRecentHistoryTranscript())
            .answerRecentTranscript(executionPlan.getAnswerRecentTranscript())
            .answerHistoryContext(executionPlan.getAnswerHistoryContext() == null
                ? ""
                : executionPlan.getAnswerHistoryContext().getRenderedText())
            .answerHistoryFollowUpQuestion(executionPlan.getAnswerHistoryContext() != null
                && executionPlan.getAnswerHistoryContext().isFollowUpQuestion())
            .retrievalAnchorApplied(executionPlan.getRetrievalAnchorContext() != null
                && executionPlan.getRetrievalAnchorContext().isAnchorApplied())
            .retrievalAnchorResolvedQuestion(executionPlan.getRetrievalAnchorContext() == null
                ? ""
                : executionPlan.getRetrievalAnchorContext().getResolvedQuestion())
            .retrievalAnchorRootTopic(executionPlan.getRetrievalAnchorContext() == null
                ? ""
                : executionPlan.getRetrievalAnchorContext().getRootTopic())
            .retrievalAnchorRootSectionCode(executionPlan.getRetrievalAnchorContext() == null
                ? ""
                : executionPlan.getRetrievalAnchorContext().getRootSectionCode())
            .retrievalAnchorRootSectionTitle(executionPlan.getRetrievalAnchorContext() == null
                ? ""
                : executionPlan.getRetrievalAnchorContext().getRootSectionTitle())
            .retrievalAnchorFacet(executionPlan.getRetrievalAnchorContext() == null
                ? ""
                : executionPlan.getRetrievalAnchorContext().getTargetFacet())
            .retrievalAnchorTargetSectionHint(executionPlan.getRetrievalAnchorContext() == null
                ? ""
                : executionPlan.getRetrievalAnchorContext().getTargetSectionHint())
            .retrievalAnchorItemIndex(executionPlan.getRetrievalAnchorContext() == null
                ? null
                : executionPlan.getRetrievalAnchorContext().getReferencedItemIndex())
            .retrievalAnchorItemText(executionPlan.getRetrievalAnchorContext() == null
                ? ""
                : executionPlan.getRetrievalAnchorContext().getReferencedItemText())
            .historyCompressionApplied(executionPlan.isHistoryCompressionApplied())
            .historyCoveredExchangeId(executionPlan.getHistoryCoveredExchangeId())
            .historyCoveredExchangeCount(executionPlan.getHistoryCoveredExchangeCount())
            .historyCompressionCount(executionPlan.getHistoryCompressionCount())
            .currentDateText(executionPlan.getCurrentDateText())
            .requiresFreshSearch(executionPlan.isRequiresFreshSearch())
            .requiresCurrentDateAnchoring(executionPlan.isRequiresCurrentDateAnchoring())
            /*
             * 子问题列表需要显式拷贝，避免后续执行期对原 plan 的修改影响调试快照；
             * 文档范围字段则直接记录单值即可。
             */
            .retrievalSubQuestions(executionPlan.getRetrievalSubQuestions() == null ? List.of() : new ArrayList<>(executionPlan.getRetrievalSubQuestions()))
            .selectedDocumentId(executionPlan.getSelectedDocumentId())
            .selectedTaskId(executionPlan.getSelectedTaskId())
            /*
             * retrievalNotes / usedChannels 会在执行期不断被补充，
             * 所以初始化时先准备空容器，保持调试轨迹对象始终结构完整。
             */
            .retrievalNotes(Collections.synchronizedList(new ArrayList<>()))
            .usedChannels(Collections.synchronizedList(new ArrayList<>()))
            .toolTraces(Collections.synchronizedList(new ArrayList<>()))
            .noEvidenceReply(executionPlan.getNoEvidenceReply())
            .build();
    }

    private ConversationExecutionPlan prepareExecutionPlan(TaskInfo taskInfo) {
        /*
         * 前置编排刻意放在真正执行前、且运行在独立线程上：
         * 1. WebFlux 可以先把 SSE 通道建起来
         * 2. 前端能先看到“正在分析问题上下文”的过程事件
         * 3. 改写 / 检索规划出错时，也能回到统一的流式失败协议
         */
        ConversationExecutionPlan executionPlan = chatPreparationOrchestrator.prepare(
            taskInfo.conversationId(),
            taskInfo.question(),
            taskInfo.chatMode(),
            taskInfo.selectedDocumentId(),
            taskInfo.selectedTaskId(),
            taskInfo.currentDate(),
            taskInfo.currentDateText()
        );
        /*
         * agentQuestion 不是用户原话的替身，而是给 Agent 路径追加的运行时上下文。
         * 它会把当前日期、历史摘要和时效性约束统一拼进去，
         * 保证 ReAct 路径在跨轮对话里也能拿到和 RAG 路径同等级别的上下文信息。
         */
        executionPlan.setAgentQuestion(buildAgentQuestion(executionPlan));
        taskInfo.setExecutionPlan(executionPlan);
        taskInfo.setDebugTrace(initializeDebugTrace(executionPlan));
        taskInfo.runnableConfig().context().put(ChatContextKeys.DEBUG_TRACE, taskInfo.debugTrace());
        return executionPlan;
    }

    /**
     * 把数据库里的业务会话视图和 ReactAgent 的 checkpoint 信息合并成最终返回对象。
     *
     * <p>数据库负责存“业务上要展示的 turn 明细”，
     * checkpoint 负责存“模型继续对话所需的运行记忆”。
     * 两者合并后，接口既能返回完整的问答记录，也能返回当前线程的消息统计。</p>
     */
    private ConversationSessionView toSessionView(ConversationArchiveStore.ConversationArchiveRecord archiveRecord,
                                                  boolean includeMemorySummary,
                                                  boolean includeExchanges) {
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
        List<ConversationExchangeView> archiveExchanges = archiveRecord.exchanges() == null ? List.of() : archiveRecord.exchanges();
        List<ConversationExchangeView> exchanges = includeExchanges ? archiveExchanges : List.of();
        int businessMessageCount = businessMessageCount(archiveExchanges);
        String businessLatestUserMessage = latestExchangeQuestion(archiveExchanges);
        String businessLatestAssistantMessage = latestExchangeAnswer(archiveExchanges);
        ConversationExchangeView latestExchange = latestExchange(archiveExchanges);

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
             * 列表页真正关心的是“这条会话在业务上已经产生了多少可展示消息”，
             * 而不是 Graph checkpoint 当前缓存了多少条内部消息。
             *
             * 因此这里优先使用业务归档 exchanges 计算 messageCount，
             * 只有当业务归档还没有可用消息时，才退回 checkpoint 里的 message 数量做兜底。
             */
            businessMessageCount > 0 ? businessMessageCount : messageList.size(),
            StrUtil.isNotBlank(businessLatestUserMessage) ? businessLatestUserMessage : latestMessage(messageList, MessageType.USER),
            StrUtil.isNotBlank(businessLatestAssistantMessage) ? businessLatestAssistantMessage : latestMessage(messageList, MessageType.ASSISTANT),
            latestExchange == null ? null : latestExchange.getExchangeId(),
            latestExchange == null || latestExchange.getStatus() == null ? "" : latestExchange.getStatus().name(),
            latestExchange == null || latestExchange.getErrorMessage() == null ? "" : latestExchange.getErrorMessage(),
            archiveRecord.chatMode(),
            archiveRecord.selectedDocumentId() == null ? "" : String.valueOf(archiveRecord.selectedDocumentId()),
            archiveRecord.selectedDocumentName(),
            archiveRecord.createdAt(),
            archiveRecord.updatedAt(),
            exchanges,
            includeMemorySummary ? conversationMemoryService.getConversationSummary(archiveRecord.conversationId()) : null
        );
    }

    private ConversationSessionView overlayRuntimeSnapshot(ConversationSessionView sessionView) {
        if (sessionView == null || sessionView.getExchanges() == null || sessionView.getExchanges().isEmpty()) {
            return sessionView;
        }
        Optional<TaskInfo> runtimeOptional = chatRuntimeRegistry.get(sessionView.getConversationId());
        if (runtimeOptional.isEmpty()) {
            return sessionView;
        }
        TaskInfo taskInfo = runtimeOptional.get();
        List<ConversationExchangeView> exchanges = new ArrayList<>(sessionView.getExchanges().size());
        boolean replaced = false;
        for (ConversationExchangeView exchange : sessionView.getExchanges()) {
            if (exchange == null) {
                continue;
            }
            if (exchange.getExchangeId() == taskInfo.exchangeId()) {
                exchanges.add(mergeRuntimeExchange(exchange, taskInfo));
                replaced = true;
                continue;
            }
            exchanges.add(exchange);
        }
        if (!replaced) {
            return sessionView;
        }
        sessionView.setExchanges(exchanges);
        sessionView.setMessageCount(businessMessageCount(exchanges));
        sessionView.setRunning(true);
        sessionView.setUpdatedAt(Instant.now());
        sessionView.setLatestExchangeId(taskInfo.exchangeId());
        sessionView.setLatestTurnStatus(ChatTurnStatus.RUNNING.name());
        String liveAnswer = taskInfo.answerBuffer().toString();
        if (StrUtil.isNotBlank(liveAnswer)) {
            sessionView.setLatestAssistantMessage(liveAnswer);
        }
        return sessionView;
    }

    private ConversationExchangeView mergeRuntimeExchange(ConversationExchangeView exchange,
                                                          TaskInfo taskInfo) {
        return new ConversationExchangeView(
            exchange.getExchangeId(),
            exchange.getQuestion(),
            taskInfo.answerBuffer().toString(),
            snapshotStringList(taskInfo.thinkingSteps()),
            deduplicateReferences(snapshotReferenceList(taskInfo.references())),
            exchange.getRecommendations() == null ? List.of() : exchange.getRecommendations(),
            snapshotUsedTools(taskInfo.usedTools()),
            taskInfo.debugTrace(),
            ChatTurnStatus.RUNNING,
            exchange.getErrorMessage(),
            toNullable(taskInfo.firstResponseTimeMs().get()),
            System.currentTimeMillis() - taskInfo.startTime(),
            exchange.getCreateTime(),
            exchange.getEditTime()
        );
    }

    private KnowledgeDocumentDescriptor resolveSelectedDocument(ChatQueryMode chatMode, String selectedDocumentId) {
        if (chatMode == null) {
            throw new IllegalArgumentException("chatMode 不能为空");
        }
        String normalizedDocumentId = StrUtil.trimToNull(selectedDocumentId);
        if (chatMode == ChatQueryMode.OPEN_CHAT) {
            /*
             * 开放式提问的设计目标是“完全不使用业务知识库文档”。
             * 因此一旦请求里还带着 selectedDocumentId，就直接判为参数冲突，
             * 比悄悄忽略更适合教学项目。
             */
            if (normalizedDocumentId != null) {
                throw new IllegalArgumentException("开放式提问模式下不能传 selectedDocumentId");
            }
            return null;
        }

        /*
         * 文档问答模式要求“每一轮请求自己把文档说清楚”。
         * 这里显式禁止会话级静默继承，
         * 让接口本身就能完整表达这轮回答的边界。
         */
        if (normalizedDocumentId == null) {
            throw new IllegalArgumentException("当前文档问答模式下必须选择一个文档");
        }
        final Long resolvedDocumentId = parseRequiredLong(normalizedDocumentId, "selectedDocumentId");
        return documentKnowledgeService.listRetrievableDocuments().stream()
            .filter(item -> Objects.equals(item.getDocumentId(), resolvedDocumentId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("所选文档当前不可检索: " + normalizedDocumentId));
    }

    private KnowledgeDocumentOptionView toKnowledgeDocumentOptionView(KnowledgeDocumentDescriptor descriptor) {
        return new KnowledgeDocumentOptionView(
            descriptor.getDocumentId() == null ? "" : String.valueOf(descriptor.getDocumentId()),
            descriptor.getDocumentName(),
            descriptor.getKnowledgeScopeName(),
            descriptor.getBusinessCategory(),
            descriptor.getDocumentTags()
        );
    }

    private Long parseRequiredLong(String value, String fieldName) {
        try {
            return Long.parseLong(value);
        }
        catch (NumberFormatException exception) {
            throw new IllegalArgumentException(fieldName + " 非法: " + value, exception);
        }
    }

    private int parsePositiveInt(String value, int defaultValue) {
        if (StrUtil.isBlank(value)) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : defaultValue;
        }
        catch (NumberFormatException exception) {
            throw new IllegalArgumentException("分页参数非法: " + value, exception);
        }
    }

    private String normalizeOptionalText(String value) {
        return StrUtil.isBlank(value) ? null : value.trim();
    }

    private ChatQueryMode parseOptionalChatMode(String value) {
        if (StrUtil.isBlank(value) || "ALL".equalsIgnoreCase(value.trim())) {
            return null;
        }
        try {
            return ChatQueryMode.valueOf(value.trim().toUpperCase());
        }
        catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("chatMode 非法: " + value, exception);
        }
    }

    private ChatTurnStatus parseOptionalTurnStatus(String value) {
        if (StrUtil.isBlank(value) || "ALL".equalsIgnoreCase(value.trim())) {
            return null;
        }
        try {
            return ChatTurnStatus.valueOf(value.trim().toUpperCase());
        }
        catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("turnStatus 非法: " + value, exception);
        }
    }

    private int businessMessageCount(List<ConversationExchangeView> exchanges) {
        if (exchanges == null || exchanges.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (ConversationExchangeView exchange : exchanges) {
            if (exchange == null) {
                continue;
            }
            if (StrUtil.isNotBlank(exchange.getQuestion())) {
                count++;
            }
            if (StrUtil.isNotBlank(exchange.getAnswer())) {
                count++;
            }
        }
        return count;
    }

    private String latestExchangeQuestion(List<ConversationExchangeView> exchanges) {
        if (exchanges == null || exchanges.isEmpty()) {
            return "";
        }
        for (int index = exchanges.size() - 1; index >= 0; index--) {
            ConversationExchangeView exchange = exchanges.get(index);
            if (exchange != null && StrUtil.isNotBlank(exchange.getQuestion())) {
                return exchange.getQuestion();
            }
        }
        return "";
    }

    private String latestExchangeAnswer(List<ConversationExchangeView> exchanges) {
        if (exchanges == null || exchanges.isEmpty()) {
            return "";
        }
        for (int index = exchanges.size() - 1; index >= 0; index--) {
            ConversationExchangeView exchange = exchanges.get(index);
            if (exchange != null && StrUtil.isNotBlank(exchange.getAnswer())) {
                return exchange.getAnswer();
            }
        }
        return "";
    }

    private ConversationExchangeView latestExchange(List<ConversationExchangeView> exchanges) {
        if (exchanges == null || exchanges.isEmpty()) {
            return null;
        }
        for (int index = exchanges.size() - 1; index >= 0; index--) {
            ConversationExchangeView exchange = exchanges.get(index);
            if (exchange != null) {
                return exchange;
            }
        }
        return null;
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
         * 推荐问题只需要最近几轮上下文，不应该再把整条会话全量历史读出来。
         * 因此这里改成真正的“最近窗口查询”。
         */
        return conversationArchiveStore.listRecentExchanges(
            conversationId,
            Math.max(1, chatAgentProperties.getHistoryPreviewTurns())
        );
    }

    private List<ConversationExchangeView> historicalRecentExchanges(TaskInfo taskInfo) {
        return recentExchanges(taskInfo.conversationId()).stream()
            .filter(exchange -> exchange.getExchangeId() != taskInfo.exchangeId())
            .toList();
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

    private void putContextIfNotNull(RunnableConfig runnableConfig, String key, Object value) {
        if (runnableConfig == null || StrUtil.isBlank(key) || value == null) {
            return;
        }
        runnableConfig.context().put(key, value);
    }

    private void putContextIfNotBlank(RunnableConfig runnableConfig, String key, String value) {
        if (runnableConfig == null || StrUtil.isBlank(key) || StrUtil.isBlank(value)) {
            return;
        }
        runnableConfig.context().put(key, value.trim());
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
        stopTask(taskInfo, "会话租约已失效，已停止生成");
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
         * 更深层的语义处理应该交给问题改写阶段，而不是在最入口就偷偷改用户问题。
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

    private String buildAgentQuestion(ConversationExecutionPlan executionPlan) {
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
        builder.append("当前日期是 ").append(executionPlan.getCurrentDateText()).append("，时区为 Asia/Shanghai。\n");

        if (executionPlan.isRequiresCurrentDateAnchoring()) {
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

        if (executionPlan.isRequiresFreshSearch()) {
            /*
             * 一旦当前问题具备强时效性，这里直接明确要求优先联网核实。
             * 这不是最终答案，而是给 Agent 的执行偏好约束。
             */
            builder.append("当前问题需要核实最新外部事实，回答前必须优先调用联网搜索工具。\n");
            builder.append("如果搜索结果里的日期与当前日期不一致，必须明确说明来源日期，不要把旧日期说成今天。\n");
            builder.append("如果无法找到与当前日期匹配的可靠结果，要明确说明不确定性，不要编造最新信息。\n");
        }

        if (StrUtil.isNotBlank(executionPlan.getHistorySummary())) {
            builder.append("\n相关会话背景：\n");
            builder.append(executionPlan.getHistorySummary()).append("\n");
        }

        /*
         * 最后再把用户原问题拼进去。
         * 这样增强信息始终是“系统补充上下文”，而不是把用户原话覆盖掉。
         */
        builder.append("\n用户问题：\n");
        builder.append(executionPlan.getOriginalQuestion());
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

    private List<String> snapshotStringList(List<String> source) {
        synchronized (source) {
            return List.copyOf(source);
        }
    }

    private List<SearchReference> snapshotReferenceList(List<SearchReference> source) {
        synchronized (source) {
            return new ArrayList<>(source);
        }
    }

    private List<String> snapshotUsedTools(Set<String> source) {
        return new ArrayList<>(source);
    }

    private void safeRefreshConversationSummary(String conversationId) {
        try {
            conversationMemoryService.refreshConversationSummaryAsync(conversationId);
        }
        catch (RuntimeException exception) {
            log.warn("刷新会话摘要失败, conversationId={}", conversationId, exception);
        }
    }

}
