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

public interface DocumentKnowledgeService {

    List<KnowledgeDocumentDescriptor> listRetrievableDocuments();

    List<Document> vectorSearch(DocumentRetrieveRequest request);

    List<Document> keywordSearch(DocumentRetrieveRequest request);

    List<Document> elevateToParentBlocks(List<Document> childDocuments, int maxChars);//父块提升
    /*
    项目的分块策略是"父子双层"：小块用于检索（精准），大块用于回答（完整）。检索时用小块找到相关内容，然后"提升"到它所属的父块，把更完整的上下文交给模型。
    这样做的好处：检索精度高（小块匹配更准），回答质量好（大块上下文更完整）。
     */
}
