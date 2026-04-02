package org.javaup.ai.chatagent.tool;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextHelper;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.config.TavilySearchProperties;
import org.javaup.ai.chatagent.model.SearchReference;
import org.javaup.ai.chatagent.support.ChatContextKeys;
import org.javaup.ai.chatagent.support.SinkEmitHelper;
import org.javaup.ai.chatagent.support.StreamEventWriter;
import org.javaup.ai.chatagent.support.TimeSensitiveQueryHelper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Slf4j
@Component
public class TavilySearchTool {

    private static final Set<String> ALLOWED_TOPICS = Set.of("general", "news", "finance");

    private final TavilySearchProperties properties;
    private final StreamEventWriter streamEventWriter;
    private final RestClient restClient;

    public TavilySearchTool(TavilySearchProperties properties, StreamEventWriter streamEventWriter) {
        this.properties = properties;
        this.streamEventWriter = streamEventWriter;
        this.restClient = RestClient.builder()
            .baseUrl(properties.getBaseUrl())
            .build();
    }

    /**
     * ReactAgent 的联网搜索工具入口。
     *
     * <p>这个方法做了三件事：</p>
     * <p>1. 调 Tavily 搜索接口拿网页结果。</p>
     * <p>2. 把搜索得到的来源链接写回 RunnableConfig 上下文，供最终 reference 事件使用。</p>
     * <p>3. 在流式过程中通过 thinking 事件告诉前端“正在搜索”和“搜索完成”。</p>
     *
     * <p>也就是说，这里既是工具执行器，也是产品体验的一部分。</p>
     */
    public TavilySearchToolResult search(TavilySearchRequest request, ToolContext toolContext) {
        /*
         * 先校验本次工具调用的最小必要条件。
         * query、工具开关和 API Key 缺一不可，否则后面的 HTTP 调用没有意义。
         */
        String rawQuery = request != null && StrUtil.isNotBlank(request.getQuery()) ? request.getQuery().trim() : "";
        if (StrUtil.isBlank(rawQuery)) {
            throw new IllegalArgumentException("query 不能为空");
        }
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Tavily 搜索工具当前已禁用");
        }
        if (StrUtil.isBlank(properties.getApiKey())) {
            throw new IllegalStateException("Tavily API Key 未配置");
        }

        /*
         * 在真正发请求前，先把工具使用痕迹和 thinking 事件写入上下文。
         * 这样无论后面请求成功还是失败，前端和数据库都能看到这次工具尝试。
         */
        markToolUsed(toolContext, "tavily_search");
        publishThinking(toolContext, "🔍 正在联网搜索: " + rawQuery);

        try {
            /*
             * 对所有明显带有“今天/现在/当前/最新/本周/本月/今年”语义的问题，
             * 都统一追加当前绝对日期，而不再只针对天气做特殊处理。
             *
             * 例如：
             * - “查一下北京的天气” -> “查一下北京的天气 2026-03-28 今天”
             * - “北京限号” -> “北京限号 2026-03-28 今天”
             * - “最新美元汇率” -> “最新美元汇率 2026-03-28 最新”
             *
             * 这样搜索结果会更稳定地锚定到当前日期，减少模型把旧日期误说成今天。
             */
            String effectiveQuery = buildEffectiveQuery(rawQuery, toolContext);

            /*
             * 先把 topic 归一化，再调用 Tavily 接口。
             * 这里既兼容模型传来的 topic，也兼容配置里的默认 topic。
             */
            String topic = resolveTopic(request);
            TavilySearchApiResponse response = restClient.post()
                .uri(properties.getSearchPath())
                .header("Authorization", "Bearer " + properties.getApiKey())
                .body(new TavilySearchApiRequest(
                    effectiveQuery,
                    topic,
                    properties.getSearchDepth(),
                    request != null && request.getMaxResults() != null && request.getMaxResults() > 0
                        ? request.getMaxResults()
                        : properties.getMaxResults(),
                    properties.isIncludeAnswer(),
                    properties.isIncludeRawContent()
                ))
                .retrieve()
                .body(TavilySearchApiResponse.class);

            if (response == null) {
                throw new IllegalStateException("Tavily 返回空响应");
            }

            /*
             * 工具对外只保留最适合前端展示和引用聚合的字段，
             * 比如标题、链接和简要内容摘要。
             */
            List<SearchReference> references = new ArrayList<>();
            if (response.results() != null) {
                for (TavilyResultItem item : response.results()) {
                    if (StrUtil.isBlank(item.url())) {
                        continue;
                    }
                    references.add(new SearchReference(
                        item.title(),
                        item.url(),
                        StrUtil.isNotBlank(item.content()) ? item.content() : ""
                    ));
                }
            }

            /*
             * 搜索结果一方面作为工具返回值交给 ReactAgent 继续推理，
             * 另一方面也额外存进上下文，供最终的 reference 事件统一展示。
             */
            appendReferences(toolContext, references);
            publishThinking(toolContext, "📚 搜索完成，找到 " + references.size() + " 条候选来源");

            return new TavilySearchToolResult(
                effectiveQuery,
                StrUtil.isNotBlank(response.answer()) ? response.answer() : "",
                List.copyOf(references)
            );
        }
        catch (RuntimeException exception) {
            /*
             * 工具失败时同样补一条 thinking，方便前端感知和后续问题排查。
             */
            publishThinking(toolContext, "⚠️ 搜索失败: " + exception.getMessage());
            log.warn("Tavily 搜索失败, query={}", rawQuery, exception);
            throw exception;
        }
    }

    private String buildEffectiveQuery(String query, ToolContext toolContext) {
        if (StrUtil.isBlank(query)) {
            return query;
        }

        /*
         * 统一通过 TimeSensitiveQueryHelper 判断是否需要做“绝对日期增强”。
         * 这套规则不再只看天气关键词，而是统一覆盖所有相对时间/强时效场景。
         */
        return TimeSensitiveQueryHelper.buildEffectiveSearchQuery(query, resolveCurrentDate(toolContext));
    }

    private String resolveCurrentDate(ToolContext toolContext) {
        RunnableConfig config = ToolContextHelper.getConfig(toolContext).orElse(null);
        if (config == null) {
            return "";
        }
        Object value = config.context().get(ChatContextKeys.CURRENT_DATE);
        if (value instanceof String text && StrUtil.isNotBlank(text)) {
            return text.trim();
        }
        return "";
    }

    /**
     * Tavily 当前只接受固定枚举 topic。
     *
     * <p>由于 topic 来自模型工具参数，模型有时会生成诸如 tech、web、search 之类的自由文本，
     * 如果直接透传给 Tavily，就会收到 400 Bad Request。
     * 因此这里统一做一次归一化：
     * 1. 先尝试用本次工具调用传入的 topic。</p>
     * <p>2. 如果本次 topic 非法，再退回配置文件中的默认 topic。</p>
     * <p>3. 如果连配置值也不合法，则最终兜底到 general。</p>
     */
    private String resolveTopic(TavilySearchRequest request) {
        /*
         * 优先尊重模型当前这次工具调用里给出的 topic，
         * 只有当它不合法时，才回退到配置文件中的默认值。
         */
        String requestedTopic = normalizeTopic(request != null ? request.getTopic() : null);
        if (requestedTopic != null) {
            return requestedTopic;
        }

        /*
         * 如果模型没有传 topic 或传了非法值，就尝试使用运维配置的默认 topic。
         */
        String configuredTopic = normalizeTopic(properties.getTopic());
        if (configuredTopic != null) {
            return configuredTopic;
        }

        if (StrUtil.isNotBlank(properties.getTopic())) {
            log.warn("Tavily 默认 topic 配置不合法: {}, 自动回退为 general", properties.getTopic());
        }
        return "general";
    }

    private String normalizeTopic(String rawTopic) {
        if (StrUtil.isBlank(rawTopic)) {
            return null;
        }

        /*
         * Tavily 的 topic 是固定枚举，因此先做 trim + 小写归一化，
         * 再判断是不是允许值之一。
         */
        String normalized = rawTopic.trim().toLowerCase(Locale.ROOT);
        if (ALLOWED_TOPICS.contains(normalized)) {
            return normalized;
        }

        log.warn("收到不受支持的 Tavily topic: {}, 允许值仅为 {}", rawTopic, ALLOWED_TOPICS);
        return null;
    }

    /**
     * 工具返回的是原始搜索结果，但前端真正需要的是“可展示的引用来源”。
     * 因此这里把链接信息缓存到 context，等回答结束后统一输出 reference 事件。
     */
    @SuppressWarnings("unchecked")
    private void appendReferences(ToolContext toolContext, List<SearchReference> references) {
        RunnableConfig config = ToolContextHelper.getConfig(toolContext).orElse(null);
        if (config == null || references.isEmpty()) {
            return;
        }

        /*
         * references 容器来自 BusinessChatService 提前塞进 RunnableConfig 的上下文，
         * 这里直接把本次搜索结果追加进去，等回答结束后统一去重输出。
         */
        Object container = config.context().get(ChatContextKeys.REFERENCES);
        if (container instanceof List<?> list) {
            ((List<SearchReference>) list).addAll(references);
        }
    }

    /**
     * 记录本轮实际用到了哪些工具，方便后续排查、审计或者做会话详情展示。
     */
    @SuppressWarnings("unchecked")
    private void markToolUsed(ToolContext toolContext, String toolName) {
        RunnableConfig config = ToolContextHelper.getConfig(toolContext).orElse(null);
        if (config == null) {
            return;
        }

        /*
         * usedTools 用 Set 存储，天然能去掉同一工具多次调用产生的重复名称。
         */
        Object container = config.context().get(ChatContextKeys.USED_TOOLS);
        if (container instanceof Set<?> set) {
            ((Set<String>) set).add(toolName);
        }
    }

    /**
     * 向流式链路注入 thinking 事件。
     *
     * <p>这些内容不会被当成最终答案正文，而是以独立事件流给前端，
     * 用来展示“正在联网搜索”“搜索完成”等过程感知信息。</p>
     */
    @SuppressWarnings("unchecked")
    private void publishThinking(ToolContext toolContext, String content) {
        RunnableConfig config = ToolContextHelper.getConfig(toolContext).orElse(null);
        if (config == null) {
            return;
        }

        /*
         * 当前业务对话链路统一走 SSE，因此工具过程提示会实时推给前端。
         * 这里仍然保留 sink 判空，是为了让工具在测试或独立复用场景下更稳。
         */
        Object sinkCandidate = config.context().get(ChatContextKeys.EVENT_SINK);
        if (sinkCandidate instanceof Sinks.Many<?> sink) {
            SinkEmitHelper.emitNext((Sinks.Many<String>) sink, streamEventWriter.thinking(content));
        }

        /*
         * 同时把 thinking 文本记到上下文里，
         * 方便最终持久化和会话详情回显。
         */
        Object stepsCandidate = config.context().get(ChatContextKeys.THINKING_STEPS);
        if (stepsCandidate instanceof List<?> list) {
            ((List<String>) list).add(content);
        }
    }

    private record TavilySearchApiRequest(
        String query,
        String topic,
        @JsonProperty("search_depth")
        String searchDepth,
        @JsonProperty("max_results")
        int maxResults,
        @JsonProperty("include_answer")
        boolean includeAnswer,
        @JsonProperty("include_raw_content")
        boolean includeRawContent
    ) {
    }

    private record TavilySearchApiResponse(
        String answer,
        List<TavilyResultItem> results
    ) {
    }

    private record TavilyResultItem(
        String title,
        String url,
        String content
    ) {
    }
}
