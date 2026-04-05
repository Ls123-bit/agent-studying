package org.javaup.ai.chatagent.rag.service;

import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.manage.model.KnowledgeDocumentDescriptor;
import org.javaup.ai.manage.service.DocumentKnowledgeService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

class KnowledgeScopeResolverTest {

    private final KnowledgeScopeResolver resolver = new KnowledgeScopeResolver(
        Mockito.mock(DocumentKnowledgeService.class),
        new ChatRagProperties()
    );

    @Test
    void shouldPreferSpecificRefundScopeOverBroadOrderScope() {
        List<KnowledgeDocumentDescriptor> descriptors = List.of(
            descriptor(1L, 11L, "ORDER", "订单系统", "订单系统操作手册"),
            descriptor(2L, 22L, "ORDER_REFUND", "订单退款系统", "订单退款操作手册")
        );

        var resolution = resolver.resolve("订单退款怎么处理", "", descriptors);

        assertIterableEquals(List.of(2L), resolution.getSelectedDocumentIds());
        assertIterableEquals(List.of(22L), resolution.getSelectedTaskIds());
        assertEquals(1, resolution.getOptions().size());
        assertEquals("订单退款系统", resolution.getOptions().get(0).getScopeName());
    }

    @Test
    void shouldKeepBroadScopeWhenQuestionExactlyMatchesIt() {
        List<KnowledgeDocumentDescriptor> descriptors = List.of(
            descriptor(1L, 11L, "ORDER", "订单系统", "订单系统操作手册"),
            descriptor(2L, 22L, "ORDER_REFUND", "订单退款系统", "订单退款操作手册")
        );

        var resolution = resolver.resolve("订单系统怎么配置", "", descriptors);

        assertIterableEquals(List.of(1L), resolution.getSelectedDocumentIds());
        assertIterableEquals(List.of(11L), resolution.getSelectedTaskIds());
        assertEquals(1, resolution.getOptions().size());
        assertEquals("订单系统", resolution.getOptions().get(0).getScopeName());
    }

    @Test
    void shouldRetainMultipleScopesWhenQuestionIsStillTrulyAmbiguous() {
        List<KnowledgeDocumentDescriptor> descriptors = List.of(
            descriptor(1L, 11L, "PURCHASE_SYS", "采购系统", "采购系统操作手册"),
            descriptor(2L, 22L, "PURCHASE_PLATFORM", "采购平台", "采购平台操作手册")
        );

        var resolution = resolver.resolve("采购", "", descriptors);

        assertIterableEquals(List.of(1L, 2L), resolution.getSelectedDocumentIds());
        assertEquals(2, resolution.getOptions().size());
    }

    private KnowledgeDocumentDescriptor descriptor(Long documentId,
                                                   Long taskId,
                                                   String scopeCode,
                                                   String scopeName,
                                                   String documentName) {
        return new KnowledgeDocumentDescriptor(
            documentId,
            documentName,
            taskId,
            scopeCode,
            scopeName,
            "",
            ""
        );
    }
}
