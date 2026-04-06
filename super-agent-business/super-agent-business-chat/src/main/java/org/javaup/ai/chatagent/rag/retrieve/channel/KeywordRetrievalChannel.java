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

/**
 * 关键词检索通道。
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
    }

    @Override
    public boolean supports(ConversationExecutionPlan plan) {
        /*
         * 关键词通道除了要有文档范围，还要看配置是否开启。
         * 这样一旦后续想只保留向量通道，只改配置即可，不需要删代码。
         */
        return properties.isKeywordChannelEnabled()
            && CollectionUtil.isNotEmpty(plan.getSelectedDocumentIds());
    }

    @Override
    public RetrievalChannelResult retrieve(String subQuestion, ConversationExecutionPlan plan) {
        List<Document> documentList = documentKnowledgeService.keywordSearch(
            documentRetrieveRequestFactory.build(subQuestion, plan, properties.getKeywordTopK())
        );
        /*
         * 关键词通道的作用不是替代向量通道，而是补它的盲区：
         * 版本号、英文缩写、专有名词、数字配置项等都更适合这条路径命中。
         */
        return new RetrievalChannelResult(
            channelName(), documentList
        );
    }
}
