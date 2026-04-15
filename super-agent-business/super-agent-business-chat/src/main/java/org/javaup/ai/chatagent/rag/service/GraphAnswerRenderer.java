package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import org.javaup.ai.chatagent.rag.model.DocumentNavigationAction;
import org.javaup.ai.chatagent.rag.model.DocumentNavigationDecision;
import org.javaup.ai.manage.service.navigation.DocumentStructureGraphService.GraphItem;
import org.javaup.ai.manage.service.navigation.DocumentStructureGraphService.GraphSection;
import org.javaup.ai.manage.service.navigation.StructureGraphQueryEngine;
import org.javaup.ai.manage.service.navigation.StructureGraphQueryEngine.GraphItemWithContext;
import org.javaup.ai.manage.service.navigation.StructureGraphQueryEngine.GraphSectionWithChildren;
import org.javaup.ai.manage.service.navigation.StructureGraphQueryEngine.GraphSectionWithSiblings;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 结构图答案渲染器。
 *
 * <p>对 GRAPH_ONLY 和 GRAPH_THEN_EVIDENCE 的结果直接渲染答案，
 * 不调用回答模型或只做轻量语言润色。</p>
 */
@Component
public class GraphAnswerRenderer {

    private final StructureGraphQueryEngine graphQueryEngine;

    public GraphAnswerRenderer(StructureGraphQueryEngine graphQueryEngine) {
        this.graphQueryEngine = graphQueryEngine;
    }

    /**
     * 渲染 GRAPH_ONLY 答案。
     */
    public String renderGraphOnlyAnswer(DocumentNavigationDecision decision, Long documentId) {
        DocumentNavigationAction action = decision.getNavigationAction();
        if (action == DocumentNavigationAction.SECTION_ADJACENCY_LOOKUP) {
            return renderAdjacencyAnswer(decision, documentId);
        }
        String subject = decision.getSubjectAnchor() == null ? "" : safe(decision.getSubjectAnchor().getAnchorText());
        if (StrUtil.isNotBlank(subject)) {
            return renderChildrenAnswer(decision, documentId, subject);
        }
        return null;
    }

    /**
     * 渲染 GRAPH_THEN_EVIDENCE 答案。
     */
    public String renderGraphThenEvidenceAnswer(DocumentNavigationDecision decision, Long documentId) {
        if (decision.getStructureAnchor() == null || decision.getStructureAnchor().getStructureNodeId() == null) {
            return null;
        }
        Long sectionNodeId = decision.getStructureAnchor().getStructureNodeId();
        if (decision.getItemAnchor() != null && decision.getItemAnchor().getItemIndex() != null) {
            return renderItemAnswer(documentId, sectionNodeId, decision.getItemAnchor().getItemIndex());
        }
        List<GraphItem> items = graphQueryEngine.listItems(documentId, sectionNodeId);
        if (!items.isEmpty()) {
            return renderItemListAnswer(items, decision);
        }
        return null;
    }

    private String renderAdjacencyAnswer(DocumentNavigationDecision decision, Long documentId) {
        if (decision.getStructureAnchor() == null || decision.getStructureAnchor().getStructureNodeId() == null) {
            return null;
        }
        GraphSectionWithSiblings result = graphQueryEngine.findSectionWithSiblings(
            documentId, decision.getStructureAnchor().getStructureNodeId());
        if (result == null || result.section() == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        GraphSection section = result.section();
        sb.append("「").append(safe(section.title())).append("」");
        if (result.parent() != null) {
            sb.append("属于「").append(safe(result.parent().title())).append("」。\n\n");
        }
        else {
            sb.append("是顶层章节。\n\n");
        }
        if (result.previousSibling() != null) {
            sb.append("上一节：").append(safe(result.previousSibling().title())).append("\n");
        }
        else {
            sb.append("上一节：无（它是同级第一个章节）\n");
        }
        if (result.nextSibling() != null) {
            sb.append("下一节：").append(safe(result.nextSibling().title()));
        }
        else {
            sb.append("下一节：无（它是同级最后一个章节）");
        }
        return sb.toString();
    }

    private String renderChildrenAnswer(DocumentNavigationDecision decision, Long documentId, String topic) {
        GraphSectionWithChildren result = graphQueryEngine.findSectionWithChildren(documentId, topic);
        if (result == null || result.section() == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("「").append(safe(result.section().title())).append("」包含以下章节：\n\n");
        List<GraphSection> children = result.children();
        if (children == null || children.isEmpty()) {
            sb.append("该章节下没有子章节。");
        }
        else {
            for (int i = 0; i < children.size(); i++) {
                GraphSection child = children.get(i);
                sb.append(i + 1).append(". ").append(safe(child.title())).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private String renderItemAnswer(Long documentId, Long sectionNodeId, Integer itemIndex) {
        GraphItemWithContext result = graphQueryEngine.findItemInSection(documentId, sectionNodeId, itemIndex);
        if (result == null || result.item() == null) {
            return null;
        }
        GraphItem item = result.item();
        StringBuilder sb = new StringBuilder();
        sb.append("第").append(itemIndex).append("步");
        if (StrUtil.isNotBlank(item.title())) {
            sb.append("（").append(safe(item.title())).append("）");
        }
        sb.append("：\n\n").append(safe(item.contentText()));
        return sb.toString();
    }

    private String renderItemListAnswer(List<GraphItem> items, DocumentNavigationDecision decision) {
        StringBuilder sb = new StringBuilder();
        String sectionTitle = decision.getStructureAnchor() == null ? "" : safe(decision.getStructureAnchor().getRootSectionTitle());
        if (StrUtil.isNotBlank(sectionTitle)) {
            sb.append("「").append(sectionTitle).append("」包含以下步骤：\n\n");
        }
        for (GraphItem item : items) {
            if (item.itemIndex() != null) {
                sb.append(item.itemIndex()).append(". ");
            }
            sb.append(StrUtil.isNotBlank(item.title()) ? safe(item.title()) : safe(item.contentText()));
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * 渲染章节邻接答案（不依赖导航决策）。
     */
    public String renderAdjacencyAnswer(Long documentId, Long sectionNodeId) {
        if (documentId == null || sectionNodeId == null) {
            return null;
        }
        GraphSectionWithSiblings result = graphQueryEngine.findSectionWithSiblings(documentId, sectionNodeId);
        if (result == null || result.section() == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        GraphSection section = result.section();
        sb.append("\u300c").append(safe(section.title())).append("\u300d");
        if (result.parent() != null) {
            sb.append("\u5c5e\u4e8e\u300c").append(safe(result.parent().title())).append("\u300d\u3002\n\n");
        } else {
            sb.append("\u662f\u9876\u5c42\u7ae0\u8282\u3002\n\n");
        }
        if (result.previousSibling() != null) {
            sb.append("\u4e0a\u4e00\u8282\uff1a").append(safe(result.previousSibling().title())).append("\n");
        } else {
            sb.append("\u4e0a\u4e00\u8282\uff1a\u65e0\uff08\u5b83\u662f\u540c\u7ea7\u7b2c\u4e00\u4e2a\u7ae0\u8282\uff09\n");
        }
        if (result.nextSibling() != null) {
            sb.append("\u4e0b\u4e00\u8282\uff1a").append(safe(result.nextSibling().title()));
        } else {
            sb.append("\u4e0b\u4e00\u8282\uff1a\u65e0\uff08\u5b83\u662f\u540c\u7ea7\u6700\u540e\u4e00\u4e2a\u7ae0\u8282\uff09");
        }
        return sb.toString();
    }

    /**
     * 渲染章节子节点答案（不依赖导航决策）。
     */
    public String renderChildrenAnswer(Long documentId, String topic) {
        if (documentId == null || StrUtil.isBlank(topic)) {
            return null;
        }
        GraphSectionWithChildren result = graphQueryEngine.findSectionWithChildren(documentId, topic);
        if (result == null || result.section() == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\u300c").append(safe(result.section().title())).append("\u300d\u5305\u542b\u4ee5\u4e0b\u7ae0\u8282\uff1a\n\n");
        List<GraphSection> children = result.children();
        if (children == null || children.isEmpty()) {
            sb.append("\u8be5\u7ae0\u8282\u4e0b\u6ca1\u6709\u5b50\u7ae0\u8282\u3002");
        } else {
            for (int i = 0; i < children.size(); i++) {
                sb.append(i + 1).append(". ").append(safe(children.get(i).title())).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * 渲染步骤/条目精确引用答案（不依赖导航决策）。
     */
    public String renderItemByIndex(Long documentId, Long sectionNodeId, Integer itemIndex) {
        return renderItemAnswer(documentId, sectionNodeId, itemIndex);
    }

    /**
     * 渲染步骤/条目搜索答案（不依赖导航决策）。
     */
    public String renderItemSearch(Long documentId, Long sectionNodeId, String keyword) {
        if (documentId == null || sectionNodeId == null || StrUtil.isBlank(keyword)) {
            return null;
        }
        List<GraphItem> items = graphQueryEngine.searchItemsInSection(documentId, sectionNodeId, keyword);
        if (items == null || items.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (GraphItem item : items) {
            if (item.itemIndex() != null) {
                sb.append("\u7b2c").append(item.itemIndex()).append("\u6b65");
            }
            if (StrUtil.isNotBlank(item.title())) {
                sb.append("\uff08").append(safe(item.title())).append("\uff09");
            }
            sb.append("\uff1a\n").append(safe(item.contentText())).append("\n\n");
        }
        return sb.toString().trim();
    }

    private String safe(String text) {
        return text == null ? "" : text.trim();
    }
}
