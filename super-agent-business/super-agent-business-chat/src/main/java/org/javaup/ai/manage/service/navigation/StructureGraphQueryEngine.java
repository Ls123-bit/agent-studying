package org.javaup.ai.manage.service.navigation;

import cn.hutool.core.util.StrUtil;
import org.javaup.ai.manage.service.navigation.DocumentStructureGraphService.GraphItem;
import org.javaup.ai.manage.service.navigation.DocumentStructureGraphService.GraphSection;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 结构图高层查询引擎。
 *
 * <p>组合 {@link DocumentStructureGraphService} 的原子操作，
 * 提供面向导航引擎的复合查询能力。</p>
 */
@Service
public class StructureGraphQueryEngine {

    private final DocumentStructureGraphService graphService;

    public StructureGraphQueryEngine(DocumentStructureGraphService graphService) {
        this.graphService = graphService;
    }

    /**
     * 定位章节 + 查子章节列表。
     * 适用于 GRAPH_ONLY: "包含哪些章节？"
     */
    public GraphSectionWithChildren findSectionWithChildren(Long documentId, String topic) {
        GraphSection section = graphService.findSectionByTitle(documentId, topic);
        if (section == null) {
            return null;
        }
        List<GraphSection> children = graphService.listChildren(documentId, section.nodeId());
        return new GraphSectionWithChildren(section, children);
    }

    /**
     * 定位章节 + 查前后兄弟。
     * 适用于 GRAPH_ONLY: "上一节/下一节"
     */
    public GraphSectionWithSiblings findSectionWithSiblings(Long documentId, Long sectionNodeId) {
        GraphSection section = graphService.findSectionByNodeId(documentId, sectionNodeId);
        if (section == null) {
            return null;
        }
        GraphSection parent = graphService.parentSection(documentId, sectionNodeId);
        GraphSection prev = graphService.previousSibling(documentId, sectionNodeId);
        GraphSection next = graphService.nextSibling(documentId, sectionNodeId);
        return new GraphSectionWithSiblings(section, parent, prev, next);
    }

    /**
     * 定位章节 + 查指定 item。
     * 适用于 GRAPH_THEN_EVIDENCE: "第五步是什么？"
     */
    public GraphItemWithContext findItemInSection(Long documentId, Long sectionNodeId, Integer itemIndex) {
        if (sectionNodeId == null || itemIndex == null) {
            return null;
        }
        GraphSection section = graphService.findSectionByNodeId(documentId, sectionNodeId);
        GraphItem item = graphService.findItemByIndex(documentId, sectionNodeId, itemIndex);
        return new GraphItemWithContext(section, item);
    }

    /**
     * 在指定章节子树内搜索包含关键词的 item。
     * 适用于 GRAPH_THEN_EVIDENCE: "哪一步要求修改密码？"
     */
    public List<GraphItem> searchItemsInSection(Long documentId, Long sectionNodeId, String keyword) {
        if (sectionNodeId == null || StrUtil.isBlank(keyword)) {
            return List.of();
        }
        return graphService.searchItemsInSection(documentId, sectionNodeId, keyword);
    }

    /**
     * 查指定章节的所有 item 列表。
     */
    public List<GraphItem> listItems(Long documentId, Long sectionNodeId) {
        if (sectionNodeId == null) {
            return List.of();
        }
        return graphService.listItems(documentId, sectionNodeId);
    }

    public record GraphSectionWithChildren(
        GraphSection section,
        List<GraphSection> children
    ) {
    }

    public record GraphSectionWithSiblings(
        GraphSection section,
        GraphSection parent,
        GraphSection previousSibling,
        GraphSection nextSibling
    ) {
    }

    public record GraphItemWithContext(
        GraphSection section,
        GraphItem item
    ) {
    }
}
