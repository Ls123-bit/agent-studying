package org.javaup.ai.chatagent.rag.retrieve.channel;

import cn.hutool.core.collection.CollectionUtil;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.service.DocumentRetrieveRequestFactory;
import org.javaup.ai.manage.service.DocumentKnowledgeService;
import org.javaup.enums.RetrievalChannelEnum;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;

/*
关键词检索
基于 Elasticsearch 的关键词检索。用传统的文本匹配方式找相关文档块。

VectorRetrievalChannel（向量检索）+KeywordRetrievalChannel（关键词检索） -->双通道
向量检索擅长语义理解（"怎么部署"能匹配到"安装步骤"），但可能漏掉精确关键词。关键词检索擅长精确匹配（专有名词、代码片段），但不理解语义。两个通道互补，效果比单通道好很多。

 */
@Component
public class KeywordRetrievalChannel implements RetrievalChannel {

    private final DocumentKnowledgeService documentKnowledgeService;
    private final ChatRagProperties properties;
    private final DocumentRetrieveRequestFactory documentRetrieveRequestFactory;

    public KeywordRetrievalChannel(DocumentKnowledgeService documentKnowledgeService,
                                   ChatRagProperties properties,
                                   DocumentRetrieveRequestFactory documentRetrieveRequestFactory) {
        this.documentKnowledgeService = documentKnowledgeService;
        this.properties = properties;
        this.documentRetrieveRequestFactory = documentRetrieveRequestFactory;
    }

    @Override
    public String channelName() {
        return RetrievalChannelEnum.KEYWORD.getName();
    }//返回 "KEYWORD"

    @Override
    public boolean supports(ConversationExecutionPlan plan) {//关键词通道启用 且 有选中文档时返回 true

        return properties.isKeywordChannelEnabled()
            && plan.getSelectedDocumentId() != null;
    }

    @Override
    public RetrievalChannelResult retrieve(String subQuestion, ConversationExecutionPlan plan) {//调用 documentKnowledgeService.keywordSearch()
        List<Document> documentList = documentKnowledgeService.keywordSearch(
            documentRetrieveRequestFactory.build(subQuestion, plan, properties.getKeywordTopK())//配置项： keywordTopK — 关键词检索返回的候选数量
        );

        return new RetrievalChannelResult(
            channelName(), documentList
        );
    }
}
