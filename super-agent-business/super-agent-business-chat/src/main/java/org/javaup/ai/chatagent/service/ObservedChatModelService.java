package org.javaup.ai.chatagent.service;

import cn.hutool.core.util.StrUtil;
import org.javaup.ai.chatagent.model.debug.ChatModelUsageTrace;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 统一的模型调用观测封装。
 */
@Service
public class ObservedChatModelService {

    private final ChatModel chatModel;

    public ObservedChatModelService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public String callText(String stageName,
                           String systemPrompt,
                           String userPrompt,
                           ConversationTraceRecorder traceRecorder) {
        long startTime = System.currentTimeMillis();
        String provider = resolveProvider();
        String model = resolveModel();
        try {
            ChatResponse response = chatModel.call(buildPrompt(systemPrompt, userPrompt));
            String responseText = response == null || response.getResult() == null || response.getResult().getOutput() == null
                ? ""
                : StrUtil.blankToDefault(response.getResult().getOutput().getText(), "");
            ChatModelUsageTrace usageTrace = buildUsageTrace(
                stageName,
                provider,
                model,
                response == null ? null : response.getMetadata(),
                System.currentTimeMillis() - startTime,
                "COMPLETED",
                systemPrompt,
                userPrompt,
                responseText
            );
            appendUsage(traceRecorder, usageTrace);
            return responseText;
        }
        catch (RuntimeException exception) {
            appendUsage(traceRecorder, ChatModelUsageTrace.builder()
                .stageName(stageName)
                .provider(provider)
                .model(model)
                .durationMs(System.currentTimeMillis() - startTime)
                .promptTokens(estimateTokens(systemPrompt) + estimateTokens(userPrompt))
                .status("FAILED")
                .build());
            throw exception;
        }
    }

    public Flux<String> streamText(String stageName,
                                   String systemPrompt,
                                   String userPrompt,
                                   ConversationTraceRecorder traceRecorder) {
        String provider = resolveProvider();
        String model = resolveModel();
        long startTime = System.currentTimeMillis();
        AtomicReference<ChatResponseMetadata> metadataRef = new AtomicReference<>();
        AtomicLong durationRef = new AtomicLong(0L);
        StringBuilder outputBuilder = new StringBuilder();

        return chatModel.stream(buildPrompt(systemPrompt, userPrompt))
            .map(response -> {
                if (response != null && response.getMetadata() != null) {
                    metadataRef.set(response.getMetadata());
                }
                if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                    return "";
                }
                return StrUtil.blankToDefault(response.getResult().getOutput().getText(), "");
            })
            .filter(StrUtil::isNotBlank)
            .doOnNext(outputBuilder::append)
            .doOnComplete(() -> {
                long durationMs = System.currentTimeMillis() - startTime;
                durationRef.set(durationMs);
                appendUsage(traceRecorder, buildUsageTrace(stageName, provider, model, metadataRef.get(), durationMs, "COMPLETED", systemPrompt, userPrompt, outputBuilder.toString()));
            })
            .doOnError(error -> appendUsage(traceRecorder, ChatModelUsageTrace.builder()
                .stageName(stageName)
                .provider(provider)
                .model(model)
                .promptTokens(estimateTokens(systemPrompt) + estimateTokens(userPrompt))
                .completionTokens(estimateTokens(outputBuilder.toString()))
                .totalTokens(estimateTokens(systemPrompt) + estimateTokens(userPrompt) + estimateTokens(outputBuilder.toString()))
                .estimatedCost(estimateCost(model, estimateTokens(systemPrompt) + estimateTokens(userPrompt), estimateTokens(outputBuilder.toString())))
                .durationMs(durationRef.get() > 0 ? durationRef.get() : System.currentTimeMillis() - startTime)
                .status("FAILED")
                .build()));
    }

    private Prompt buildPrompt(String systemPrompt, String userPrompt) {
        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
        if (StrUtil.isNotBlank(systemPrompt)) {
            messages.add(new SystemMessage(systemPrompt));
        }
        messages.add(new UserMessage(StrUtil.blankToDefault(userPrompt, "")));
        return new Prompt(messages);
    }

    private void appendUsage(ConversationTraceRecorder traceRecorder, ChatModelUsageTrace trace) {
        if (traceRecorder != null && trace != null) {
            traceRecorder.addModelUsageTrace(trace);
        }
    }

    private ChatModelUsageTrace buildUsageTrace(String stageName,
                                                String provider,
                                                String model,
                                                ChatResponseMetadata metadata,
                                                long durationMs,
                                                String status,
                                                String systemPrompt,
                                                String userPrompt,
                                                String responseText) {
        Usage usage = metadata == null ? null : metadata.getUsage();
        Integer promptTokens = usage == null ? null : usage.getPromptTokens();
        Integer completionTokens = usage == null ? null : usage.getCompletionTokens();
        Integer totalTokens = usage == null ? null : usage.getTotalTokens();
        if (promptTokens == null || promptTokens <= 0) {
            promptTokens = estimateTokens(systemPrompt) + estimateTokens(userPrompt);
        }
        if (completionTokens == null || completionTokens <= 0) {
            completionTokens = estimateTokens(responseText);
        }
        if (totalTokens == null || totalTokens <= 0) {
            totalTokens = (promptTokens == null ? 0 : promptTokens) + (completionTokens == null ? 0 : completionTokens);
        }
        return ChatModelUsageTrace.builder()
            .stageName(stageName)
            .provider(provider)
            .model(StrUtil.blankToDefault(metadata == null ? model : metadata.getModel(), model))
            .promptTokens(promptTokens)
            .completionTokens(completionTokens)
            .totalTokens(totalTokens)
            .estimatedCost(estimateCost(model, promptTokens, completionTokens))
            .durationMs(durationMs)
            .status(status)
            .build();
    }

    private Integer estimateTokens(String content) {
        if (StrUtil.isBlank(content)) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(content.trim().length() / 4.0));
    }

    private String resolveProvider() {
        String className = chatModel.getClass().getName().toLowerCase();
        if (className.contains("deepseek")) {
            return "deepseek";
        }
        if (className.contains("openai")) {
            return "openai-compatible";
        }
        if (className.contains("ollama")) {
            return "ollama";
        }
        return "unknown";
    }

    private String resolveModel() {
        ChatOptions options = chatModel.getDefaultOptions();
        return options == null ? "" : StrUtil.blankToDefault(options.getModel(), "");
    }

    /**
     * 粗略估算模型成本，单位按“元”近似表达。
     *
     * <p>这里只是管理侧排障参考，不作为计费依据。</p>
     */
    private Double estimateCost(String model, Integer promptTokens, Integer completionTokens) {
        if ((promptTokens == null || promptTokens <= 0) && (completionTokens == null || completionTokens <= 0)) {
            return null;
        }
        String normalizedModel = StrUtil.blankToDefault(model, "").toLowerCase();
        double promptRatePer1k;
        double completionRatePer1k;
        if (normalizedModel.contains("qwen-plus")) {
            promptRatePer1k = 0.004;
            completionRatePer1k = 0.012;
        }
        else if (normalizedModel.contains("deepseek")) {
            promptRatePer1k = 0.002;
            completionRatePer1k = 0.008;
        }
        else {
            promptRatePer1k = 0.0;
            completionRatePer1k = 0.0;
        }
        double promptCost = (promptTokens == null ? 0D : promptTokens / 1000D) * promptRatePer1k;
        double completionCost = (completionTokens == null ? 0D : completionTokens / 1000D) * completionRatePer1k;
        double total = promptCost + completionCost;
        return total > 0D ? total : null;
    }
}
