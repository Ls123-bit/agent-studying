package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.ConversationIntentResolution;
import org.javaup.ai.chatagent.rag.model.ConversationRetrievalMode;
import org.javaup.ai.chatagent.rag.model.RagRewriteResult;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 知识问答场景的问题改写服务。
 *
 * <p>这里不直接复用 Spring AI 的 QueryTransformer 组件，
 * 是因为当前业务需要在较稳定的结构输出里完成：</p>
 * <p>1. 指代消解。</p>
 * <p>2. 上下文补全。</p>
 * <p>3. 口语转书面。</p>
 *
 * <p>在“没有结构化意图规划结果”的兜底路径里，它仍可顺带输出规则级拆分；
 * 但在当前教学主链中，真正的子问题拆分职责已经收拢到语义规划层，
 * rewrite 只负责表达改写，不再承担最终拆分决策。</p>
 */
@Slf4j
@Service
public class ChatQueryRewriteService {

    private static final String REWRITE_PROMPT = """
        你是业务知识问答的查询改写助手。
        请结合历史上下文和当前问题，输出一个 JSON：
        {{
          "rewrite": "改写后的独立问题",
          "sub_questions": ["子问题1", "子问题2"]
        }}

        规则：
        1. 将代词替换成具体实体。
        2. 补全省略信息，让问题脱离上下文也能独立理解。
        3. 将口语表达改成更适合检索的书面表达。
        4. 如果是复合问题，拆成 2~4 个独立子问题。
        5. 如果问题本身已经完整，就尽量少改，不要过度发挥。
        6. 只返回合法 JSON，不要附加解释。

        历史上下文：
        {history}

        当前问题：
        {question}
        """;

    private static final String CONSTRAINED_REWRITE_PROMPT = """
        你是业务知识问答系统的“受约束查询改写器”。
        你不能重新定义用户的问题，只能在既定意图约束下做表达层改写。

        请输出一个 JSON：
        {{
          "rewrite": "改写后的独立问题"
        }}

        当前意图约束：
        - relation_type: {relation_type}
        - resolved_topic: {resolved_topic}
        - resolved_facet: {resolved_facet}
        - information_need: {information_need}
        - answer_shape: {answer_shape}
        - retrieval_mode: {retrieval_mode}
        - planned_retrieval_query: {planned_retrieval_query}
        - planned_sub_questions: {planned_sub_questions}

        规则：
        1. 改写必须忠实表达当前问题真正要的内容，不要被上一轮助手答案的论证结构带偏。
        2. retrieval_mode、planned_sub_questions 只是告诉你“最终检索如何规划”，你不能再次重做子问题拆分。
        3. 即使 retrieval_mode 是 ANALYTIC_DECOMPOSITION，你在这里也只负责生成一个表达清晰的 rewrite，最终子问题由语义规划层决定。
        4. rewrite 要尽量复用用户原词、目录词、章节词，不要抽象成“内容结构”“相关情况”这类泛词。
        5. 历史上下文只用于消解指代，不代表必须继承上一轮的答案角度。
        6. 只返回合法 JSON，不要附加解释。

        历史上下文：
        {history}

        当前问题：
        {question}
        """;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final ChatRagProperties properties;

    public ChatQueryRewriteService(ChatModel chatModel,
                                   ObjectMapper objectMapper,
                                   ChatRagProperties properties) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * 针对知识问答场景生成改写结果。
     */
    public RagRewriteResult rewrite(String question, String historySummary) {
        return rewrite(question, historySummary, null);
    }

    /**
     * 在结构化意图约束下生成改写结果。
     */
    public RagRewriteResult rewrite(String question,
                                    String historySummary,
                                    ConversationIntentResolution intentResolution) {
        /*
         * 先把用户问题规整成无首尾空白的版本。
         * 这一步放在最前面，可以避免后面提示词和规则判断反复 trim。
         */
        String normalizedQuestion = StrUtil.trim(question);
        if (StrUtil.isBlank(normalizedQuestion)) {
            return new RagRewriteResult("", List.of());
        }

        /*
         * 如果当前轮根本不需要改写，就不要硬调一次 LLM。
         * 这里直接回退成“原问题 + 规则拆分”，保证低延迟和可用性。
         */
        if (!properties.isRewriteEnabled() || !needsRewrite(normalizedQuestion, historySummary)) {
            RagRewriteResult fallbackResult = buildFallbackResult(normalizedQuestion, intentResolution);
            log.info("RAG改写跳过: question='{}', historyPresent={}, rewritten='{}', subQuestions={}",
                normalizedQuestion,
                StrUtil.isNotBlank(historySummary),
                fallbackResult.getRewrittenQuestion(),
                fallbackResult.getSubQuestions());
            return fallbackResult;
        }

        try {
            /*
             * 真正需要改写时，才调用模型。
             * 提示词要求它一次性返回 rewrite 和 sub_questions，避免拆成多次调用。
             */
            String prompt = buildRewritePrompt(normalizedQuestion, historySummary, intentResolution);
            String content = chatClient.prompt()
                .user(prompt)
                .call()
                .content();
            RagRewriteResult parsed = parse(content);
            /*
             * 只有在模型返回了一个可用的 rewrittenQuestion 时，才信任这次改写结果。
             * 否则继续走兜底逻辑，避免把脏数据带进后续文档检索。
             */
            if (parsed != null && StrUtil.isNotBlank(parsed.getRewrittenQuestion())) {
                RagRewriteResult normalizedParsed = normalizeForIntentOwnedSplit(parsed, normalizedQuestion, intentResolution);
                log.info("RAG改写完成: question='{}', historyPresent={}, rewritten='{}', subQuestions={}",
                    normalizedQuestion,
                    StrUtil.isNotBlank(historySummary),
                    normalizedParsed.getRewrittenQuestion(),
                    normalizedParsed.getSubQuestions());
                return normalizedParsed;
            }
            log.info("RAG改写结果不可用，准备回退: question='{}', historyPresent={}, rawContent='{}'",
                normalizedQuestion,
                StrUtil.isNotBlank(historySummary),
                StrUtil.blankToDefault(content, ""));
        }
        catch (Exception exception) {
            /*
             * 改写失败不应该让整轮问答直接报错。
             * 这里记录日志后退回规则拆分，是为了保证主链路始终可继续执行。
             */
            log.warn("问题改写失败，回退到规则拆分: {}", exception.getMessage());
        }

        RagRewriteResult fallbackResult = buildFallbackResult(normalizedQuestion, intentResolution);
        log.info("RAG改写回退: question='{}', historyPresent={}, rewritten='{}', subQuestions={}",
            normalizedQuestion,
            StrUtil.isNotBlank(historySummary),
            fallbackResult.getRewrittenQuestion(),
            fallbackResult.getSubQuestions());
        return fallbackResult;
    }

    private RagRewriteResult buildFallbackResult(String normalizedQuestion,
                                                 ConversationIntentResolution intentResolution) {
        if (usesIntentOwnedSplit(intentResolution)) {
            return new RagRewriteResult(normalizedQuestion, List.of(normalizedQuestion));
        }
        return new RagRewriteResult(normalizedQuestion, ruleBasedSplit(normalizedQuestion));
    }

    private RagRewriteResult normalizeForIntentOwnedSplit(RagRewriteResult parsed,
                                                          String originalQuestion,
                                                          ConversationIntentResolution intentResolution) {
        if (!usesIntentOwnedSplit(intentResolution)) {
            return parsed;
        }
        String rewrite = StrUtil.blankToDefault(parsed == null ? "" : parsed.getRewrittenQuestion(), originalQuestion).trim();
        if (rewrite.isBlank()) {
            rewrite = originalQuestion;
        }
        return new RagRewriteResult(rewrite, List.of(rewrite));
    }

    private boolean usesIntentOwnedSplit(ConversationIntentResolution intentResolution) {
        return intentResolution != null
            && intentResolution.getRetrievalMode() != null
            && intentResolution.getRetrievalMode() != ConversationRetrievalMode.UNKNOWN;
    }

    private String buildRewritePrompt(String question,
                                      String historySummary,
                                      ConversationIntentResolution intentResolution) {
        /*
         * 这里显式在业务层完成字符串拼装，而不是依赖模板引擎的占位符替换。
         * 原因是提示词里本身包含 JSON 示例，模板引擎很容易把 JSON 花括号误当成模板语法。
         */
        if (intentResolution == null || intentResolution.getRetrievalMode() == null || intentResolution.getRetrievalMode() == ConversationRetrievalMode.UNKNOWN) {
            return REWRITE_PROMPT
                .replace("{history}", StrUtil.isNotBlank(historySummary) ? historySummary : "无历史上下文")
                .replace("{question}", StrUtil.blankToDefault(question, ""));
        }
        String plannedSubQuestions = intentResolution.getRetrievalSubQuestions() == null || intentResolution.getRetrievalSubQuestions().isEmpty()
            ? "[]"
            : intentResolution.getRetrievalSubQuestions().toString();
        return CONSTRAINED_REWRITE_PROMPT
            .replace("{relation_type}", String.valueOf(intentResolution.getRelationType()))
            .replace("{resolved_topic}", StrUtil.blankToDefault(intentResolution.getResolvedTopic(), ""))
            .replace("{resolved_facet}", StrUtil.blankToDefault(intentResolution.getResolvedFacet(), ""))
            .replace("{information_need}", StrUtil.blankToDefault(intentResolution.getInformationNeed(), ""))
            .replace("{answer_shape}", String.valueOf(intentResolution.getAnswerShape()))
            .replace("{retrieval_mode}", String.valueOf(intentResolution.getRetrievalMode()))
            .replace("{planned_retrieval_query}", StrUtil.blankToDefault(intentResolution.getRetrievalQuery(), ""))
            .replace("{planned_sub_questions}", plannedSubQuestions)
            .replace("{history}", StrUtil.isNotBlank(historySummary) ? historySummary : "无历史上下文")
            .replace("{question}", StrUtil.blankToDefault(question, ""));
    }

    /**
     * 用简单规则过滤掉不必要的改写调用，减少额外的 LLM 成本。
     */
    private boolean needsRewrite(String question, String historySummary) {
        /*
         * 没有历史时，只把明显太短或疑似多问句的问题送去改写。
         * 这样可以避免“本来已经很完整的问题”还额外浪费一次模型调用。
         */
        if (StrUtil.isBlank(historySummary)) {
            return question.length() < 8 || containsSplitSymbols(question);
        }
        /*
         * 有历史时，触发改写的门槛会放宽，因为代词、省略主语这类问题更常见。
         */
        return question.length() < 12
            || containsPronoun(question)
            || containsSplitSymbols(question);
    }

    private boolean containsPronoun(String question) {
        /*
         * 这里列的是当前最容易让检索模块丢上下文的指代词。
         * 只要命中一个，就优先认为需要让改写模型做上下文补全。
         */
        return Arrays.stream(new String[]{"它", "这个", "那个", "上面", "前面", "刚才", "之前"})
            .anyMatch(question::contains);
    }

    private boolean containsSplitSymbols(String question) {
        /*
         * 多问句通常会带问号、分号、逗号等结构提示。
         * 这里先用极轻量规则拦一层，尽量在真正调用模型前就知道它可能需要拆分。
         */
        return question.contains("？") || question.contains("?") || question.contains("；") || question.contains(";") || question.contains("，");
    }

    /**
     * 解析模型返回的 JSON。
     */
    private RagRewriteResult parse(String raw) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        try {
            /*
             * 这里假设模型会严格返回 JSON。
             * 如果解析失败，说明模型输出结构不可信，后面必须立即回退。
             */
            JsonNode root = objectMapper.readTree(raw.trim());
            String rewrite = root.path("rewrite").asText("").trim();
            List<String> parsedSubQuestions = new ArrayList<>();
            JsonNode subQuestionNode = root.path("sub_questions");
            if (subQuestionNode.isArray()) {
                /*
                 * 这里只收“非空字符串”子问题，其他脏结构全部忽略。
                 */
                subQuestionNode.forEach(item -> {
                    String text = item.asText("").trim();
                    if (StrUtil.isNotBlank(text)) {
                        parsedSubQuestions.add(text);
                    }
                });
            }
            if (StrUtil.isBlank(rewrite)) {
                /*
                 * rewrite 是这次结构化改写结果里最关键的字段。
                 * 它缺失就说明结果不可用，必须回退。
                 */
                return null;
            }
            List<String> subQuestions = parsedSubQuestions;
            if (subQuestions.isEmpty()) {
                /*
                 * 如果模型没有给子问题列表，就把 rewrite 本身当成唯一子问题。
                 * 这样后面的检索链路仍然能按统一结构继续执行。
                 */
                subQuestions = List.of(rewrite);
            }
            if (subQuestions.size() > properties.getMaxSubQuestions()) {
                /*
                 * 子问题数量上限是产品层约束，不是模型自己决定的。
                 * 这里强制裁剪，是为了避免一次问题被拆得过细导致检索成本失控。
                 */
                subQuestions = subQuestions.subList(0, properties.getMaxSubQuestions());
            }
            return new RagRewriteResult(rewrite, subQuestions);
        }
        catch (Exception exception) {
            /*
             * 这里不把异常往上抛，而是返回 null 交给上层统一兜底。
             * 这样 rewrite(...) 方法里的回退逻辑能保持集中处理。
             */
            log.warn("解析问题改写结果失败，raw={}", raw, exception);
            return null;
        }
    }

    /**
     * 规则兜底拆分。
     */
    private List<String> ruleBasedSplit(String question) {
        /*
         * 规则拆分只承担兜底职责：
         * 1. 先按典型问句分隔符拆开
         * 2. 再去空、限流
         * 3. 最后去重
         */
        List<String> result = Arrays.stream(question.split("[?？；;\\n]+"))
            .map(String::trim)
            .filter(StrUtil::isNotBlank)
            .limit(properties.getMaxSubQuestions())
            .toList();
        if (result.isEmpty()) {
            /*
             * 连规则都拆不出来时，就把整个问题原样保留成唯一子问题。
             */
            return List.of(question);
        }
        return new ArrayList<>(new LinkedHashSet<>(result));
    }
}
