package org.javaup.ai.chatagent.rag.service;

import org.javaup.ai.chatagent.model.ConversationExchangeView;
import org.javaup.ai.chatagent.model.debug.ChatDebugTrace;
import org.javaup.ai.chatagent.model.memory.ConversationMemoryContext;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.ExecutionMode;
import org.javaup.ai.chatagent.rag.model.KnowledgeScopeOption;
import org.javaup.ai.chatagent.service.ConversationArchiveStore;
import org.javaup.ai.chatagent.service.ConversationMemoryService;
import org.javaup.ai.manage.service.DocumentKnowledgeService;
import org.javaup.enums.ChatRouteType;
import org.javaup.enums.ChatTurnStatus;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatPreparationOrchestratorTest {

    @Test
    void shouldResolveNumericSelectionAgainstPreviousClarifyOptions() {
        ChatRouteService chatRouteService = Mockito.mock(ChatRouteService.class);
        ChatQueryRewriteService rewriteService = Mockito.mock(ChatQueryRewriteService.class);
        KnowledgeScopeResolver knowledgeScopeResolver = Mockito.mock(KnowledgeScopeResolver.class);
        DocumentKnowledgeService documentKnowledgeService = Mockito.mock(DocumentKnowledgeService.class);
        ConversationMemoryService conversationMemoryService = Mockito.mock(ConversationMemoryService.class);
        ConversationArchiveStore conversationArchiveStore = Mockito.mock(ConversationArchiveStore.class);

        when(conversationMemoryService.loadMemoryContext("c1")).thenReturn(ConversationMemoryContext.builder()
            .assembledHistory("【最近对话原文】\n用户：这个产品的协议配置\n助手：请在候选里选一个")
            .build());
        when(conversationArchiveStore.listRecentExchanges("c1", 6)).thenReturn(List.of(clarifyExchange()));
        ClarifyFollowUpService clarifyFollowUpService = new ClarifyFollowUpService(conversationArchiveStore);

        ChatPreparationOrchestrator orchestrator = new ChatPreparationOrchestrator(
            new ChatRagProperties(),
            chatRouteService,
            rewriteService,
            knowledgeScopeResolver,
            documentKnowledgeService,
            conversationMemoryService,
            clarifyFollowUpService
        );

        var plan = orchestrator.prepare("c1", "1", LocalDate.of(2026, 4, 5), "2026-04-05（星期日）");

        assertEquals(ExecutionMode.RAG_CHAT, plan.getMode());
        assertEquals(ChatRouteType.KNOWLEDGE, plan.getRouteType());
        assertEquals("这个产品的协议配置", plan.getRewrittenQuestion());
        assertIterableEquals(List.of(1L), plan.getSelectedDocumentIds());
        assertIterableEquals(List.of(101L), plan.getSelectedTaskIds());
        verify(chatRouteService, never()).route(anyString(), anyString(), Mockito.anyBoolean());
    }

    @Test
    void shouldResolveScopeNameSelectionAgainstPreviousClarifyOptions() {
        ChatRouteService chatRouteService = Mockito.mock(ChatRouteService.class);
        ChatQueryRewriteService rewriteService = Mockito.mock(ChatQueryRewriteService.class);
        KnowledgeScopeResolver knowledgeScopeResolver = Mockito.mock(KnowledgeScopeResolver.class);
        DocumentKnowledgeService documentKnowledgeService = Mockito.mock(DocumentKnowledgeService.class);
        ConversationMemoryService conversationMemoryService = Mockito.mock(ConversationMemoryService.class);
        ConversationArchiveStore conversationArchiveStore = Mockito.mock(ConversationArchiveStore.class);

        when(conversationMemoryService.loadMemoryContext("c1")).thenReturn(ConversationMemoryContext.builder()
            .assembledHistory("【最近对话原文】\n用户：这个产品的协议配置\n助手：请在候选里选一个")
            .build());
        when(conversationArchiveStore.listRecentExchanges("c1", 6)).thenReturn(List.of(clarifyExchange()));
        ClarifyFollowUpService clarifyFollowUpService = new ClarifyFollowUpService(conversationArchiveStore);

        ChatPreparationOrchestrator orchestrator = new ChatPreparationOrchestrator(
            new ChatRagProperties(),
            chatRouteService,
            rewriteService,
            knowledgeScopeResolver,
            documentKnowledgeService,
            conversationMemoryService,
            clarifyFollowUpService
        );

        var plan = orchestrator.prepare("c1", "产品手册.pdf", LocalDate.of(2026, 4, 5), "2026-04-05（星期日）");

        assertEquals(ExecutionMode.RAG_CHAT, plan.getMode());
        assertEquals("这个产品的协议配置", plan.getRewrittenQuestion());
        assertIterableEquals(List.of(1L), plan.getSelectedDocumentIds());
    }

    private ConversationExchangeView clarifyExchange() {
        return new ConversationExchangeView(
            10L,
            "这个产品的协议配置",
            "我需要先确认你想问的是哪个业务系统，请在下面这些知识域里选一个：\n1. 产品手册.pdf\n2. Java语言特性与核心概念.md",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            ChatDebugTrace.builder()
                .routeType("CLARIFY")
                .scopeOptions(List.of(
                    new KnowledgeScopeOption("DOC1", "产品手册.pdf", List.of(1L), List.of(101L), 10D, List.of("产品手册.pdf")),
                    new KnowledgeScopeOption("DOC2", "Java语言特性与核心概念.md", List.of(2L), List.of(202L), 6D, List.of("Java语言特性与核心概念.md"))
                ))
                .build(),
            ChatTurnStatus.COMPLETED,
            "",
            null,
            null,
            new Date(),
            new Date()
        );
    }
}
