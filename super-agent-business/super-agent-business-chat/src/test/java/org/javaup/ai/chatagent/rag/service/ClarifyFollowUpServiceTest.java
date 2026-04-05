package org.javaup.ai.chatagent.rag.service;

import org.javaup.ai.chatagent.model.ConversationExchangeView;
import org.javaup.ai.chatagent.model.debug.ChatDebugTrace;
import org.javaup.ai.chatagent.rag.model.KnowledgeScopeOption;
import org.javaup.ai.chatagent.service.ConversationArchiveStore;
import org.javaup.enums.ChatTurnStatus;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class ClarifyFollowUpServiceTest {

    @Test
    void shouldResolveDoubleDigitSelectionFromLatestClarifyTurn() {
        ConversationArchiveStore conversationArchiveStore = Mockito.mock(ConversationArchiveStore.class);
        when(conversationArchiveStore.listRecentExchanges("c1", 6)).thenReturn(List.of(clarifyExchangeWithTenOptions()));
        ClarifyFollowUpService service = new ClarifyFollowUpService(conversationArchiveStore);

        var decision = service.resolve("c1", "选10").orElseThrow();

        assertEquals(ClarifyFollowUpService.ClarifyFollowUpAction.SELECTED, decision.action());
        assertEquals("网关协议配置", decision.originalQuestion());
        assertEquals("候选10", decision.selectedOption().getScopeName());
    }

    @Test
    void shouldResolveNaturalAliasSelection() {
        ConversationArchiveStore conversationArchiveStore = Mockito.mock(ConversationArchiveStore.class);
        when(conversationArchiveStore.listRecentExchanges("c1", 6)).thenReturn(List.of(clarifyExchangeForManual()));
        ClarifyFollowUpService service = new ClarifyFollowUpService(conversationArchiveStore);

        var decision = service.resolve("c1", "那个手册").orElseThrow();

        assertEquals(ClarifyFollowUpService.ClarifyFollowUpAction.SELECTED, decision.action());
        assertEquals("产品手册.pdf", decision.selectedOption().getScopeName());
    }

    @Test
    void shouldStayInClarifyWhenUserRejectsAllCandidates() {
        ConversationArchiveStore conversationArchiveStore = Mockito.mock(ConversationArchiveStore.class);
        when(conversationArchiveStore.listRecentExchanges("c1", 6)).thenReturn(List.of(clarifyExchangeForManual()));
        ClarifyFollowUpService service = new ClarifyFollowUpService(conversationArchiveStore);

        var decision = service.resolve("c1", "都不是").orElseThrow();

        assertEquals(ClarifyFollowUpService.ClarifyFollowUpAction.REASK, decision.action());
        assertTrue(decision.clarifyPrompt().contains("可以直接回复序号"));
        assertEquals(2, decision.scopeOptions().size());
    }

    @Test
    void shouldLearnDynamicSharedSuffixesFromCurrentCandidates() {
        ConversationArchiveStore conversationArchiveStore = Mockito.mock(ConversationArchiveStore.class);
        ClarifyFollowUpService service = new ClarifyFollowUpService(conversationArchiveStore);

        List<String> suffixes = service.resolveEffectiveSuffixes(List.of(
            new KnowledgeScopeOption("DOC1", "工业网关接入指引", List.of(1L), List.of(11L), 0D, List.of("工业网关接入指引")),
            new KnowledgeScopeOption("DOC2", "边缘路由接入指引", List.of(2L), List.of(22L), 0D, List.of("边缘路由接入指引"))
        ));

        assertTrue(suffixes.contains("接入指引"));
    }

    @Test
    void shouldSkipCurrentRunningExchangeAndUsePreviousClarifyTurn() {
        ConversationArchiveStore conversationArchiveStore = Mockito.mock(ConversationArchiveStore.class);
        when(conversationArchiveStore.listRecentExchanges("c1", 6)).thenReturn(List.of(
            clarifyExchangeForManual(),
            runningExchange("1")
        ));
        ClarifyFollowUpService service = new ClarifyFollowUpService(conversationArchiveStore);

        var decision = service.resolve("c1", "1").orElseThrow();

        assertEquals(ClarifyFollowUpService.ClarifyFollowUpAction.SELECTED, decision.action());
        assertEquals("产品手册.pdf", decision.selectedOption().getScopeName());
    }

    private ConversationExchangeView clarifyExchangeWithTenOptions() {
        List<KnowledgeScopeOption> options = new ArrayList<>();
        LongStream.rangeClosed(1, 10).forEach(index -> options.add(
            new KnowledgeScopeOption(
                "DOC" + index,
                "候选" + index,
                List.of(index),
                List.of(index * 10),
                10D - index,
                List.of("候选" + index + ".pdf")
            )
        ));
        return new ConversationExchangeView(
            100L,
            "网关协议配置",
            "请选择一个候选",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            ChatDebugTrace.builder()
                .routeType("CLARIFY")
                .scopeOptions(options)
                .build(),
            ChatTurnStatus.COMPLETED,
            "",
            null,
            null,
            new Date(),
            new Date()
        );
    }

    private ConversationExchangeView clarifyExchangeForManual() {
        return new ConversationExchangeView(
            200L,
            "这个产品的协议配置",
            "请选择一个候选",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            ChatDebugTrace.builder()
                .routeType("CLARIFY")
                .scopeOptions(List.of(
                    new KnowledgeScopeOption("DOC1", "产品手册.pdf", List.of(1L), List.of(101L), 8D, List.of("产品手册.pdf")),
                    new KnowledgeScopeOption("DOC2", "部署指南.md", List.of(2L), List.of(202L), 6D, List.of("部署指南.md"))
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

    private ConversationExchangeView runningExchange(String question) {
        return new ConversationExchangeView(
            300L,
            question,
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
        );
    }
}
