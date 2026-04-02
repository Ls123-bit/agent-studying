package org.javaup.ai.chatagent.support;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallExecutionContext;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 给 tavily_search 工具补一层“空参数自修复”能力。
 *
 * <p>问题背景是：某些模型会产出正确的工具名，但 arguments 为空字符串、空对象，
 * 甚至直接把 query 当成一段普通文本丢出来。
 * 而 Spring AI 的 FunctionToolCallback 会在真正进入 TavilySearchTool.search(...) 之前，
 * 先强校验 toolInput 必须是非空字符串；如果这里不提前修正，框架会直接抛
 * "toolInput cannot be null or empty"。</p>
 *
 * <p>因此，这个拦截器的职责不是替代真正的工具逻辑，而是在工具执行前把“明显不合法、
 * 但又有机会修复”的输入改写成 TavilySearchRequest 能接受的 JSON：</p>
 * <p>1. 如果 arguments 为空，就回退到当前用户问题作为 query。</p>
 * <p>2. 如果 arguments 是 JSON，但 query 字段缺失或为空，也自动补上 query。</p>
 * <p>3. 如果 arguments 根本不是 JSON，而是一段普通文本，就把它包成 {"query":"..."}。</p>
 *
 * <p>这样做的目的，是尽量把模型的轻微工具调用格式错误修正掉，避免一次本可成功的联网搜索
 * 因为入参格式问题而整轮失败。</p>
 */
@Component
public class TavilyToolInputFallbackInterceptor extends ToolInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TavilyToolInputFallbackInterceptor.class);
    private static final String TAVILY_TOOL_NAME = "tavily_search";

    private final ObjectMapper objectMapper;

    public TavilyToolInputFallbackInterceptor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "tavilyToolInputFallbackInterceptor";
    }

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        if (!TAVILY_TOOL_NAME.equals(request.getToolName())) {
            return handler.call(request);
        }

        /*
         * 先把本次工具调用的 arguments 规整成稳定的 JSON。
         * 如果能修，就继续把修好的请求交给真正的 ToolCallback；
         * 如果连回退 query 都拿不到，就返回更明确的工具错误，避免把底层断言异常直接暴露出来。
         */
        String normalizedArguments = normalizeArguments(request);
        if (StrUtil.isBlank(normalizedArguments)) {
            log.warn("工具 {} 缺少可用入参，toolCallId={}", request.getToolName(), request.getToolCallId());
            return ToolCallResponse.error(
                request.getToolCallId(),
                request.getToolName(),
                "tavily_search 工具缺少可用的 query 参数"
            );
        }

        if (normalizedArguments.equals(request.getArguments())) {
            return handler.call(request);
        }

        /*
         * ToolCallRequest.builder(request) 默认不会把 executionContext 自动拷过去，
         * 所以这里要把 executionContext 手动带回去。
         * 否则后续真正执行工具时，ToolContextHelper 就拿不到 RunnableConfig，工具里的 thinking/reference
         * 聚合也会一起失效。
         */
        ToolCallRequest.Builder builder = ToolCallRequest.builder(request)
            .arguments(normalizedArguments);
        request.getExecutionContext().ifPresent(builder::executionContext);

        log.warn("工具 {} 收到空或不规范的 arguments，已自动改写为 {}", request.getToolName(), normalizedArguments);
        return handler.call(builder.build());
    }

    private String normalizeArguments(ToolCallRequest request) {
        String arguments = request.getArguments();
        String fallbackQuery = resolveFallbackQuery(request);

        /*
         * 最常见的报错场景就是 arguments 直接为空。
         * 这时优先回退到当前用户问题，例如“帮我查一下杭州今天的天气”。
         */
        if (StrUtil.isBlank(arguments)) {
            return buildQueryPayload(fallbackQuery);
        }

        try {
            JsonNode rootNode = objectMapper.readTree(arguments);

            /*
             * 如果模型已经给了一个标准 JSON 对象，就尽量保留它原来的字段，
             * 只在 query 缺失时才补上回退值。
             */
            if (rootNode != null && rootNode.isObject()) {
                ObjectNode objectNode = ((ObjectNode) rootNode).deepCopy();
                if (StrUtil.isNotBlank(objectNode.path("query").asText())) {
                    return arguments;
                }
                if (StrUtil.isBlank(fallbackQuery)) {
                    return null;
                }
                objectNode.put("query", fallbackQuery);
                return objectMapper.writeValueAsString(objectNode);
            }

            /*
             * 有些模型会把 query 直接输出成一个 JSON 字符串值，例如：
             * "杭州今天的天气"
             * 这时把它包成标准工具入参即可。
             */
            if (rootNode != null && rootNode.isTextual() && StrUtil.isNotBlank(rootNode.asText())) {
                return buildQueryPayload(rootNode.asText().trim());
            }

            return buildQueryPayload(fallbackQuery);
        }
        catch (JsonProcessingException exception) {
            /*
             * 如果 arguments 根本不是 JSON，就把它当成一段普通查询文本处理。
             * 例如模型直接输出：
             * 杭州今天的天气
             * 那我们就自动包成 {"query":"杭州今天的天气"}。
             */
            if (StrUtil.isNotBlank(arguments)) {
                return buildQueryPayload(arguments.trim());
            }
            return buildQueryPayload(fallbackQuery);
        }
    }

    private String resolveFallbackQuery(ToolCallRequest request) {
        /*
         * ToolCallRequest 自带的 context 来源于 RunnableConfig.metadata()，
         * 而我们当前业务真正想用的是 RunnableConfig.context() 里那份运行时上下文。
         * 因此这里从 executionContext -> config -> context 里取原始问题。
         */
        return request.getExecutionContext()
            .map(ToolCallExecutionContext::config)
            .map(RunnableConfig::context)
            .map(context -> context.get(ChatContextKeys.QUESTION))
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .filter(StrUtil::isNotBlank)
            .map(String::trim)
            .orElse("");
    }

    private String buildQueryPayload(String query) {
        if (StrUtil.isBlank(query)) {
            return null;
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("query", query.trim());

        try {
            return objectMapper.writeValueAsString(payload);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("构造 tavily_search 入参 JSON 失败", exception);
        }
    }
}
