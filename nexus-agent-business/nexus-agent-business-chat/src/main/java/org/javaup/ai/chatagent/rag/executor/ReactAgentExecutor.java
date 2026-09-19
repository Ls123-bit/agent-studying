package org.javaup.ai.chatagent.rag.executor;

import cn.hutool.core.util.StrUtil;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import org.javaup.ai.chatagent.model.trace.ConversationTraceStageCode;
import org.javaup.ai.chatagent.rag.model.ExecutionMode;
import org.javaup.ai.chatagent.rag.support.ExecutorEventSupport;
import org.javaup.ai.chatagent.service.ConversationTraceRecorder;
import org.javaup.ai.chatagent.service.TaskInfo;
import org.javaup.ai.chatagent.support.StreamEventWriter;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/*
开放式ReAct Agent
模式：ExecutionMode.REACT_AGENT
处理开放式问题，Agent 可以自主决定是否调用工具（比如网络搜索），支持多步推理。
执行流程：
1.调用 reactAgent.stream(agentQuestion, runnableConfig)
2.监听 NodeOutput 事件流
3.处理两种输出类型：AGENT_MODEL_STREAMING（流式文本）和 AGENT_MODEL_FINISHED（完成）
4.捕获 GraphRunnerException（Agent 执行异常）

安全限制：


 */
@Component
public class ReactAgentExecutor implements ConversationExecutor {

    private final ReactAgent reactAgent;
    private final StreamEventWriter streamEventWriter;

    public ReactAgentExecutor(ReactAgent businessChatReactAgent,
                              StreamEventWriter streamEventWriter) {
        this.reactAgent = businessChatReactAgent;
        this.streamEventWriter = streamEventWriter;
    }

    @Override
    public ExecutionMode mode() {
        return ExecutionMode.REACT_AGENT;
    }

    @Override
    public Flux<String> execute(TaskInfo taskInfo) {//启动 ReactAgent 流式执行
        AtomicBoolean streamedText = new AtomicBoolean(false);
        ExecutorEventSupport.publishThinking(taskInfo, streamEventWriter, "当前问题进入开放式 Agent 自主执行阶段。");

        taskInfo.debugTrace().getRetrievalNotes().add("当前问题走 ReactAgent 执行路径，由 Agent 自主决定是否调用联网搜索或其他工具。");
        ConversationTraceRecorder.StageHandle agentStage = taskInfo.traceRecorder() == null
            ? null
            : taskInfo.traceRecorder().startStage(
                ConversationTraceStageCode.REACT_AGENT,
                mode().name(),
                "正在执行 ReAct Agent 推理与工具调用。",
                null
            );
        try {
            return reactAgent.stream(taskInfo.executionPlan().getAgentQuestion(), taskInfo.runnableConfig())
                .publishOn(Schedulers.boundedElastic())
                .concatMap(output -> extractTextChunk(output, streamedText))
                .doOnComplete(() -> {
                    if (taskInfo.traceRecorder() != null) {
                        taskInfo.traceRecorder().completeStage(agentStage, "ReAct Agent 执行完成。", Map.of(
                            "toolNames", taskInfo.debugTrace().getToolTraces() == null ? List.of() : taskInfo.debugTrace().getToolTraces(),
                            "usedTools", taskInfo.usedTools() == null ? List.of() : taskInfo.usedTools()
                        ));
                    }
                })
                .doOnError(error -> {
                    if (taskInfo.traceRecorder() != null) {
                        taskInfo.traceRecorder().failStage(agentStage, "ReAct Agent 执行失败。", error.getMessage(), null);
                    }
                });
        }
        catch (GraphRunnerException exception) {

            if (taskInfo.traceRecorder() != null) {
                taskInfo.traceRecorder().failStage(agentStage, "ReAct Agent 执行失败。", exception.getMessage(), null);
            }
            return Flux.error(exception);
        }
    }

    private Mono<String> extractTextChunk(NodeOutput output, AtomicBoolean streamedText) {//从 Agent 输出中提取文本片段
        if (!(output instanceof StreamingOutput<?> streamingOutput)) {

            return Mono.empty();
        }

        String content = extractStreamingText(streamingOutput);
        if (StrUtil.isBlank(content)) {
            return Mono.empty();
        }

        if (streamingOutput.getOutputType() == OutputType.AGENT_MODEL_STREAMING) {

            streamedText.set(true);
            return Mono.just(content);
        }

        if (streamingOutput.getOutputType() == OutputType.AGENT_MODEL_FINISHED) {

            if (streamedText.get()) {
                return Mono.empty();
            }
            return Mono.just(content);
        }

        return Mono.empty();
    }

    private String extractStreamingText(StreamingOutput<?> streamingOutput) {//处理流式输出对象
        Message message = streamingOutput.message();
        if (message != null && StrUtil.isNotBlank(message.getText())) {
            return message.getText();
        }

        Object originData = streamingOutput.getOriginData();
        if (originData instanceof Message originMessage && StrUtil.isNotBlank(originMessage.getText())) {
            return originMessage.getText();
        }
        if (originData instanceof String text && StrUtil.isNotBlank(text)) {
            return text;
        }
        return "";
    }
}
