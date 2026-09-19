package org.javaup.ai.chatagent.service;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.ai.openai.OpenAiChatOptions;


@Slf4j
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
        return callText(stageName, systemPrompt, userPrompt, null, traceRecorder);
    }



    /*
    callText 是系统调用大模型的统一入口之一，它的核心作用是：把提示词发送给大模型，等待模型返回完整答案，并记录整个调用过程的观测数据。
    在摘要合并场景中，它的作用是：
1.发送合并请求：把"旧摘要 + 新增批次"发送给大模型
2.等待完整返回：等待模型一次性返回合并后的 JSON 结构
3.记录调用数据：记录 token 消耗、耗时、成本估算等，供调试面板展示
与流式调用（streamText）的区别：
callText：等待模型一次性返回完整答案，适合问题改写、摘要合并、路由判断等"输出不需要边生成边展示"的阶段
streamText：边生成边返回，适合答案生成阶段，用户可以实时看到回答内容
     */
    /*
    这个方法除了调用模型之外，还额外做了三件事：
1.合并调用参数——把本次调用参数与默认模型参数合并，避免覆写时把默认配置整体丢失
2.记录观测数据——把 token 消耗、耗时、成本估算等写入追踪器，供调试面板展示
3.失败兜底——调用失败时也会记录 prompt token 和耗时，保留失败现场
     */
    public String callText(String stageName,
                           String systemPrompt,
                           String userPrompt,
                           ChatOptions callOptions,
                           ConversationTraceRecorder traceRecorder) {//callText 是 ObservedChatModelService 提供的非流式模型调用入口：
        // 在调用开始前先固定起始时间以及 provider / model，便于成功和失败分支统一记录 usage。
        long startTime = System.currentTimeMillis();
        String provider = resolveProvider();
        String model = resolveModel();
        try {
            // 本次调用参数会先与默认模型参数合并，避免覆写时把默认配置整体丢失。
            ChatOptions effectiveOptions = mergeOptions(callOptions);
            logStageCallOptions(stageName, provider, model, effectiveOptions);
            ChatResponse response = chatModel.call(buildPrompt(systemPrompt, userPrompt, effectiveOptions));
            String responseText = response == null || response.getResult() == null || response.getResult().getOutput() == null
                ? ""
                : StrUtil.blankToDefault(response.getResult().getOutput().getText(), "");
            // 成功时优先使用底层 metadata 中的 usage；若缺失，再由 buildUsageTrace 内部回退到估算值。
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
            // 把本次调用的 token、耗时、成本估算等观测数据写入追踪器。
            appendUsage(traceRecorder, usageTrace);
            return responseText;
        }
        catch (RuntimeException exception) {
            // 非流式调用失败时，至少把 prompt token 和耗时记录下来，保留失败现场。
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
        return buildPrompt(systemPrompt, userPrompt, null);
    }

    private Prompt buildPrompt(String systemPrompt, String userPrompt, ChatOptions callOptions) {
        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
        if (StrUtil.isNotBlank(systemPrompt)) {
            messages.add(new SystemMessage(systemPrompt));
        }
        messages.add(new UserMessage(StrUtil.blankToDefault(userPrompt, "")));
        ChatOptions mergedOptions = mergeOptions(callOptions);
        return mergedOptions == null ? new Prompt(messages) : new Prompt(messages, mergedOptions);
    }


    /*
    mergeOptions 是 callText 内部调用的参数合并方法，它解决了一个常见问题：如果某个阶段需要覆写模型参数（比如调低 temperature），直接替换会把默认配置整体丢掉。
    ——————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————
    合并逻辑遵循"调用级参数优先，默认参数兜底"的原则：
1.先复制默认参数：defaultOpenAi.copy() 把全局默认配置完整复制一份作为基础
2.逐项覆盖：只有调用方显式传入的字段（非 null）才会覆盖默认值，没传的字段保留默认值
3.extraBody 做 map merge：extraBody 是扩展参数 map，不能整体替换，而是把调用方的 key 合并进去，避免默认扩展参数被清空
——————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————
摘要合并阶段不传 callOptions
在 mergeSummaryPayload 中调用 callText 时，没有传 callOptions（即传的是 null），所以 mergeOptions 直接返回 null，最终使用全局默认模型参数。这意味着摘要合并阶段完全沿用配置文件中的默认模型和参数。
————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————
调用时传入的四个参数：
"summary"：阶段名称，用于追踪记录中区分不同阶段的模型调用
CONVERSATION_SUMMARY_SYSTEM：系统提示词（模板渲染后的文本）
buildSummaryMergePrompt(...)：用户提示词（包含旧摘要和新增批次）
traceRecorder：追踪记录器，可为空

     */
    private ChatOptions mergeOptions(ChatOptions callOptions) {
        if (callOptions == null) {
            return null;
        }
        ChatOptions defaultOptions = chatModel.getDefaultOptions();
        if (defaultOptions instanceof OpenAiChatOptions defaultOpenAi
            && callOptions instanceof OpenAiChatOptions overrideOpenAi) {
            // 先复制默认参数，再按"调用级参数优先"的方式逐项覆盖。
            OpenAiChatOptions merged = defaultOpenAi.copy();
            if (overrideOpenAi.getModel() != null) {
                merged.setModel(overrideOpenAi.getModel());
            }
            if (overrideOpenAi.getTemperature() != null) {
                merged.setTemperature(overrideOpenAi.getTemperature());
            }
            if (overrideOpenAi.getTopP() != null) {
                merged.setTopP(overrideOpenAi.getTopP());
            }
            if (overrideOpenAi.getReasoningEffort() != null) {
                merged.setReasoningEffort(overrideOpenAi.getReasoningEffort());
            }
            if (overrideOpenAi.getVerbosity() != null) {
                merged.setVerbosity(overrideOpenAi.getVerbosity());
            }
            if (overrideOpenAi.getExtraBody() != null && !overrideOpenAi.getExtraBody().isEmpty()) {
                // extraBody 不是简单替换，而是做 map merge，保证默认扩展参数不会被整体覆盖掉。
                /*
                为什么 extraBody 要特殊处理？
                extraBody 通常用于传递模型特有的扩展参数，比如 DeepSeek 的 enable_thinking。
                如果默认配置里已经设置了 enable_thinking: true，而调用方只想覆盖 temperature，直接替换 extraBody 会把 enable_thinking 一起清掉。
                map merge 保证了两者都能保留。
                 */
                Map<String, Object> mergedExtraBody = new LinkedHashMap<>();
                if (merged.getExtraBody() != null && !merged.getExtraBody().isEmpty()) {
                    mergedExtraBody.putAll(merged.getExtraBody());
                }
                mergedExtraBody.putAll(overrideOpenAi.getExtraBody());
                merged.setExtraBody(mergedExtraBody);
            }
            return merged;
        }
        return callOptions;
    }

    private void logStageCallOptions(String stageName,
                                     String provider,
                                     String fallbackModel,
                                     ChatOptions effectiveOptions) {
        if (!(effectiveOptions instanceof OpenAiChatOptions openAiOptions)) {
            if (effectiveOptions != null) {
                log.info("模型调用参数: stage={}, provider={}, model={}, optionsClass={}",
                    StrUtil.blankToDefault(stageName, ""),
                    provider,
                    fallbackModel,
                    effectiveOptions.getClass().getName());
            }
            return;
        }
        log.info("模型调用参数: stage={}, provider={}, model={}, temperature={}, topP={}, reasoningEffort={}, verbosity={}, extraBody={}",
            StrUtil.blankToDefault(stageName, ""),
            provider,
            StrUtil.blankToDefault(openAiOptions.getModel(), fallbackModel),
            openAiOptions.getTemperature(),
            openAiOptions.getTopP(),
            StrUtil.blankToDefault(openAiOptions.getReasoningEffort(), ""),
            StrUtil.blankToDefault(openAiOptions.getVerbosity(), ""),
            openAiOptions.getExtraBody() == null ? Map.of() : openAiOptions.getExtraBody());
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
