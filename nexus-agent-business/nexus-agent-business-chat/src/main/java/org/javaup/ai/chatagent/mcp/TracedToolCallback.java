package org.javaup.ai.chatagent.mcp;

import cn.hutool.core.util.StrUtil;
import org.javaup.ai.chatagent.model.debug.ChatToolTrace;
import org.javaup.ai.chatagent.support.StreamEventWriter;
import org.javaup.ai.chatagent.support.ToolTraceSupport;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/*
MCP 工具的可观测性装饰器。

为什么需要它：tavily_search 和 load_skill 都是自己写的方法，内部直接调用 ToolTraceSupport
打点；而 MCP 工具是 SyncMcpToolCallbackProvider 生产的裸 ToolCallback，不经过任何打点逻辑，
导致前端"工具使用轨迹"里完全看不到 MCP 调用，used_tool_list 归档也缺一条。

为什么装饰器能拿到 ToolContext：Alibaba Graph 的 AgentToolNode.executeSyncTool 调用的是
ToolCallback.call(String, ToolContext) 这个两参版本，并且会给非 StateAwareToolCallback 的回调
注入 _AGENT_STATE_ / _AGENT_CONFIG_ / _AGENT_STATE_FOR_UPDATE_。所以这里重写两参 call 就能拿到
RunnableConfig，ToolTraceSupport 可以原样复用。

两条铁律：
1. getToolDefinition() / getToolMetadata() 必须原样透传。ToolCallLimitHook 按 definition.name()
   建限额，AgentToolNode 按 metadata.returnDirect() 决定是否提前结束；任何改写都会让治理静默失效。
2. 异常必须原样抛出，交给 ToolErrorInterceptor 兜底转成错误消息，不要在这里吞掉。
 */
public class TracedToolCallback implements ToolCallback {

    /*
    输入摘要的最大字符数。
    MCP 工具的入参可能是很大的 JSON，直接塞进 ChatToolTrace 会把 debug_trace_json 撑得很难看，
    这里只保留开头的可辨识片段。
     */
    private static final int INPUT_SUMMARY_MAX_CHARS = 200;

    private final String serverName;
    private final ToolCallback delegate;
    private final StreamEventWriter streamEventWriter;

    private TracedToolCallback(String serverName, ToolCallback delegate, StreamEventWriter streamEventWriter) {
        this.serverName = serverName;
        this.delegate = delegate;
        this.streamEventWriter = streamEventWriter;
    }

    /*
    包装入口。serverName 用于给轨迹和日志加前缀，便于定位是哪个 MCP server 出的问题。
     */
    public static ToolCallback wrap(String serverName, ToolCallback delegate, StreamEventWriter streamEventWriter) {
        if (delegate == null) {
            return null;
        }
        return new TracedToolCallback(serverName, delegate, streamEventWriter);
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    /*
    单参版本只在框架没有传入上下文时才会走到（例如手工调用），此时打点会退化为"只记录日志"，
    因为 ToolTraceSupport 在 ToolContext 为空时会直接跳过。
     */
    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String traceName = qualifiedName();
        long startTime = System.currentTimeMillis();
        ChatToolTrace toolTrace = ToolTraceSupport.registerToolTrace(toolContext, ChatToolTrace.builder()
            .toolName(traceName)
            .status("RUNNING")
            .inputSummary(summarize(toolInput))
            .build());
        ToolTraceSupport.markToolUsed(toolContext, traceName);
        ToolTraceSupport.publishThinking(toolContext, streamEventWriter, "🔧 正在调用工具: " + traceName);

        try {
            String result = delegate.call(toolInput, toolContext);
            ToolTraceSupport.completeToolTrace(toolTrace, startTime,
                "工具返回 " + (result == null ? 0 : result.length()) + " 字符");
            ToolTraceSupport.publishThinking(toolContext, streamEventWriter,
                "🔧 工具调用完成: " + traceName + "（" + (result == null ? 0 : result.length()) + " 字符）");
            return result;
        } catch (RuntimeException exception) {
            ToolTraceSupport.failToolTrace(toolTrace, exception, startTime);
            ToolTraceSupport.publishThinking(toolContext, streamEventWriter, "⚠️ 工具调用失败: " + traceName);
            throw exception;
        }
    }

    /*
    轨迹名统一成 server/tool 形式：多个 server 可能暴露同名工具（装配阶段已剔除重名，
    但人工排查时仍然需要一眼看出工具来自哪个 server）。
     */
    private String qualifiedName() {
        String toolName = getToolDefinition() == null ? null : getToolDefinition().name();
        String safeToolName = StrUtil.blankToDefault(toolName, "unknown_tool");
        return StrUtil.isBlank(serverName) ? safeToolName : serverName + "/" + safeToolName;
    }

    private String summarize(String toolInput) {
        if (StrUtil.isBlank(toolInput)) {
            return "";
        }
        String trimmed = toolInput.trim();
        return trimmed.length() <= INPUT_SUMMARY_MAX_CHARS
            ? trimmed
            : trimmed.substring(0, INPUT_SUMMARY_MAX_CHARS) + "...";
    }
}
