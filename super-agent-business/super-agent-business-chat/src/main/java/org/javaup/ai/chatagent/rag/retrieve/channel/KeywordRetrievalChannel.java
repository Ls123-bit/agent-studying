package org.javaup.ai.chatagent.rag.retrieve.channel;

import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.manage.model.DocumentRetrieveRequest;
import org.javaup.ai.manage.service.DocumentKnowledgeService;
import org.springframework.stereotype.Component;

/**
 * 关键词检索通道。
 */
@Component
public class KeywordRetrievalChannel implements RetrievalChannel {

    private final DocumentKnowledgeService documentKnowledgeService;
    private final ChatRagProperties properties;

    public KeywordRetrievalChannel(DocumentKnowledgeService documentKnowledgeService,
                                   ChatRagProperties properties) {
        this.documentKnowledgeService = documentKnowledgeService;
        this.properties = properties;
    }

    @Override
    public String channelName() {
        return "keyword";
    }

    @Override
    public boolean supports(ConversationExecutionPlan plan) {
        /*
         * 关键词通道除了要有文档范围，还要看配置是否开启。
         * 这样一旦后续想只保留向量通道，只改配置即可，不需要删代码。
         */
        return properties.isKeywordChannelEnabled()
            && plan.getSelectedDocumentIds() != null
            && !plan.getSelectedDocumentIds().isEmpty();
    }

    @Override
    public RetrievalChannelResult retrieve(String subQuestion, ConversationExecutionPlan plan) {
        /*
         * 关键词通道的作用不是替代向量通道，而是补它的盲区：
         * 版本号、英文缩写、专有名词、数字配置项等都更适合这条路径命中。
         */
        return new RetrievalChannelResult(
            channelName(),
            documentKnowledgeService.keywordSearch(new DocumentRetrieveRequest(
                subQuestion,
                plan.getSelectedDocumentIds(),
                plan.getSelectedTaskIds(),
                properties.getKeywordTopK()
            ))
        );
    }
}
