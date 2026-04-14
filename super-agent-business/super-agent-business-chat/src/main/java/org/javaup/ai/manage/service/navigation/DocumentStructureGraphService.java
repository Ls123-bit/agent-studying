package org.javaup.ai.manage.service.navigation;

import java.util.List;

/**
 * 文档结构图查询服务。
 *
 * <p>统一提供章节、子章节、步骤、列表项的查询能力，
 * 以及父子、兄弟、前后、归属关系查询。</p>
 *
 * <p>当前基于 MySQL 结构节点表实现，
 * 后续引入 Neo4j 后切换底层实现即可，上层调用方不需要改动。</p>
 */
public interface DocumentStructureGraphService {

    GraphSection findSectionByNodeId(Long documentId, Long nodeId);

    GraphSection findSectionByCode(Long documentId, String nodeCode);

    GraphSection findSectionByTitle(Long documentId, String title);

    List<GraphSection> listTopSections(Long documentId);

    List<GraphSection> listChildren(Long documentId, Long sectionNodeId);

    GraphSection parentSection(Long documentId, Long sectionNodeId);

    GraphSection previousSibling(Long documentId, Long sectionNodeId);

    GraphSection nextSibling(Long documentId, Long sectionNodeId);

    GraphItem findItemByIndex(Long documentId, Long sectionNodeId, Integer itemIndex);

    List<GraphItem> listItems(Long documentId, Long sectionNodeId);

    /**
     * 在指定章节子树内搜索包含关键词的 item。
     */
    List<GraphItem> searchItemsInSection(Long documentId, Long sectionNodeId, String keyword);

    record GraphSection(
        Long nodeId,
        Long documentId,
        String nodeCode,
        String title,
        String anchorText,
        String sectionPath,
        String canonicalPath,
        Integer depth,
        Long parentNodeId,
        Long prevSiblingNodeId,
        Long nextSiblingNodeId,
        String contentText
    ) {
    }

    record GraphItem(
        Long nodeId,
        Long documentId,
        String nodeType,
        Integer itemIndex,
        String title,
        String anchorText,
        String sectionPath,
        String canonicalPath,
        String contentText,
        Long parentNodeId
    ) {
    }
}
