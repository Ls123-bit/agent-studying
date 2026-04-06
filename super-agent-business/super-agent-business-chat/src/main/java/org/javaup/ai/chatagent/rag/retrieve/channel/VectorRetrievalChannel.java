package org.javaup.ai.chatagent.rag.retrieve.channel;

import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.service.DocumentRetrieveRequestFactory;
import org.javaup.ai.manage.service.DocumentKnowledgeService;
import org.javaup.enums.RetrievalChannelEnum;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 向量检索通道。
 */
@Component
public class VectorRetrievalChannel implements RetrievalChannel {

    private final DocumentKnowledgeService documentKnowledgeService;
    private final ChatRagProperties properties;
    private final DocumentRetrieveRequestFactory documentRetrieveRequestFactory;

    public VectorRetrievalChannel(DocumentKnowledgeService documentKnowledgeService,
                                  ChatRagProperties properties,
                                  DocumentRetrieveRequestFactory documentRetrieveRequestFactory) {
        this.documentKnowledgeService = documentKnowledgeService;
        this.properties = properties;
        this.documentRetrieveRequestFactory = documentRetrieveRequestFactory;
    }

    @Override
    public String channelName() {
        return RetrievalChannelEnum.VECTOR.getName();
    }

    @Override
    public boolean supports(ConversationExecutionPlan plan) {
        /*
         * 向量通道的最小前提就是：当前轮已经收敛到了具体文档范围。
         * 如果 selectedDocumentIds 为空，就说明这轮知识问答根本没有可查的文档集合。
         */
        return plan.getSelectedDocumentIds() != null && !plan.getSelectedDocumentIds().isEmpty();
    }

    @Override
    public RetrievalChannelResult retrieve(String subQuestion, ConversationExecutionPlan plan) {
        /*
         * 这里不直接做向量化和 SQL 检索，而是统一复用 DocumentKnowledgeService。
         * RetrievalChannel 只负责“通道编排层”的职责，不承担底层数据访问细节。
         */
        List<Document> documentList = documentKnowledgeService.vectorSearch(
            documentRetrieveRequestFactory.build(subQuestion, plan, properties.getVectorTopK())
        );
        return new RetrievalChannelResult(
            channelName(), documentList
        );
    }
}
