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

/*
入参兜底拦截器
它解决什么问题？
大模型在生成工具调用参数时，偶尔会出现以下情况：
参数为空字符串
参数是纯文本而不是 JSON
JSON 里没有 query 字段
query 字段值为空
这些情况如果直接传给 TavilySearchTool，要么参数校验失败，要么搜索结果不对。这个拦截器的作用就是在工具执行前修正参数，确保 tavily_search 总是能拿到有效的查询词。
————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————
逻辑分三种情况：
1.不是 tavily_search——直接放行，不干预其他工具
2.参数修正后仍然为空——返回错误响应给模型，让模型知道参数有问题
3.参数需要修正——用修正后的参数构建新请求，继续执行
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
        // 只拦截 tavily_search 工具，其他工具直接放行，不做任何干预。
        if (!TAVILY_TOOL_NAME.equals(request.getToolName())) {
            return handler.call(request);
        }
        // 对 tavily_search 的入参做归一化处理，确保 query 字段有效。
        String normalizedArguments = normalizeArguments(request);
        // 归一化后仍然为空，说明既没有有效入参也没有可用的兜底查询词，返回错误让模型知道参数有问题。
        if (StrUtil.isBlank(normalizedArguments)) {
            log.warn("工具 {} 缺少可用入参，toolCallId={}", request.getToolName(), request.getToolCallId());
            return ToolCallResponse.error(
                request.getToolCallId(),
                request.getToolName(),
                "tavily_search 工具缺少可用的 query 参数"
            );
        }
        // 归一化后和原始入参一致，说明入参本身就是合法的，直接放行。
        if (normalizedArguments.equals(request.getArguments())) {
            return handler.call(request);
        }
        // 入参被修正了，用修正后的参数构建新的请求对象。
        ToolCallRequest.Builder builder = ToolCallRequest.builder(request)
            .arguments(normalizedArguments);
        // 保留原始请求的执行上下文，确保工具执行时能访问到 RunnableConfig 等共享资源。
        request.getExecutionContext().ifPresent(builder::executionContext);

        log.warn("工具 {} 收到空或不规范的 arguments，已自动改写为 {}", request.getToolName(), normalizedArguments);
        // 用修正后的请求继续执行工具调用链。
        return handler.call(builder.build());
    }


    /*
    参数归一化逻辑
     */
    private String normalizeArguments(ToolCallRequest request) {
        // 取出原始入参字符串。
        String arguments = request.getArguments();
        // 从执行上下文中解析兜底查询词（用户的原始问题）。
        String fallbackQuery = resolveFallbackQuery(request);
        // 入参为空时，直接用兜底查询词构建标准 JSON。
        if (StrUtil.isBlank(arguments)) {
            return buildQueryPayload(fallbackQuery);
        }

        try {
            // 尝试将入参解析为 JSON。
            JsonNode rootNode = objectMapper.readTree(arguments);
            // 入参是合法的 JSON 对象时，检查 query 字段。
            if (rootNode != null && rootNode.isObject()) {
                ObjectNode objectNode = ((ObjectNode) rootNode).deepCopy();
                // query 字段有值，说明入参合法，直接返回原始入参。
                if (StrUtil.isNotBlank(objectNode.path("query").asText())) {
                    return arguments;
                }
                // query 为空且没有兜底查询词，无法修复，返回 null 让上层报错。
                if (StrUtil.isBlank(fallbackQuery)) {
                    return null;
                }
                // query 为空但有兜底查询词，补上 query 字段。
                objectNode.put("query", fallbackQuery);
                return objectMapper.writeValueAsString(objectNode);
            }

            // 入参是纯文本 JSON 节点（如 "Java 21"），把文本内容当作 query。
            if (rootNode != null && rootNode.isTextual() && StrUtil.isNotBlank(rootNode.asText())) {
                return buildQueryPayload(rootNode.asText().trim());
            }
            // 其他 JSON 类型（数组、数字等），用兜底查询词构建标准 JSON。
            return buildQueryPayload(fallbackQuery);
        }
        catch (JsonProcessingException exception) {
            // JSON 解析失败，说明入参不是合法 JSON，把整个字符串当作查询词。
            if (StrUtil.isNotBlank(arguments)) {
                return buildQueryPayload(arguments.trim());
            }
            // 入参也为空，最后用兜底查询词兜底。
            return buildQueryPayload(fallbackQuery);
        }
    }


    /*
    兜底查询词的来源
    兜底查询词从哪来？从 RunnableConfig 的上下文中取 QUESTION 字段——也就是用户的原始问题。逻辑是：如果模型没能正确生成搜索词，那就用用户问的问题本身作为搜索词，总比什么都不搜要好。
     */
    private String resolveFallbackQuery(ToolCallRequest request) {
        // 从执行上下文中逐层取值：ToolCallExecutionContext → RunnableConfig → context Map → QUESTION 字段。
        return request.getExecutionContext()
            .map(ToolCallExecutionContext::config)// 取出执行上下文中的 RunnableConfig。
            .map(RunnableConfig::context)// 取出 config 中的 context Map。
            .map(context -> context.get(ChatContextKeys.QUESTION))// 从 context Map 中取出用户原始问题。
                // 确保取出的值是 String 类型。
            .filter(String.class::isInstance)
            .map(String.class::cast)
                // 过滤掉空白字符串。
            .filter(StrUtil::isNotBlank)
            .map(String::trim)
                // 所有环节都取不到时，返回空串。
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
