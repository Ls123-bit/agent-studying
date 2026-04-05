package org.javaup.ai.chatagent.rag.service;

import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.retrieve.channel.RetrievalChannel;
import org.javaup.ai.chatagent.rag.retrieve.channel.RetrievalChannelResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.document.Document;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagRetrievalEngineTest {

    private final ExecutorService executorService = Executors.newFixedThreadPool(4);

    @AfterEach
    void tearDown() {
        executorService.shutdownNow();
    }

    @Test
    void shouldDegradeWhenRetrievalChannelsFailOrTimeout() {
        ChatRagProperties properties = new ChatRagProperties();
        properties.setRerankEnabled(false);
        properties.setChannelTimeoutMs(50L);
        properties.setSubQuestionTimeoutMs(500L);
        properties.setCandidateTopK(5);
        properties.setFinalTopK(5);

        HttpDocumentRerankPostProcessor rerankPostProcessor = Mockito.mock(HttpDocumentRerankPostProcessor.class);

        RagRetrievalEngine engine = new RagRetrievalEngine(
            List.of(new SuccessChannel(), new TimeoutChannel(), new FailureChannel()),
            properties,
            rerankPostProcessor,
            executorService
        );

        ConversationExecutionPlan plan = ConversationExecutionPlan.builder()
            .rewrittenQuestion("如何配置审批流程")
            .subQuestions(List.of("如何配置审批流程"))
            .selectedDocumentIds(List.of(1L))
            .selectedTaskIds(List.of(10L))
            .build();

        var context = engine.retrieve(plan);

        assertEquals(1, context.flattenReferences().size());
        assertTrue(context.getRetrievalNotes().stream().anyMatch(note -> note.contains("通道[timeout]")));
        assertTrue(context.getRetrievalNotes().stream().anyMatch(note -> note.contains("通道[failure]")));
        assertTrue(context.getUsedChannels().contains("success"));
    }

    private static final class SuccessChannel implements RetrievalChannel {
        @Override
        public String channelName() {
            return "success";
        }

        @Override
        public boolean supports(ConversationExecutionPlan plan) {
            return true;
        }

        @Override
        public RetrievalChannelResult retrieve(String subQuestion, ConversationExecutionPlan plan) {
            return new RetrievalChannelResult(
                channelName(),
                List.of(Document.builder()
                    .id("doc-1")
                    .text("审批流程可以通过管理台配置。")
                    .metadata(new LinkedHashMap<>())
                    .build())
            );
        }
    }

    private static final class TimeoutChannel implements RetrievalChannel {
        @Override
        public String channelName() {
            return "timeout";
        }

        @Override
        public boolean supports(ConversationExecutionPlan plan) {
            return true;
        }

        @Override
        public RetrievalChannelResult retrieve(String subQuestion, ConversationExecutionPlan plan) {
            try {
                Thread.sleep(200L);
            }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return new RetrievalChannelResult(channelName(), List.of());
        }
    }

    private static final class FailureChannel implements RetrievalChannel {
        @Override
        public String channelName() {
            return "failure";
        }

        @Override
        public boolean supports(ConversationExecutionPlan plan) {
            return true;
        }

        @Override
        public RetrievalChannelResult retrieve(String subQuestion, ConversationExecutionPlan plan) {
            throw new IllegalStateException("boom");
        }
    }
}
