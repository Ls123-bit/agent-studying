package org.javaup.ai.chatagent.rag.executor;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import cn.hutool.core.util.StrUtil;
import org.javaup.ai.chatagent.rag.model.ExecutionMode;
import org.javaup.ai.chatagent.rag.support.ExecutorEventSupport;
import org.javaup.ai.chatagent.model.trace.ConversationTraceStageCode;
import org.javaup.ai.chatagent.service.ConversationTraceRecorder;
import org.javaup.ai.chatagent.service.TaskInfo;
import org.javaup.ai.chatagent.support.StreamEventWriter;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: ReactAgent 执行器
 * @author: 阿星不是程序员
 **/
/**
 * ReactAgent 执行器。
 *
 * <p>这条路径主要服务开放式问题、联网搜索和未来工具型能力。
 * 它继续复用现有 Spring AI Alibaba Agent 体系，
 * 但对外仍然遵循统一的“只产出正文分片”接口。</p>
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
    public Flux<String> execute(TaskInfo taskInfo) {
        AtomicBoolean streamedText = new AtomicBoolean(false);
        ExecutorEventSupport.publishThinking(taskInfo, streamEventWriter, "当前问题进入开放式 Agent 自主执行阶段。");
        /*
         * 先把这轮为什么走 Agent 路径记进调试轨迹。
         * 后台观测页看到这条说明时，就能知道当前不是知识问答模式，而是开放式执行模式。
         */
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
                        taskInfo.traceRecorder().completeStage(agentStage, "ReAct Agent 执行完成。", java.util.Map.of(
                            "toolNames", taskInfo.debugTrace().getToolTraces() == null ? java.util.List.of() : taskInfo.debugTrace().getToolTraces(),
                            "usedTools", taskInfo.usedTools() == null ? java.util.List.of() : taskInfo.usedTools()
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
            /*
             * 这里不在执行器内吞掉异常，而是继续往上抛成 Flux.error，
             * 让 BusinessChatService 统一按 FAILED 流程收尾和落库。
             */
            if (taskInfo.traceRecorder() != null) {
                taskInfo.traceRecorder().failStage(agentStage, "ReAct Agent 执行失败。", exception.getMessage(), null);
            }
            return Flux.error(exception);
        }
    }

    /**
     * 从 Graph Runtime 的节点输出中提取当前这一帧真正可以展示给前端的正文分片。
     */
    private Mono<String> extractTextChunk(NodeOutput output, AtomicBoolean streamedText) {
        if (!(output instanceof StreamingOutput<?> streamingOutput)) {
            /*
             * 不是所有 NodeOutput 都代表可展示正文。
             * 工具节点、图推进节点等事件在这里统一跳过。
             */
            return Mono.empty();
        }

        String content = extractStreamingText(streamingOutput);
        if (StrUtil.isBlank(content)) {
            return Mono.empty();
        }

        if (streamingOutput.getOutputType() == OutputType.AGENT_MODEL_STREAMING) {
            /*
             * 真正的增量正文分片会走这里。
             * 一旦命中过一次 streaming，后面的 finished 帧就不应该再补发完整答案。
             */
            streamedText.set(true);
            return Mono.just(content);
        }

        if (streamingOutput.getOutputType() == OutputType.AGENT_MODEL_FINISHED) {
            /*
             * 有些模型会在结束帧里再补一份完整答案。
             * 如果前面已经真实流过正文，这里再发一次就会造成整段答案重复。
             */
            if (streamedText.get()) {
                return Mono.empty();
            }
            return Mono.just(content);
        }

        return Mono.empty();
    }

    /**
     * 兼容不同流式帧结构的正文读取方式。
     */
    private String extractStreamingText(StreamingOutput<?> streamingOutput) {
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
