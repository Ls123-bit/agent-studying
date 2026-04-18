package org.javaup.ai.manage.service.keyword;

import org.javaup.ai.manage.data.SuperAgentDocumentChunk;
import org.javaup.ai.manage.model.DocumentRetrieveRequest;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 服务层
 * @author: 阿星不是程序员
 **/
/**
 * 文档关键词检索网关。
 *
 * <p>这层把“倒排索引怎么建立、关键词怎么搜索”从主业务里抽离出来，
 * 这样当前可以接 Elasticsearch，未来如果要换 OpenSearch 或其他全文检索后端，
 * 上层流程仍然保持不变。</p>
 */
public interface DocumentKeywordSearchGateway {

    /**
     * 把一批业务 chunk 写入关键词检索索引。
     */
    void indexChunks(List<SuperAgentDocumentChunk> chunkList);

    /**
     * 基于关键词检索返回候选文档。
     */
    List<Document> search(DocumentRetrieveRequest request);

    /**
     * 删除某个文档在关键词索引中的全部数据。
     */
    void deleteByDocumentId(Long documentId);
}
