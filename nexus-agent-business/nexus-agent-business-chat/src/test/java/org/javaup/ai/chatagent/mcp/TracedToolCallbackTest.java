package org.javaup.ai.chatagent.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
TracedToolCallback 的单元测试。

关注三件事，任何一件破了都会让治理静默失效：
1. 定义与元数据必须原样透传（ToolCallLimitHook 按定义名建限额，AgentToolNode 按 metadata.returnDirect 决定提前结束）；
2. 异常必须原样抛出（交给 ToolErrorInterceptor 兜底）；
3. 没有 ToolContext 时不能炸（手工调用场景）。
 */
class TracedToolCallbackTest {

    private static ToolDefinition definition(String name) {
        return ToolDefinition.builder().name(name).description("desc").inputSchema("{}").build();
    }

    private static final class StubCallback implements ToolCallback {

        private final ToolDefinition toolDefinition;

        private final ToolMetadata toolMetadata;

        private final AtomicReference<String> lastInput = new AtomicReference<>();

        private final RuntimeException failure;

        StubCallback(String name, boolean returnDirect, RuntimeException failure) {
            this.toolDefinition = definition(name);
            this.toolMetadata = ToolMetadata.builder().returnDirect(returnDirect).build();
            this.failure = failure;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return toolDefinition;
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return toolMetadata;
        }

        @Override
        public String call(String toolInput) {
            lastInput.set(toolInput);
            if (failure != null) {
                throw failure;
            }
            return "result-of-" + toolDefinition.name();
        }
    }

    @Test
    void delegatesDefinitionAndMetadataUnchanged() {
        StubCallback delegate = new StubCallback("check_attendance", true, null);
        ToolCallback wrapped = TracedToolCallback.wrap("office", delegate, null);

        // 必须返回同一个对象：任何包装/改写都会让按名字匹配的 Hook 与 returnDirect 判断失准。
        assertSame(delegate.getToolDefinition(), wrapped.getToolDefinition());
        assertSame(delegate.getToolMetadata(), wrapped.getToolMetadata());
        assertEquals("check_attendance", wrapped.getToolDefinition().name());
        assertTrue(wrapped.getToolMetadata().returnDirect());
    }

    @Test
    void returnsDelegateResultAndForwardsInput() {
        StubCallback delegate = new StubCallback("check_attendance", false, null);
        ToolCallback wrapped = TracedToolCallback.wrap("office", delegate, null);

        // 传 null ToolContext：ToolTraceSupport 会直接跳过打点，调用本身必须照常完成。
        String result = wrapped.call("{\"userId\":\"u1\"}", null);

        assertEquals("result-of-check_attendance", result);
        assertEquals("{\"userId\":\"u1\"}", delegate.lastInput.get());
    }

    @Test
    void passesToolContextThroughToDelegate() {
        AtomicReference<ToolContext> seenContext = new AtomicReference<>();
        ToolDefinition toolDefinition = definition("list_rooms");
        ToolMetadata toolMetadata = ToolMetadata.builder().returnDirect(false).build();
        ToolCallback delegate = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return toolDefinition;
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return toolMetadata;
            }

            @Override
            public String call(String toolInput) {
                return "single-arg";
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                seenContext.set(toolContext);
                return "two-arg";
            }
        };

        ToolContext toolContext = new ToolContext(Map.of("k", "v"));
        String result = TracedToolCallback.wrap("office", delegate, null).call("{}", toolContext);

        assertEquals("two-arg", result);
        assertSame(toolContext, seenContext.get());
    }

    @Test
    void propagatesDelegateFailureUntouched() {
        IllegalStateException boom = new IllegalStateException("mcp down");
        StubCallback delegate = new StubCallback("check_attendance", false, boom);
        ToolCallback wrapped = TracedToolCallback.wrap("office", delegate, null);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> wrapped.call("{}", null));

        // 同一个异常实例：ToolErrorInterceptor 依赖这个异常被继续抛出才能兜底成错误消息。
        assertSame(boom, thrown);
    }
}
