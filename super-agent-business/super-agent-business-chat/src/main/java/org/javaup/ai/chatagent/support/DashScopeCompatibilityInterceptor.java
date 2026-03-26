package org.javaup.ai.chatagent.support;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;
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

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        if (!(request.getOptions() instanceof OpenAiChatOptions options)) {
            return handler.call(request);
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
        return handler.call(compatibleRequest);
    }

    @Override
    public String getName() {
        return "dashscope_compatibility_interceptor";
    }

    private String summarizeTools(List<String> tools) {
        return CollectionUtils.isEmpty(tools) ? "[]" : tools.toString();
    }

    private String summarizeMessages(List<Message> messages) {
        if (CollectionUtils.isEmpty(messages)) {
            return "[]";
        }
        return messages.stream()
            .map(this::summarizeMessage)
            .collect(Collectors.joining(" | "));
    }

    private String summarizeMessage(Message message) {
        String type = message.getMessageType() != null
            ? message.getMessageType().name()
            : message.getClass().getSimpleName();

        if (message instanceof AbstractMessage abstractMessage && StringUtils.hasText(abstractMessage.getText())) {
            String text = abstractMessage.getText().replaceAll("\\s+", " ").trim();
            if (text.length() > 80) {
                text = text.substring(0, 80) + "...";
            }
            return type + ":" + text;
        }
        return type;
    }
}
