package org.javaup.route.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.javaup.route.model.RouteDecision;
import org.javaup.route.model.RouteIntent;
import org.javaup.route.model.RouteMessage;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 混合意图分类：同一个类里把三种方案串起来。
 * 1. classifyByRule 代表规则方案
 * 2. classifyByLlm 代表大模型方案
 * 3. classify 代表生产环境更常用的混合方案
 */
@Slf4j
@Service
public class RouteIntentClassifier {

    private static final Set<String> GREETING_WORDS = Set.of(
        "你好", "您好", "hi", "hello", "在吗", "谢谢", "感谢", "辛苦了", "拜拜", "再见"
    );

    private static final Set<String> TOOL_HINTS = Set.of(
        "订单", "订单号", "支付", "进度", "学到哪", "学员", "班级", "排期", "直播课", "作业"
    );

    private static final Set<String> AMBIGUOUS_QUERIES = Set.of(
        "推荐一下", "推荐", "怎么弄", "怎么办", "这个呢", "那个呢", "部署", "课程", "作业"
    );

    private static final String INTENT_PROMPT = """
        你是 JavaUp 学习平台的路由分类器，需要根据用户问题和历史对话判断这条消息应该走哪条通道。

        可选意图只有四种：
        1. knowledge：问的是平台通用规则、课程说明、退款政策、发票、回放、证书这类知识库问题。
        2. tool：问的是个人订单、学习进度、班级排期、已购课程状态等实时或个性化数据，需要查业务系统。
        3. chitchat：打招呼、感谢、寒暄，不需要检索，也不需要查工具。
        4. clarify：问题太泛、对象不明确、缺关键信息，必须先追问再继续。

        判断原则：
        - 出现“我的”“帮我查”“帮我看”“订单号”“学到哪了”这类明显的个人状态查询，优先判断为 tool。
        - 平台规则类问题，比如“发票怎么开”“回放多久出来”“证书怎么拿”，优先判断为 knowledge。
        - 不要因为问题短就直接判 clarify，要先结合历史消息。
        - 只有真的缺信息，回答了也会跑偏，才判 clarify。

        历史对话：
        {history}

        当前用户问题：
        {question}

        只输出 JSON，不要附带解释：
        {
          "intent": "knowledge|tool|chitchat|clarify",
          "confidence": 0.0,
          "reason": "一句中文理由"
        }
        """;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public RouteIntentClassifier(ChatClient.Builder builder, ObjectMapper objectMapper) {
        this.chatClient = builder.build();
        this.objectMapper = objectMapper;
    }

    /**
     * 对外统一入口。
     * 先走低成本规则层，规则判断不了再交给大模型。
     */
    public RouteDecision classify(String question, List<RouteMessage> history) {
        RouteDecision ruleDecision = classifyByRule(question, history);
        if (ruleDecision != null) {
            return ruleDecision;
        }

        // 规则兜不住，再走大模型；如果模型自己也拿不准，就保守回退到知识检索。
        RouteDecision llmDecision = classifyByLlm(question, history);
        if (llmDecision.getConfidence() < 0.45d) {
            return new RouteDecision(
                RouteIntent.KNOWLEDGE,
                0.45d,
                "fallback",
                "模型判定置信度偏低，先回退到知识检索兜底"
            );
        }
        return llmDecision;
    }

    /**
     * 规则层只处理“高置信度、低争议”的消息。
     * 目的是拦住最明显的 case，节省一次 LLM 调用。
     */
    private RouteDecision classifyByRule(String question, List<RouteMessage> history) {
        String normalized = normalize(question);
        if (!StringUtils.hasText(normalized)) {
            return new RouteDecision(RouteIntent.CLARIFY, 0.99d, "rule", "用户消息为空，先引导补充问题");
        }

        if (normalized.length() <= 12 && GREETING_WORDS.stream().anyMatch(normalized::contains)) {
            return new RouteDecision(RouteIntent.CHITCHAT, 0.98d, "rule", "短消息命中了寒暄规则");
        }

        if (isActionConfirmation(normalized, history)) {
            return new RouteDecision(RouteIntent.TOOL, 0.86d, "rule", "结合上一轮上下文，这是在确认继续执行查询或操作");
        }

        if (hasExplicitOrderId(normalized) || isToolQuestion(normalized)) {
            return new RouteDecision(RouteIntent.TOOL, 0.92d, "rule", "命中了订单或个人进度查询规则");
        }

        if (normalized.length() <= 4 || AMBIGUOUS_QUERIES.contains(normalized)) {
            return new RouteDecision(RouteIntent.CLARIFY, 0.80d, "rule", "问题过短或对象不明，先追问更稳妥");
        }

        return null;
    }

    /**
     * 规则层判断不了的消息，再交给大模型做语义分类。
     */
    private RouteDecision classifyByLlm(String question, List<RouteMessage> history) {
        try {
            String content = chatClient.prompt()
                .user(user -> user.text(INTENT_PROMPT)
                    .param("history", formatHistory(history))
                    .param("question", question))
                .call()
                .content();

            JsonNode root = objectMapper.readTree(extractJson(content));
            RouteIntent intent = RouteIntent.from(root.path("intent").asText());
            double confidence = root.path("confidence").asDouble(0.65d);
            String reason = root.path("reason").asText("大模型完成了语义分类");
            return new RouteDecision(intent, confidence, "llm", reason);
        }
        catch (Exception exception) {
            // 这里不把异常往外抛，是为了保证主链路始终可用。
            log.warn("意图识别解析失败，回退到知识检索: {}", exception.getMessage());
            return new RouteDecision(
                RouteIntent.KNOWLEDGE,
                0.55d,
                "fallback",
                "模型返回格式异常，已回退到知识检索"
            );
        }
    }

    /**
     * 工具通道通常既要有业务关键词，又要带一点“查我自己的”语气。
     */
    private boolean isToolQuestion(String normalized) {
        boolean containsToolHint = TOOL_HINTS.stream().anyMatch(normalized::contains);
        boolean containsPersonalContext = normalized.contains("我的")
            || normalized.contains("帮我查")
            || normalized.contains("帮我看")
            || normalized.contains("看下")
            || normalized.contains("查下");
        return containsToolHint && containsPersonalContext;
    }

    /**
     * 订单号属于高置信度特征，命中后基本就可以判成工具查询。
     */
    private boolean hasExplicitOrderId(String normalized) {
        return normalized.matches(".*ju[-_]?\\d{8}[-_]?\\d{4}.*");
    }

    /**
     * 处理“好的，继续吧”这类依赖上下文的确认语句。
     */
    private boolean isActionConfirmation(String normalized, List<RouteMessage> history) {
        if (history == null || history.isEmpty()) {
            return false;
        }
        boolean confirmMessage = Set.of("好的", "行", "确认", "那就帮我查吧", "那就继续", "帮我处理吧")
            .stream()
            .anyMatch(normalized::contains);
        if (!confirmMessage) {
            return false;
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            RouteMessage message = history.get(i);
            if ("assistant".equalsIgnoreCase(message.getRole())) {
                String assistantText = normalize(message.getContent());
                return assistantText.contains("订单号")
                    || assistantText.contains("学员id")
                    || assistantText.contains("班级编号")
                    || assistantText.contains("继续帮你查");
            }
        }
        return false;
    }

    /**
     * 把结构化历史整理成更适合 Prompt 理解的纯文本。
     */
    private String formatHistory(List<RouteMessage> history) {
        if (history == null || history.isEmpty()) {
            return "无历史对话";
        }
        StringBuilder builder = new StringBuilder();
        for (RouteMessage message : history) {
            String role = "assistant".equalsIgnoreCase(message.getRole()) ? "助手" : "用户";
            builder.append(role).append("：").append(message.getContent()).append("\n");
        }
        return builder.toString().strip();
    }

    /**
     * 兼容模型偶尔返回 markdown code block 的情况。
     */
    private String extractJson(String content) {
        if (!StringUtils.hasText(content)) {
            return "{\"intent\":\"knowledge\",\"confidence\":0.55,\"reason\":\"空响应\"}";
        }
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf('{');
            int end = trimmed.lastIndexOf('}');
            if (start >= 0 && end >= start) {
                return trimmed.substring(start, end + 1);
            }
        }
        return trimmed;
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "";
    }
}
