package org.javaup.ai.chatagent.support;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * DashScope 的 OpenAI 兼容接口在流式工具调用场景下对部分扩展字段兼容性较弱。
 *
 * <p>这里在真正发起模型调用前，统一把已知容易触发 500 internal_error 的选项收敛掉，
 * 同时把关键请求形状打印出来，便于排查剩余兼容问题。</p>
 */
@Component
public class DashScopeCompatibilityInterceptor extends ModelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(DashScopeCompatibilityInterceptor.class);
    private final String openAiBaseUrl;

    public DashScopeCompatibilityInterceptor(@Value("${spring.ai.openai.base-url:}") String openAiBaseUrl) {
        this.openAiBaseUrl = openAiBaseUrl;
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        String callId = UUID.randomUUID().toString().substring(0, 8);
        if (!(request.getOptions() instanceof OpenAiChatOptions options)) {
            String provider = resolveProvider(openAiBaseUrl);
            logModelRequest(callId, provider, null, request, false, false);
            return wrapModelResponse(callId, provider, null, request, handler.call(request));
        }

        OpenAiChatOptions compatibleOptions = options.copy();
        boolean adjustedParallelToolCalls = Boolean.TRUE.equals(compatibleOptions.getParallelToolCalls());
        boolean adjustedStreamUsage = Boolean.TRUE.equals(compatibleOptions.getStreamUsage())
            || compatibleOptions.getStreamOptions() != null;

        if (adjustedParallelToolCalls) {
            compatibleOptions.setParallelToolCalls(Boolean.FALSE);
        }
        if (adjustedStreamUsage) {
            compatibleOptions.setStreamOptions(null);
        }

        log.debug(
            "DashScope请求兼容检查: model={}, parallelToolCalls={}{}{}, tools={}, messages={}",
            compatibleOptions.getModel(),
            compatibleOptions.getParallelToolCalls(),
            adjustedParallelToolCalls ? " (was true)" : "",
            adjustedStreamUsage ? ", streamOptions removed" : "",
            summarizeTools(request.getTools()),
            summarizeMessages(request.getMessages())
        );

        ModelRequest compatibleRequest = ModelRequest.builder(request)
            .options(compatibleOptions)
            .build();
        String provider = resolveProvider(openAiBaseUrl);
        logModelRequest(callId, provider, compatibleOptions, compatibleRequest, adjustedParallelToolCalls, adjustedStreamUsage);

        try {
            return wrapModelResponse(callId, provider, compatibleOptions, compatibleRequest, handler.call(compatibleRequest));
        }
        catch (RuntimeException exception) {
            log.error(
                "模型调用异常[{}]: provider={}, model={}, tools={}, messages={}, error={}",
                callId,
                provider,
                compatibleOptions.getModel(),
                summarizeTools(compatibleRequest.getTools()),
                summarizeMessages(compatibleRequest.getMessages()),
                exception.getMessage(),
                exception
            );
            throw exception;
        }
    }

    @Override
    public String getName() {
        return "dashscope_compatibility_interceptor";
    }

    private void logModelRequest(String callId,
                                 String provider,
                                 OpenAiChatOptions options,
                                 ModelRequest request,
                                 boolean adjustedParallelToolCalls,
                                 boolean adjustedStreamUsage) {
        log.info(
            "模型请求[{}]: provider={}, baseUrl={}, optionsClass={}, model={}, parallelToolCalls={}{}{}, tools={}, messageCount={}, tailMessages={}, lastAssistant={}, lastTool={}, messages={}",
            callId,
            provider,
            StrUtil.blankToDefault(openAiBaseUrl, "<empty>"),
            request.getOptions() != null ? request.getOptions().getClass().getName() : "<null>",
            options != null ? options.getModel() : "<unknown>",
            options != null ? options.getParallelToolCalls() : null,
            adjustedParallelToolCalls ? " (was true)" : "",
            adjustedStreamUsage ? ", streamOptions removed" : "",
            summarizeTools(request.getTools()),
            CollUtil.size(request.getMessages()),
            summarizeTailMessages(request.getMessages(), 3),
            summarizeLastAssistantMessage(request.getMessages()),
            summarizeLastToolMessage(request.getMessages()),
            summarizeMessages(request.getMessages())
        );
    }

    private ModelResponse wrapModelResponse(String callId,
                                            String provider,
                                            OpenAiChatOptions options,
                                            ModelRequest request,
                                            ModelResponse response) {
        if (response == null) {
            log.warn(
                "模型响应为空[{}]: provider={}, model={}, tools={}",
                callId,
                provider,
                options != null ? options.getModel() : "<unknown>",
                summarizeTools(request.getTools())
            );
            return null;
        }

        if (response.getChatResponse() != null) {
            log.info(
                "模型同步响应[{}]: provider={}, model={}, summary={}",
                callId,
                provider,
                options != null ? options.getModel() : "<unknown>",
                summarizeChatResponse(response.getChatResponse())
            );
        }

        Object message = response.getMessage();
        if (message instanceof Flux<?> originalFlux) {
            AtomicInteger emissionCount = new AtomicInteger();
            Flux<?> wrappedFlux = originalFlux
                .doOnSubscribe(subscription -> log.info(
                    "模型流开始[{}]: provider={}, model={}, tools={}",
                    callId,
                    provider,
                    options != null ? options.getModel() : "<unknown>",
                    summarizeTools(request.getTools())
                ))
                .doOnNext(item -> {
                    int index = emissionCount.incrementAndGet();
                    log.info(
                        "模型流片段[{}#{}]: provider={}, model={}, summary={}",
                        callId,
                        index,
                        provider,
                        options != null ? options.getModel() : "<unknown>",
                        summarizeStreamItem(item)
                    );
                })
                .doOnComplete(() -> {
                    if (emissionCount.get() == 0) {
                        log.warn(
                            "模型流为空[{}]: provider={}, model={}, tools={}, messages={}",
                            callId,
                            provider,
                            options != null ? options.getModel() : "<unknown>",
                            summarizeTools(request.getTools()),
                            summarizeMessages(request.getMessages())
                        );
                        return;
                    }
                    log.info(
                        "模型流完成[{}]: provider={}, model={}, emissionCount={}",
                        callId,
                        provider,
                        options != null ? options.getModel() : "<unknown>",
                        emissionCount.get()
                    );
                })
                .doOnError(error -> log.error(
                    "模型流异常[{}]: provider={}, model={}, emissionCount={}, error={}",
                    callId,
                    provider,
                    options != null ? options.getModel() : "<unknown>",
                    emissionCount.get(),
                    error.getMessage(),
                    error
                ));
            return new ModelResponse(wrappedFlux, response.getChatResponse());
        }

        if (message instanceof AssistantMessage assistantMessage) {
            log.info(
                "模型消息响应[{}]: provider={}, model={}, summary={}",
                callId,
                provider,
                options != null ? options.getModel() : "<unknown>",
                summarizeAssistantMessage(assistantMessage)
            );
        }
        else if (message != null) {
            log.info(
                "模型消息响应[{}]: provider={}, model={}, type={}",
                callId,
                provider,
                options != null ? options.getModel() : "<unknown>",
                message.getClass().getName()
            );
        }
        else {
            log.warn(
                "模型消息为空[{}]: provider={}, model={}, tools={}",
                callId,
                provider,
                options != null ? options.getModel() : "<unknown>",
                summarizeTools(request.getTools())
            );
        }

        return response;
    }

    private String resolveProvider(String baseUrl) {
        if (StrUtil.isBlank(baseUrl)) {
            return "unknown";
        }
        String normalized = baseUrl.trim().toLowerCase();
        if (normalized.contains("siliconflow")) {
            return "siliconflow";
        }
        if (normalized.contains("dashscope") || normalized.contains("aliyuncs")) {
            return "dashscope";
        }
        return normalized;
    }

    private String summarizeTools(List<String> tools) {
        return CollUtil.isEmpty(tools) ? "[]" : tools.toString();
    }

    private String summarizeTailMessages(List<Message> messages, int limit) {
        if (CollUtil.isEmpty(messages)) {
            return "[]";
        }
        int startIndex = Math.max(0, messages.size() - limit);
        List<String> tail = new ArrayList<>(messages.size() - startIndex);
        for (int index = startIndex; index < messages.size(); index++) {
            tail.add(summarizeMessage(messages.get(index)));
        }
        return tail.toString();
    }

    private String summarizeMessages(List<Message> messages) {
        if (CollUtil.isEmpty(messages)) {
            return "[]";
        }
        return messages.stream()
            .map(this::summarizeMessage)
            .collect(Collectors.joining(" | "));
    }

    private String summarizeLastAssistantMessage(List<Message> messages) {
        if (CollUtil.isEmpty(messages)) {
            return "<none>";
        }
        for (int index = messages.size() - 1; index >= 0; index--) {
            Message message = messages.get(index);
            if (message instanceof AssistantMessage assistantMessage) {
                return summarizeAssistantMessage(assistantMessage);
            }
        }
        return "<none>";
    }

    private String summarizeLastToolMessage(List<Message> messages) {
        if (CollUtil.isEmpty(messages)) {
            return "<none>";
        }
        for (int index = messages.size() - 1; index >= 0; index--) {
            Message message = messages.get(index);
            if (message instanceof ToolResponseMessage toolResponseMessage) {
                return summarizeToolResponseMessage(toolResponseMessage);
            }
        }
        return "<none>";
    }

    private String summarizeMessage(Message message) {
        String type = message.getMessageType() != null
            ? message.getMessageType().name()
            : message.getClass().getSimpleName();

        if (message instanceof AssistantMessage assistantMessage) {
            return type + ":" + summarizeAssistantMessage(assistantMessage);
        }
        if (message instanceof ToolResponseMessage toolResponseMessage) {
            return type + ":" + summarizeToolResponseMessage(toolResponseMessage);
        }
        if (message instanceof AbstractMessage abstractMessage && StrUtil.isNotBlank(abstractMessage.getText())) {
            String text = abstractMessage.getText().replaceAll("\\s+", " ").trim();
            if (text.length() > 80) {
                text = text.substring(0, 80) + "...";
            }
            return type + ":" + text;
        }
        return type;
    }

    private String summarizeStreamItem(Object item) {
        if (item instanceof ChatResponse chatResponse) {
            return summarizeChatResponse(chatResponse);
        }
        if (item instanceof AssistantMessage assistantMessage) {
            return summarizeAssistantMessage(assistantMessage);
        }
        if (item == null) {
            return "<null>";
        }
        return item.getClass().getName();
    }

    private String summarizeChatResponse(ChatResponse chatResponse) {
        if (chatResponse == null) {
            return "chatResponse=null";
        }
        if (chatResponse.getResult() == null) {
            return "chatResponse.result=null";
        }
        return "results=" + (chatResponse.getResults() != null ? chatResponse.getResults().size() : 0)
            + ", output=" + summarizeAssistantMessage(chatResponse.getResult().getOutput());
    }

    private String summarizeAssistantMessage(AssistantMessage assistantMessage) {
        if (assistantMessage == null) {
            return "assistantMessage=null";
        }
        String text = assistantMessage.getText();
        List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls();
        int textLength = text != null ? text.length() : 0;
        int toolCallCount = toolCalls != null ? toolCalls.size() : 0;
        String preview = StrUtil.isNotBlank(text)
            ? StrUtil.maxLength(text.replaceAll("\\s+", " ").trim(), 120)
            : "";
        return "hasText=" + StrUtil.isNotBlank(text)
            + ", textLength=" + textLength
            + ", toolCalls=" + toolCallCount
            + (toolCallCount > 0 ? ", toolCallDetails=" + summarizeToolCalls(toolCalls) : "")
            + (StrUtil.isNotBlank(preview) ? ", preview=" + preview : "");
    }

    private String summarizeToolResponseMessage(ToolResponseMessage toolResponseMessage) {
        if (toolResponseMessage == null) {
            return "toolResponseMessage=null";
        }
        List<ToolResponseMessage.ToolResponse> responses = toolResponseMessage.getResponses();
        int responseCount = responses != null ? responses.size() : 0;
        String text = toolResponseMessage.getText();
        String preview = StrUtil.isNotBlank(text)
            ? StrUtil.maxLength(text.replaceAll("\\s+", " ").trim(), 120)
            : "";
        return "responses=" + responseCount
            + ", hasText=" + StrUtil.isNotBlank(text)
            + (responseCount > 0 ? ", responseDetails=" + summarizeToolResponses(responses) : "")
            + (StrUtil.isNotBlank(preview) ? ", preview=" + preview : "");
    }

    private String summarizeToolCalls(List<AssistantMessage.ToolCall> toolCalls) {
        if (CollUtil.isEmpty(toolCalls)) {
            return "[]";
        }
        return toolCalls.stream()
            .map(toolCall -> "{id=" + StrUtil.blankToDefault(toolCall.id(), "<empty>")
                + ", type=" + StrUtil.blankToDefault(toolCall.type(), "<empty>")
                + ", name=" + StrUtil.blankToDefault(toolCall.name(), "<empty>")
                + ", arguments=" + summarizeArguments(toolCall.arguments()) + "}")
            .collect(Collectors.joining(", ", "[", "]"));
    }

    private String summarizeToolResponses(List<ToolResponseMessage.ToolResponse> responses) {
        if (CollUtil.isEmpty(responses)) {
            return "[]";
        }
        return responses.stream()
            .map(response -> "{id=" + StrUtil.blankToDefault(response.id(), "<empty>")
                + ", name=" + StrUtil.blankToDefault(response.name(), "<empty>")
                + ", responseData=" + summarizeArguments(response.responseData()) + "}")
            .collect(Collectors.joining(", ", "[", "]"));
    }

    private String summarizeArguments(String raw) {
        if (StrUtil.isBlank(raw)) {
            return "<empty>";
        }
        String normalized = raw.replaceAll("\\s+", " ").trim();
        return StrUtil.maxLength(normalized, 160);
    }
}
