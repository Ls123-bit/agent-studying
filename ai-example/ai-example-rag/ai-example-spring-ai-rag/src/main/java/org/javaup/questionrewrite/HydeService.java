package org.javaup.questionrewrite;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * HyDE（Hypothetical Document Embeddings）服务
 * 先让大模型生成假设性回答，实际项目中再用假设回答去向量库检索，提高短问题/模糊问题的召回率
 */
@Slf4j
@Service
public class HydeService {

    private final ChatClient chatClient;

    private static final String HYDE_PROMPT = """
        请根据以下问题，生成一段可能的回答。
        这段回答不需要完全准确，但应该包含相关的专业术语和概念。
        直接输出回答内容，不要加任何前缀或解释。

        问题：{question}
        """;

    public HydeService(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    /**
     * 生成假设性回答
     * 实际项目中，用这个假设回答的向量去检索，命中率比用原始短问题高
     */
    public String generateHypothetical(String question) {
        String hypothetical = chatClient.prompt()
                .user(u -> u.text(HYDE_PROMPT).param("question", question))
                .call()
                .content();

        log.info("HyDE假设回答: {}", hypothetical);
        return hypothetical;
    }
}
