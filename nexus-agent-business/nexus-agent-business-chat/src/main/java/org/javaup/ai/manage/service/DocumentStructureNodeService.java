package org.javaup.ai.manage.service;

import org.javaup.ai.manage.data.SuperAgentDocumentStructureNode;
import org.javaup.ai.manage.support.DocumentStructureNodeCandidate;

import java.util.List;
import java.util.Map;


/*
结构节点的用途
结构节点是文档的"骨架"——标题、段落、列表等层级关系。它们会被写入 Neo4j 图数据库，支持图结构查询引擎的导航功能。
 */
public interface DocumentStructureNodeService {
//替换文档的结构节点（解析后调用）
    List<SuperAgentDocumentStructureNode> replaceDocumentNodes(Long documentId,
                                                               Long parseTaskId,
                                                               List<DocumentStructureNodeCandidate> candidates);
//列出文档的所有结构节点
    List<SuperAgentDocumentStructureNode> listDocumentNodes(Long documentId, Long parseTaskId);
//获取节点 Map（ID → 节点）
    Map<Long, SuperAgentDocumentStructureNode> nodeMap(Long documentId, Long parseTaskId);
//删除文档的所有结构节点
    void deleteByDocumentId(Long documentId);
}
