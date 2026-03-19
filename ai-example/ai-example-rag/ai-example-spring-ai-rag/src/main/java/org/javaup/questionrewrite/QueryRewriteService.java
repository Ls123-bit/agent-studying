package org.javaup.questionrewrite;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自定义查询改写服务
 * 支持：指代消解 + 上下文补全 + 口语转书面
 */
@Slf4j
@Service
public class QueryRewriteService {

    private final ChatClient chatClient;

    private static final String REWRITE_PROMPT = """
        你是一个查询改写助手。将用户的当前提问改写为独立、完整、适合检索的查询语句。

        改写规则：
        1. 将代词替换为具体实体
        2. 补全省略的信息
        3. 口语化表达转为书面表达
        4. 如果提问已经完整清晰，原样输出
        5. 只输出改写后的查询，不要解释

        对话历史：
        {chat_history}

        当前提问：{question}
        """;

    /**
     * 改写结果缓存：同一session内相同问题+相同历史，改写结果可以缓存
     */
    private final Map<String, String> rewriteCache = new ConcurrentHashMap<>();

    public QueryRewriteService(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    /**
     * 单查询改写（带缓存）
     */
    public String rewriteWithCache(String sessionId, String question, List<Message> history) {
        String cacheKey = sessionId + ":" + question.hashCode();
        return rewriteCache.computeIfAbsent(cacheKey, k -> safeRewrite(question, history));
    }

    /**
     * 单查询改写：指代消解 + 上下文补全 + 口语转书面
     */
    public String rewrite(String question, List<Message> history) {
        // 先判断是否需要改写，节省不必要的LLM调用
        if (!needsRewrite(question, history)) {
            return question;
        }

        String historyText = formatHistory(history);
        String result = chatClient.prompt()
                .user(u -> u.text(REWRITE_PROMPT)
                        .param("chat_history", historyText)
                        .param("question", question))
                .call()
                .content();

        return validateResult(result, question);
    }

    /**
     * 带兜底的安全改写：LLM调用失败时回退到原始问题
     */
    public String safeRewrite(String question, List<Message> history) {
        try {
            String result = rewrite(question, history);
            if (result != null && !result.isBlank()
                    && result.length() < 500
                    && !result.equals(question)) {
                return result;
            }
            return question;
        } catch (Exception e) {
            log.warn("问题改写失败，回退到原始问题: {}", e.getMessage());
            return question;
        }
    }

    /**
     * 简单的启发式判断：是否需要改写
     */
    private boolean needsRewrite(String question, List<Message> history) {
        if (history == null || history.isEmpty()) {
            // 没有对话历史，只需要判断是否口语化（可选）
            return question.length() < 6; // 太短的问题可能需要补全
        }
        // 包含代词，大概率需要改写
        String[] pronouns = {"它", "这个", "那个", "他", "她", "上面", "刚才", "之前"};
        for (String p : pronouns) {
            if (question.contains(p)) return true;
        }
        // 问题很短，可能省略了信息
        return question.length() < 10;
    }

    private String validateResult(String result, String original) {
        if (result == null || result.isBlank() || result.length() > 500) {
            return original; // 改写失败或结果异常，回退到原始问题
        }
        return result.strip();
    }

    private String formatHistory(List<Message> history) {
        if (history == null || history.isEmpty()) return "无";
        StringBuilder sb = new StringBuilder();
        for (Message msg : history) {
            String role = msg instanceof UserMessage ? "用户" : "助手";
            sb.append(role).append("：").append(msg.getText()).append("\n");
        }
        return sb.toString();
    }
}
