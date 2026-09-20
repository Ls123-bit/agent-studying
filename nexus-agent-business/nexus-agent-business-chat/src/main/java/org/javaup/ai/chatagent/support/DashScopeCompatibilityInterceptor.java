package org.javaup.ai.chatagent.support;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;


/*DashScopeCompatibilityInterceptor：API 兼容拦截器
它解决什么问题？
项目使用的是阿里云 DashScope 的大模型 API（通过 OpenAI 兼容接口调用）。但 DashScope 和标准 OpenAI API 之间有几个不兼容的地方：
1.不支持 parallelToolCalls 参数——传了会报错
2.不支持 streamOptions 参数——传了会报错
3.工具调用可能被拆成碎片返回——一个完整的工具调用被拆成多个片段，需要合并
这个拦截器就是在模型调用前后做兼容处理，让上层代码不需要关心这些差异。
 */
@Slf4j
@Component
public class DashScopeCompatibilityInterceptor extends ModelInterceptor {
    
    private final String openAiBaseUrl;

    public DashScopeCompatibilityInterceptor(@Value("${spring.ai.openai.base-url:}") String openAiBaseUrl) {
        this.openAiBaseUrl = openAiBaseUrl;
    }


    /*
    这段代码的核心逻辑：
    1.生成一个 8 位的 callId 用于日志关联——同一次模型调用的请求和响应日志可以通过这个 ID 串起来
    2.如果请求的 options 不是 OpenAiChatOptions 类型，说明不是走 OpenAI 兼容接口，直接放行
    3.复制一份 options（不修改原始对象），然后做两个调整：
    把 parallelToolCalls 从 true 改为 false
    移除 streamOptions
     */
    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        // 为每次模型调用生成一个短 callId，贯穿请求日志、响应日志和异常日志，方便串联排查。
        String callId = UUID.randomUUID().toString().substring(0, 8);
        // 只有 OpenAiChatOptions 才能调整 parallelToolCalls 和 streamOptions；其他 options 类型只记录日志并透传
        if (!(request.getOptions() instanceof OpenAiChatOptions options)) {
            // 通过 baseUrl 粗略识别供应商，方便日志按 provider 聚合。
            String provider = resolveProvider(openAiBaseUrl);
            // 非 OpenAI options 无法做兼容修改，记录原始请求信息。
            logModelRequest(callId, provider, null, request, false, false);
            // 直接调用下游模型，并包装响应日志/归一化逻辑。
            return wrapModelResponse(callId, provider, null, request, handler.call(request));
        }
// 复制一份 OpenAiChatOptions，避免直接修改原始 request 中的 options 对象。
        OpenAiChatOptions compatibleOptions = options.copy();
        // DashScope 兼容接口对 parallel_tool_calls=true 支持不稳定，这里记录是否需要关闭。
        boolean adjustedParallelToolCalls = Boolean.TRUE.equals(compatibleOptions.getParallelToolCalls());
        // stream usage / stream_options 在部分 OpenAI 兼容供应商上不兼容，这里记录是否需要移除。
        boolean adjustedStreamUsage = Boolean.TRUE.equals(compatibleOptions.getStreamUsage())
            || compatibleOptions.getStreamOptions() != null;
        // 如果原配置启用了并行工具调用，就强制关闭，保证模型一次只产出可控的工具调用序列。
        if (adjustedParallelToolCalls) {
            compatibleOptions.setParallelToolCalls(Boolean.FALSE);
        }
        // 如果原配置带有 streamOptions，就清空，避免 DashScope 兼容接口因不支持该字段而报错。
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


        /*
        请求改写后继续调用
        用修改后的 options 构建新的请求，然后调用 handler.call(compatibleRequest) 继续执行链路。如果模型调用抛异常，会记录详细的错误日志（包含 callId、provider、model、tools、messages 等信息），方便排查。
         */
        // 用兼容后的 options 重新构建 ModelRequest，其他字段沿用原始 request。
        ModelRequest compatibleRequest = ModelRequest.builder(request)
                // 替换为已经关闭 parallelToolCalls/streamOptions 的 options。
            .options(compatibleOptions)
            .build();
        // 根据 baseUrl 解析 provider，后续只对 dashscope 响应做工具调用归一化。
        String provider = resolveProvider(openAiBaseUrl);
        // 记录最终发往模型的请求摘要。
        logModelRequest(callId, provider, compatibleOptions, compatibleRequest, adjustedParallelToolCalls, adjustedStreamUsage);

        try {
            // 调用下游模型，并对返回的同步消息或流式消息做日志包装和 DashScope 兼容归一化。
            return wrapModelResponse(callId, provider, compatibleOptions, compatibleRequest, handler.call(compatibleRequest));
        }
        catch (RuntimeException exception) {
            log.error(
                    // 模型调用同步抛出异常时，记录模型、工具、消息摘要和异常详情。
                "模型调用异常[{}]: provider={}, model={}, tools={}, messages={}, error={}",
                callId,
                provider,
                compatibleOptions.getModel(),
                summarizeTools(compatibleRequest.getTools()),
                summarizeMessages(compatibleRequest.getMessages()),
                exception.getMessage(),
                exception
            );
            // 保持原异常向上抛出，让上层统一错误处理逻辑接管。
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

        ChatResponse normalizedChatResponse = normalizeChatResponseForProvider(provider, response != null ? response.getChatResponse() : null);
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

        if (normalizedChatResponse != null) {
            log.info(
                "模型同步响应[{}]: provider={}, model={}, summary={}",
                callId,
                provider,
                options != null ? options.getModel() : "<unknown>",
                summarizeChatResponse(normalizedChatResponse)
            );
        }

        Object message = response.getMessage();
        if (message instanceof Flux<?> originalFlux) {
            AtomicInteger emissionCount = new AtomicInteger();
            Flux<?> wrappedFlux = originalFlux

                .map(item -> normalizeStreamItemForProvider(provider, item))
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
            return new ModelResponse(wrappedFlux, normalizedChatResponse);
        }

        Object normalizedMessage = normalizeDirectMessageForProvider(provider, message);
        if (message instanceof AssistantMessage assistantMessage) {
            log.info(
                "模型消息响应[{}]: provider={}, model={}, summary={}",
                callId,
                provider,
                options != null ? options.getModel() : "<unknown>",
                summarizeAssistantMessage((AssistantMessage) normalizedMessage)
            );
        }
        else if (normalizedMessage != null) {
            log.info(
                "模型消息响应[{}]: provider={}, model={}, type={}",
                callId,
                provider,
                options != null ? options.getModel() : "<unknown>",
                normalizedMessage.getClass().getName()
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

        return new ModelResponse(normalizedMessage, normalizedChatResponse);
    }


    /*
    provider识别
    通过 base URL 判断当前用的是哪个模型提供商。只有 DashScope 才需要做碎片合并，其他提供商直接跳过归一化逻辑。
     */
    private String resolveProvider(String baseUrl) {
        // baseUrl 为空时无法判断供应商，统一标记为 unknown。
        if (StrUtil.isBlank(baseUrl)) {
            return "unknown";
        }
        // 先去空格再转小写，避免大小写或配置空白影响 provider 判断。
        String normalized = baseUrl.trim().toLowerCase();
        // SiliconFlow 也提供 OpenAI 兼容接口，这里单独标记，方便日志区分。
        if (normalized.contains("siliconflow")) {
            return "siliconflow";
        }
        // DashScope 的 OpenAI 兼容地址可能包含 dashscope 或 aliyuncs，两种都识别为 dashscope。
        if (normalized.contains("dashscope") || normalized.contains("aliyuncs")) {
            return "dashscope";
        }
        // 其他供应商直接返回规范化后的 baseUrl，至少能在日志里看到真实来源。
        return normalized;
    }

    private Object normalizeStreamItemForProvider(String provider, Object item) {
        if (item instanceof ChatResponse chatResponse) {
            return normalizeChatResponseForProvider(provider, chatResponse);
        }
        if (item instanceof AssistantMessage assistantMessage) {
            return normalizeAssistantMessageForProvider(provider, assistantMessage);
        }
        return item;
    }

    private Object normalizeDirectMessageForProvider(String provider, Object message) {
        if (message instanceof AssistantMessage assistantMessage) {

            return normalizeAssistantMessageForProvider(provider, assistantMessage);
        }
        return message;
    }

    private ChatResponse normalizeChatResponseForProvider(String provider, ChatResponse chatResponse) {
        if (!isDashScopeProvider(provider) || chatResponse == null || CollUtil.isEmpty(chatResponse.getResults())) {
            return chatResponse;
        }

        boolean changed = false;
        List<Generation> normalizedResults = new ArrayList<>(chatResponse.getResults().size());
        for (Generation generation : chatResponse.getResults()) {
            if (generation == null) {
                normalizedResults.add(null);
                continue;
            }

            AssistantMessage normalizedOutput = normalizeAssistantMessageForProvider(provider, generation.getOutput());
            if (normalizedOutput != generation.getOutput()) {
                changed = true;
            }
            normalizedResults.add(new Generation(normalizedOutput, generation.getMetadata()));
        }

        if (!changed) {
            return chatResponse;
        }

        return new ChatResponse(normalizedResults, chatResponse.getMetadata());
    }

    private AssistantMessage normalizeAssistantMessageForProvider(String provider, AssistantMessage assistantMessage) {

        if (!isDashScopeProvider(provider) || assistantMessage == null || CollUtil.isEmpty(assistantMessage.getToolCalls())) {
            return assistantMessage;
        }

        List<AssistantMessage.ToolCall> normalizedToolCalls = mergeDashScopeFragmentedToolCalls(assistantMessage.getToolCalls());
        if (Objects.equals(normalizedToolCalls, assistantMessage.getToolCalls())) {
            return assistantMessage;
        }

        log.warn(
            "DashScope工具调用兼容修正: before={}, after={}",
            summarizeToolCalls(assistantMessage.getToolCalls()),
            summarizeToolCalls(normalizedToolCalls)
        );

        return AssistantMessage.builder()
            .content(assistantMessage.getText())
            .properties(assistantMessage.getMetadata())
            .toolCalls(normalizedToolCalls)
            .media(assistantMessage.getMedia())
            .build();
    }

    private boolean isDashScopeProvider(String provider) {
        return "dashscope".equals(provider);
    }


    /*
    响应归一化：合并碎片化工具使用
    这是这个拦截器最复杂的部分。DashScope 有时候会把一个工具调用拆成多个片段返回（相同的 id，但 name 和 arguments 分散在不同片段里）。wrapModelResponse 方法会对响应做归一化处理：
    合并逻辑：
    1.用 LinkedHashMap 按工具调用 ID 分组（保持顺序）
    2.遇到相同 ID 的片段时，把各字段合并——哪个片段有值就用哪个
    3.如果某个片段连 ID 都没有（DashScope 的 bug），用索引生成一个临时 key
    4.最终返回合并后的工具调用列表
    为什么需要这个合并？
    举个例子，DashScope 可能返回这样的碎片：
    片段1：{id: "call_abc", name: "tavily_search", arguments: ""}
    片段2：{id: "call_abc", name: "", arguments: "{\"query\": \"Java 21\"}"}
    如果不合并，框架会认为这是两个独立的工具调用，而且第一个没有参数、第二个没有名字，都会执行失败。合并后变成一个完整的调用：{id: "call_abc", name: "tavily_search", arguments: "{\"query\": \"Java 21\"}"}。
     */
    private List<AssistantMessage.ToolCall> mergeDashScopeFragmentedToolCalls(List<AssistantMessage.ToolCall> toolCalls) {
        // 空工具调用列表直接返回空列表，避免后续遍历空集合。
        if (CollUtil.isEmpty(toolCalls)) {
            return List.of();
        }
        // 按工具调用 id 保存已经看到的工具调用，用于合并同 id 的分片字段。
        Map<String, AssistantMessage.ToolCall> mergedById = new LinkedHashMap<>();
        // 保持原始工具调用出现顺序的归一化列表。
        List<AssistantMessage.ToolCall> normalizedToolCalls = new ArrayList<>();
        // 遍历原始工具调用列表，逐个按 id 合并。
        for (int index = 0; index < toolCalls.size(); index++) {
            // 取出当前工具调用片段。
            AssistantMessage.ToolCall toolCall = toolCalls.get(index);
            // 原列表里如果出现 null，直接跳过，避免后续读取字段空指针。
            if (toolCall == null) {
                continue;
            }
            // 有 id 时按 id 合并；没有 id 时用带 index 的虚拟 key，避免多个无 id 调用被错误合并。
            String mergeKey = StrUtil.isNotBlank(toolCall.id()) ? toolCall.id() : "__dashscope_missing_id__" + index;
            // 查找同 key 的已有工具调用。
            AssistantMessage.ToolCall existing = mergedById.get(mergeKey);
            // 第一次看到该 key 时，直接加入 map 和结果列表。
            if (existing == null) {
                mergedById.put(mergeKey, toolCall);
                normalizedToolCalls.add(toolCall);
                continue;
            }
            // 再次看到同 key 时，将已有片段和当前片段按字段进行补齐式合并。
            AssistantMessage.ToolCall merged = new AssistantMessage.ToolCall(
                StrUtil.blankToDefault(existing.id(), toolCall.id()), // id 优先保留已有值，已有值为空时使用当前片段。
                StrUtil.blankToDefault(existing.type(), toolCall.type()),// type 优先保留已有值，已有值为空时使用当前片段。
                StrUtil.blankToDefault(existing.name(), toolCall.name()),// name 优先保留已有值，已有值为空时使用当前片段。
                StrUtil.blankToDefault(existing.arguments(), toolCall.arguments())  // arguments 优先保留已有值，已有值为空时使用当前片段。
            );
            // 更新 map，后续同 key 片段会继续基于合并后的结果补齐字段。
            mergedById.put(mergeKey, merged);
            // 找到结果列表中旧工具调用的位置，用合并后的工具调用替换它。
            int replaceIndex = normalizedToolCalls.indexOf(existing);
            // 正常情况下 replaceIndex 一定存在；这里保守判断，避免异常列表状态导致越界。
            if (replaceIndex >= 0) {
                normalizedToolCalls.set(replaceIndex, merged);
            }
        }
        // 返回保持顺序且已合并同 id 分片的工具调用列表。
        return normalizedToolCalls;
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
