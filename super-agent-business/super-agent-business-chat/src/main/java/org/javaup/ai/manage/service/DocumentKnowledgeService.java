package org.javaup.ai.manage.service;

import org.javaup.ai.manage.model.DocumentRetrieveRequest;
import org.javaup.ai.manage.model.KnowledgeDocumentDescriptor;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 服务层
 * @author: 阿星不是程序员
 **/
/**
 * 文档知识检索服务。
 *
 * <p>这个接口故意只暴露“列目录”和“取证据”两类能力：</p>
 * <p>1. 管理台问答可以在它之上继续生成自然语言答案。</p>
 * <p>2. 聊天侧可以把它当成可复用的知识检索底座，自己编排问题改写、Prompt 和流式输出。</p>
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

    /**
     * 把命中的 child chunk 提升成稳定的 parent 证据块。
     */
    List<Document> elevateToParentBlocks(List<Document> childDocuments, int maxChars);
}
