package org.javaup.ai.chatagent.rag.retrieve.channel;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.config.TavilySearchProperties;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.support.TimeSensitiveQueryHelper;
import org.javaup.ai.manage.support.DocumentKnowledgeMetadataKeys;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

/**
 * 网页搜索检索通道。
 *
 * <p>它和当前 Tavily Tool 的定位不同：</p>
 * <p>1. Tavily Tool 属于 Agent 运行过程中的“工具调用”。</p>
 * <p>2. WebSearchChannel 属于回答前的“证据召回通道”。</p>
 *
 * <p>把网页搜索纳入 RetrievalChannel 体系后，
 * 外部网页结果就能和内部文档结果一起进入统一的去重、融合、重排和 Prompt 编排链路，
 * 从而支持真正的 KB + Web 混合证据回答。</p>
 */
@Slf4j
@Component
public class WebSearchRetrievalChannel implements RetrievalChannel {

    private static final Set<String> ALLOWED_TOPICS = Set.of("general", "news", "finance");

    private final TavilySearchProperties properties;
    private final RestClient restClient;

    public WebSearchRetrievalChannel(TavilySearchProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder()
            .baseUrl(properties.getBaseUrl())
            .build();
    }

    @Override
    public String channelName() {
        return "web";
    }

    @Override
    public boolean supports(ConversationExecutionPlan plan) {
        /*
         * 只有在知识问答场景下，且用户问题明确带有“最新/今天/当前”这类时效性语义时，
         * 才让网页搜索参与证据召回。
         * 这样可以避免普通文档问答也无脑联网，导致结果噪音变大、延迟变长。
         */
        return plan.isRequiresFreshSearch()
            && properties.isEnabled()
            && StrUtil.isNotBlank(properties.getApiKey());
    }

    @Override
    public RetrievalChannelResult retrieve(String subQuestion, ConversationExecutionPlan plan) {
        try {
            /*
             * 对网页搜索问题统一补绝对日期，是为了让“今天/最新”这类表达真正锚定到当前时间，
             * 减少搜索结果把历史日期误当成今天的情况。
             */
            String effectiveQuery = TimeSensitiveQueryHelper.buildEffectiveSearchQuery(
                subQuestion,
                plan.getCurrentDate() == null ? "" : plan.getCurrentDate().toString()
            );
            TavilySearchApiResponse response = restClient.post()
                .uri(properties.getSearchPath())
                .header("Authorization", "Bearer " + properties.getApiKey())
                .body(new TavilySearchApiRequest(
                    effectiveQuery,
                    normalizeTopic(properties.getTopic()),
                    properties.getSearchDepth(),
                    properties.getMaxResults(),
                    properties.isIncludeAnswer(),
                    properties.isIncludeRawContent()
                ))
                .retrieve()
                .body(TavilySearchApiResponse.class);
            if (response == null || response.results() == null || response.results().isEmpty()) {
                return new RetrievalChannelResult(channelName(), List.of());
            }

            List<Document> documents = new ArrayList<>();
            for (int index = 0; index < response.results().size(); index++) {
                TavilyResultItem item = response.results().get(index);
                if (StrUtil.isBlank(item.url())) {
                    /*
                     * URL 是网页证据最基本的定位信息。
                     * 如果连链接都没有，这条结果就不适合进入统一证据体系。
                     */
                    continue;
                }
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "WEB");
                metadata.put(DocumentKnowledgeMetadataKeys.CHANNEL, channelName());
                metadata.put(DocumentKnowledgeMetadataKeys.TITLE, StrUtil.blankToDefault(item.title(), item.url()));
                metadata.put(DocumentKnowledgeMetadataKeys.URL, item.url());
                metadata.put(DocumentKnowledgeMetadataKeys.TOOL_NAME, "tavily_search");
                /*
                 * Tavily 当前返回值里没有一个稳定的“相关度分数”，
                 * 这里先用排名倒数构造一个可比较的粗粒度分值，
                 * 让它至少能进入统一的候选裁剪和可视化展示。
                 */
                metadata.put(DocumentKnowledgeMetadataKeys.SCORE, 1D / (index + 1));
                documents.add(Document.builder()
                    .id("web-" + Math.abs(item.url().hashCode()))
                    /*
                     * text 字段统一承载“最适合后续回答使用的正文摘要”：
                     * - 优先 Tavily 的 content
                     * - 其次 answer
                     * - 最后退回 title
                     */
                    .text(StrUtil.blankToDefault(item.content(), StrUtil.blankToDefault(response.answer(), item.title())))
                    .metadata(metadata)
                    .score(1D / (index + 1))
                    .build());
            }
            return new RetrievalChannelResult(channelName(), documents);
        }
        catch (Exception exception) {
            log.warn("WebSearchChannel 调用 Tavily 失败，自动忽略本通道: {}", exception.getMessage());
            return new RetrievalChannelResult(channelName(), List.of());
        }
    }

    private String normalizeTopic(String rawTopic) {
        if (StrUtil.isBlank(rawTopic)) {
            return "general";
        }
        String normalized = rawTopic.trim().toLowerCase(Locale.ROOT);
        return ALLOWED_TOPICS.contains(normalized) ? normalized : "general";
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
