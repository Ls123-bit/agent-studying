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

/*
所以从架构上看，BusinessChatService 不关心“这个问题最后到底走 RAG、Agent、结构图还是澄清”。它只关心：
1.计划准备好了没有。
2.计划里的模式是什么。
3.注册表能不能根据模式拿到执行器。
4.执行器能不能返回文本流。
 */
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
    第一层延迟：openConversationStream
    openConversationStream 是 Controller 调用的服务入口。它看起来只有一行，但这一行很关键。
     */
    /**
     * 打开对话的流式响应入口。
     * 这里使用 {@link Flux#defer} 的核心目的，是把真正的会话启动动作推迟到客户端订阅时执行，
     * 避免仅仅创建了 Flux 对象，就提前抢占租约、创建轮次或注册运行态任务。
     *
     * @param request 前端提交的会话请求
     * @return 面向前端的 SSE 文本流
     */
    /*
    这里的 Flux.defer 可以理解成“先别干活，等有人真正来消费这个流再说”。
如果没有这一层，方法被调用时就可能提前抢租约、创建轮次、注册运行态任务。但 WebFlux 里的 Flux 本质上是一个流声明，只有被订阅之后才算真正开始跑。defer 的好处就是让启动行为和订阅行为对齐。
这层 defer 保护的是什么？
保护的是“启动阶段的副作用”。比如抢 Redis 租约、写轮次记录、注册运行态任务，这些动作都不应该发生在一个没人消费的流上。
     */
    public Flux<String> openConversationStream(ChatRequestDto request) {//对外暴露的总入口，用Flux.defer()包装

        // 延迟到订阅时再执行启动逻辑，确保每次订阅都触发一次独立、完整的初始化流程。
        return Flux.defer(() -> openDeferredConversationStream(request));
    }


    /*
    openDeferredConversationStream：启动总控
    真正的启动逻辑在 openDeferredConversationStream。这段代码可以看成 openConversationStream 的总控台：先整理请求，再抢租约，然后创建轮次和运行态，最后返回可以被消费的 SSE 输出流。
     */
    /**
     * 执行真正的会话启动流程。
     * 这里会依次完成请求标准化、会话租约抢占、轮次初始化、运行态注册，并最终返回客户端可消费的响应流。
     * @param request 原始请求参数
     * @return 启动成功时返回正常响应流，启动失败时返回仅包含错误事件的响应流
     */
    private Flux<String> openDeferredConversationStream(ChatRequestDto request) {//真正编排逻辑：构建计划——>抢租约——>引导会话

        // 记录原始请求内容，方便排查具体是哪一次提问触发了后续的异常或分支逻辑。
        log.info("======request内容：{}", JSON.toJSONString(request));
        StreamLaunchPlan launchPlan = null;
        boolean leaseClaimed = false;
        try {

            // 把前端传入的松散参数整理成后续执行链统一使用的启动计划对象。
            launchPlan = buildLaunchPlan(request);

            // 启动前先抢占分布式会话租约，防止相同 conversationId 被并发执行。
            leaseClaimed = claimConversationLease(launchPlan);
            if (!leaseClaimed) {//如果抢占失败，说明这个会话已经有一个任务在跑了，直接返回拒绝流 //租约抢占失败，返回错误提示
                return rejectionFlux("该会话当前正在执行中，请稍后再试", launchPlan.getConversationId(), null);
            }

            // 租约拿到后，再去创建轮次记录、注册运行态任务并绑定客户端输出通道。
            BootstrapResult bootstrapResult = bootstrapConversation(launchPlan);
            if (StrUtil.isNotBlank(bootstrapResult.getRejectionMessage())) {
                return rejectionFlux(bootstrapResult.getRejectionMessage(), launchPlan.getConversationId(), null);
            }
            return bootstrapResult.getOutbound();
        }
        catch (RuntimeException exception) {
            // 启动过程中的任何运行时异常都在这里统一兜底，避免前端一直挂起等待响应。
            log.error("会话启动失败, conversationId={}, question={}",
                launchPlan == null ? "" : launchPlan.getConversationId(),
                request.getQuestion(),
                exception);
            if (leaseClaimed && launchPlan != null) {
                // 如果租约已经成功拿到，但后续流程中断，必须释放租约，避免该会话被长时间误判为执行中。
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
    /**
     * 根据前端请求构建启动计划。
     * 该计划会固化会话启动所需的关键字段，包括问题、会话 id、问答模式、文档范围、租约信息和当前日期文本。
     * @param request 原始请求
     * @return 标准化后的启动计划
     */
    private StreamLaunchPlan buildLaunchPlan(ChatRequestDto request) {//参数标准化，文档解析，生成租约凭证

        // 先校验并规整用户问题，确保下游不会处理空白问题。    // 问题不能为空，同时统一去掉首尾空白，避免把脏数据带入提示词。
        String question = normalizeQuestion(request.getQuestion());

        // conversationId 允许前端不传；如果不传则为新会话自动生成一个稳定 ID。 // 如果前端没有带 conversationId，则自动生成一个新的会话 id。
        String conversationId = normalizeConversationId(request.getConversationId());
        ChatQueryMode chatMode = parseRequiredChatMode(request.getChatMode());

        // 在当前文档问答模式下，这里会校验 selectedDocumentId 是否合法、是否可检索。 // 按问答模式解析并校验文档作用域，防止模式与文档参数互相冲突。
        KnowledgeDocumentDescriptor selectedDocument = resolveSelectedDocument(chatMode, request.getSelectedDocumentId());

        // 当前日期会被写入 prompt 和上下文，作为处理“今天/最新/本周”等相对时效语义的统一基准。 // 使用业务时区生成“当前日期”文本，供后续时间锚定和提示词渲染使用。
        LocalDate currentDate = LocalDate.now(CHAT_ZONE_ID);
        String currentDateText = formatCurrentDate(currentDate);
        return new StreamLaunchPlan(
                question,
                conversationId,
                chatMode,
                selectedDocument == null ? null : selectedDocument.getDocumentId(),
                selectedDocument == null ? "" : selectedDocument.getDocumentName(),
                selectedDocument == null ? null : selectedDocument.getLastIndexTaskId(),

                // 每个会话共用一个运行租约键，用来防止并发生成。   // 每个 conversationId 都对应一个独立租约 key，用于全局互斥控制。
                buildChatLeaseKey(conversationId),

                // ownerToken 代表本次请求对租约的“所有权”，续期和释放时都靠它校验。   // ownerToken 用于后续续租和释放时校验“是不是同一个持有者”。
                UUID.randomUUID().toString(),
                currentDate,
                currentDateText
        );
    }


    //请求开始时获取租约
    /*
    抢占分布式租约
    启动计划构建好之后，紧接着就是抢占 Redis 分布式租约。这是为了保证同一个会话在任意时刻只有一个生成任务在运行：
    只要同一个 conversationId 已被别的执行占用，本次请求就必须立即拒绝，避免并发写同一会话。
    @param launchPlan 启动计划
 * @return true 表示抢占成功，false 表示已有其他执行持有该会话
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



    /*创建轮次和运行态
    拿到租约以后，才进入引导阶段。引导阶段负责把“这次请求”变成“一个可以被执行、可以被停止、可以被追踪、可以被收尾的运行态任务”。
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
 * 完成会话引导阶段。
 * 这里会先创建一条新的问答轮次记录，再构建本轮执行需要的 TaskInfo，
 * 最后把任务注册到运行态注册表中，供停止、查询运行快照、清理资源等逻辑复用。
 * @param launchPlan 已规范化后的启动计划
 * @return bootstrap 结果；可能是可执行的流，也可能是一个拒绝原因
 */


    private BootstrapResult bootstrapConversation(StreamLaunchPlan launchPlan) {//创建交换记录，构建TaskInfo，注册到Registry

        // exchangeView 表示本次问答轮次的归档记录，后续无论成功还是失败都依赖它进行收尾落库。
        ConversationExchangeView exchangeView = null;
        try {

            // 一旦启动流程开始，就先在归档层生成一条“新轮次”，这样后续异常也能被定位到具体 exchange。  // 先在归档层创建一条“进行中的轮次”，这样后续成功、失败、停止都能基于同一条记录收尾。
            exchangeView = conversationArchiveStore.startExchange(//这里有一个细节非常关键：conversationArchiveStore.startExchange(...) 已经创建了“进行中”的轮次。只要轮次创建成功，后面如果出错，就不能简单地返回错误了，还要把这条轮次补成失败态。
                launchPlan.getConversationId(),
                launchPlan.getQuestion(),
                launchPlan.getChatMode(),
                launchPlan.getSelectedDocumentId(),
                launchPlan.getSelectedDocumentName()
            );

            // TaskInfo 聚合了本次会话运行所需的所有状态：SSE sink、trace、引用、上下文等。// 构造运行期上下文对象，集中保存本轮会话的状态、缓冲区、追踪器与租约信息。
            TaskInfo taskInfo = createTaskInfo(launchPlan, exchangeView);

            if (!chatRuntimeRegistry.register(taskInfo)) {

                // 极端情况下，即使抢到租约，也可能在运行态注册时发现已有同会话任务占用，必须补偿性收尾。 // 即使分布式租约拿到了，本地运行态注册失败时也要将当前轮次标记为失败，避免悬空记录。
                failBootstrappedExchange(launchPlan.getConversationId(), exchangeView.getExchangeId(), "该会话当前正在执行中，请稍后再试");

                // 注册失败说明本地已有同会话任务占用，立即释放租约，让后续请求有机会重试。
                releaseLeaseQuietly(launchPlan.getLeaseKey(), launchPlan.getLeaseOwnerToken());
                return BootstrapResult.rejected("该会话当前正在执行中，请稍后再试");
            }

            // 只有在归档、运行态、SSE 通道都准备好之后，才把流返回给上层。  // 注册成功后先返回可订阅的客户端通道，真正的生成会在订阅时触发。
            return BootstrapResult.ready(bindClientChannel(taskInfo));
        }
        catch (RuntimeException exception) {

            // bootstrap 过程中只要失败，就先释放租约，再把已经创建的轮次标记为失败，避免悬空数据。// 引导阶段异常时必须释放租约，防止后续请求一直被旧租约挡住。
            releaseLeaseQuietly(launchPlan.getLeaseKey(), launchPlan.getLeaseOwnerToken());
            if (exchangeView != null) {

                // 如果轮次已经创建出来了，就要把该轮次补记为失败，保证归档记录状态完整。
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
    /*
    Sink创建与通道绑定（创建单播Sink）
    SSE 流的起点是一个 Reactor Sink。在 createTaskInfo 方法中，每次会话都会创建一个全新的单播 Sink：

     */
    /**
     * 为当前轮会话创建完整的运行态对象。
     * TaskInfo 是后续整个执行链的核心上下文载体，里面会保存：
     * 会话标识、轮次标识、事件输出通道、执行配置、调试追踪、引用集合、工具集合、租约信息等。
     *
     * @param launchPlan 启动计划
     * @param exchangeView 当前轮次归档对象
     * @return 已初始化完成的运行态任务
     */
    /*
    这一段里最值得记住的是两条线：
1.SSE 输出线：sink、eventMetadata、thinkingSteps、references 会支撑后面的流式事件推送。
2.执行上下文线：runnableConfig.context() 会把问题、模式、文档范围、当前日期、调试轨迹等信息交给下游编排器和执行器。
所以 TaskInfo 不是普通 DTO，它更像本轮对话的“运行时工作台”。
     */
    private TaskInfo createTaskInfo(StreamLaunchPlan launchPlan, ConversationExchangeView exchangeView) {//组装完整的任务上下文对象

        // 每个会话只有一个单播 sink，确保一条流只服务当前订阅的前端连接。 // 为当前会话创建单播 Sink，确保一个对话流只面向当前订阅客户端输出。
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();

        // RunnableConfig 是底层 ReactAgent 和 checkpoint 体系识别当前线程上下文的关键对象。// RunnableConfig 会被 Agent 执行、中断、上下文共享等多个环节反复使用。
        RunnableConfig runnableConfig = buildSessionConfig(launchPlan.getConversationId());

        // 这些集合会在执行过程中不断追加内容，因此使用线程安全容器保存运行态快照。 // 这些集合会在生成过程中被不同阶段并发写入，因此需要使用线程安全容器。
        List<String> thinkingSteps = Collections.synchronizedList(new ArrayList<>());
        List<SearchReference> references = Collections.synchronizedList(new ArrayList<>());
        Set<String> usedTools = ConcurrentHashMap.newKeySet();
        // traceId 用于把同一轮会话的阶段埋点、检索观察、模型调用记录串联起来。
        String traceId = UUID.randomUUID().toString().replace("-", "");
        // traceRecorder 负责把执行过程切成多个阶段，并记录每一阶段的状态、耗时和附加信息。
        ConversationTraceRecorder traceRecorder = new ConversationTraceRecorder(
            conversationTraceStageStore,
            retrievalObserveStore,
            launchPlan.getConversationId(),
            exchangeView.getExchangeId(),
            traceId
        );
        // eventMetadata 会被放进每条 SSE 事件中，方便前端知道事件归属哪个会话、哪个轮次。 // 事件元数据会跟随每一条 SSE 事件一起发送，方便前端识别所属会话和轮次。
        StreamEventMetadata eventMetadata = new StreamEventMetadata(
            launchPlan.getConversationId(),
            exchangeView.getExchangeId()
        );

        // 下方这些 context 值会被执行链路中的工具、检索器、追踪器共同读取。   // 把运行时共享对象写入上下文，供下游执行器和工具链直接读取。
        runnableConfig.context().put(ChatContextKeys.EVENT_SINK, sink);
        runnableConfig.context().put(ChatContextKeys.EVENT_METADATA, eventMetadata);
        runnableConfig.context().put(ChatContextKeys.THINKING_STEPS, thinkingSteps);
        runnableConfig.context().put(ChatContextKeys.REFERENCES, references);
        runnableConfig.context().put(ChatContextKeys.USED_TOOLS, usedTools);
        runnableConfig.context().put(ChatContextKeys.TRACE_ID, traceId);

        // 原始问题文本是整个编排链和回答链的基础输入。
        runnableConfig.context().put(ChatContextKeys.QUESTION, launchPlan.getQuestion());

        // 当前会话采用的问答模式会影响后续执行器选择、检索策略和提示词结构。
        runnableConfig.context().put(ChatContextKeys.CHAT_MODE, launchPlan.getChatMode().name());

        // 把“当前日期”和它的文本形式显式注入上下文，是为了让相对时间问题有统一的锚点。 // 当前日期信息用于处理“今天/最近/昨天”等相对时间语义的锚定。
        runnableConfig.context().put(ChatContextKeys.CURRENT_DATE, launchPlan.getCurrentDate().toString());
        runnableConfig.context().put(ChatContextKeys.CURRENT_DATE_TEXT, launchPlan.getCurrentDateText());

        // 文档问答模式下，所选文档和索引任务信息也会随上下文透传。  // 文档问答场景下，把用户选择的文档范围写入上下文，供检索阶段限定作用域。
        putContextIfNotNull(runnableConfig, ChatContextKeys.SELECTED_DOCUMENT_ID, launchPlan.getSelectedDocumentId());
        putContextIfNotBlank(runnableConfig, ChatContextKeys.SELECTED_DOCUMENT_NAME, launchPlan.getSelectedDocumentName());
        putContextIfNotNull(runnableConfig, ChatContextKeys.SELECTED_TASK_ID, launchPlan.getSelectedTaskId());

        // 在真正生成 executionPlan 之前，先放一个空白调试轨迹，保证链路中随时都能读取到 debugTrace。 // 先放入一个基础的调试轨迹占位对象，执行计划准备好后再回填更完整的信息。
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
    /*
    Sink创建好之后，通过 bindClientChannel 方法把它转成前端可以消费的 Flux<String>：     绑定SSE输出通道
    这里有两个关键的回调：
    doOnSubscribe：前端建立 SSE 连接的那一刻触发，这时候才真正启动模型生成。这和 Flux.defer 配合，实现了"不订阅不执行"的延迟启动语义。
    doOnCancel：前端关闭浏览器标签页或主动断开连接时触发，后端会立刻停止生成任务，释放资源。
     */
    /*
    第二层延迟：bindClientChannel 和 doOnSubscribe
bootstrapConversation 注册成功以后，并不会立刻调用模型。它只是把 TaskInfo 里的 sink 转成一个可以被订阅的 Flux。
将运行态任务绑定为客户端可直接消费的输出流。
 * 真正的模型执行不会在这里立即发生，而是等待客户端订阅之后再由 doOnSubscribe 触发。
 * @param taskInfo 当前任务
 * @return 对外暴露的响应流

     */
    private Flux<String> bindClientChannel(TaskInfo taskInfo) {//把Sink转成Flux，绑定订阅和取消回调

        return taskInfo.sink().asFlux()

                /*
                这里的 .doOnSubscribe 是第二层延迟启动。
                第一层 Flux.defer 解决的是“不要提前启动会话”。第二层 .doOnSubscribe 解决的是“通道建好了，但真正生成也要等订阅开始”
                顺便看一下取消订阅的处理：如果连接断了，.doOnCancel 会触发 stopTask。这样模型不会继续在后台空跑，租约、执行流、运行态注册最终也会被清掉
                 */
                // 前端真正订阅时，才异步启动生成任务   // 客户端一旦开始订阅，说明前端已经准备好接收 SSE，此时再正式启动后台生成。
            .doOnSubscribe(ignored -> activateGeneration(taskInfo))

                // 前端断开连接时，主动中断后台生成，避免资源空转    // 如果前端主动取消订阅，需要尽快停止后台任务，避免模型继续空跑。

            .doOnCancel(() -> stopTask(taskInfo, "客户端已取消请求"));
    }

    //activateGeneration() 做两件事：启动租约续期、启动执行链路：
    // BusinessChatService.java —— 激活生成
    /*activateGeneration：从通道订阅到执行流订阅
    .doOnSubscribe 触发后，会进入 activateGeneration。这个方法做两件事：
1.启动租约续期，保证长回答期间 Redis 租约不会自然过期。
2.订阅 buildConversationExecution(taskInfo)，真正让执行流跑起来。
     */
    /**
     * 激活后台生成逻辑。
     * 这里先启动租约续期任务，再订阅真正的执行流，保证长时间会话也能持续持有合法租约。
     *
     * @param taskInfo 当前任务
     */
    private void activateGeneration(TaskInfo taskInfo) {//启动租约续期，订阅执行流
        try {

            /*
            注意这里有两个 finalized 检查。它们不是重复代码，而是在处理竞态：
可能刚准备启动时，这个任务已经被停止了。
可能刚启动续租以后，任务又被取消了。
可能刚订阅执行流以后，失败收尾或停止收尾已经发生了。
所以 activateGeneration 每往前推进一步，都要确认这轮会话还活着
             */



            // 如果任务已经在别处被收尾，则不允许重复启动。
            if (taskInfo.finalized().get()) {
                return;// 任务已被其他线程结束，不再重复启动
            }

            // 长时间执行的会话需要定期续租，否则别的节点会认为任务已失效        // 启动后台续租，避免长回答场景下 Redis 租约自然过期。
            Disposable leaseRenewalDisposable = startLeaseRenewal(taskInfo);
            taskInfo.setLeaseRenewalDisposable(leaseRenewalDisposable);
            if (taskInfo.finalized().get() && !leaseRenewalDisposable.isDisposed()) {
                // 处理竞态情况：如果刚启动续租任务时会话已经结束，要立即回收该定时任务。
                leaseRenewalDisposable.dispose();
                return;
            }

            // 真正的生成执行链路在这里启动订阅          // 订阅执行流之后，真正的执行器、模型调用和分片输出才会开始向前推进。
            Disposable disposable = buildConversationExecution(taskInfo).subscribe();

            taskInfo.setDisposable(disposable);
            // 如果任务在刚启动后立即被标记为结束，主动 dispose
            if (taskInfo.finalized().get() && !disposable.isDisposed()) {
                // 如果在订阅建立后任务已被收尾，则立刻停止执行流，避免继续产生副作用。
                disposable.dispose();
            }
        }
        catch (RuntimeException exception) {
            // 激活阶段的任何异常都统一按失败流程收尾，确保流关闭、状态落库、资源释放都能完成。
            finishWithFailure(taskInfo, exception);
        }
    }


    /*
    核心：组装执行流 buildConversationExecution  真正的执行入口 是从“会话生命周期管理”切到“业务执行器”的分界点。它不自己回答问题，而是负责把执行流搭好：
    这是整条链路最关键的方法，把"发送思考提示->准备执行计划 → 选择执行器 → 消费模型输出 → 收尾"串成一条完整的响应式流：
1.先发一个 thinking 事件。
2.准备 ConversationExecutionPlan。
3.根据 plan.getMode() 找到对应执行器。
4.消费执行器输出的文本流。
5.按成功或失败走统一收尾。

     */
    // BusinessChatService.java —— 组装完整的对话执行流
    /**
     * 组装完整的对话执行流。
     * <p>
     * 链路大致分为：发送“正在分析”提示 -> 准备执行计划 -> 按计划选择执行器 -> 消费模型输出 ->
     * 正常完成时收尾，异常时失败收尾。
     * </p>
     * @param taskInfo 当前任务
     *  * @return 真正驱动模型输出的执行流
     */
    /*
    执行器返回的是 Flux<String>。每来一个 chunk，BusinessChatService 都会通过 emitModelChunk 做统一处理：追加到答案缓冲区、记录首包耗时、写 SSE 文本事件。
也就是说，执行器只需要“吐文本流”，至于怎么变成 SSE 事件、怎么累计答案、怎么最终落库，都由外层统一管理。
     */
    private Flux<String> buildConversationExecution(TaskInfo taskInfo) {//准备执行计划——>选择执行器——>执行


        /*
        Flux.defer：第三层延迟
这里又出现了一次 Flux.defer。
这一层和入口处的 Flux.defer 关注点不一样：
openConversationStream 的 defer 是为了延迟“启动会话”。
buildConversationExecution 的 defer 是为了延迟“构建并执行真正的业务执行流”。
这样做以后，只有 activateGeneration 真的调用 .subscribe() 时，才会发 thinking、准备执行计划、选择执行器。
         */
        return Flux.defer(() -> {

            /*
            safeEmit：先给一个可见状态
            这句不是模型输出，它只是告诉订阅方：后端已经进入分析阶段了。对于流式对话来说，这个体验很重要。因为执行计划准备阶段可能会做历史压缩、路由判断、文档范围判断等工作，如果这段时间没有任何事件，用户会以为请求卡住了。
             */
                    // 先给前端发一个"分析中"的状态事件，让用户知道系统在工作         // 在真正出字前先给前端一个可见状态，减少用户等待时的空白感。
                safeEmit(taskInfo.sink(), streamEventWriter.thinking("正在分析问题上下文。", taskInfo.eventMetadata()));



/*
prepareExecutionPlan 不是简单的字段转换，它可能涉及比较重的准备动作。所以这里用 Mono.fromCallable 包成响应式任务，再通过 boundedElastic 线程池执行。
boundedElastic 适合处理可能阻塞的任务，比如数据库查询、外部服务调用、复杂编排准备。这样不会把响应式链路里的关键线程堵住
 */
                    // 执行计划的准备可能包含检索、摘要、编排等阻塞操作，因此切到弹性线程池执行。 这个 prepareExecutionPlan 方法是整个问答链路的"大脑"，它负责把用户的原始问题整理成后续执行器可以消费的执行计划。
                return Mono.fromCallable(() -> prepareExecutionPlan(taskInfo))
                        // 计划准备包含检索、压缩历史、读取摘要等 IO 操作，放到弹性线程池
                    .subscribeOn(Schedulers.boundedElastic())



/*
flatMapMany：从执行计划切到执行器输出流

 */
                    .flatMapMany(plan -> {
                        // 根据编排结果选择合适的执行器           // 根据执行模式选择对应执行器，例如澄清、开放问答、文档问答等不同路径     这是整个方法里最像“路口”的地方。
                        //prepareExecutionPlan 产出 ConversationExecutionPlan，里面有一个 mode。conversationExecutorRegistry.get(plan.getMode()) 根据这个模式拿到具体执行器。然后 executor.execute(taskInfo) 才进入具体回答链路。
                        ConversationExecutor executor = conversationExecutorRegistry.get(plan.getMode());
                        return executor.execute(taskInfo);
                    });
            })                        //publishOn 和 doOnNext：统一接管输出
            .publishOn(Schedulers.boundedElastic())
                // 模型每产生一个增量片段，实时追加到缓冲区并推送给前端            // 每收到一段模型输出，就实时累计答案并向前端推送，形成流式体验。

            .doOnNext(chunk -> emitModelChunk(taskInfo, chunk))//// 每收到一段模型输出，就实时累计答案并向前端推送，形成流式体验。
                // 任意异常进入统一失败收尾       // 统一把异常收敛到失败收尾逻辑，确保状态落库和资源释放不会漏掉。


                //doOnError 和 doOnComplete：统一收尾   这个设计让收尾逻辑和执行器解耦。执行器内部只要正常完成，外层就走 finishSuccessfully。执行器内部任何异常冒出来，外层就走 finishWithFailure。不管是哪条路，最后都会进入统一清理，释放租约和运行态资源。
            .doOnError(error -> finishWithFailure(taskInfo, error))// 统一把异常收敛到失败收尾逻辑，确保状态落库和资源释放不会漏掉。
                // 正常完成时执行成功收尾       // 执行流自然结束时走成功态收尾逻辑，例如补发引用、推荐问题和最终归档。
            .doOnComplete(() -> finishSuccessfully(taskInfo));// 执行流自然结束时走成功态收尾逻辑，例如补发引用、推荐问题和最终归档。
    }













    /*
    生命周期终结方法（成功收尾）成功收尾的事件补发
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
     * 事件顺序不能乱
     * 补发事件必须在关闭流之前完成，否则前端收不到引用和推荐。顺序是：text chunks → references → recommendations → 流关闭。代码里用 try-finally 保证即使补发失败，流也一定会被关闭。
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
                    // finalize 阶段在“落库成功”后才标记完成，这样 trace 中的完成态才真正代表整轮闭环完成。
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

    /*
    执行失败
    模型调用出错、检索引擎超时、任何未捕获的异常，都会走到finishWithFailure
    处理会话执行失败时的统一收尾。
 * <p>
 * 失败场景下不发送引用和推荐（正文都没生成完，发这些没有意义），只发送错误事件，并按 FAILED 状态落库。
 * </p>
     */
    private void finishWithFailure(TaskInfo taskInfo, Throwable error) {//异常完成：发送错误事件，归档为失败，清理
        if (!taskInfo.finalized().compareAndSet(false, true)) {//CAS finalized：保证单次收尾  整个收尾机制的核心是 TaskInfo 里的 finalized 字段——一个 AtomicBoolean。不管是成功、失败还是停止，收尾方法的第一行都是：
            return;//已经有其他线程做过收尾了，直接退出
        }

        // 错误信息会尽量展开到底层 WebClient 的响应体，方便接口问题排查。
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
            // 先向前端明确发出失败事件，让页面立即结束等待状态。
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
                // 失败状态同样需要释放运行态，避免后续请求被误判为仍在执行。
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
    // BusinessChatService.java —— 清理资源（统一资源释放：所有收尾路径（成功、失败、停止）最终都会调用 cleanup 方法：）
    /*
    最终的统一清理方法是cleanup
    清理的顺序也有讲究：先停续期、再停执行流、然后释放租约、最后移除注册。如果顺序反了，比如先释放租约再停执行流，可能出现"租约已释放但执行流还在跑"的窗口期，导致新请求进来时和旧任务冲突。
    {清理顺序也很讲究：
1.先停租约续期，不要让结束后的任务继续续租。
2.再停主执行流，避免后续继续产出事件。
3.然后释放 Redis 租约，让同一个会话可以再次启动。
4.最后移除本地运行态注册，让查询接口不再认为它还在运行。}
    释放任务占用的本地资源和分布式资源。
 * 成功、失败、停止三种收尾路径最终都会汇聚到这里。
  @param taskInfo 当前任务
     */
    private void cleanup(TaskInfo taskInfo) {//最终清理：释放租约，移除注册，释放Disposable

        // 停掉租约续期定时任务
        Disposable disposable = taskInfo.disposable();
        Disposable leaseRenewalDisposable = taskInfo.leaseRenewalDisposable();


        if (leaseRenewalDisposable != null && !leaseRenewalDisposable.isDisposed()) {

            // 先停掉租约续期任务         // 先取消租约续期任务，避免会话结束后后台还在继续续租。
            leaseRenewalDisposable.dispose();
        }

        // 停掉业务执行流
        if (disposable != null && !disposable.isDisposed()) {

            // 再停掉业务执行流 // 再取消主执行流，确保不会再有新的输出和副作用产生。
            disposable.dispose();
        }

        // 释放 Redis 租约（分布式租约），其他节点或后续请求才能再次启动同一个会话  // 主动释放分布式租约，让后续同一会话的新请求能够重新启动。
        releaseLeaseQuietly(taskInfo.leaseKey(), taskInfo.leaseOwnerToken());

        // 从运行时注册表移除当前任务  // 从运行态注册表中移除，避免查询接口继续把该任务视作“运行中”。
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

    //cleanup 时静默释放    releaseLeaseQuietly：失败和收尾都要释放租约
    /*
    租约释放贯穿好几条路径：
启动阶段拿到租约后发生异常，要释放。
引导阶段注册运行态失败，要释放。
执行成功、执行失败、用户停止，最终清理时也要释放。
源码里释放租约用的是 releaseLeaseQuietly：
静释放会话租约。
 * 即使释放失败，也只记录日志，不再继续打断主流程。
 *
 * @param leaseKey 租约 key
 * @param leaseOwnerToken 租约持有者令牌
     */
    private void releaseLeaseQuietly(String leaseKey, String leaseOwnerToken) {//静默释放租约，吞掉异常
        try {

            redisLeaseManager.release(leaseKey, leaseOwnerToken);
        }
        catch (RuntimeException exception) {
            //这里叫 Quietly，意思是释放失败只记日志，不继续往外抛。原因也很实际：释放租约通常发生在异常兜底或最终清理阶段，如果这里再抛一个新异常，就会把原始错误盖掉，反而更难排查。
            log.warn("释放会话租约时出现异常, leaseKey={}", leaseKey, exception);
        }
    }











    /*prepareExecutionPlan：把计划写回 TaskInfo                       prepareExecutionPlan 是 buildConversationExecution 里最关键的嵌套方法。它负责调用前置编排器生成执行计划，并把计划写回 TaskInfo。
    辅助方法（挑几个重要的）  编排器：prepareExecutionPlan  执行计划的生成由 ChatPreparationOrchestrator 负责，它是整条链路的"大脑"，决定了这次问答到底走哪条路。
    这里要抓住一个点：chatPreparationOrchestrator.prepare(taskInfo) 是真正做前置编排的地方。它会产出一个 ConversationExecutionPlan，后面执行器就按照这个计划走。
     */
    // BusinessChatService.java —— 准备执行计划
    /**
     * 准备本轮会话真正执行所需的编排计划。并将其结果同步回 TaskInfo 与上下文。
     * 这一层会调用编排器分析历史上下文、决定执行模式、构造 agentQuestion，并在必要时刷新会话绑定的文档范围。
     * @param taskInfo 当前任务
     *  * @return 执行计划
     */
    /**
     * 准备本轮对话真正要执行的计划，并将其结果同步回 TaskInfo 与上下文。
     * 这个方法是"会话启动完成"与"真正执行器开始工作"之间的桥接层：
     * 会先调用编排器产出最终执行计划，再把计划里确定下来的文档范围、Agent 问题、调试信息等回写到运行态对象中。
     *
     * 这里主要做三件事：
     * 1. 固化编排阶段的结果，避免后续执行链重复计算；
     * 2. 当自动路由重定向了文档范围时，同步刷新会话持久层作用域；
     * 3. 重建最终版 debugTrace，让后续执行器和收尾逻辑拿到的是"本轮最终执行计划"的调试快照。
     */
    private ConversationExecutionPlan prepareExecutionPlan(TaskInfo taskInfo) {//委托给 ChatPreparationOrchestrator 准备执行计划

        // 编排器会综合问题、历史、摘要、文档选择等信息生成本轮执行计划。 // 在这里统一完成问题改写、历史压缩、模式判定、检索规划等准备动作。
        /*这个方法的核心逻辑只有这一行 后续的代码主要是：
1.渲染 Agent 问题——把时间锚定和历史摘要带入提示词
2.同步文档范围——如果执行计划重新选择了文档，需要同步到持久层和运行时上下文
3.回写执行计划——把执行计划和调试信息写回 TaskInfo
ChatPreparationOrchestrator 是聊天准备编排器，
         */
        ConversationExecutionPlan executionPlan = chatPreparationOrchestrator.prepare(taskInfo);//第一步：调用编排器   这就是前面十几篇文档讲的那一大段流程——问题改写、文档路由、执行模式判定、执行计划装配，全部在这一行完成。返回的 executionPlan 已经包含了本轮对话所需的所有信息。



        //第二步：渲染Agent问题  （prepare 方法产出的执行计划里，agentQuestion 字段还是空的。这里调用 buildAgentQuestion 把它填上：）
        // agentQuestion 是最终喂给 Agent 的问题文本，会补充时间锚点和上下文摘要。// 基于准备好的执行计划重新渲染 Agent 问题，确保时间锚定和历史摘要都被带入提示词。
        executionPlan.setAgentQuestion(buildAgentQuestion(executionPlan));

        /*第三步：文档范围同步刷新
        这个 if 判断的是：编排阶段是否重新路由了文档范围。
        触发条件：
        executionPlan.getSelectedDocumentId() 不为 null（路由到了具体文档）
        且和 taskInfo.selectedDocumentId()（本轮开始时的文档）不一样
        这种情况发生在 AUTO_DOCUMENT 模式下，用户没有手动选文档，系统自动路由到了一个新文档。
         */
        if (executionPlan.getSelectedDocumentId() != null
                && !Objects.equals(executionPlan.getSelectedDocumentId(), taskInfo.selectedDocumentId())) {
            // 如果编排阶段修正了文档范围，需要同步刷新归档中的会话范围与运行上下文。 // 如果执行计划重新选择了文档范围，需要同步刷新持久层会话范围和运行时上下文。
            conversationArchiveStore.refreshSessionScope(//刷新持久层会话范围
                    taskInfo.conversationId(),
                    executionPlan.getChatMode(),
                    executionPlan.getSelectedDocumentId(),
                    executionPlan.getSelectedDocumentName()
            );
            // 同时把最新文档范围回写到 runnableConfig 上下文，保证执行器和工具链读取到的是同一份作用域信息。   回写运行时上下文
            putContextIfNotNull(taskInfo.runnableConfig(), ChatContextKeys.SELECTED_DOCUMENT_ID, executionPlan.getSelectedDocumentId());
            putContextIfNotBlank(taskInfo.runnableConfig(), ChatContextKeys.SELECTED_DOCUMENT_NAME, executionPlan.getSelectedDocumentName());
            putContextIfNotNull(taskInfo.runnableConfig(), ChatContextKeys.SELECTED_TASK_ID, executionPlan.getSelectedTaskId());
        }

        /*
        第四步：回写 TaskInfo 并重建 debugTrace 先把执行计划写回 taskInfo，再用最终的执行计划重建 debugTrace。
         */
        // 把最终执行计划和对应的调试轨迹回写到任务对象，供执行链路和收尾阶段复用。// 把最新执行计划与调试信息写回任务对象，供执行器和最终收尾逻辑统一读取。
        taskInfo.setExecutionPlan(executionPlan);
        // 用最终 executionPlan 重建一版 debugTrace，避免继续沿用初始化阶段的占位信息。
        taskInfo.setDebugTrace(initializeDebugTrace(executionPlan));//initializeDebugTrace 把执行计划里的关键字段全部快照到 ChatDebugTrace 对象里：
        taskInfo.runnableConfig().context().put(ChatContextKeys.DEBUG_TRACE, taskInfo.debugTrace());
        return executionPlan;
    }



    /*
    prepare 方法产出的执行计划里，agentQuestion 字段还是空的。这里调用 buildAgentQuestion 把它填上：
     */
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
/*
逐块推送
模型每产出一个文本片段，都会经过 emitModelChunk 方法处理：处理模型输出的单个增量片段
这个方法同时做了三件事：累积答案缓冲区、记录首包耗时、推送 SSE 事件。首包耗时用的是 AtomicLong.compareAndSet，保证只有第一个 chunk 会写入时间戳。
 */
    private void emitModelChunk(TaskInfo taskInfo, String chunk) {//接收模型输出的每个 chunk，追加到 buffer 并推送

        // answerBuffer 持续累积，最终落库时用它拿到完整答案
        taskInfo.answerBuffer().append(chunk);
        // 首包耗时只记录第一次收到正文输出的时刻，后续chunk不再覆盖
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






    /**
     * 这段代码不是为了给用户展示回答，而是为了保证后台数据状态完整。否则数据库里会留下一条永远 RUNNING 的轮次，以后查会话历史、看运行状态、做问题排查都会很别扭。
     * 将已经创建但未能正常进入执行阶段的轮次标记为失败。
     *
     * @param conversationId 会话 id
     * @param exchangeId 轮次 id
     * @param errorMessage 失败原因
     */
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


    /*
    启动阶段的拒绝流（构造拒绝流）
    构造一个携带会话元数据的错误事件流
    如果会话在启动阶段就被拦截了（比如租约抢占失败、参数校验不通过），不会走正常的执行链路，而是直接返回一个只包含 error 事件的"拒绝流"：
    拒绝流的特点是：只有一条 error 事件，没有 thinking、text、reference 等其他事件，流发完就自动结束。
    @param message 错误信息
 * @param conversationId 会话 id
 * @param exchangeId 轮次 id
 * @return 单条错误事件流
 rejectionFlux 的语义很直接：只发一条 error 事件，然后流自然结束。它不走模型，不创建后台执行任务，也不会假装继续输出。
     */
    private Flux<String> rejectionFlux(String message, String conversationId, Long exchangeId) {
// 只发一条 error 事件，流立刻结束
        return Flux.just(streamEventWriter.error(message, new StreamEventMetadata(conversationId, exchangeId)));
    }

    public ConversationStopVo stopConversation(String conversationId) {
        return stopConversation(conversationId, "用户已停止生成");
    }



    /*
    用户主动停止
    用户点击"停止生成"按钮时，前端调用 /api/chat/session/stop 接口，后端会发送一个 status 事件：
     真正执行任务停止、资源释放和停止态收尾。
 * 这一层会同时处理：并发幂等、防止旧任务误停新任务、中断 Agent、关闭 SSE、归档停止结果、刷新摘要以及清理运行态。
     */
    private ConversationStopVo stopTask(TaskInfo taskInfo, String reason) {

        // compareAndSet 保证停止逻辑只会成功执行一次，避免重复收尾导致状态覆盖。
        if (!taskInfo.finalized().compareAndSet(false, true)) {//CAS finalized ：保证单次收尾 整个收尾机制的核心是 TaskInfo 里的 finalized 字段——一个 AtomicBoolean。不管是成功、失败还是停止，收尾方法的第一行都是：
            return new ConversationStopVo(taskInfo.conversationId(), false, "会话已经结束");
        }

        Optional<TaskInfo> currentTask = chatRuntimeRegistry.get(taskInfo.conversationId());
        if (currentTask.isPresent() && currentTask.get() != taskInfo) {

            // 如果注册表里已经是另一个新任务，说明当前旧任务不应该再去中断或清理共享状态。
            return new ConversationStopVo(taskInfo.conversationId(), false, "会话已由新的执行接管");
        }

        try {

            // 尝试中断底层 ReactAgent，让模型与工具链尽快停止继续生成。
            businessChatReactAgent.interrupt(taskInfo.runnableConfig());
        }
        catch (RuntimeException exception) {
            log.debug("中断 ReactAgent 时出现异常，继续释放资源", exception);
        }

        Disposable disposable = taskInfo.disposable();
        if (disposable != null && !disposable.isDisposed()) {

            // 如果执行流还在运行，主动 dispose，防止后续仍继续向 sink 推送内容
            disposable.dispose();
        }

        String responseMessage = "已停止会话生成";
        // finalize 阶段用于把“停止收尾”也纳入追踪体系，便于排查停止过程中到底卡在哪一步。
        ConversationTraceRecorder.StageHandle finalizeStage = taskInfo.traceRecorder() == null
            ? null
            : taskInfo.traceRecorder().startStage(
                org.javaup.ai.chatagent.model.trace.ConversationTraceStageCode.FINALIZE,
                taskInfo.executionPlan() == null || taskInfo.executionPlan().getMode() == null ? "" : taskInfo.executionPlan().getMode().name(),
                "正在收尾停止中的会话。",
                null
            );
        try {
            // 先通知前端“本次会话已停止”，这样页面可以及时结束 loading 状态。
            safeEmit(taskInfo.sink(), streamEventWriter.status("⏹ " + reason, taskInfo.eventMetadata()));
        }
        catch (RuntimeException exception) {
            log.warn("发送停止事件失败, conversationId={}, exchangeId={}", taskInfo.conversationId(), taskInfo.exchangeId(), exception);
            responseMessage = "会话已停止，停止事件发送失败";
        }
        finally {
            try {
                // 无论停止事件是否发送成功，都要尝试关闭 SSE 流，结束前端订阅。
                safeComplete(taskInfo.sink());
            }
            catch (RuntimeException exception) {
                log.warn("关闭停止中的 SSE 流失败, conversationId={}, exchangeId={}", taskInfo.conversationId(), taskInfo.exchangeId(), exception);
            }
            try {
                // 先把运行期统计回填到 debugTrace，再把当前已生成的内容按 STOPPED 状态落库。
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
                // 停止后仍然要刷新会话摘要，并移除运行态、租约、订阅对象等资源。
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







/*
错误信息展开
buildErrorMessage 会沿着异常的 cause 链向下查找，如果发现 WebClientResponseException（调用外部 API 失败），会把 HTTP 状态码、请求地址和响应体都提取出来：
构建可读的错误信息
 */
    private String buildErrorMessage(Throwable error) {

        Throwable current = error;
        while (current != null) {
            // 沿 cause 链查找最具信息量的网络异常
            if (current instanceof WebClientResponseException responseException) {
                String responseBody = responseException.getResponseBodyAsString();
                if (StrUtil.isNotBlank(responseBody)) {
                    // 输出格式：404 from GET https://api.xxx.com/... | responseBody=...
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


    //initializeDebugTrace 把执行计划里的关键字段全部快照到 ChatDebugTrace 对象里：
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
        runnableConfig.context().put(key, value);//runnableConfig.context() 是一个运行时 Map，执行器和工具链在执行过程中会从这里读取当前文档范围。如果不同步回写，就会出现"持久层已经更新了文档范围，但执行器还在用旧的"的不一致问题。
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

    /*
    safeEmit 和 safeComplete
BusinessChatService 里的 safeEmit 和 safeComplete 是对 SinkEmitHelper 的简单封装：
     */
    private void safeEmit(Sinks.Many<String> sink, String payload) {//安全发送和关闭

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
