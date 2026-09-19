package org.javaup.ai.chatagent.tool;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextHelper;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.config.TavilySearchProperties;
import org.javaup.ai.chatagent.model.debug.ChatDebugTrace;
import org.javaup.ai.chatagent.model.debug.ChatToolTrace;
import org.javaup.ai.chatagent.model.SearchReference;
import org.javaup.ai.chatagent.support.ChatContextKeys;
import org.javaup.ai.chatagent.support.RestClientFactorySupport;
import org.javaup.ai.chatagent.support.SinkEmitHelper;
import org.javaup.ai.chatagent.support.StreamEventMetadata;
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

/*
工具调用（React Agent）
开放式问答模式下，Agent 可以调用外部工具来获取信息。目前主要的工具是 Tavily 网络搜索。
 */
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
        this.restClient = RestClientFactorySupport.create(
            properties.getBaseUrl(),
            properties.getConnectTimeoutMs(),
            properties.getReadTimeoutMs()
        );
    }






    /*
    执行网络搜索
    search（）流程
1.构建有效查询 — buildEffectiveQuery(query, toolContext)
    如果问题涉及时间，自动附加当前日期上下文
2.解析搜索主题 — resolveTopic(request)
    支持三种主题：general、news、finance
3.调用 Tavily API — POST 请求，Bearer Token 认证
4.处理结果 — 把 API 返回的结果转成 SearchReference 列表
5.发布思考事件 — 通过 SSE 推送"正在搜索"的状态
6.追加引用 — 把搜索结果写入 ToolContext 的引用列表
7.记录工具追踪 — 记录调用耗时、结果数量等
     */
    /*
    方法一进来就做了三个前置校验：
    1.query 不能为空——没有搜索词就没法搜
    2.工具必须是启用状态——可以通过配置临时关闭
    3.API Key 必须已配置——没有 Key 调不了 Tavily API
    这三个校验任何一个不通过都会直接抛异常，拦截器链会捕获异常并转换成错误消息返回给模型。
     */
    public TavilySearchToolResult search(TavilySearchRequest request, ToolContext toolContext) {

        // 从工具入参中取出 query，并做 trim；request 或 query 为空时统一转成空串。
        String rawQuery = request != null && StrUtil.isNotBlank(request.getQuery()) ? request.getQuery().trim() : "";
        // query 是 Tavily 搜索的必填项，空 query 直接以参数异常结束。
        if (StrUtil.isBlank(rawQuery)) {
            throw new IllegalArgumentException("query 不能为空");
        }
        // 工具开关关闭时，不允许继续调用 Tavily。
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Tavily 搜索工具当前已禁用");
        }
        // API Key 缺失时无法完成鉴权，提前报错，避免发起无效 HTTP 请求。
        if (StrUtil.isBlank(properties.getApiKey())) {
            throw new IllegalStateException("Tavily API Key 未配置");
        }


        /*
        注册工具追踪与状态推送
        校验通过后，开始准备执行：
        1.记录开始时间——后面用来算耗时
        2.解析 topic——确定搜索主题（默认 general）
        3.注册工具追踪——往调试轨迹里写入一条工具执行记录，状态为 RUNNING
        4.标记工具已使用——往共享上下文的 usedTools 集合里加入 tavily_search
        5.推送 thinking 事件——让前端显示"正在联网搜索: xxx"
         */
        long startTime = System.currentTimeMillis();// 记录调用开始时间，用于后面计算工具执行耗时。
        String topic = resolveTopic(request);// 根据请求参数和默认配置解析合法 topic，不合法时会回退到 general。
        ChatToolTrace toolTrace = registerToolTrace(toolContext, ChatToolTrace.builder()// 注册工具调用 trace，初始状态为 RUNNING，便于前端或调试面板展示工具正在执行。
            .toolName("tavily_search")// 记录工具名，和 Agent 工具注册名保持一致。
            .status("RUNNING")// 初始状态标记为 RUNNING，成功或失败后再更新。
            .inputSummary(rawQuery) // inputSummary 保存原始搜索词，方便调试时快速确认 Agent 搜了什么。
            .topic(topic) // topic 保存最终搜索主题，用于判断搜索范围是 general/news/finance。
            .build());
        markToolUsed(toolContext, "tavily_search");// 将 tavily_search 标记为本轮已使用工具，供最终 trace 汇总使用。
        publishThinking(toolContext, "🔍 正在联网搜索: " + rawQuery);// 向前端推送搜索开始事件，让用户知道 Agent 正在联网搜索。

        /*
        构建有效查询
         */
        try {
            // 构造最终搜索词；如果当前日期可用，会补入时间上下文以提升时效性问题的检索质量。
            String effectiveQuery = buildEffectiveQuery(rawQuery, toolContext);
            // 如果 trace 注册成功，就把最终搜索词也写进去，区分原始 query 和时间增强后的 query。
            if (toolTrace != null) {
                toolTrace.setEffectiveInput(effectiveQuery);
            }

            /*
            调用Tavily API

             */
            // 发起 Tavily /search 请求，并把 JSON 响应反序列化为内部 response record。
            TavilySearchApiResponse response = restClient.post()
                .uri(properties.getSearchPath())// 使用配置里的搜索路径，默认是 /search。
                .header("Authorization", "Bearer " + properties.getApiKey())// Tavily 使用 Bearer Token 鉴权。
                .body(new TavilySearchApiRequest(// 构造 Tavily 请求体，包含 query、topic、深度、结果数和返回内容选项。
                    effectiveQuery,// 实际发送给 Tavily 的搜索词。
                    topic,// 已经过白名单校验后的搜索 topic。
                    properties.getSearchDepth(),// 搜索深度由配置控制，例如 basic 或 advanced。
                    request != null && request.getMaxResults() != null && request.getMaxResults() > 0// 请求级 maxResults 优先；没有传或传入非正数时使用配置默认值。
                        ? request.getMaxResults()
                        : properties.getMaxResults(),
                    properties.isIncludeAnswer(),// 是否让 Tavily 返回聚合答案。
                    properties.isIncludeRawContent() // 是否让 Tavily 返回 rawContent，当前结果转换只使用 content。
                ))
                .retrieve() // 触发 HTTP 请求并进入响应读取流程。
                .body(TavilySearchApiResponse.class);// 将响应体映射成 TavilySearchApiResponse
// Tavily 正常请求却没有响应体时，视为异常响应。
            if (response == null) {
                throw new IllegalStateException("Tavily 返回空响应");
            }


            /*
            处理搜索结果
            把 Tavily 返回的结果逐条转换成 SearchReference 对象。过滤掉没有 URL 的结果（无效数据）。每个 reference 包含标题、链接和内容摘要。
             */
            List<SearchReference> references = new ArrayList<>();// 用于累积最终要写入上下文和返回给 Agent 的搜索引用。
            // Tavily results 可能为空；非空时逐条转换为系统统一 SearchReference。
            if (response.results() != null) {
                // 遍历 Tavily 返回的每条搜索结果。
                for (TavilyResultItem item : response.results()) {
                    // URL 是引用来源的核心字段，没有 URL 的结果不能作为可追溯来源。
                    if (StrUtil.isBlank(item.url())) {
                        continue;
                    }
                    // 将 Tavily 的 title/url/content 转成系统统一的搜索引用对象。
                    references.add(new SearchReference(
                        item.title(),// 引用标题，可能为空但仍保留原值。
                        item.url(),// 引用 URL，用于前端展示和答案引用。
                        StrUtil.isNotBlank(item.content()) ? item.content() : ""// 摘要优先使用 content；为空时用空串兜底。
                    ));
                }
            }


            /*
            收集引用并推送状态
             */
            appendReferences(toolContext, references); // 把搜索引用写入 RunnableConfig 上下文，让最终回答或调试信息能拿到来源列表。
            publishThinking(toolContext, "📚 搜索完成，找到 " + references.size() + " 条候选来源"); // 向前端推送搜索完成事件，并带上候选来源数量。
            completeToolTrace(toolTrace, response, references.size(), startTime); // 更新工具 trace 为完成状态，并记录引用数量和执行耗时。


            /*
            返回工具结果
            最终返回一个 TavilySearchToolResult，包含实际使用的查询词、Tavily 生成的摘要答案、以及所有搜索引用。这个结果会被框架序列化后作为工具调用的响应返回给模型，模型会基于这些搜索结果来生成最终答案。
             */
            // 返回工具结果给 Agent：包含实际 query、Tavily answer 和引用列表快照。
            return new TavilySearchToolResult(
                effectiveQuery,// 返回时间增强后的最终搜索词，方便 Agent 或调试面板看到真实查询内容。
                StrUtil.isNotBlank(response.answer()) ? response.answer() : "", // Tavily 聚合答案为空时用空串兜底。
                List.copyOf(references) // 返回不可变副本，避免后续上下文引用列表变化影响工具结果。
            );
        }
        /*
        异常处理
        如果搜索过程中出了任何异常（网络超时、API 限流、响应解析失败等）：
            1.更新工具追踪为失败状态
            2.推送失败提示给前端
            3.记录警告日志
            4.重新抛出异常——让拦截器链的 ToolRetryInterceptor 来决定是否重试
         */
        catch (RuntimeException exception) {
            // 搜索请求、响应解析或上下文写入失败时，将工具 trace 标记为失败。
            failToolTrace(toolTrace, exception, startTime);
            // 向前端发送搜索失败提示，保留异常消息帮助用户理解失败原因。
            publishThinking(toolContext, "⚠️ 搜索失败: " + exception.getMessage());
            // 记录服务端 warn 日志，带上原始 query 和完整异常堆栈。
            log.warn("Tavily 搜索失败, query={}", rawQuery, exception);
            // 继续抛出原异常，让 Agent/上层统一错误处理流程接管。
            throw exception;
        }
    }


    /*
    如果用户的问题涉及时间（比如"最新的 Java 版本是什么"），这个方法会把当前日期信息附加到查询词中，让搜索引擎返回更时效性的结果。
     */
    //关键私有方法 时间敏感查询增强
    private String buildEffectiveQuery(String query, ToolContext toolContext) {
        // 空 query 不做时间增强，直接返回原值，保持上层参数校验后的语义。
        if (StrUtil.isBlank(query)) {
            return query;
        }
        // 根据当前日期构造更适合联网搜索的查询词，尤其适用于"最新/今天/最近"类问题。
        return TimeSensitiveQueryHelper.buildEffectiveSearchQuery(query, resolveCurrentDate(toolContext));
    }
    //关键私有方法 从上下文获取当前日期
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
    //关键私有方法 主题解析
    private String resolveTopic(TavilySearchRequest request) {

        String requestedTopic = normalizeTopic(request != null ? request.getTopic() : null);
        if (requestedTopic != null) {
            return requestedTopic;
        }

        String configuredTopic = normalizeTopic(properties.getTopic());
        if (configuredTopic != null) {
            return configuredTopic;
        }

        if (StrUtil.isNotBlank(properties.getTopic())) {
            log.warn("Tavily 默认 topic 配置不合法: {}, 自动回退为 general", properties.getTopic());
        }
        return "general";
    }
    //关键私有方法 主题标准化
    private String normalizeTopic(String rawTopic) {
        if (StrUtil.isBlank(rawTopic)) {
            return null;
        }

        String normalized = rawTopic.trim().toLowerCase(Locale.ROOT);
        if (ALLOWED_TOPICS.contains(normalized)) {
            return normalized;
        }

        log.warn("收到不受支持的 Tavily topic: {}, 允许值仅为 {}", rawTopic, ALLOWED_TOPICS);
        return null;
    }


    /*
    三步收尾：
    1.appendReferences——把搜索到的引用追加到共享上下文的引用列表中
    这些引用最终会在回答结束后作为"参考来源"展示给用户。
1.推送 thinking 事件——告诉前端搜索完成了，找到了多少条结果
2.完成工具追踪——更新追踪记录的状态为 COMPLETED，记录耗时和结果摘要
     */
    @SuppressWarnings("unchecked")
    //关键私有方法 追加引用到上下文
    private void appendReferences(ToolContext toolContext, List<SearchReference> references) {
        // 从工具上下文中取 RunnableConfig，引用列表容器存放在 config.context() 中。
        RunnableConfig config = ToolContextHelper.getConfig(toolContext).orElse(null);
        // 没有配置或没有新引用时无需写入上下文。
        if (config == null || references.isEmpty()) {
            return;
        }
        // 读取统一的 REFERENCES 容器，业务层会提前放入可变 List<SearchReference>。
        Object container = config.context().get(ChatContextKeys.REFERENCES);
        // 只有容器确实是 List 时才追加，避免上下文污染导致类型转换异常。
        if (container instanceof List<?> list) {
            // 泛型在运行期被擦除，这里依赖业务约定将 List<?> 转回 List<SearchReference>。
            ((List<SearchReference>) list).addAll(references);
        }
    }

    //关键私有方法 标记工具已使用
    @SuppressWarnings("unchecked")
    private void markToolUsed(ToolContext toolContext, String toolName) {
        RunnableConfig config = ToolContextHelper.getConfig(toolContext).orElse(null);
        if (config == null) {
            return;
        }

        Object container = config.context().get(ChatContextKeys.USED_TOOLS);
        if (container instanceof Set<?> set) {
            ((Set<String>) set).add(toolName);
        }
    }


    /*
    工具内部的 publishThinking 方法也是类似的思路——从 ToolContext 中取出 SSE sink，然后推送事件：
     */
    //关键私有方法 发布思考事件到 SSE
    @SuppressWarnings("unchecked")
    private void publishThinking(ToolContext toolContext, String content) {
        // 读取 RunnableConfig，用于获取 SSE sink、事件元数据和 thinkingSteps 容器。
        RunnableConfig config = ToolContextHelper.getConfig(toolContext).orElse(null);
        // 没有 RunnableConfig 时无法发事件，也无法记录思考步骤。
        if (config == null) {
            return;
        }

        // 从上下文读取前端事件 sink，只有存在 sink 时才能实时推送消息。
        Object sinkCandidate = config.context().get(ChatContextKeys.EVENT_SINK);
        // 解析事件元数据，保证 thinking 事件能带上 conversationId、exchangeId 等上下文。
        StreamEventMetadata metadata = resolveMetadata(config);
        // sink 类型匹配时，写入 thinking 事件。
        if (sinkCandidate instanceof Sinks.Many<?> sink) {
            // 将 Sinks.Many<?> 转为 Sinks.Many<String>，因为 SSE 流实际传输的是字符串事件。
            SinkEmitHelper.emitNext((Sinks.Many<String>) sink, streamEventWriter.thinking(content, metadata));
        }
        // 同步把 thinking 文案写入任务上下文中的步骤列表。
        Object stepsCandidate = config.context().get(ChatContextKeys.THINKING_STEPS);
        // 只有容器是 List 时才追加，避免上下文类型错误导致异常。
        if (stepsCandidate instanceof List<?> list) {
            // thinkingSteps 约定为 List<String>，用于最终调试信息或会话记录。
            ((List<String>) list).add(content);
        }
    }

    private StreamEventMetadata resolveMetadata(RunnableConfig config) {
        if (config == null) {
            return null;
        }
        Object metadataCandidate = config.context().get(ChatContextKeys.EVENT_METADATA);
        if (metadataCandidate instanceof StreamEventMetadata metadata) {
            return metadata;
        }
        return null;
    }



    /*
    它通过 ToolContext 拿到 RunnableConfig，再从 config 的上下文中取出 ChatDebugTrace 对象，把当前工具的追踪记录加进去。这样在 execute 方法的 doOnComplete 里就能把所有工具追踪一起写入 trace 阶段。
     */
    //关键私有方法 注册工具调用追踪
    private ChatToolTrace registerToolTrace(ToolContext toolContext, ChatToolTrace trace) {
        // trace 入参为空时没有可注册对象，直接返回 null。
        if (trace == null) {
            return null;
        }
        // 从工具上下文中读取 RunnableConfig，用于拿到 debugTrace 容器。
        RunnableConfig config = ToolContextHelper.getConfig(toolContext).orElse(null);
        // 如果没有 config，仍返回 trace 本身，后续 search 方法可以继续更新这个本地对象。
        if (config == null) {
            return trace;
        }
        // DEBUG_TRACE 保存整次对话的调试轨迹，其中包含工具调用轨迹列表。
        Object candidate = config.context().get(ChatContextKeys.DEBUG_TRACE);
        // 上下文里确实存在 ChatDebugTrace 时，把当前工具 trace 加入列表。
        if (candidate instanceof ChatDebugTrace debugTrace) {
            debugTrace.getToolTraces().add(trace);
        }
        // 返回 trace，供调用方继续更新状态、耗时、输出摘要或错误信息。
        return trace;
    }



    //关键私有方法 完成追踪记录
    private void completeToolTrace(ChatToolTrace toolTrace,
                                   TavilySearchApiResponse response,
                                   int referenceCount,
                                   long startTime) {
        if (toolTrace == null) {
            return;
        }
        toolTrace.setStatus("COMPLETED");
        toolTrace.setReferenceCount(referenceCount);
        toolTrace.setDurationMs(Math.max(0L, System.currentTimeMillis() - startTime));
        String answer = response == null ? "" : StrUtil.blankToDefault(response.answer(), "");
        if (StrUtil.isNotBlank(answer)) {
            toolTrace.setOutputSummary("联网结果已返回，答案摘要：" + clipText(answer, 160));
            return;
        }
        toolTrace.setOutputSummary("联网结果已返回，候选来源 " + referenceCount + " 条");
    }


    //关键私有方法 失败追踪记录
    private void failToolTrace(ChatToolTrace toolTrace, RuntimeException exception, long startTime) {
        if (toolTrace == null) {
            return;
        }
        toolTrace.setStatus("FAILED");
        toolTrace.setDurationMs(Math.max(0L, System.currentTimeMillis() - startTime));
        toolTrace.setErrorMessage(exception == null ? "" : StrUtil.blankToDefault(exception.getMessage(), ""));
    }

    private String clipText(String value, int maxLength) {
        if (StrUtil.isBlank(value) || maxLength <= 0) {
            return "";
        }
        String normalized = value.trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "...";
    }








    /*
    API请求模型

     */
    private record TavilySearchApiRequest(
        String query,//搜索查询
        String topic,//搜索主题（general/news/finance）
        @JsonProperty("search_depth")
        String searchDepth,//搜索深度（deep/quick）
        @JsonProperty("max_results")
        int maxResults,//最大结果数（1-100）
        @JsonProperty("include_answer")
        boolean includeAnswer,//是否包含AI摘要
        @JsonProperty("include_raw_content")
        boolean includeRawContent  //是否包含原始内容
    ) {
    }


    //API响应模型
    private record TavilySearchApiResponse(
        String answer,//AI 生成的摘要回答
        List<TavilyResultItem> results//搜索结果列表（title、url、content）
    ) {
    }

    private record TavilyResultItem(
        String title,
        String url,
        String content
    ) {
    }
}
