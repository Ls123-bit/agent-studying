package org.javaup.ai.chatagent.support;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextHelper;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.model.debug.ChatToolTrace;
import org.springframework.ai.chat.model.ToolContext;
import reactor.core.publisher.Sinks;

/*
工具侧上下文辅助（静态工具类）。
从 ToolContext 里读写 ReactAgent 共享状态的标准姿势，与 TavilySearchTool 内部的私有方法
语义保持一致（markToolUsed / publishThinking / registerToolTrace 等），
供新增工具（如 load_skill）复用，避免每个工具复制一份样板代码。
 */
@Slf4j
public final class ToolTraceSupport {

    private ToolTraceSupport() {
    }

    /*
    把工具名记入共享上下文的 USED_TOOLS 集合，最终随归档写入 used_tool_list。
     */
    @SuppressWarnings("unchecked")
    public static void markToolUsed(ToolContext toolContext, String toolName) {
        RunnableConfig config = ToolContextHelper.getConfig(toolContext).orElse(null);
        if (config == null) {
            return;
        }
        Object container = config.context().get(ChatContextKeys.USED_TOOLS);
        if (container instanceof java.util.Set<?> set) {
            ((java.util.Set<String>) set).add(toolName);
        }
    }

    /*
    向前端 SSE 推送 thinking 事件，并同步写入 THINKING_STEPS 列表（最终随归档落库）。
     */
    @SuppressWarnings("unchecked")
    public static void publishThinking(ToolContext toolContext, StreamEventWriter streamEventWriter, String content) {
        RunnableConfig config = ToolContextHelper.getConfig(toolContext).orElse(null);
        if (config == null) {
            return;
        }

        Object sinkCandidate = config.context().get(ChatContextKeys.EVENT_SINK);
        StreamEventMetadata metadata = resolveMetadata(config);
        if (sinkCandidate instanceof Sinks.Many<?> sink) {
            SinkEmitHelper.emitNext((Sinks.Many<String>) sink, streamEventWriter.thinking(content, metadata));
        }
        Object stepsCandidate = config.context().get(ChatContextKeys.THINKING_STEPS);
        if (stepsCandidate instanceof java.util.List<?> list) {
            ((java.util.List<String>) list).add(content);
        }
    }

    /*
    注册一条 RUNNING 状态的工具追踪记录到 ChatDebugTrace，返回 trace 供后续更新状态。
     */
    public static ChatToolTrace registerToolTrace(ToolContext toolContext, ChatToolTrace trace) {
        if (trace == null) {
            return null;
        }
        RunnableConfig config = ToolContextHelper.getConfig(toolContext).orElse(null);
        if (config == null) {
            return trace;
        }
        Object candidate = config.context().get(ChatContextKeys.DEBUG_TRACE);
        if (candidate instanceof org.javaup.ai.chatagent.model.debug.ChatDebugTrace debugTrace) {
            debugTrace.getToolTraces().add(trace);
        }
        return trace;
    }

    public static void completeToolTrace(ChatToolTrace trace, long startTime, String outputSummary) {
        if (trace == null) {
            return;
        }
        trace.setStatus("COMPLETED");
        trace.setDurationMs(Math.max(0L, System.currentTimeMillis() - startTime));
        if (StrUtil.isNotBlank(outputSummary)) {
            trace.setOutputSummary(outputSummary);
        }
    }

    public static void failToolTrace(ChatToolTrace trace, RuntimeException exception, long startTime) {
        if (trace == null) {
            return;
        }
        trace.setStatus("FAILED");
        trace.setDurationMs(Math.max(0L, System.currentTimeMillis() - startTime));
        trace.setErrorMessage(exception == null ? "" : StrUtil.blankToDefault(exception.getMessage(), ""));
    }

    private static StreamEventMetadata resolveMetadata(RunnableConfig config) {
        if (config == null) {
            return null;
        }
        Object metadataCandidate = config.context().get(ChatContextKeys.EVENT_METADATA);
        return metadataCandidate instanceof StreamEventMetadata metadata ? metadata : null;
    }
}
