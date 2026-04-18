package org.javaup.ai.manage.service;

import org.javaup.ai.manage.model.graph.GraphItem;
import org.javaup.ai.manage.model.graph.GraphSection;

import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 服务层
 * @author: 阿星不是程序员
 **/
/**
 * 文档结构图查询服务。
 *
 * <p>调用方只面向这组原子查询，不关心底层来自 MySQL 结构表还是 Neo4j 图数据库。</p>
 */
public interface DocumentStructureGraphService {

    /**
     * 当前服务是否可服务指定文档。
     */
    default boolean isGraphAvailable(Long documentId) {
        return documentId != null;
    }

    GraphSection findSectionById(Long documentId, Long sectionNodeId);

    GraphSection findSectionByCode(Long documentId, String nodeCode);

    GraphSection findSectionByTitle(Long documentId, String title);

    GraphSection findSectionByCanonicalPath(Long documentId, String canonicalPath);

    GraphSection findBestSection(Long documentId, String topic, String facet);

    List<GraphSection> listSections(Long documentId);

    List<GraphSection> listChildren(Long documentId, Long sectionNodeId);

    GraphSection parentSection(Long documentId, Long sectionNodeId);

    GraphSection previousSibling(Long documentId, Long sectionNodeId);

    GraphSection nextSibling(Long documentId, Long sectionNodeId);

    GraphItem findItemByIndex(Long documentId, Long sectionNodeId, Integer itemIndex);

    List<GraphItem> listItems(Long documentId, Long sectionNodeId);

    List<GraphItem> searchItemsInSection(Long documentId, Long sectionNodeId, String keyword);
}
