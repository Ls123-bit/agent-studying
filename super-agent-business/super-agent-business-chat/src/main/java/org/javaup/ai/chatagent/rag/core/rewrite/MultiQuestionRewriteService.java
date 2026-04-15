package org.javaup.ai.chatagent.rag.core.rewrite;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.model.ConversationExchangeView;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.service.ConversationTraceRecorder;
import org.javaup.ai.chatagent.service.ObservedChatModelService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 独立查询改写服务。
 *
 * <p>对标 ragent 的 MultiQuestionRewriteService。
 * 核心设计原则：<b>改写阶段完全独立，不依赖意图分类或导航等上游结果</b>，
 * 避免上游错误向下传播。</p>
 *
 * <p>与旧版 ChatQueryRewriteService 的关键区别：</p>
 * <ul>
 *   <li>只有一种 prompt 模式（无 CONSTRAINED 模式），改写不受任何约束</li>
 *   <li>输入是原始对话历史，而非经过意图解析加工后的上下文</li>
 *   <li>子问题拆分权归改写服务自己，不被上游垄断</li>
 *   <li>多层降级保证永远有结果：LLM → 原始问题 + 规则拆分</li>
 * </ul>
 */
@Slf4j
@Service("multiQuestionRewriteService")
public class MultiQuestionRewriteService implements ChatQueryRewriteService {

    /**
     * 单一改写 prompt，不再有受约束模式。
     *
     * <p>参考 ragent 的设计：低温度 + 简洁指令 + JSON 输出。
     * 用双花括号 {{}} 转义 JSON 示例中的花括号，避免被模板引擎误解析。</p>
     */
    private static final String REWRITE_PROMPT = """
            你是查询改写助手。根据对话历史，将用户的当前问题改写为独立、完整、适合检索的形式。

            规则：
            1. 将代词（它、这个、那个、上面、前面、刚才）替换成具体实体。
            2. 补全省略信息，让问题脱离上下文也能独立理解。
            3. 将口语表达改成更适合检索的书面表达。
            4. 如果问题包含多个独立子问题，拆分为 2~4 个子问题。
            5. 如果问题本身已经完整，尽量少改，不要过度发挥。
            6. 只返回合法 JSON，不要附加解释。

            输出格式：
            {{"rewrite": "改写后的完整问题", "sub_questions": ["子问题1", "子问题2"]}}
            如果只有一个问题，sub_questions 只包含 rewrite 的内容。

            对话历史：
            {history}

            当前问题：
            {question}
            """;

    private static final int MAX_HISTORY_TURNS = 4;

    private final ObservedChatModelService observedChatModelService;
    private final ObjectMapper objectMapper;
    private final ChatRagProperties properties;

    public MultiQuestionRewriteService(ObservedChatModelService observedChatModelService,
                                       ObjectMapper objectMapper,
                                       ChatRagProperties properties) {
        this.observedChatModelService = observedChatModelService;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public RewriteResult rewriteWithSplit(String question,
                                          List<ConversationExchangeView> recentHistory) {
        return rewriteWithSplit(question, recentHistory, null);
    }

    /**
     * 改写并拆分，支持 trace 记录。
     */
    public RewriteResult rewriteWithSplit(String question,
                                          List<ConversationExchangeView> recentHistory,
                                          ConversationTraceRecorder traceRecorder) {
        String normalized = StrUtil.trim(question);
        if (StrUtil.isBlank(normalized)) {
            return new RewriteResult("", List.of());
        }

        // 无历史且问题足够清晰时，跳过 LLM 调用
        String historyText = buildHistoryText(recentHistory);
        if (!properties.isRewriteEnabled() || !needsRewrite(normalized, historyText)) {
            RewriteResult fallback = buildFallback(normalized);
            log.info("[改写] 跳过LLM: question='{}', result={}", normalized, fallback);
            return fallback;
        }

        try {
            String prompt = REWRITE_PROMPT
                    .replace("{history}", StrUtil.isNotBlank(historyText) ? historyText : "无对话历史")
                    .replace("{question}", normalized);

            String content = observedChatModelService.callText("rewrite", null, prompt, traceRecorder);
            RewriteResult parsed = parse(content, normalized);
            if (parsed != null) {
                log.info("[改写] 完成: question='{}', rewritten='{}', subQuestions={}",
                        normalized, parsed.rewrittenQuestion(), parsed.subQuestions());
                return parsed;
            }
            log.info("[改写] LLM结果不可用，回退: question='{}', raw='{}'",
                    normalized, StrUtil.blankToDefault(content, ""));
        } catch (Exception e) {
            log.warn("[改写] LLM调用失败，回退到规则拆分: {}", e.getMessage());
        }

        RewriteResult fallback = buildFallback(normalized);
        log.info("[改写] 回退: question='{}', result={}", normalized, fallback);
        return fallback;
    }

    // ── 历史构建 ──

    /**
     * 从对话交换记录构建历史文本。
     *
     * <p>直接使用原始对话内容，不依赖任何中间处理结果（如导航决策），
     * 保证改写服务看到的是未失真的原始信息。</p>
     */
    private String buildHistoryText(List<ConversationExchangeView> recentHistory) {
        if (recentHistory == null || recentHistory.isEmpty()) {
            return "";
        }
        // 只取最近 MAX_HISTORY_TURNS 轮（参考 ragent：最近4条消息 = 2轮）
        List<ConversationExchangeView> recent = recentHistory.stream()
                .filter(e -> StrUtil.isNotBlank(e.getQuestion()) && StrUtil.isNotBlank(e.getAnswer()))
                .limit(MAX_HISTORY_TURNS)
                .toList();
        if (recent.isEmpty()) {
            return "";
        }
        return recent.stream()
                .map(e -> "用户: " + e.getQuestion().trim() + "\n助手: " + truncate(e.getAnswer().trim(), 200))
                .collect(Collectors.joining("\n\n"));
    }

    private String truncate(String text, int maxLen) {
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...";
    }

    // ── 改写判断 ──

    private boolean needsRewrite(String question, String historyText) {
        if (StrUtil.isBlank(historyText)) {
            // 无历史时，只有太短或多问句才需要改写
            return question.length() < 8 || containsSplitSymbols(question);
        }
        // 有历史时，放宽门槛——代词、省略主语更常见
        return question.length() < 12
                || containsPronoun(question)
                || containsSplitSymbols(question);
    }

    private boolean containsPronoun(String question) {
        return List.of("它", "这个", "那个", "上面", "前面", "刚才", "之前", "上一个")
                .stream().anyMatch(question::contains);
    }

    private boolean containsSplitSymbols(String question) {
        return question.contains("？") || question.contains("?")
                || question.contains("；") || question.contains(";");
    }

    // ── 解析 ──

    private RewriteResult parse(String raw, String originalQuestion) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        try {
            // 去除可能的 markdown 代码块包裹
            String cleaned = stripMarkdownCodeFence(raw.trim());
            JsonNode root = objectMapper.readTree(cleaned);

            String rewrite = root.path("rewrite").asText("").trim();
            if (StrUtil.isBlank(rewrite)) {
                return null;
            }

            List<String> subQuestions = new ArrayList<>();
            JsonNode subNode = root.path("sub_questions");
            if (subNode.isArray()) {
                subNode.forEach(item -> {
                    String text = item.asText("").trim();
                    if (StrUtil.isNotBlank(text)) {
                        subQuestions.add(text);
                    }
                });
            }

            List<String> finalSubs = subQuestions.isEmpty() ? List.of(rewrite) : subQuestions;
            if (finalSubs.size() > properties.getMaxSubQuestions()) {
                finalSubs = finalSubs.subList(0, properties.getMaxSubQuestions());
            }
            return new RewriteResult(rewrite, finalSubs);
        } catch (Exception e) {
            log.warn("[改写] JSON解析失败: raw={}", raw, e);
            return null;
        }
    }

    private String stripMarkdownCodeFence(String text) {
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline > 0) {
                text = text.substring(firstNewline + 1);
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3);
            }
        }
        return text.trim();
    }

    // ── 降级 ──

    private RewriteResult buildFallback(String question) {
        return new RewriteResult(question, ruleBasedSplit(question));
    }

    private List<String> ruleBasedSplit(String question) {
        List<String> result = Arrays.stream(question.split("[?？；;\\n]+"))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .limit(properties.getMaxSubQuestions())
                .toList();
        if (result.isEmpty()) {
            return List.of(question);
        }
        return new ArrayList<>(new LinkedHashSet<>(result));
    }
}
