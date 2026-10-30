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






/*
运行时上下文的数据载体类  一次对话请求的所有运行时状态都装在这个对象里，贯穿整个生命周期
运行时的万能上下文
TaskInfo 是整条执行链路的核心数据载体，几乎所有组件都要从它身上拿东西。看看它都装了什么：
TaskInfo 是这条链路的核心运行态对象。它把一次对话执行需要的东西都放在一起：会话 ID、轮次 ID、SSE Sink、事件元数据、租约信息、执行计划、调试轨迹、答案缓冲区、引用集合、使用过的工具集合等。

 */
@Data
public class TaskInfo {
    private final String conversationId;//会话ID
    private final long exchangeId;//本次问答的ID

    private final String question;//用户问题
    private final ChatQueryMode chatMode;//聊天模式
    private final String traceId;
    private final Long selectedDocumentId;//选中文档的名称
    private final String selectedDocumentName;
    private final Long selectedTaskId;
    private final LocalDate currentDate;//当前日期
    private final String currentDateText;//格式化的日期文本（如"2026-05-20（星期二）"）

    private volatile ConversationExecutionPlan executionPlan;//执行计划（volatile，准备阶段填充）

    private volatile ChatDebugTrace debugTrace;

    private final RunnableConfig runnableConfig;//Agent 运行配置
    private final ConversationTraceRecorder traceRecorder;//链路追踪记录器

    private final Sinks.Many<String> sink;//Reactor 单播 Sink（SSE事件推送通道）    ——————————sink 是 Reactor 的单播通道，所有 SSE 事件都通过它推给前端
    private final StreamEventMetadata eventMetadata;
    private final String leaseKey;//租约的 key（租约键）
    private final String leaseOwnerToken;//租约的 owner token  （租约所有权的token）

    private final StringBuffer answerBuffer = new StringBuffer();//累积的回答文本 （答案累积缓冲区）————————————answerBuffer 用 StringBuffer（线程安全），因为模型输出和收尾落库可能在不同线程

    private final List<String> thinkingSteps;//思考步骤记录————————thinkingSteps、references、usedTools 都用线程安全容器，因为执行过程中多个组件会并发写入
    private final List<SearchReference> references;//引用来源————————thinkingSteps、references、usedTools 都用线程安全容器，因为执行过程中多个组件会并发写入
    private final Set<String> usedTools;//使用过的工具————————thinkingSteps、references、usedTools 都用线程安全容器，因为执行过程中多个组件会并发写入

    private final long startTime;//任务开始时间戳

    private final AtomicLong firstResponseTimeMs = new AtomicLong(0L);//首包耗时
    private final AtomicBoolean finalized = new AtomicBoolean(false);//CAS 标记，确保终结逻辑只执行一次    （是否已经结束，保证收尾只执行一次）——————————finalized 用 AtomicBoolean，通过 CAS 保证停止/成功/失败的收尾逻辑只执行一次



    //保证多线程间变量的**可见性**：一个线程修改了这个引用，其他线程能立刻读到最新值；禁止指令重排
    private volatile Disposable disposable;//主执行流的 Disposable（可丢弃可取消）**持有订阅关系，调用 `dispose()` 就可以中断任务、停止定时任务、释放资源**。
    private volatile Disposable leaseRenewalDisposable;//租约续期的 Disposable   看门狗续期任务的句柄 redission分布式锁：拿到锁之后 后台起一个定时任务，不断延长锁的过期时间 拿到这个变量后 停止自动续期

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
