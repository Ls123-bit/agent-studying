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





/*
数据载体类  一次对话请求的所有运行时状态都装在这个对象里，贯穿整个生命周期
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

    private final Sinks.Many<String> sink;//Reactor 单播 Sink
    private final StreamEventMetadata eventMetadata;
    private final String leaseKey;//租约的 key
    private final String leaseOwnerToken;//租约的 owner token

    private final StringBuffer answerBuffer = new StringBuffer();//累积的回答文本

    private final List<String> thinkingSteps;//思考步骤记录
    private final List<SearchReference> references;//引用来源
    private final Set<String> usedTools;//使用过的工具

    private final long startTime;

    private final AtomicLong firstResponseTimeMs = new AtomicLong(0L);
    private final AtomicBoolean finalized = new AtomicBoolean(false);//CAS 标记，确保终结逻辑只执行一次

    private volatile Disposable disposable;//主执行流的 Disposable
    private volatile Disposable leaseRenewalDisposable;//租约续期的 Disposable

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
