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
import org.javaup.ai.chatagent.model.ConversationExchangeDetailView;
import org.javaup.ai.chatagent.model.ConversationExchangeView;
import org.javaup.ai.chatagent.model.KnowledgeDocumentOptionView;
import org.javaup.ai.chatagent.model.ConversationMemorySummaryView;
import org.javaup.ai.chatagent.model.ConversationSessionView;
import org.javaup.ai.chatagent.model.ChannelExecutionView;
import org.javaup.ai.chatagent.model.RetrievalResultView;
import org.javaup.ai.chatagent.model.StageBenchmarkView;
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
import org.javaup.ai.prompt.PromptTemplateNames;
import org.javaup.ai.prompt.PromptTemplateService;
import org.javaup.enums.ChatTurnStatus;
import org.javaup.enums.ChatQueryMode;
import org.javaup.exception.SuperAgentFrameException;
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
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 服务层
 * @author: 阿星不是程序员
 **/

@Slf4j
@AllArgsConstructor
@Service
public class BusinessChatService {//整个对话系统的总指挥
    /*
设计思路
虽然 BusinessChatService 依赖的组件看起来很多，但每个组件的职责都很单一。这种"轻度的总控 + 厚度的组件"的设计，让每个组件都可以独立测试和替换，总控只负责把它们串起来。
     */
    // BusinessChatService 的依赖注入（共 14 个组件）
    private static final ZoneId CHAT_ZONE_ID = ZoneId.of("Asia/Shanghai");
    private static final String CHAT_RUNNING_LEASE_PREFIX = "chat:running:";//租约 key 前缀
    private static final Duration CHAT_RUNNING_LEASE_TTL = Duration.ofSeconds(30);//租约过期时间
    private static final Duration CHAT_RUNNING_LEASE_RENEW_INTERVAL = Duration.ofSeconds(10);//续期间隔 10秒
    // Agent 相关
    private final ReactAgent businessChatReactAgent;// 底层 ReactAgent
    private final ChatCheckpointManager checkpointManager;// Checkpoint 管理
    // 配置
    private final ChatAgentProperties chatAgentProperties;// 聊天配置
    // 会话生命周期
    private final ConversationArchiveStore conversationArchiveStore;// 归档存储
    private final ChatRuntimeRegistry chatRuntimeRegistry;// 运行态注册表
    private final ConversationMemoryService conversationMemoryService; // 会话记忆
    // 输出与推荐
    private final RecommendationService recommendationService;// 推荐追问
    private final StreamEventWriter streamEventWriter; // SSE 事件写入
    // 分布式控制
    private final RedisLeaseManager redisLeaseManager; // Redis 租约
    // 执行编排
    private final ChatPreparationOrchestrator chatPreparationOrchestrator;// 编排器
    private final ConversationExecutorRegistry conversationExecutorRegistry;// 执行器注册表

    // 观测与追踪
    private final ConversationTraceStageStore conversationTraceStageStore;// 阶段追踪存储
    private final RetrievalObserveStore retrievalObserveStore;// 检索观察存储
    private final StageBenchmarkService stageBenchmarkService; // 阶段基准

    private final PromptTemplateService promptTemplateService;
    // 文档知识
    private final DocumentKnowledgeService documentKnowledgeService;// 文档知识服务






    /*
    主流程方法
     */
    public Flux<String> openConversationStream(ChatRequestDto request) {//对外暴露的总入口，用Flux.defer()包装

        return Flux.defer(() -> openDeferredConversationStream(request));
    }

    private Flux<String> openDeferredConversationStream(ChatRequestDto request) {//真正编排逻辑：构建计划——>抢租约——>引导会话

        log.info("======request内容：{}", JSON.toJSONString(request));
        StreamLaunchPlan launchPlan = null;
        boolean leaseClaimed = false;
        try {

            launchPlan = buildLaunchPlan(request);

            leaseClaimed = claimConversationLease(launchPlan);
            if (!leaseClaimed) {//如果抢占失败，说明这个会话已经有一个任务在跑了，直接返回拒绝流 //租约抢占失败，返回错误提示
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



    /*
    构建启动计划：buildLaunchPlan
    真正的启动逻辑在 openDeferredConversationStream() 里，第一步就是把前端传来的参数规范化，转成内部使用的 StreamLaunchPlan 对象：
    把外部请求转换成内部启动计划。
 * <p>
 * 这一步会完成问题与会话 ID 规范化、聊天模式解析、所选文档校验，以及时间锚点准备。
 * </p>
这一步做的事情不复杂，但很重要——它把外部不可控的前端参数，转换成了内部稳定、可信赖的数据结构。后续所有环节都基于这个 StreamLaunchPlan 来工作。
     */
    private StreamLaunchPlan buildLaunchPlan(ChatRequestDto request) {//参数标准化，文档解析，生成租约凭证

        // 先校验并规整用户问题，确保下游不会处理空白问题。
        String question = normalizeQuestion(request.getQuestion());

        // conversationId 允许前端不传；如果不传则为新会话自动生成一个稳定 ID。
        String conversationId = normalizeConversationId(request.getConversationId());
        ChatQueryMode chatMode = parseRequiredChatMode(request.getChatMode());

        // 在当前文档问答模式下，这里会校验 selectedDocumentId 是否合法、是否可检索。
        KnowledgeDocumentDescriptor selectedDocument = resolveSelectedDocument(chatMode, request.getSelectedDocumentId());

        // 当前日期会被写入 prompt 和上下文，作为处理“今天/最新/本周”等相对时效语义的统一基准。
        LocalDate currentDate = LocalDate.now(CHAT_ZONE_ID);
        String currentDateText = formatCurrentDate(currentDate);
        return new StreamLaunchPlan(
                question,
                conversationId,
                chatMode,
                selectedDocument == null ? null : selectedDocument.getDocumentId(),
                selectedDocument == null ? "" : selectedDocument.getDocumentName(),
                selectedDocument == null ? null : selectedDocument.getLastIndexTaskId(),

                // 每个会话共用一个运行租约键，用来防止并发生成。
                buildChatLeaseKey(conversationId),

                // ownerToken 代表本次请求对租约的“所有权”，续期和释放时都靠它校验。
                UUID.randomUUID().toString(),
                currentDate,
                currentDateText
        );
    }


    //请求开始时获取租约
    /*
    抢占分布式租约
    启动计划构建好之后，紧接着就是抢占 Redis 分布式租约。这是为了保证同一个会话在任意时刻只有一个生成任务在运行：

     */
    // BusinessChatService.java —— 抢占租约
    private boolean claimConversationLease(StreamLaunchPlan launchPlan) {//抢占Redis分布式租约（30秒TTL）

        // 用 Redis 实现分布式锁，TTL 30 秒，后续会定期续期
        return redisLeaseManager.acquire(
                launchPlan.getLeaseKey(), // 键：chat:running:{conversationId}
                launchPlan.getLeaseOwnerToken(),// 值：本次请求的唯一 token
                CHAT_RUNNING_LEASE_TTL// 过期时间：30 秒
        );
    }



    /*
    Bootstrap：创建轮次、构建 TaskInfo、注册运行态
拿到租约之后，进入 bootstrapConversation()，这一步要做三件事：
1.在数据库里创建一条新的轮次（exchange）记录
2.构建 TaskInfo 运行时上下文对象
3.把任务注册到内存运行态注册表
     */
    /*
    // BusinessChatService.java —— 会话 bootstrap
/**
 * 对会话做启动前置处理。
 * <p>
 * 包括创建一条新的 exchange 归档记录、构建运行时任务对象、注册到运行时注册表，并把 SSE 通道与任务绑定。
 * </p>
 *
 * @param launchPlan 已规范化后的启动计划
 * @return bootstrap 结果；可能是可执行的流，也可能是一个拒绝原因
 */

    private BootstrapResult bootstrapConversation(StreamLaunchPlan launchPlan) {//创建交换记录，构建TaskInfo，注册到Registry

        // exchangeView 表示本次问答轮次的归档记录，后续无论成功还是失败都依赖它进行收尾落库。
        ConversationExchangeView exchangeView = null;
        try {

            // 一旦启动流程开始，就先在归档层生成一条“新轮次”，这样后续异常也能被定位到具体 exchange。
            exchangeView = conversationArchiveStore.startExchange(
                launchPlan.getConversationId(),
                launchPlan.getQuestion(),
                launchPlan.getChatMode(),
                launchPlan.getSelectedDocumentId(),
                launchPlan.getSelectedDocumentName()
            );

            // TaskInfo 聚合了本次会话运行所需的所有状态：SSE sink、trace、引用、上下文等。
            TaskInfo taskInfo = createTaskInfo(launchPlan, exchangeView);

            if (!chatRuntimeRegistry.register(taskInfo)) {

                // 极端情况下，即使抢到租约，也可能在运行态注册时发现已有同会话任务占用，必须补偿性收尾。
                failBootstrappedExchange(launchPlan.getConversationId(), exchangeView.getExchangeId(), "该会话当前正在执行中，请稍后再试");

                releaseLeaseQuietly(launchPlan.getLeaseKey(), launchPlan.getLeaseOwnerToken());
                return BootstrapResult.rejected("该会话当前正在执行中，请稍后再试");
            }

            // 只有在归档、运行态、SSE 通道都准备好之后，才把流返回给上层。
            return BootstrapResult.ready(bindClientChannel(taskInfo));
        }
        catch (RuntimeException exception) {

            // bootstrap 过程中只要失败，就先释放租约，再把已经创建的轮次标记为失败，避免悬空数据。
            releaseLeaseQuietly(launchPlan.getLeaseKey(), launchPlan.getLeaseOwnerToken());
            if (exchangeView != null) {

                failBootstrappedExchange(launchPlan.getConversationId(), exchangeView.getExchangeId(), buildErrorMessage(exception));
            }
            return BootstrapResult.rejected(buildErrorMessage(exception));
        }
    }


    /*
    TaskInfo是怎么组装出来的
    TaskInfo不是简单new出来的，他的构建涉及SSE通道初始化，Agent运行配置，上下文注入等一系列操作
     */
    // BusinessChatService.java —— 构建运行时任务对象
    /**
     * 构建一个运行中的任务快照对象。
     * <p>
     * 这里会初始化 SSE sink、RunnableConfig、调试追踪对象、引用集合、工具集合等上下文，
     * 后续执行链路中的各个组件都会围绕这个 {@link TaskInfo} 协作。
     * </p>
     * 这里有个很重要的设计：RunnableConfig.context() 是一个共享的 Map，执行链路中的各个组件（工具、检索器、追踪器）都通过它来读写运行时状态。这样就不需要在每个方法签名里传一堆参数，所有组件都能通过 context 拿到自己需要的东西。
     */
    private TaskInfo createTaskInfo(StreamLaunchPlan launchPlan, ConversationExchangeView exchangeView) {//组装完整的任务上下文对象

        // 每个会话只有一个单播 sink，确保一条流只服务当前订阅的前端连接。
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();

        // RunnableConfig 是底层 ReactAgent 和 checkpoint 体系识别当前线程上下文的关键对象。
        RunnableConfig runnableConfig = buildSessionConfig(launchPlan.getConversationId());

        // 这些集合会在执行过程中不断追加内容，因此使用线程安全容器保存运行态快照。
        List<String> thinkingSteps = Collections.synchronizedList(new ArrayList<>());
        List<SearchReference> references = Collections.synchronizedList(new ArrayList<>());
        Set<String> usedTools = ConcurrentHashMap.newKeySet();
        String traceId = UUID.randomUUID().toString().replace("-", "");
        // traceRecorder 负责把执行过程切成多个阶段，并记录每一阶段的状态、耗时和附加信息。
        ConversationTraceRecorder traceRecorder = new ConversationTraceRecorder(
            conversationTraceStageStore,
            retrievalObserveStore,
            launchPlan.getConversationId(),
            exchangeView.getExchangeId(),
            traceId
        );
        // eventMetadata 会被放进每条 SSE 事件中，方便前端知道事件归属哪个会话、哪个轮次。
        StreamEventMetadata eventMetadata = new StreamEventMetadata(
            launchPlan.getConversationId(),
            exchangeView.getExchangeId()
        );

        // 下方这些 context 值会被执行链路中的工具、检索器、追踪器共同读取。
        runnableConfig.context().put(ChatContextKeys.EVENT_SINK, sink);
        runnableConfig.context().put(ChatContextKeys.EVENT_METADATA, eventMetadata);
        runnableConfig.context().put(ChatContextKeys.THINKING_STEPS, thinkingSteps);
        runnableConfig.context().put(ChatContextKeys.REFERENCES, references);
        runnableConfig.context().put(ChatContextKeys.USED_TOOLS, usedTools);
        runnableConfig.context().put(ChatContextKeys.TRACE_ID, traceId);

        runnableConfig.context().put(ChatContextKeys.QUESTION, launchPlan.getQuestion());

        runnableConfig.context().put(ChatContextKeys.CHAT_MODE, launchPlan.getChatMode().name());

        // 把“当前日期”和它的文本形式显式注入上下文，是为了让相对时间问题有统一的锚点。
        runnableConfig.context().put(ChatContextKeys.CURRENT_DATE, launchPlan.getCurrentDate().toString());
        runnableConfig.context().put(ChatContextKeys.CURRENT_DATE_TEXT, launchPlan.getCurrentDateText());

        // 文档问答模式下，所选文档和索引任务信息也会随上下文透传。
        putContextIfNotNull(runnableConfig, ChatContextKeys.SELECTED_DOCUMENT_ID, launchPlan.getSelectedDocumentId());
        putContextIfNotBlank(runnableConfig, ChatContextKeys.SELECTED_DOCUMENT_NAME, launchPlan.getSelectedDocumentName());
        putContextIfNotNull(runnableConfig, ChatContextKeys.SELECTED_TASK_ID, launchPlan.getSelectedTaskId());

        // 在真正生成 executionPlan 之前，先放一个空白调试轨迹，保证链路中随时都能读取到 debugTrace。
        ChatDebugTrace debugTrace = initializeDebugTrace(null);
        runnableConfig.context().put(ChatContextKeys.DEBUG_TRACE, debugTrace);

        return new TaskInfo(
            launchPlan.getConversationId(),
            exchangeView.getExchangeId(),
            launchPlan.getQuestion(),
            launchPlan.getChatMode(),
            traceId,
            launchPlan.getSelectedDocumentId(),
            launchPlan.getSelectedDocumentName(),
            launchPlan.getSelectedTaskId(),
            launchPlan.getCurrentDate(),
            launchPlan.getCurrentDateText(),
            null,
            debugTrace,
            runnableConfig,
            traceRecorder,
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


    /*
    绑定SSE通道与激活生成
    Bootstrap 完成后，bindClientChannel() 把 TaskInfo 的内部 sink 转成前端可消费的 Flux：
     */
    // BusinessChatService.java —— 绑定客户端通道
    /**
     * 把任务的内部 sink 绑定成可返回给前端的 Flux。
     * <p>
     * 只有前端真正订阅时，才会触发生成任务启动；如果前端断开订阅，则主动停止当前任务。
     * </p>
     * 这里的 doOnSubscribe 是整条链路的"点火开关"——前端建立 SSE 连接的那一刻，后端才真正开始干活。
     */
    private Flux<String> bindClientChannel(TaskInfo taskInfo) {//把Sink转成Flux，绑定订阅和取消回调

        return taskInfo.sink().asFlux()

                // 前端真正订阅时，才异步启动生成任务
            .doOnSubscribe(ignored -> activateGeneration(taskInfo))

                // 前端断开连接时，主动中断后台生成，避免资源空转
            .doOnCancel(() -> stopTask(taskInfo, "客户端已取消请求"));
    }

    //activateGeneration() 做两件事：启动租约续期、启动执行链路：
    // BusinessChatService.java —— 激活生成
    private void activateGeneration(TaskInfo taskInfo) {//启动租约续期，订阅执行流
        try {
            if (taskInfo.finalized().get()) {
                return;// 任务已被其他线程结束，不再重复启动
            }

            // 长时间执行的会话需要定期续租，否则别的节点会认为任务已失效
            Disposable leaseRenewalDisposable = startLeaseRenewal(taskInfo);
            taskInfo.setLeaseRenewalDisposable(leaseRenewalDisposable);
            if (taskInfo.finalized().get() && !leaseRenewalDisposable.isDisposed()) {
                leaseRenewalDisposable.dispose();
                return;
            }

            // 真正的生成执行链路在这里启动订阅
            Disposable disposable = buildConversationExecution(taskInfo).subscribe();

            taskInfo.setDisposable(disposable);
            // 如果任务在刚启动后立即被标记为结束，主动 dispose
            if (taskInfo.finalized().get() && !disposable.isDisposed()) {
                disposable.dispose();
            }
        }
        catch (RuntimeException exception) {

            finishWithFailure(taskInfo, exception);
        }
    }


    /*
    核心：组装执行流 buildConversationExecution
    这是整条链路最关键的方法，把"准备执行计划 → 选择执行器 → 消费模型输出 → 收尾"串成一条完整的响应式流：
     */
    // BusinessChatService.java —— 组装完整的对话执行流
    /**
     * 组装完整的对话执行流。
     * <p>
     * 链路大致分为：发送“正在分析”提示 -> 准备执行计划 -> 按计划选择执行器 -> 消费模型输出 ->
     * 正常完成时收尾，异常时失败收尾。
     * </p>
     */
    private Flux<String> buildConversationExecution(TaskInfo taskInfo) {//准备执行计划——>选择执行器——>执行
        return Flux.defer(() -> {

                    // 先给前端发一个"分析中"的状态事件，让用户知道系统在工作
                safeEmit(taskInfo.sink(), streamEventWriter.thinking("正在分析问题上下文。", taskInfo.eventMetadata()));
                return Mono.fromCallable(() -> prepareExecutionPlan(taskInfo))
                        // 计划准备包含检索、压缩历史、读取摘要等 IO 操作，放到弹性线程池
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMapMany(plan -> {
                        // 根据编排结果选择合适的执行器
                        ConversationExecutor executor = conversationExecutorRegistry.get(plan.getMode());
                        return executor.execute(taskInfo);
                    });
            })
            .publishOn(Schedulers.boundedElastic())
                // 模型每产生一个增量片段，实时追加到缓冲区并推送给前端

            .doOnNext(chunk -> emitModelChunk(taskInfo, chunk))
                // 任意异常进入统一失败收尾
            .doOnError(error -> finishWithFailure(taskInfo, error))
                // 正常完成时执行成功收尾
            .doOnComplete(() -> finishSuccessfully(taskInfo));
    }













    /*
    生命周期终结方法（成功收尾）
    模型输出完毕后，doOnComplete 触发成功收尾逻辑。这一步要做的事情不少：
     */
    // BusinessChatService.java —— 成功收尾
    /**
     * 处理会话成功完成时的统一收尾。
     * <p>
     * 这个方法位于“模型正文已经正常输出完成”之后，是一次成功对话真正结束前的最后一道总收口。
     * 它承担的不是单一动作，而是一整套按顺序执行的完成态闭环：
     * 1. 通过 CAS 把任务标记为 finalized，确保成功收尾只执行一次；
     * 2. 从运行态缓冲区中冻结最终答案、引用和推荐追问所需的数据快照；
     * 3. 在追踪体系中开启 finalize/recommendation 阶段，便于调试和耗时分析；
     * 4. 生成或提取推荐追问，并把推荐阶段标记为完成；
     * 5. 向前端补发“引用”和“推荐追问”事件；
     * 6. 关闭 SSE 流，告诉前端这一轮输出已经彻底结束；
     * 7. 以 {@link ChatTurnStatus#COMPLETED} 状态把最终结果完整落库；
     * 8. 异步刷新会话摘要，并清理租约、订阅、运行态注册表等临时资源。
     * </p>
     * <p>
     * 这里的顺序不能随意打乱。特别是：
     * “补发事件”必须发生在“关闭 SSE 流”之前，否则前端会收不到引用和推荐；
     * “落库和清理”要放在 finally 中，保证即使补发事件失败，也不会让会话停留在未收尾状态。
     * </p>
     * ————————————————————————————————————————————————————————————————————
     * 收尾阶段的事件推送顺序是：正文 → 引用 → 推荐追问 → 关闭流。前端按这个顺序依次渲染，用户先看到答案，再看到引用来源和推荐的下一步问题。
     */
    private void finishSuccessfully(TaskInfo taskInfo) {//正常完成：生成推荐问题，发送引用，归档，清理
        // finalized 从 false 置为 true 说明当前线程拿到了“唯一一次成功收尾权”；
        // 如果这里失败，表示别的线程已经做过停止/失败/成功收尾，本次直接退出避免重复落库。
        if (!taskInfo.finalized().compareAndSet(false, true)) {
            return;
        }

        // answer 是最终要持久化的完整回答文本；
        // uniqueReferences 先对运行态引用做快照再去重，避免后续落库和前端展示出现重复证据。
        String answer = taskInfo.answerBuffer().toString();
        List<SearchReference> uniqueReferences = deduplicateReferences(snapshotReferenceList(taskInfo.references()));
        // finalizeStage 用来观测“成功收尾”本身的耗时和状态，而不是正文生成耗时。
        ConversationTraceRecorder.StageHandle finalizeStage = taskInfo.traceRecorder() == null
                ? null
                : taskInfo.traceRecorder().startStage(
                org.javaup.ai.chatagent.model.trace.ConversationTraceStageCode.FINALIZE,
                taskInfo.executionPlan() == null || taskInfo.executionPlan().getMode() == null ? "" : taskInfo.executionPlan().getMode().name(),
                "正在收尾已完成会话。",
                null
        );
        // recommendationStage 单独拆出来，是因为推荐追问生成可能本身就是一个有成本、可失败、可观测的子阶段。
        ConversationTraceRecorder.StageHandle recommendationStage = taskInfo.traceRecorder() == null
                ? null
                : taskInfo.traceRecorder().startStage(
                org.javaup.ai.chatagent.model.trace.ConversationTraceStageCode.RECOMMENDATION,
                taskInfo.executionPlan() == null || taskInfo.executionPlan().getMode() == null ? "" : taskInfo.executionPlan().getMode().name(),
                "正在生成推荐追问。",
                null
        );
        List<String> recommendations;
        if (taskInfo.executionPlan() != null
                && taskInfo.executionPlan().getMode() == org.javaup.ai.chatagent.rag.model.ExecutionMode.CLARIFICATION) {
            // 澄清模式下，“推荐追问”本质上就是编排阶段已经产出的澄清选项；
            // 这里直接复用，避免再调用推荐服务生成一批与澄清问题语义不一致的新建议。
            recommendations = taskInfo.executionPlan().getClarificationOptions() == null
                    ? List.of()
                    : new ArrayList<>(taskInfo.executionPlan().getClarificationOptions());
        }
        else {
            // 常规问答模式下，再基于“原问题 + 最终答案 + 最近历史轮次”生成后续建议，
            // 这样推荐内容能更贴近本轮回答结果，而不是只基于用户问题孤立生成。
            recommendations = recommendationService.generateRecommendations(
                    taskInfo.question(),
                    answer,
                    historicalRecentExchanges(taskInfo),
                    taskInfo.traceRecorder()
            );
        }
        if (taskInfo.traceRecorder() != null) {
            // 推荐阶段到这里就算结束，无论这些推荐稍后能否成功发到前端，
            // 至少“推荐内容本身已经生成出来”这一事实要被追踪记录下来。
            taskInfo.traceRecorder().completeStage(recommendationStage, "推荐追问生成完成。", Map.of(
                    "recommendationCount", recommendations.size(),
                    "recommendations", recommendations
            ));
        }

        try {
            // 正文流结束后再补发引用，前端可以按“正文 -> 引用”的顺序稳定渲染，
            // 避免引用事件插入正文中间导致页面状态机更复杂。
            if (!uniqueReferences.isEmpty()) {
                safeEmit(taskInfo.sink(), streamEventWriter.references(uniqueReferences, taskInfo.eventMetadata()));
            }
            // 推荐追问放在引用之后发送，表示“本轮回答及其证据已经给齐，接下来给你下一步建议”。
            if (!recommendations.isEmpty()) {
                safeEmit(taskInfo.sink(), streamEventWriter.recommendations(recommendations, taskInfo.eventMetadata()));
            }
        }
        catch (RuntimeException exception) {
            // 这里故意只记 warn，不把会话整体改判为失败；
            // 因为正文已经成功完成，补发事件失败不应推翻“本轮回答成功生成”这一主结果。
            log.warn("补发引用或推荐事件失败, conversationId={}, exchangeId={}", taskInfo.conversationId(), taskInfo.exchangeId(), exception);
        }
        finally {
            try {
                // 无论补发事件成功与否，都要主动关闭 SSE 流，明确告诉前端“这一轮已经结束”。

                safeComplete(taskInfo.sink());
            }
            catch (RuntimeException exception) {
                log.warn("关闭成功完成的 SSE 流失败, conversationId={}, exchangeId={}", taskInfo.conversationId(), taskInfo.exchangeId(), exception);
            }
            try {
                // 先把运行期间累积的模型调用、工具调用等统计补写进 debugTrace，
                // 再统一把本轮结果以 COMPLETED 状态落库，保证数据库里存的是最终完整快照。
                refreshDebugTraceRuntimeStats(taskInfo);
                conversationArchiveStore.completeExchange(
                        taskInfo.conversationId(),
                        taskInfo.exchangeId(),
                        answer,
                        // thinkingSteps / references / tools 都在收尾时取快照，避免并发修改影响持久化结果。
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
                if (taskInfo.traceRecorder() != null) {
                    taskInfo.traceRecorder().completeStage(finalizeStage, "会话已按完成状态收尾。", Map.of(
                            "finalStatus", ChatTurnStatus.COMPLETED.name(),
                            "referenceCount", uniqueReferences.size(),
                            "recommendationCount", recommendations.size(),
                            "answerLength", answer.length()
                    ));
                }
            }
            catch (RuntimeException exception) {
                // 到这里说明“回答是成功生成的”，但“成功态收尾”失败了，通常是落库或调试追踪更新失败；
                // 因此日志级别提升为 error，方便排查为什么用户看到了回答但后台没有完整持久化。
                log.error("成功会话收尾落库失败, conversationId={}, exchangeId={}", taskInfo.conversationId(), taskInfo.exchangeId(), exception);
                if (taskInfo.traceRecorder() != null) {
                    taskInfo.traceRecorder().failStage(finalizeStage, "完成态收尾失败。", exception.getMessage(), null);
                }
            }
            finally {
                // 这里无论前面的“补发事件”“关闭流”“落库”是否成功，都必须做最终清理：
                // 1. 尝试刷新长期摘要，让后续轮次能拿到最新上下文；
                // 2. 释放租约、订阅和运行态注册，避免同一个 conversationId 被永久占用。
                safeRefreshConversationSummary(taskInfo.conversationId());
                cleanup(taskInfo);
            }
        }
    }

    private void finishWithFailure(TaskInfo taskInfo, Throwable error) {//异常完成：发送错误事件，归档为失败，清理
        if (!taskInfo.finalized().compareAndSet(false, true)) {
            return;
        }

        String errorMessage = buildErrorMessage(error);
        ConversationTraceRecorder.StageHandle finalizeStage = taskInfo.traceRecorder() == null
                ? null
                : taskInfo.traceRecorder().startStage(
                org.javaup.ai.chatagent.model.trace.ConversationTraceStageCode.FINALIZE,
                taskInfo.executionPlan() == null || taskInfo.executionPlan().getMode() == null ? "" : taskInfo.executionPlan().getMode().name(),
                "正在收尾失败会话。",
                null
        );

        log.error("会话执行失败, conversationId={}, exchangeId={}, error={}",
                taskInfo.conversationId(),
                taskInfo.exchangeId(),
                errorMessage,
                error);

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
                refreshDebugTraceRuntimeStats(taskInfo);
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
                if (taskInfo.traceRecorder() != null) {
                    taskInfo.traceRecorder().completeStage(finalizeStage, "会话已按失败状态收尾。", Map.of(
                            "finalStatus", ChatTurnStatus.FAILED.name(),
                            "errorMessage", errorMessage,
                            "answerLength", taskInfo.answerBuffer().length()
                    ));
                }
            }
            catch (RuntimeException exception) {
                log.error("失败会话收尾落库失败, conversationId={}, exchangeId={}", taskInfo.conversationId(), taskInfo.exchangeId(), exception);
                if (taskInfo.traceRecorder() != null) {
                    taskInfo.traceRecorder().failStage(finalizeStage, "失败态收尾失败。", exception.getMessage(), null);
                }
            }
            finally {
                safeRefreshConversationSummary(taskInfo.conversationId());
                cleanup(taskInfo);
            }
        }
    }

    public ConversationStopVo stopConversation(String conversationId, String reason) {//用户停止：中断Agent，释放订阅，归档为停止
        Optional<TaskInfo> taskInfoOptional = chatRuntimeRegistry.get(conversationId);
        if (taskInfoOptional.isEmpty()) {
            return new ConversationStopVo(conversationId, false, "没有找到正在执行的会话");
        }
        return stopTask(taskInfoOptional.get(), reason);
    }


    /*
    资源清理：cleanup
    最后一部是释放所有运行态资源

     */
    // BusinessChatService.java —— 清理资源
    private void cleanup(TaskInfo taskInfo) {//最终清理：释放租约，移除注册，释放Disposable

        // 停掉租约续期定时任务
        Disposable disposable = taskInfo.disposable();
        Disposable leaseRenewalDisposable = taskInfo.leaseRenewalDisposable();


        if (leaseRenewalDisposable != null && !leaseRenewalDisposable.isDisposed()) {

            leaseRenewalDisposable.dispose();
        }

        // 停掉业务执行流
        if (disposable != null && !disposable.isDisposed()) {

            disposable.dispose();
        }

        // 释放 Redis 租约，其他节点或后续请求才能再次启动同一个会话
        releaseLeaseQuietly(taskInfo.leaseKey(), taskInfo.leaseOwnerToken());

        // 从运行时注册表移除当前任务
        chatRuntimeRegistry.remove(taskInfo.conversationId(), taskInfo);
    }











    /*
    租约管理方法
    启动定时续期（Flux.interval 每10秒）定时续期
     */
    private Disposable startLeaseRenewal(TaskInfo taskInfo) {//每10秒续期一次租约，返回Disposable

        // 每隔 10 秒触发一次续期
        return Flux.interval(CHAT_RUNNING_LEASE_RENEW_INTERVAL, CHAT_RUNNING_LEASE_RENEW_INTERVAL)

                .subscribe(ignored -> renewLeaseOrStop(taskInfo), error ->
                        log.warn("租约续期任务出现异常, conversationId={}, exchangeId={}",
                                taskInfo.conversationId(),
                                taskInfo.exchangeId(),
                                error)
                );
    }

    //续期失败时自动停止任务 -----续期失败自动停止
    // BusinessChatService.java —— 续期或停止
    // 这个"续期失败就自动停止"的机制很关键——它保证了系统不会出现"租约已经过期但任务还在跑"的幽灵状态。
    private void renewLeaseOrStop(TaskInfo taskInfo) {//续期失败时自动停止任务

        // 尝试续期
        boolean renewed = redisLeaseManager.renew(
                taskInfo.leaseKey(),
                taskInfo.leaseOwnerToken(),
                CHAT_RUNNING_LEASE_TTL
        );
        if (renewed) {

            return;// 续期成功，继续执行
        }

        // 续期失败，说明租约已经被别人抢走或者 Redis 出了问题
        log.warn("会话租约续期失败，准备停止当前会话, conversationId={}, exchangeId={}",
                taskInfo.conversationId(),
                taskInfo.exchangeId());
        // 先停掉续期定时器自身，避免重复触发
        Disposable leaseRenewalDisposable = taskInfo.leaseRenewalDisposable();
        if (leaseRenewalDisposable != null && !leaseRenewalDisposable.isDisposed()) {
            leaseRenewalDisposable.dispose();
        }
        // 主动停止当前会话
        stopTask(taskInfo, "会话租约已失效，已停止生成");
    }

    //cleanup 时静默释放
    private void releaseLeaseQuietly(String leaseKey, String leaseOwnerToken) {//静默释放租约，吞掉异常
        try {

            redisLeaseManager.release(leaseKey, leaseOwnerToken);
        }
        catch (RuntimeException exception) {

            log.warn("释放会话租约时出现异常, leaseKey={}", leaseKey, exception);
        }
    }











    /*
    辅助方法（挑几个重要的）  编排器：prepareExecutionPlan  执行计划的生成由 ChatPreparationOrchestrator 负责，它是整条链路的"大脑"，决定了这次问答到底走哪条路。
     */
    // BusinessChatService.java —— 准备执行计划
    /**
     * 准备本轮会话真正执行所需的编排计划。
     * <p>
     * 这一层会调用编排器分析历史上下文、决定执行模式、构造 agentQuestion，并在必要时刷新会话绑定的文档范围。
     * </p>
     */
    private ConversationExecutionPlan prepareExecutionPlan(TaskInfo taskInfo) {//委托给 ChatPreparationOrchestrator 准备执行计划

        // 编排器会综合问题、历史、摘要、文档选择等信息生成本轮执行计划。
        ConversationExecutionPlan executionPlan = chatPreparationOrchestrator.prepare(taskInfo);

        // agentQuestion 是最终喂给 Agent 的问题文本，会补充时间锚点和上下文摘要。
        executionPlan.setAgentQuestion(buildAgentQuestion(executionPlan));
        if (executionPlan.getSelectedDocumentId() != null
                && !Objects.equals(executionPlan.getSelectedDocumentId(), taskInfo.selectedDocumentId())) {
            // 如果编排阶段修正了文档范围，需要同步刷新归档中的会话范围与运行上下文。
            conversationArchiveStore.refreshSessionScope(
                    taskInfo.conversationId(),
                    executionPlan.getChatMode(),
                    executionPlan.getSelectedDocumentId(),
                    executionPlan.getSelectedDocumentName()
            );
            putContextIfNotNull(taskInfo.runnableConfig(), ChatContextKeys.SELECTED_DOCUMENT_ID, executionPlan.getSelectedDocumentId());
            putContextIfNotBlank(taskInfo.runnableConfig(), ChatContextKeys.SELECTED_DOCUMENT_NAME, executionPlan.getSelectedDocumentName());
            putContextIfNotNull(taskInfo.runnableConfig(), ChatContextKeys.SELECTED_TASK_ID, executionPlan.getSelectedTaskId());
        }
        // 把最终执行计划和对应的调试轨迹回写到任务对象，供执行链路和收尾阶段复用。
        taskInfo.setExecutionPlan(executionPlan);
        taskInfo.setDebugTrace(initializeDebugTrace(executionPlan));
        taskInfo.runnableConfig().context().put(ChatContextKeys.DEBUG_TRACE, taskInfo.debugTrace());
        return executionPlan;
    }

    private String buildAgentQuestion(ConversationExecutionPlan executionPlan) {//用模板渲染最终发给 Agent 的问题
        return promptTemplateService.render(PromptTemplateNames.AGENT_QUESTION, Map.of(
                "currentDateText", StrUtil.blankToDefault(executionPlan.getCurrentDateText(), ""),
                "requiresCurrentDateAnchoring", executionPlan.isRequiresCurrentDateAnchoring(),
                "requiresFreshSearch", executionPlan.isRequiresFreshSearch(),
                "hasHistorySummary", StrUtil.isNotBlank(executionPlan.getHistorySummary()),
                "historySummary", StrUtil.blankToDefault(executionPlan.getHistorySummary(), ""),
                "question", StrUtil.blankToDefault(executionPlan.getOriginalQuestion(), "")
        ));
    }


    /*
    执行器工作过程中，模型每产出一小段文字，就会通过 Flux 推出来，然后被 emitModelChunk() 处理：
    // BusinessChatService.java —— 处理模型输出的单个增量片段
/**
 * 处理模型输出的单个增量片段。
 * <p>
 * 每收到一个 chunk，就同时做三件事：追加答案缓冲区、记录首包耗时、向前端发送文本事件。
 * </p>
 */

    private void emitModelChunk(TaskInfo taskInfo, String chunk) {//接收模型输出的每个 chunk，追加到 buffer 并推送

        // answerBuffer 持续累积，最终落库时用它拿到完整答案
        taskInfo.answerBuffer().append(chunk);
        // 首包耗时只记录第一次收到正文输出的时刻
        if (taskInfo.firstResponseTimeMs().get() == 0L) {

            taskInfo.firstResponseTimeMs().compareAndSet(0L, System.currentTimeMillis() - taskInfo.startTime());
        }

        // 每个 chunk 都即时推给前端，形成"边生成边展示"的效果
        safeEmit(taskInfo.sink(), streamEventWriter.text(chunk, taskInfo.eventMetadata()));
    }

    public ConversationResetVo resetConversation(String conversationId) {//重置会话：停止→删除所有记录→清理记忆

        ConversationStopVo stopResult = stopConversation(conversationId, "会话被重置");

        ConversationArchiveStore.ConversationRemovalResult removalResult = conversationArchiveStore.deleteSession(conversationId);

        conversationMemoryService.deleteConversationSummary(conversationId);
        conversationTraceStageStore.deleteStages(conversationId);
        retrievalObserveStore.deleteByConversation(conversationId);
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

    /*
    文档校验
    这个方法根据聊天模式来校验 selectedDocumentId 是否合法，不同模式有不同的规则：

     */
    // BusinessChatService.java —— 校验所选文档
    private KnowledgeDocumentDescriptor resolveSelectedDocument(ChatQueryMode chatMode, String selectedDocumentId) {//根据聊天模式校验并解析选中的文档
        if (chatMode == null) {
            throw new IllegalArgumentException("chatMode 不能为空");
        }
        String normalizedDocumentId = StrUtil.trimToNull(selectedDocumentId);
        if (chatMode == ChatQueryMode.OPEN_CHAT) {

            // 开放问答模式不绑定文档，传了 selectedDocumentId 就报错
            if (normalizedDocumentId != null) {
                throw new IllegalArgumentException("开放式提问模式下不能传 selectedDocumentId");
            }
            return null;
        }
        if (chatMode == ChatQueryMode.AUTO_DOCUMENT) {
            // 自动知识问答模式也不允许手动指定文档
            if (normalizedDocumentId != null) {
                throw new IllegalArgumentException("自动知识问答模式下不能传 selectedDocumentId");
            }
            return null;
        }

        // 当前文档问答模式（DOCUMENT）：必须传，而且必须是当前可检索的文档
        if (normalizedDocumentId == null) {
            throw new IllegalArgumentException("当前文档问答模式下必须选择一个文档");
        }
        final Long resolvedDocumentId = parseRequiredLong(normalizedDocumentId, "selectedDocumentId");
        // 只允许命中"当前可检索"的文档，避免引用已下线或不可用的数据源
        return documentKnowledgeService.listRetrievableDocuments().stream()
                .filter(item -> Objects.equals(item.getDocumentId(), resolvedDocumentId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("所选文档当前不可检索: " + normalizedDocumentId));
    }







    private void failBootstrappedExchange(String conversationId, long exchangeId, String errorMessage) {

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

        return Flux.just(streamEventWriter.error(message, new StreamEventMetadata(conversationId, exchangeId)));
    }

    public ConversationStopVo stopConversation(String conversationId) {
        return stopConversation(conversationId, "用户已停止生成");
    }



    private ConversationStopVo stopTask(TaskInfo taskInfo, String reason) {

        if (!taskInfo.finalized().compareAndSet(false, true)) {
            return new ConversationStopVo(taskInfo.conversationId(), false, "会话已经结束");
        }

        Optional<TaskInfo> currentTask = chatRuntimeRegistry.get(taskInfo.conversationId());
        if (currentTask.isPresent() && currentTask.get() != taskInfo) {

            return new ConversationStopVo(taskInfo.conversationId(), false, "会话已由新的执行接管");
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

        String responseMessage = "已停止会话生成";
        ConversationTraceRecorder.StageHandle finalizeStage = taskInfo.traceRecorder() == null
            ? null
            : taskInfo.traceRecorder().startStage(
                org.javaup.ai.chatagent.model.trace.ConversationTraceStageCode.FINALIZE,
                taskInfo.executionPlan() == null || taskInfo.executionPlan().getMode() == null ? "" : taskInfo.executionPlan().getMode().name(),
                "正在收尾停止中的会话。",
                null
            );
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
                refreshDebugTraceRuntimeStats(taskInfo);
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
                if (taskInfo.traceRecorder() != null) {
                    taskInfo.traceRecorder().completeStage(finalizeStage, "会话已按停止状态收尾。", Map.of(
                        "finalStatus", ChatTurnStatus.STOPPED.name(),
                        "reason", reason,
                        "answerLength", taskInfo.answerBuffer().length()
                    ));
                }
            }
            catch (RuntimeException exception) {
                log.error("停止会话落库失败, conversationId={}, exchangeId={}", taskInfo.conversationId(), taskInfo.exchangeId(), exception);
                responseMessage = "会话已停止，收尾落库失败";
                if (taskInfo.traceRecorder() != null) {
                    taskInfo.traceRecorder().failStage(finalizeStage, "停止态收尾失败。", exception.getMessage(), null);
                }
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

    public ConversationExchangeDetailView getExchangeDetail(String conversationId, String exchangeId) {
        long resolvedExchangeId = parseRequiredLong(exchangeId, "exchangeId");
        ConversationSessionView sessionView = getSession(conversationId);
        ConversationExchangeView exchangeView = sessionView.getExchanges().stream()
            .filter(item -> item != null && item.getExchangeId() == resolvedExchangeId)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("轮次不存在: " + exchangeId));
        return new ConversationExchangeDetailView(
            conversationId,
            exchangeView,
            conversationTraceStageStore.listStageViews(conversationId, resolvedExchangeId)
        );
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

    public ConversationMemorySummaryView rebuildConversationSummary(String conversationId) {
        return conversationMemoryService.rebuildConversationSummary(conversationId);
    }








    private String buildErrorMessage(Throwable error) {

        Throwable current = error;
        while (current != null) {

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

                return responseException.getMessage();
            }
            current = current.getCause();
        }

        return error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
    }

    private void refreshDebugTraceRuntimeStats(TaskInfo taskInfo) {
        if (taskInfo == null || taskInfo.debugTrace() == null || taskInfo.traceRecorder() == null) {
            return;
        }
        taskInfo.debugTrace().setModelUsageTraces(taskInfo.traceRecorder().snapshotModelUsageTraces());
        org.javaup.ai.chatagent.model.debug.ChatLimitStats limitStats = taskInfo.traceRecorder().limitStats();
        limitStats.setModelCallsUsed(taskInfo.traceRecorder().snapshotModelUsageTraces().size());
        limitStats.setModelCallsRunLimit(chatAgentProperties.getMaxModelCallsPerRun());
        limitStats.setModelCallsThreadLimit(chatAgentProperties.getMaxModelCallsPerThread());
        limitStats.setToolCallsUsed(snapshotUsedTools(taskInfo.usedTools()).size());
        limitStats.setToolCallsRunLimit(chatAgentProperties.getMaxToolCallsPerRun());
        limitStats.setToolCallsThreadLimit(chatAgentProperties.getMaxToolCallsPerThread());
        taskInfo.debugTrace().setLimitStats(limitStats);
    }


    private List<SearchReference> deduplicateReferences(List<SearchReference> references) {
        Map<String, SearchReference> unique = new LinkedHashMap<>();

        for (SearchReference reference : references) {
            if (reference == null) {
                continue;
            }
            unique.putIfAbsent(reference.uniqueKey(), reference);
        }
        return new ArrayList<>(unique.values());
    }

    private ChatDebugTrace initializeDebugTrace(ConversationExecutionPlan executionPlan) {
        if (executionPlan == null) {
            return ChatDebugTrace.builder()
                .retrievalNotes(Collections.synchronizedList(new ArrayList<>()))
                .usedChannels(Collections.synchronizedList(new ArrayList<>()))
                .build();
        }
        return ChatDebugTrace.builder()

            .executionMode(executionPlan.getMode() == null ? "" : executionPlan.getMode().name())
            .chatMode(executionPlan.getChatMode())

            .originalQuestion(executionPlan.getOriginalQuestion())
            .rewriteQuestion(executionPlan.getRewriteQuestion())
            .rewriteSubQuestions(executionPlan.getRewriteSubQuestions() == null ? List.of() : new ArrayList<>(executionPlan.getRewriteSubQuestions()))
            .retrievalQuestion(executionPlan.getRetrievalQuestion())
            .agentQuestion(executionPlan.getAgentQuestion())
            .navigationDecision(executionPlan.getNavigationDecision())

            .historySummary(executionPlan.getHistorySummary())
            .longTermSummary(executionPlan.getLongTermSummary())
            .recentHistoryTranscript(executionPlan.getRecentHistoryTranscript())
            .answerRecentTranscript(executionPlan.getAnswerRecentTranscript())
            .answerHistoryContext(executionPlan.getAnswerHistoryContext() == null
                ? ""
                : executionPlan.getAnswerHistoryContext().getRenderedText())
            .answerHistoryFollowUpQuestion(executionPlan.getAnswerHistoryContext() != null
                && executionPlan.getAnswerHistoryContext().isFollowUpQuestion())
            .historyCompressionApplied(executionPlan.isHistoryCompressionApplied())
            .historyCoveredExchangeId(executionPlan.getHistoryCoveredExchangeId())
            .historyCoveredExchangeCount(executionPlan.getHistoryCoveredExchangeCount())
            .historyCompressionCount(executionPlan.getHistoryCompressionCount())
            .currentDateText(executionPlan.getCurrentDateText())
            .requiresFreshSearch(executionPlan.isRequiresFreshSearch())
            .requiresCurrentDateAnchoring(executionPlan.isRequiresCurrentDateAnchoring())

            .retrievalSubQuestions(executionPlan.getRetrievalSubQuestions() == null ? List.of() : new ArrayList<>(executionPlan.getRetrievalSubQuestions()))
            .selectedDocumentId(executionPlan.getSelectedDocumentId())
            .selectedTaskId(executionPlan.getSelectedTaskId())

            .retrievalNotes(Collections.synchronizedList(new ArrayList<>()))
            .usedChannels(Collections.synchronizedList(new ArrayList<>()))
            .toolTraces(Collections.synchronizedList(new ArrayList<>()))
            .noEvidenceReply(executionPlan.getNoEvidenceReply())
            .build();
    }



    private ConversationSessionView toSessionView(ConversationArchiveStore.ConversationArchiveRecord archiveRecord,
                                                  boolean includeMemorySummary,
                                                  boolean includeExchanges) {

        RunnableConfig runnableConfig = RunnableConfig.builder()
            .threadId(archiveRecord.conversationId())
            .build();

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

        return new ConversationSessionView(
            archiveRecord.conversationId(),
            archiveRecord.running(),

            checkpointManager.list(runnableConfig).size(),

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



    /*
    前端传的是字符串（比如 "OPEN_CHAT"），这里负责转成枚举。如果传了个不认识的值，直接抛异常拒绝，不会让非法模式流入后续链路。
     */
    private ChatQueryMode parseOptionalChatMode(String value) {
        // 空值或 "ALL" 表示不过滤（用于列表查询场景）
        if (StrUtil.isBlank(value) || "ALL".equalsIgnoreCase(value.trim())) {
            return null;
        }
        try {
            // 把前端传的字符串转成枚举，大小写不敏感
            return ChatQueryMode.valueOf(value.trim().toUpperCase());
        }
        catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("chatMode 非法: " + value, exception);
        }
    }

    /*
    聊天模式解析
    BusinessChatService.java —— 解析聊天模式

     */
    private ChatQueryMode parseRequiredChatMode(String value) {
        ChatQueryMode chatMode = parseOptionalChatMode(value);
        if (chatMode == null) {
            throw new IllegalArgumentException("chatMode 不能为空");
        }
        return chatMode;
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

        for (int index = messages.size() - 1; index >= 0; index--) {
            Object candidate = messages.get(index);
            if (candidate instanceof AbstractMessage message && message.getMessageType() == type) {
                return message.getText();
            }
        }
        return "";
    }

    private List<ConversationExchangeView> recentExchanges(String conversationId) {

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







    private String buildChatLeaseKey(String conversationId) {

        return CHAT_RUNNING_LEASE_PREFIX + conversationId;
    }

    private Long toNullable(long value) {

        return value > 0 ? value : null;
    }

    private String normalizeQuestion(String question) {
        if (StrUtil.isBlank(question)) {
            throw new SuperAgentFrameException("question 不能为空");
        }

        return question.trim();
    }




    /*
    会话ID规范化
    // BusinessChatService.java —— 规范化 conversationId
    这个设计让前端可以灵活控制：传了 conversationId 就是继续已有会话，不传就是开启新会话。
     */
    private String normalizeConversationId(String conversationId) {
        // 前端传了就直接用（去掉首尾空格）
        if (StrUtil.isNotBlank(conversationId)) {

            return conversationId.trim();
        }

        // 没传就自动生成一个 UUID 作为新会话的 ID
        return UUID.randomUUID().toString().replace("-", "");
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

        SinkEmitHelper.emitNext(sink, payload);
    }

    private void safeComplete(Sinks.Many<String> sink) {

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

    public List<RetrievalResultView> getRetrievalResults(String conversationId, long exchangeId) {
        return retrievalObserveStore.listResults(conversationId, exchangeId);
    }

    public List<ChannelExecutionView> getChannelExecutions(String conversationId, long exchangeId) {
        return retrievalObserveStore.listChannelExecutions(conversationId, exchangeId);
    }

    public List<StageBenchmarkView> getStageBenchmarks() {
        return stageBenchmarkService.listAll();
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
