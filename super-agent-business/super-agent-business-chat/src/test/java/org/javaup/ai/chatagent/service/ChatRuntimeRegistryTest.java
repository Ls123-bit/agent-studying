package org.javaup.ai.chatagent.service;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import org.javaup.ai.chatagent.model.debug.ChatDebugTrace;
import org.javaup.ai.chatagent.support.StreamEventMetadata;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatRuntimeRegistryTest {

    @Test
    void removeShouldOnlyAffectMatchingTask() {
        ChatRuntimeRegistry registry = new ChatRuntimeRegistry();
        TaskInfo originalTask = newTask("conversation-1", 1L);
        TaskInfo otherTask = newTask("conversation-1", 2L);

        assertTrue(registry.register(originalTask));

        registry.remove("conversation-1", otherTask);
        assertTrue(registry.get("conversation-1").isPresent());
        assertEquals(1L, registry.get("conversation-1").orElseThrow().exchangeId());

        registry.remove("conversation-1", originalTask);
        assertFalse(registry.get("conversation-1").isPresent());
    }

    private TaskInfo newTask(String conversationId, long exchangeId) {
        return new TaskInfo(
            conversationId,
            exchangeId,
            "question",
            LocalDate.of(2026, 4, 4),
            "2026-04-04（星期六）",
            null,
            ChatDebugTrace.builder().build(),
            RunnableConfig.builder().threadId(conversationId).build(),
            Sinks.many().unicast().onBackpressureBuffer(),
            new StreamEventMetadata(conversationId, exchangeId),
            "chat:running:" + conversationId,
            "owner-" + exchangeId,
            Collections.synchronizedList(new ArrayList<>()),
            Collections.synchronizedList(new ArrayList<>()),
            ConcurrentHashMap.newKeySet(),
            System.currentTimeMillis()
        );
    }
}
