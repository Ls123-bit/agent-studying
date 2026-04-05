package org.javaup.ai.chatagent.service;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.javaup.ai.chatagent.config.ChatAgentProperties;
import org.javaup.ai.chatagent.dto.ChatRequestDto;
import org.javaup.ai.chatagent.model.ConversationExchangeView;
import org.javaup.ai.chatagent.rag.executor.ClarifyExecutor;
import org.javaup.ai.chatagent.rag.executor.ConversationExecutorRegistry;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.model.ExecutionMode;
import org.javaup.ai.chatagent.rag.service.ChatPreparationOrchestrator;
import org.javaup.ai.chatagent.support.StreamEventWriter;
import org.javaup.enums.ChatRouteType;
import org.javaup.enums.ChatTurnStatus;
import org.javaup.lease.RedisLeaseManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BusinessChatServiceTest {

    @Mock
    private ReactAgent reactAgent;
    @Mock
    private ChatCheckpointManager checkpointManager;
    @Mock
    private ConversationArchiveStore conversationArchiveStore;
    @Mock
    private RecommendationService recommendationService;
    @Mock
    private RedisLeaseManager redisLeaseManager;
    @Mock
    private ChatPreparationOrchestrator chatPreparationOrchestrator;
    @Mock
    private ConversationMemoryService conversationMemoryService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void synchronousClarifyExecutionShouldCleanupAndEmitConversationMetadata() throws Exception {
        ChatAgentProperties chatAgentProperties = new ChatAgentProperties();
        chatAgentProperties.setRecommendationEnabled(false);

        StreamEventWriter streamEventWriter = new StreamEventWriter(objectMapper);
        ChatRuntimeRegistry runtimeRegistry = new ChatRuntimeRegistry();
        ConversationExecutorRegistry executorRegistry = new ConversationExecutorRegistry(
            List.of(new ClarifyExecutor(streamEventWriter))
        );

        BusinessChatService service = new BusinessChatService(
            reactAgent,
            checkpointManager,
            chatAgentProperties,
            conversationArchiveStore,
            runtimeRegistry,
            recommendationService,
            streamEventWriter,
            redisLeaseManager,
            chatPreparationOrchestrator,
            executorRegistry,
            conversationMemoryService
        );

        when(redisLeaseManager.acquire(anyString(), anyString(), any())).thenReturn(true);
        when(conversationArchiveStore.startExchange(anyString(), anyString())).thenAnswer(invocation ->
            new ConversationExchangeView(
                1001L,
                invocation.getArgument(1, String.class),
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                ChatTurnStatus.RUNNING,
                "",
                null,
                null,
                new Date(),
                new Date()
            )
        );
        when(chatPreparationOrchestrator.prepare(anyString(), anyString(), any(LocalDate.class), anyString()))
            .thenAnswer(invocation -> ConversationExecutionPlan.builder()
                .mode(ExecutionMode.CLARIFY)
                .routeType(ChatRouteType.CLARIFY)
                .originalQuestion(invocation.getArgument(1, String.class))
                .rewrittenQuestion(invocation.getArgument(1, String.class))
                .currentDate(invocation.getArgument(2, LocalDate.class))
                .currentDateText(invocation.getArgument(3, String.class))
                .historySummary("")
                .longTermSummary("")
                .recentHistoryTranscript("")
                .clarifyPrompt("请补充更具体的系统名称")
                .build());

        List<String> events = service.openConversationStream(new ChatRequestDto("这个流程怎么走", null))
            .collectList()
            .block(Duration.ofSeconds(5));

        assertNotNull(events);
        assertFalse(events.isEmpty());

        Map<String, Object> firstEvent = objectMapper.readValue(events.get(0), new TypeReference<>() {
        });
        String conversationId = firstEvent.get("conversationId").toString();

        assertNotNull(conversationId);
        assertFalse(conversationId.isBlank());
        assertNotNull(firstEvent.get("exchangeId"));
        verify(redisLeaseManager).release(eq("chat:running:" + conversationId), anyString());
        verify(conversationMemoryService).refreshConversationSummaryAsync(conversationId);
        verify(conversationArchiveStore).completeExchange(
            eq(conversationId),
            eq(1001L),
            anyString(),
            anyList(),
            anyList(),
            anyList(),
            anyList(),
            any(),
            eq(ChatTurnStatus.COMPLETED),
            eq(""),
            any(),
            anyLong()
        );
        assertFalse(runtimeRegistry.get(conversationId).isPresent());
    }
}
