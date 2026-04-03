package org.javaup.ai.manage.service;

import org.javaup.ai.manage.model.DocumentRetrieveRequest;
import org.javaup.ai.manage.model.KnowledgeDocumentDescriptor;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 文档知识检索服务。
 *
 * <p>这个接口故意只暴露“列目录”和“取证据”两类能力：</p>
 * <p>1. 管理台问答可以在它之上继续生成自然语言答案。</p>
 * <p>2. 聊天侧可以把它当成可复用的知识检索底座，自己编排问题改写、路由、Prompt 和流式输出。</p>
 */
public interface DocumentKnowledgeService {

    /**
     * 列出当前所有可参与知识问答的文档。
     */
    List<KnowledgeDocumentDescriptor> listRetrievableDocuments();

    /**
     * 执行向量检索。
     */
    List<Document> vectorSearch(DocumentRetrieveRequest request);

    /**
     * 执行关键词检索。
     */
    List<Document> keywordSearch(DocumentRetrieveRequest request);
}
