package org.javaup.ai.manage.service;

import org.javaup.ai.manage.data.SuperAgentDocumentStructureNode;
import org.javaup.ai.manage.support.DocumentStructureNodeCandidate;

import java.util.List;
import java.util.Map;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 服务层
 * @author: 阿星不是程序员
 **/
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
