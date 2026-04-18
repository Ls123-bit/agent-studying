package org.javaup.ai.chatagent.service;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import lombok.Data;
import org.javaup.ai.chatagent.model.debug.ChatDebugTrace;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.model.SearchReference;
import org.javaup.ai.chatagent.support.StreamEventMetadata;
import org.javaup.enums.ChatQueryMode;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 服务层
 * @author: 阿星不是程序员
 **/
/**
 * 单个流式会话运行时的内存态上下文。
 *
 * <p>这些状态只在当前 JVM 的一次执行过程中使用：
 * answerBuffer 用来拼接正文，
 * thinking/references/usedTools 用来积累过程数据，
 * finalized 用来避免重复收尾。</p>
 *
 * <p>其中 leaseKey / leaseOwnerToken 代表这条任务在 Redis 中对应的集群执行资格，
 * disposable / leaseRenewalDisposable 则代表这条任务在当前 JVM 中的两个“活对象”：
 * 一个是真正的 Flux 订阅，一个是续租定时任务。</p>
 */
@Data
public class TaskInfo {
    private final String conversationId;
    private final long exchangeId;
    /*
     * question 保存的是用户原始输入，而不是 rewrite 后的问题。
     * 推荐问题生成、会话展示和最终归档都仍然以用户原话为准。
     */
    private final String question;
    private final ChatQueryMode chatMode;
    private final String traceId;
    private final Long selectedDocumentId;
    private final String selectedDocumentName;
    private final Long selectedTaskId;
    private final LocalDate currentDate;
    private final String currentDateText;
    /*
     * executionPlan 是这轮对话的“执行说明书”。
     * 执行器后面不再自行猜测流程，而是统一围绕这份 plan 来跑。
     */
    private volatile ConversationExecutionPlan executionPlan;
    /*
     * debugTrace 是面向教学观测和排障的结构化轨迹容器。
     * 前置编排阶段先初始化，执行器和收尾阶段再不断往里补运行时细节。
     */
    private volatile ChatDebugTrace debugTrace;
    /*
     * runnableConfig 主要服务 Agent 和工具层：
     * threadId 用来命中同一条会话线程，
     * context() 则负责承接本轮共享过程态。
     */
    private final RunnableConfig runnableConfig;
    private final ConversationTraceRecorder traceRecorder;
    /*
     * sink 是当前会话所有 SSE 事件的唯一出口。
     * 无论是正文、thinking、status、reference 还是 error，最终都会从这里流向前端。
     */
    private final Sinks.Many<String> sink;
    private final StreamEventMetadata eventMetadata;
    private final String leaseKey;
    private final String leaseOwnerToken;
    /*
     * answerBuffer 是当前轮回答正文的定稿缓存。
     * 执行过程中每个正文 chunk 都会先 append 到这里，收尾时再统一落库。
     */
    private final StringBuffer answerBuffer = new StringBuffer();
    /*
     * thinkingSteps / references / usedTools 是三类最核心的过程快照：
     * - thinkingSteps: 过程提示
     * - references: 最终或中间证据
     * - usedTools: 检索通道 / 工具 / 精排器使用痕迹
     */
    private final List<String> thinkingSteps;
    private final List<SearchReference> references;
    private final Set<String> usedTools;
    /*
     * startTime 用来计算首字耗时和总耗时。
     * 这里记录的是“这轮执行真正进入 JVM 运行态”的时间戳。
     */
    private final long startTime;

    /**
     * 首字耗时和 finalized 都是跨线程读写的运行态指标，
     * 因此使用原子类型保证并发下的数据一致性。
     */
    private final AtomicLong firstResponseTimeMs = new AtomicLong(0L);
    private final AtomicBoolean finalized = new AtomicBoolean(false);
    /*
     * disposable 是当前对话执行流本身的订阅句柄；
     * leaseRenewalDisposable 则是租约续期任务的句柄。
     *
     * 两者都是后续 stop / cleanup 阶段必须精确释放的资源。
     */
    private volatile Disposable disposable;
    private volatile Disposable leaseRenewalDisposable;

    public TaskInfo(String conversationId,
                    long exchangeId,
                    String question,
                    ChatQueryMode chatMode,
                    String traceId,
                    Long selectedDocumentId,
                    String selectedDocumentName,
                    Long selectedTaskId,
                    LocalDate currentDate,
                    String currentDateText,
                    ConversationExecutionPlan executionPlan,
                    ChatDebugTrace debugTrace,
                    RunnableConfig runnableConfig,
                    ConversationTraceRecorder traceRecorder,
                    Sinks.Many<String> sink,
                    StreamEventMetadata eventMetadata,
                    String leaseKey,
                    String leaseOwnerToken,
                    List<String> thinkingSteps,
                    List<SearchReference> references,
                    Set<String> usedTools,
                    long startTime) {
        this.conversationId = conversationId;
        this.exchangeId = exchangeId;
        this.question = question;
        this.chatMode = chatMode;
        this.traceId = traceId;
        this.selectedDocumentId = selectedDocumentId;
        this.selectedDocumentName = selectedDocumentName;
        this.selectedTaskId = selectedTaskId;
        this.currentDate = currentDate;
        this.currentDateText = currentDateText;
        this.executionPlan = executionPlan;
        this.debugTrace = debugTrace;
        this.runnableConfig = runnableConfig;
        this.traceRecorder = traceRecorder;
        this.sink = sink;
        this.eventMetadata = eventMetadata;
        this.leaseKey = leaseKey;
        this.leaseOwnerToken = leaseOwnerToken;
        this.thinkingSteps = thinkingSteps;
        this.references = references;
        this.usedTools = usedTools;
        this.startTime = startTime;
    }

    public String conversationId() {
        return conversationId;
    }

    public long exchangeId() {
        return exchangeId;
    }

    public String question() {
        return question;
    }

    public ChatQueryMode chatMode() {
        return chatMode;
    }

    public String traceId() {
        return traceId;
    }

    public RunnableConfig runnableConfig() {
        return runnableConfig;
    }

    public ConversationTraceRecorder traceRecorder() {
        return traceRecorder;
    }

    public Long selectedDocumentId() {
        return selectedDocumentId;
    }

    public String selectedDocumentName() {
        return selectedDocumentName;
    }

    public Long selectedTaskId() {
        return selectedTaskId;
    }

    public LocalDate currentDate() {
        return currentDate;
    }

    public String currentDateText() {
        return currentDateText;
    }

    public ConversationExecutionPlan executionPlan() {
        return executionPlan;
    }

    public void setExecutionPlan(ConversationExecutionPlan executionPlan) {
        this.executionPlan = executionPlan;
    }

    public ChatDebugTrace debugTrace() {
        return debugTrace;
    }

    public void setDebugTrace(ChatDebugTrace debugTrace) {
        this.debugTrace = debugTrace;
    }

    public Sinks.Many<String> sink() {
        return sink;
    }

    public StreamEventMetadata eventMetadata() {
        return eventMetadata;
    }

    public String leaseKey() {
        return leaseKey;
    }

    public String leaseOwnerToken() {
        return leaseOwnerToken;
    }

    public StringBuffer answerBuffer() {
        return answerBuffer;
    }

    public List<String> thinkingSteps() {
        return thinkingSteps;
    }

    public List<SearchReference> references() {
        return references;
    }

    public Set<String> usedTools() {
        return usedTools;
    }

    public long startTime() {
        return startTime;
    }

    public AtomicLong firstResponseTimeMs() {
        return firstResponseTimeMs;
    }

    public AtomicBoolean finalized() {
        return finalized;
    }

    public Disposable disposable() {
        return disposable;
    }
    
    public Disposable leaseRenewalDisposable() {
        return leaseRenewalDisposable;
    }
}
