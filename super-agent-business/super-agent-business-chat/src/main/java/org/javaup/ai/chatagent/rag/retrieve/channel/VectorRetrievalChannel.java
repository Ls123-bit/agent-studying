package org.javaup.ai.chatagent.rag.retrieve.channel;

import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.manage.model.DocumentRetrieveRequest;
import org.javaup.ai.manage.service.DocumentKnowledgeService;
import org.springframework.stereotype.Component;

/**
 * 向量检索通道。
 */
@Component
public class VectorRetrievalChannel implements RetrievalChannel {

    private final DocumentKnowledgeService documentKnowledgeService;
    private final ChatRagProperties properties;

    public VectorRetrievalChannel(DocumentKnowledgeService documentKnowledgeService,
                                  ChatRagProperties properties) {
        this.documentKnowledgeService = documentKnowledgeService;
        this.properties = properties;
    }

    @Override
    public String channelName() {
        return "vector";
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
        return new RetrievalChannelResult(
            channelName(),
            documentKnowledgeService.vectorSearch(new DocumentRetrieveRequest(
                subQuestion,
                plan.getSelectedDocumentIds(),
                plan.getSelectedTaskIds(),
                properties.getVectorTopK()
            ))
        );
    }
}
