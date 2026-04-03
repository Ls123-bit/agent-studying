package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
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
 * 是因为当前业务不仅需要“改写一句话”，还需要一次性完成：</p>
 * <p>1. 指代消解。</p>
 * <p>2. 上下文补全。</p>
 * <p>3. 口语转书面。</p>
 * <p>4. 多问句拆分。</p>
 *
 * <p>这些能力组合在一起时，自定义一个结构化 JSON 输出会更稳，也更符合当前项目的编排需求。</p>
 */
@Slf4j
@Service
public class ChatQueryRewriteService {

    private static final String REWRITE_PROMPT = """
        你是业务知识问答的查询改写助手。
        请结合历史上下文和当前问题，输出一个 JSON：
        {
          "rewrite": "改写后的独立问题",
          "sub_questions": ["子问题1", "子问题2"]
        }

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
            return new RagRewriteResult(normalizedQuestion, ruleBasedSplit(normalizedQuestion));
        }

        try {
            /*
             * 真正需要改写时，才调用模型。
             * 提示词要求它一次性返回 rewrite 和 sub_questions，避免拆成多次调用。
             */
            String content = chatClient.prompt()
                .user(user -> user.text(REWRITE_PROMPT)
                    .param("history", StrUtil.isNotBlank(historySummary) ? historySummary : "无历史上下文")
                    .param("question", normalizedQuestion))
                .call()
                .content();
            RagRewriteResult parsed = parse(content);
            /*
             * 只有在模型返回了一个可用的 rewrittenQuestion 时，才信任这次改写结果。
             * 否则继续走兜底逻辑，避免把脏数据带进后续知识域解析和检索。
             */
            if (parsed != null && StrUtil.isNotBlank(parsed.getRewrittenQuestion())) {
                return parsed;
            }
        }
        catch (Exception exception) {
            /*
             * 改写失败不应该让整轮问答直接报错。
             * 这里记录日志后退回规则拆分，是为了保证主链路始终可继续执行。
             */
            log.warn("问题改写失败，回退到规则拆分: {}", exception.getMessage());
        }

        return new RagRewriteResult(normalizedQuestion, ruleBasedSplit(normalizedQuestion));
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
