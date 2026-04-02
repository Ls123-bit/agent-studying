package org.javaup.ai.chatagent.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.javaup.ai.chatagent.config.ChatAgentProperties;
import org.javaup.ai.chatagent.model.ConversationExchangeView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

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
                                                List<ConversationExchangeView> recentExchanges) {
        /*
         * 推荐问题属于增强能力，不应该影响主回答主链路。
         * 因此只要功能关闭或主回答为空，就直接跳过。
         */
        if (!properties.isRecommendationEnabled() || StrUtil.isBlank(answer)) {
            return List.of();
        }

        /*
         * 推荐问题使用独立 prompt，把最近几轮上下文和当前问答拼接进去，
         * 让模型能围绕已经聊到的话题继续生成后续追问。
         */
        StringBuilder prompt = new StringBuilder(properties.getRecommendationPrompt())
            .append("\n\n最近上下文：\n");

        int startIndex = Math.max(0, recentExchanges.size() - properties.getHistoryPreviewTurns());

        /*
         * 只回看最近 N 轮，既能保留上下文，又能避免推荐 prompt 无限膨胀。
         */
        for (int index = startIndex; index < recentExchanges.size(); index++) {
            ConversationExchangeView exchange = recentExchanges.get(index);
            prompt.append("用户：").append(exchange.getQuestion()).append('\n');
            if (StrUtil.isNotBlank(exchange.getAnswer())) {
                prompt.append("助手：").append(exchange.getAnswer()).append('\n');
            }
        }

        /*
         * 当前这一轮的问答对推荐结果影响最大，因此放在 prompt 末尾强调给模型。
         */
        prompt.append("当前问题：").append(question).append('\n');
        prompt.append("当前答案：").append(answer).append('\n');

        try {
            /*
             * 推荐问题不复用 ReactAgent，而是直接用底层 ChatModel 单独调一次模型，
             * 这样主回答和推荐生成的职责边界更清晰。
             */
            String content = ChatClient.builder(chatModel)
                .build()
                .prompt()
                .user(prompt.toString())
                .call()
                .content();

            if (StrUtil.isBlank(content)) {
                return List.of();
            }

            /*
             * 模型偶尔会在 JSON 外包一层解释文本，这里先截出数组主体，再做正式反序列化。
             */
            String jsonArray = extractJsonArray(content);
            if (StrUtil.isBlank(jsonArray)) {
                log.warn("推荐问题输出不是有效 JSON 数组: {}", content);
                return List.of();
            }

            List<String> rawList = objectMapper.readValue(jsonArray, new TypeReference<List<String>>() {
            });
            LinkedHashSet<String> unique = new LinkedHashSet<>();

            /*
             * 最终只保留非空、去重后的前 3 条结果，避免模型输出重复或过长列表。
             */
            for (String item : rawList) {
                if (StrUtil.isNotBlank(item)) {
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
        /*
         * 只取最外层的 [ ... ] 片段，把多余说明文本裁掉，
         * 让后续 JSON 解析更稳。
         */
        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return null;
        }
        return content.substring(start, end + 1);
    }
}
