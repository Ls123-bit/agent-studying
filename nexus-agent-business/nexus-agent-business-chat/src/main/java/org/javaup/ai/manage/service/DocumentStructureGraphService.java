package org.javaup.ai.manage.service;

import org.javaup.ai.manage.model.graph.GraphItem;
import org.javaup.ai.manage.model.graph.GraphSection;

import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 服务层
 * @author: 阿星不是程序员
 **/
/*
底层图服务：
StructureGraphQueryEngine 内部委托给 DocumentStructureGraphService 做实际的图操作：
图服务的三种实现：
实现类                                         说明
Neo4jDocumentStructureGraphService            Neo4j 实现（主）
MysqlDocumentStructureGraphService            MySQL 实现（备）
CompositeDocumentStructureGraphService        组合实现：Neo4j + MySQL 互为备份
 */
public interface DocumentStructureGraphService {

    default boolean isGraphAvailable(Long documentId) {
        return documentId != null;
    }
//按ID精确查找章节
    GraphSection findSectionById(Long documentId, Long sectionNodeId);

    GraphSection findSectionByCode(Long documentId, String nodeCode);

    GraphSection findSectionByTitle(Long documentId, String title);

    GraphSection findSectionByCanonicalPath(Long documentId, String canonicalPath);
//按话题名模糊匹配最佳章节
    GraphSection findBestSection(Long documentId, String topic, String facet);

    List<GraphSection> listSections(Long documentId);
//列出子节点
    List<GraphSection> listChildren(Long documentId, Long sectionNodeId);
//查找父节点
    GraphSection parentSection(Long documentId, Long sectionNodeId);
//查找上一个兄弟节点
    GraphSection previousSibling(Long documentId, Long sectionNodeId);
//查找下一个兄弟节点
    GraphSection nextSibling(Long documentId, Long sectionNodeId);
//按序号查找条目
    GraphItem findItemByIndex(Long documentId, Long sectionNodeId, Integer itemIndex);
//列出章节下所有条目
    List<GraphItem> listItems(Long documentId, Long sectionNodeId);
//关键词搜索条目
    List<GraphItem> searchItemsInSection(Long documentId, Long sectionNodeId, String keyword);
}
