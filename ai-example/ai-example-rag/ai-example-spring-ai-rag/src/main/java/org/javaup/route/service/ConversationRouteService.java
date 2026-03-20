package org.javaup.route.service;

import org.javaup.route.model.RouteHandledResult;
import org.javaup.route.model.RouteMessage;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

/**
 * 会话类通道服务。
 * 把“闲聊”和“引导澄清”这两类轻量对话收在一起，避免示例拆出太多小类。
 */
@Service
public class ConversationRouteService {

    private static final String CHITCHAT_SYSTEM_PROMPT = """
        你是 JavaUp 的学习助手，语气自然、轻松、友好。
        闲聊时不需要讲知识库内容，也不要突然展开成长篇技术回答。
        """;

    private static final String CLARIFY_PROMPT = """
        你是 JavaUp 学习平台助手，用户的问题还不够具体，请你发起一轮友好的追问。

        追问要求：
        - 一次只问 1 到 2 个关键问题。
        - 尽量给用户几个可选方向，降低回答门槛。
        - 口气自然，像真人客服，不要生硬。
        - 不要直接给最终答案，因为现在信息还不够。

        历史对话：
        {history}

        当前问题：
        {question}
        """;

    private final ChatClient chatClient;

    public ConversationRouteService(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    /**
     * 闲聊消息不需要走知识库，也不需要调工具。
     * 常见寒暄先固定回复，只有更自由的闲聊才交给模型。
     */
    public RouteHandledResult answerChitchat(String question, List<RouteMessage> history) {
        String normalized = StringUtils.hasText(question) ? question.trim().toLowerCase(Locale.ROOT) : "";
        if (normalized.contains("谢谢") || normalized.contains("感谢")) {
            return new RouteHandledResult("chitchat",
                "不客气，后面你想查课程规则、订单状态或者学习进度，都可以直接喊我。",
                question,
                List.of());
        }
        if (normalized.contains("你好") || normalized.contains("您好") || normalized.contains("hello") || normalized.contains("hi")) {
            return new RouteHandledResult("chitchat",
                "你好呀，我这边可以帮你区分该查知识库、查订单，还是该先把问题问清楚。",
                question,
                List.of());
        }
        String answer = chatClient.prompt()
            .system(CHITCHAT_SYSTEM_PROMPT)
            .user(user -> user.text("""
                历史对话：
                {history}

                用户消息：
                {question}
                """)
                .param("history", formatHistory(history))
                .param("question", question))
            .call()
            .content();
        return new RouteHandledResult("chitchat", answer, question, List.of());
    }

    /**
     * 问题太模糊时，不直接硬答，而是追问一轮，把缺失条件补齐。
     */
    public RouteHandledResult answerClarify(String question, List<RouteMessage> history) {
        String answer = chatClient.prompt()
            .user(user -> user.text(CLARIFY_PROMPT)
                .param("history", formatHistory(history))
                .param("question", question))
            .call()
            .content();
        return new RouteHandledResult("clarify", answer, question, List.of());
    }

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
}
