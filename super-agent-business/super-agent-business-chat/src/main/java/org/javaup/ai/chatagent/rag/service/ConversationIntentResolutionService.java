package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.model.ConversationExchangeView;
import org.javaup.ai.chatagent.model.debug.ChatDebugTrace;
import org.javaup.ai.chatagent.rag.model.ConversationAnswerShape;
import org.javaup.ai.chatagent.rag.model.ConversationIntentRelationType;
import org.javaup.ai.chatagent.rag.model.ConversationIntentResolution;
import org.javaup.ai.chatagent.rag.model.ConversationRetrievalMode;
import org.javaup.ai.chatagent.service.ConversationTraceRecorder;
import org.javaup.ai.chatagent.service.ObservedChatModelService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 多轮会话关系解析服务。
 *
 * <p>它不负责真正检索，而是先回答一个更基础的问题：</p>
 * <p>“当前这句和上文到底是什么关系？”</p>
 *
 * <p>输出结果会被后续检索规划层消费，
 * 用来决定是继承当前主题、切换到新主题，还是把它当成一条完全独立的问题。</p>
 */
@Slf4j
@Service
public class ConversationIntentResolutionService {

    private static final double DEFAULT_CONFIDENCE = 0.75D;

    private static final String INTENT_PROMPT = """
        你是多轮文档问答系统的“会话关系解析器”。
        你的任务不是回答问题，而是判断“当前问题”和上文之间的关系。

        只输出一个 JSON：
        {{
          "relation_type": "FOLLOW_UP | TOPIC_SWITCH | FRESH_TOPIC | UNKNOWN",
          "resolved_topic": "当前真正想问的主题，尽量短、可检索",
          "resolved_facet": "当前想问的面向，例如 现象 / 可能原因 / 处理步骤 / 检查顺序 / 观察时长 / 章节 / 模块 / 层次，没有就留空字符串",
          "information_need": "当前真正想获取的信息需求描述，尽量具体，例如 列出该场景的现象 / 给出章节列表 / 说明检查顺序",
          "answer_shape": "LIST | STEPS | OUTLINE | COMPARISON | EXPLANATION | JUDGMENT | FACT | UNKNOWN",
          "retrieval_mode": "DIRECT_QUERY | SECTION_FOCUSED | ANALYTIC_DECOMPOSITION | UNKNOWN",
          "retrieval_query": "最终真正用于检索的短查询，尽量复用用户原词和文档标题词，不要抽象成'内容结构'这类泛词",
          "retrieval_sub_questions": ["真正需要拆开的检索子问题1", "子问题2"],
          "soft_section_hints": ["最值得优先命中的章节提示1", "章节提示2"],
          "query_context_hints": ["用于boost的上下文词1", "上下文词2"],
          "referenced_item_index": 1,
          "confidence": 0.0,
          "rationale": "一句简短解释"
        }}

        判断规则：
        1. FOLLOW_UP：当前问题依赖上文才能理解，且没有引入新的明确主题。
        2. TOPIC_SWITCH：当前问题表面承接上文，但已经明确引入了一个新的主题。
        3. FRESH_TOPIC：当前问题本身就是一条完整、独立、可检索的新问题，不应继承上文主题。
        4. 如果当前问题里已经明确说出新的主题，即使同时出现“这个问题/那个问题”等表达，也优先考虑 TOPIC_SWITCH 或 FRESH_TOPIC。
        5. resolved_topic 必须是当前真正要检索的主题，不要沿用旧主题。
        6. retrieval_query 必须面向检索友好，优先保留用户原词、目录词、章节词、标题词。
        7. 如果用户在问“包含哪些章节/都有哪些内容/包含哪些模块”，resolved_facet 与 retrieval_query 要尽量使用“章节/模块/层次/标题”等贴近目录的词，不要抽象成“内容结构”。
        8. soft_section_hints 优先填最可能命中的章节标题、目录词或层次词，但不要假装它们一定可作为硬过滤条件。
        9. information_need 要忠实表达“当前这句真正要什么”，不要被上一轮助手答案的论证结构带偏。
        10. retrieval_mode 只有在用户确实要从多个并列角度分析时，才用 ANALYTIC_DECOMPOSITION；如果只是同一主题下切到另一信息切面，应优先用 SECTION_FOCUSED。
        11. 如果 retrieval_mode 不是 ANALYTIC_DECOMPOSITION，retrieval_sub_questions 必须返回空数组。
        12. confidence 取 0 到 1 之间的小数。
        13. 不要输出解释性文字，只输出 JSON。

        最近完成轮次（从旧到新）：
        {recent_exchanges}

        上一轮锚点状态：
        {previous_anchor}

        当前问题：
        {question}
        """;

    private final ObservedChatModelService observedChatModelService;
    private final ObjectMapper objectMapper;

    public ConversationIntentResolutionService(ObservedChatModelService observedChatModelService, ObjectMapper objectMapper) {
        this.observedChatModelService = observedChatModelService;
        this.objectMapper = objectMapper;
    }

    /**
     * 解析当前问题与上文之间的关系。
     */
    public ConversationIntentResolution resolve(String question,
                                                List<ConversationExchangeView> recentCompletedExchanges,
                                                String previousAnchorDescription) {
        return resolve(question, recentCompletedExchanges, previousAnchorDescription, null);
    }

    /**
     * 解析当前问题与上文之间的关系，并记录模型调用观测。
     */
    public ConversationIntentResolution resolve(String question,
                                                List<ConversationExchangeView> recentCompletedExchanges,
                                                String previousAnchorDescription,
                                                ConversationTraceRecorder traceRecorder) {
        String normalizedQuestion = safeText(question);
        if (normalizedQuestion.isBlank()) {
            return unknown();
        }
        if (recentCompletedExchanges == null || recentCompletedExchanges.isEmpty()) {
            return ConversationIntentResolution.builder()
                .relationType(ConversationIntentRelationType.FRESH_TOPIC)
                .resolvedTopic(normalizedQuestion)
                .resolvedFacet("")
                .informationNeed(normalizedQuestion)
                .answerShape(ConversationAnswerShape.UNKNOWN)
                .retrievalMode(ConversationRetrievalMode.DIRECT_QUERY)
                .confidence(1D)
                .rationale("没有上文时，当前问题默认为独立新问题。")
                .build();
        }

        try {
            String prompt = buildPrompt(normalizedQuestion, recentCompletedExchanges, previousAnchorDescription);
            String raw = observedChatModelService.callText("intent", null, prompt, traceRecorder);
            ConversationIntentResolution parsed = parse(raw);
            if (parsed != null && parsed.getRelationType() != null && parsed.getRelationType() != ConversationIntentRelationType.UNKNOWN) {
                parsed.setRawModelOutput(raw);
                log.info("会话关系解析完成: question='{}', relationType={}, resolvedTopic='{}', resolvedFacet='{}', informationNeed='{}', answerShape={}, retrievalMode={}, retrievalQuery='{}', retrievalSubQuestions={}, confidence={}, rationale='{}'",
                    normalizedQuestion,
                    parsed.getRelationType(),
                    parsed.getResolvedTopic(),
                    parsed.getResolvedFacet(),
                    parsed.getInformationNeed(),
                    parsed.getAnswerShape(),
                    parsed.getRetrievalMode(),
                    parsed.getRetrievalQuery(),
                    parsed.getRetrievalSubQuestions(),
                    parsed.getConfidence(),
                    parsed.getRationale());
                return parsed;
            }
            log.info("会话关系解析结果不可用，准备回退: question='{}', raw='{}'", normalizedQuestion, StrUtil.blankToDefault(raw, ""));
        }
        catch (Exception exception) {
            log.warn("会话关系解析失败，准备回退: {}", exception.getMessage());
        }
        return unknown();
    }

    private String buildPrompt(String question,
                               List<ConversationExchangeView> recentCompletedExchanges,
                               String previousAnchorDescription) {
        return INTENT_PROMPT
            .replace("{recent_exchanges}", renderRecentExchanges(recentCompletedExchanges))
            .replace("{previous_anchor}", StrUtil.blankToDefault(previousAnchorDescription, "无"))
            .replace("{question}", question);
    }

    private String renderRecentExchanges(List<ConversationExchangeView> recentCompletedExchanges) {
        StringBuilder builder = new StringBuilder();
        int fromIndex = Math.max(0, recentCompletedExchanges.size() - 4);
        for (int index = fromIndex; index < recentCompletedExchanges.size(); index++) {
            ConversationExchangeView exchange = recentCompletedExchanges.get(index);
            if (exchange == null) {
                continue;
            }
            builder.append("轮次").append(index - fromIndex + 1).append("：\n");
            builder.append("用户：").append(clipText(exchange.getQuestion(), 180)).append('\n');
            builder.append("助手：").append(clipText(exchange.getAnswer(), 400)).append('\n');
            ChatDebugTrace debugTrace = exchange.getDebugTrace();
            if (debugTrace != null && StrUtil.isNotBlank(debugTrace.getRetrievalAnchorResolvedQuestion())) {
                builder.append("检索锚点：主题=").append(StrUtil.blankToDefault(debugTrace.getRetrievalAnchorRootTopic(), ""))
                    .append("；面向=").append(StrUtil.blankToDefault(debugTrace.getRetrievalAnchorFacet(), ""))
                    .append("；检索问题=").append(StrUtil.blankToDefault(debugTrace.getRetrievalAnchorResolvedQuestion(), ""))
                    .append('\n');
            }
            builder.append('\n');
        }
        return builder.toString().trim();
    }

    private ConversationIntentResolution parse(String raw) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(extractJsonObject(raw));
            String relationTypeText = root.path("relation_type").asText("").trim();
            ConversationIntentRelationType relationType = parseRelationType(relationTypeText);
            String resolvedTopic = root.path("resolved_topic").asText("").trim();
            String resolvedFacet = root.path("resolved_facet").asText("").trim();
            String informationNeed = root.path("information_need").asText("").trim();
            ConversationAnswerShape answerShape = parseAnswerShape(root.path("answer_shape").asText("").trim());
            ConversationRetrievalMode retrievalMode = parseRetrievalMode(root.path("retrieval_mode").asText("").trim());
            String retrievalQuery = root.path("retrieval_query").asText("").trim();
            List<String> retrievalSubQuestions = readStringArray(root.path("retrieval_sub_questions"));
            List<String> softSectionHints = readStringArray(root.path("soft_section_hints"));
            List<String> queryContextHints = readStringArray(root.path("query_context_hints"));
            Integer referencedItemIndex = root.path("referenced_item_index").isNumber()
                ? root.path("referenced_item_index").asInt()
                : null;
            Double confidence = root.path("confidence").isNumber()
                ? root.path("confidence").asDouble()
                : DEFAULT_CONFIDENCE;
            String rationale = root.path("rationale").asText("").trim();
            return ConversationIntentResolution.builder()
                .relationType(relationType)
                .resolvedTopic(resolvedTopic)
                .resolvedFacet(resolvedFacet)
                .informationNeed(informationNeed)
                .answerShape(answerShape)
                .retrievalMode(retrievalMode)
                .retrievalQuery(retrievalQuery)
                .retrievalSubQuestions(retrievalSubQuestions)
                .softSectionHints(softSectionHints)
                .queryContextHints(queryContextHints)
                .referencedItemIndex(referencedItemIndex)
                .confidence(confidence)
                .rationale(rationale)
                .build();
        }
        catch (Exception exception) {
            log.warn("解析会话关系结果失败，raw={}", raw, exception);
            return null;
        }
    }

    private ConversationIntentRelationType parseRelationType(String relationTypeText) {
        if (StrUtil.isBlank(relationTypeText)) {
            return ConversationIntentRelationType.UNKNOWN;
        }
        try {
            return ConversationIntentRelationType.valueOf(relationTypeText.trim().toUpperCase());
        }
        catch (IllegalArgumentException exception) {
            return ConversationIntentRelationType.UNKNOWN;
        }
    }

    private ConversationAnswerShape parseAnswerShape(String answerShapeText) {
        if (StrUtil.isBlank(answerShapeText)) {
            return ConversationAnswerShape.UNKNOWN;
        }
        try {
            return ConversationAnswerShape.valueOf(answerShapeText.trim().toUpperCase());
        }
        catch (IllegalArgumentException exception) {
            return ConversationAnswerShape.UNKNOWN;
        }
    }

    private ConversationRetrievalMode parseRetrievalMode(String retrievalModeText) {
        if (StrUtil.isBlank(retrievalModeText)) {
            return ConversationRetrievalMode.UNKNOWN;
        }
        try {
            return ConversationRetrievalMode.valueOf(retrievalModeText.trim().toUpperCase());
        }
        catch (IllegalArgumentException exception) {
            return ConversationRetrievalMode.UNKNOWN;
        }
    }

    private String extractJsonObject(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return raw.trim();
        }
        return raw.substring(start, end + 1);
    }

    private ConversationIntentResolution unknown() {
        return ConversationIntentResolution.builder()
            .relationType(ConversationIntentRelationType.UNKNOWN)
            .resolvedTopic("")
            .resolvedFacet("")
            .informationNeed("")
            .answerShape(ConversationAnswerShape.UNKNOWN)
            .retrievalMode(ConversationRetrievalMode.UNKNOWN)
            .retrievalQuery("")
            .retrievalSubQuestions(List.of())
            .softSectionHints(List.of())
            .queryContextHints(List.of())
            .confidence(0D)
            .rationale("")
            .build();
    }

    private List<String> readStringArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> result = new java.util.ArrayList<>();
        node.forEach(item -> {
            String text = item.asText("").trim();
            if (StrUtil.isNotBlank(text)) {
                result.add(text);
            }
        });
        return result;
    }

    private String clipText(String text, int maxChars) {
        String normalized = safeText(text);
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        if (maxChars <= 1) {
            return "";
        }
        return normalized.substring(0, maxChars - 1) + "…";
    }

    private String safeText(String text) {
        return text == null ? "" : text.trim();
    }
}
