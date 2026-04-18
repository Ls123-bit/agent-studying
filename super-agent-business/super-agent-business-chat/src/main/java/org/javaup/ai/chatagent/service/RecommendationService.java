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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 服务层
 * @author: 阿星不是程序员
 **/
@Service
public class RecommendationService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);

    private final ChatAgentProperties properties;
    private final ObjectMapper objectMapper;
    private final ExecutorService recommendationExecutorService;
    private final ObservedChatModelService observedChatModelService;

    public RecommendationService(ChatAgentProperties properties,
                                 ObjectMapper objectMapper,
                                 @Qualifier("chatPostProcessExecutorService") ExecutorService recommendationExecutorService,
                                 ObservedChatModelService observedChatModelService) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.recommendationExecutorService = recommendationExecutorService;
        this.observedChatModelService = observedChatModelService;
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
                                                List<ConversationExchangeView> recentExchanges,
                                                ConversationTraceRecorder traceRecorder) {
        /*
         * 推荐问题属于增强能力，不应该影响主回答主链路。
         * 因此只要功能关闭或主回答为空，就直接跳过。
         */
        if (!properties.isRecommendationEnabled() || StrUtil.isBlank(answer)) {
            return List.of();
        }

        try {
            return CompletableFuture.supplyAsync(
                    () -> generateRecommendationsInternal(question, answer, recentExchanges, traceRecorder),
                    // 这里的模型使用量单独由内部调用记录，不在 CompletableFuture 边界上额外处理。
                    recommendationExecutorService
                )
                /*
                 * 推荐问题是“锦上添花”的后处理，不应该拖住主回答收尾。
                 * 因此这里明确加超时：超过阈值就直接放弃推荐，不影响正文完成、引用补发和数据库定稿。
                 */
                .orTimeout(Math.max(properties.getRecommendationTimeoutMs(), 1L), TimeUnit.MILLISECONDS)
                .exceptionally(exception -> {
                    log.warn("生成推荐问题超时或失败: {}", exception.getMessage());
                    return List.of();
                })
                .join();
        }
        catch (Exception exception) {
            log.warn("生成推荐问题失败", exception);
            return List.of();
        }
    }

    private List<String> generateRecommendationsInternal(String question,
                                                         String answer,
                                                         List<ConversationExchangeView> recentExchanges,
                                                         ConversationTraceRecorder traceRecorder) {
        
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
            /*
             * 这里故意不把推荐问题和主回答塞进同一个模型调用里，
             * 因为推荐属于回答后的附加增强。如果把它并入主链路 prompt，
             * 一方面会让主回答 prompt 膨胀，另一方面失败时也会更难做超时隔离。
             */
            String content = observedChatModelService.callText("recommendation", null, prompt.toString(), traceRecorder);

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
