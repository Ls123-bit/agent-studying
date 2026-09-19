package org.javaup.ai.chatagent.rag.retrieve.channel;

import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.service.DocumentRetrieveRequestFactory;
import org.javaup.ai.manage.service.DocumentKnowledgeService;
import org.javaup.enums.RetrievalChannelEnum;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;

/*
向量检索
基于 PGVector 的语义相似度检索。把问题转成向量，在向量库里找最相似的文档块。

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
    public String channelName() {//返回 "VECTOR"
        return RetrievalChannelEnum.VECTOR.getName();
    }

    /*
    先看向量检索通道的实现
    向量检索通道的判断逻辑很简单，只有一个条件：用户必须选择了文档。
    为什么要这样设计呢？因为向量检索需要在向量索引中查找，而向量索引是按文档组织的。如果用户没有选择文档，系统就不知道该去哪个向量索引中检索。
    实际场景
    假设用户在聊天界面选择了"Java 基础教程"这个文档，然后问"Java 的特点是什么？"。这时候：
    plan.getSelectedDocumentId() 会返回"Java 基础教程"的文档 ID
    向量检索通道的 supports 方法返回 true
    系统会在"Java 基础教程"的向量索引中检索相关内容
     */
    @Override
    public boolean supports(ConversationExecutionPlan plan) {//有选中文档时返回 true
        // 向量检索通道只要求用户选择了文档就可以工作
        return plan.getSelectedDocumentId() != null;
    }

    @Override
    public RetrievalChannelResult retrieve(String subQuestion, ConversationExecutionPlan plan) {//调用 documentKnowledgeService.vectorSearch()

        List<Document> documentList = documentKnowledgeService.vectorSearch(
            documentRetrieveRequestFactory.build(subQuestion, plan, properties.getVectorTopK())//配置项：vectorTopK — 向量检索返回的候选数量
        );
        return new RetrievalChannelResult(
            channelName(), documentList
        );
    }
}
