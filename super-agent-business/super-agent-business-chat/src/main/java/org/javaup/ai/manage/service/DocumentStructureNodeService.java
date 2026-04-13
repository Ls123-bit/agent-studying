package org.javaup.ai.manage.service;

import org.javaup.ai.manage.data.SuperAgentDocumentStructureNode;
import org.javaup.ai.manage.support.DocumentStructureNodeCandidate;

import java.util.List;
import java.util.Map;

/**
 * 文档结构节点服务。
 */
public interface DocumentStructureNodeService {

    /**
     * 用新的解析结果替换当前文档的结构节点树。
     */
    List<SuperAgentDocumentStructureNode> replaceDocumentNodes(Long documentId,
                                                               Long parseTaskId,
                                                               List<DocumentStructureNodeCandidate> candidates);

    /**
     * 查询当前文档当前解析版本的结构节点。
     */
    List<SuperAgentDocumentStructureNode> listDocumentNodes(Long documentId, Long parseTaskId);

    /**
     * 按 id 构建结构节点映射。
     */
    Map<Long, SuperAgentDocumentStructureNode> nodeMap(Long documentId, Long parseTaskId);

    /**
     * 删除当前文档的结构节点。
     */
    void deleteByDocumentId(Long documentId);
}
