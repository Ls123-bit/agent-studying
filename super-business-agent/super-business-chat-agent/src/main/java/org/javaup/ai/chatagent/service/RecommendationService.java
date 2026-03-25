package org.javaup.ai.chatagent.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.javaup.ai.chatagent.config.ChatAgentProperties;
import org.javaup.ai.chatagent.model.ConversationTurnView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class RecommendationService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);

    private final ChatModel chatModel;
    private final ChatAgentProperties properties;
    private final ObjectMapper objectMapper;

    public RecommendationService(ChatModel chatModel,
                                 ChatAgentProperties properties,
                                 ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 生成推荐追问。
     *
     * <p>推荐问题不是 ReactAgent 主链路的一部分，而是回答结束后的附加增强能力。
     * 这里会把最近几轮上下文和当前问答拼成一个独立 prompt，
     * 再额外调用一次模型生成最多 3 条可继续追问的问题。</p>
     */
    public List<String> generateRecommendations(String question,
                                                String answer,
                                                List<ConversationTurnView> recentTurns) {
        if (!properties.isRecommendationEnabled() || !StringUtils.hasText(answer)) {
            return List.of();
        }

        StringBuilder prompt = new StringBuilder(properties.getRecommendationPrompt())
            .append("\n\n最近上下文：\n");

        int startIndex = Math.max(0, recentTurns.size() - properties.getHistoryPreviewTurns());
        for (int index = startIndex; index < recentTurns.size(); index++) {
            ConversationTurnView turn = recentTurns.get(index);
            prompt.append("用户：").append(turn.getQuestion()).append('\n');
            if (StringUtils.hasText(turn.getAnswer())) {
                prompt.append("助手：").append(turn.getAnswer()).append('\n');
            }
        }

        prompt.append("当前问题：").append(question).append('\n');
        prompt.append("当前答案：").append(answer).append('\n');

        try {
            String content = ChatClient.builder(chatModel)
                .build()
                .prompt()
                .user(prompt.toString())
                .call()
                .content();

            if (!StringUtils.hasText(content)) {
                return List.of();
            }

            String jsonArray = extractJsonArray(content);
            if (!StringUtils.hasText(jsonArray)) {
                log.warn("推荐问题输出不是有效 JSON 数组: {}", content);
                return List.of();
            }

            List<String> rawList = objectMapper.readValue(jsonArray, new TypeReference<List<String>>() {
            });
            LinkedHashSet<String> unique = new LinkedHashSet<>();
            for (String item : rawList) {
                if (StringUtils.hasText(item)) {
                    unique.add(item.trim());
                }
                if (unique.size() >= 3) {
                    break;
                }
            }
            return new ArrayList<>(unique);
        }
        catch (Exception exception) {
            log.warn("生成推荐问题失败", exception);
            return List.of();
        }
    }

    /**
     * 模型偶尔会在 JSON 数组前后多输出解释文本，这里做一层容错截取。
     */
    private String extractJsonArray(String content) {
        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return null;
        }
        return content.substring(start, end + 1);
    }
}
