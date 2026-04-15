package org.javaup.ai.chatagent.rag.core.intent;

import org.javaup.ai.manage.data.SuperAgentDocumentStructureNode;
import org.javaup.enums.DocumentStructureNodeTypeEnum;

import java.util.ArrayList;
import java.util.List;

/**
 * 章节意图节点，将文档结构树适配为意图分类的目标节点。
 *
 * <p>对标 ragent 的 IntentNode，但面向的是单文档内的章节结构。
 * LLM 对这些节点打分，得分作为软路由信号引导检索，而非硬性锁定。</p>
 */
public class SectionIntentNode {

    /** 结构节点主键。 */
    private final Long id;

    /** 章节编号，如 "1.2.3"、"第二章"。 */
    private final String nodeCode;

    /** 章节标题。 */
    private final String title;

    /** 完整章节路径，如 "第一章 产品概述 > 1.1 产品简介"。 */
    private final String sectionPath;

    /** 稳定的规范路径，用于过滤匹配。 */
    private final String canonicalPath;

    /** 层级深度（0=文档根，1=顶级章节，2=子章节...）。 */
    private final int depth;

    /** 父节点 ID，顶级章节为 null。 */
    private final Long parentId;

    /** 子节点列表。 */
    private final List<SectionIntentNode> children;

    public SectionIntentNode(Long id, String nodeCode, String title,
                             String sectionPath, String canonicalPath,
                             int depth, Long parentId) {
        this.id = id;
        this.nodeCode = nodeCode;
        this.title = title;
        this.sectionPath = sectionPath;
        this.canonicalPath = canonicalPath;
        this.depth = depth;
        this.parentId = parentId;
        this.children = new ArrayList<>();
    }

    /**
     * 从数据库结构节点转换。
     */
    public static SectionIntentNode fromStructureNode(SuperAgentDocumentStructureNode node) {
        return new SectionIntentNode(
                node.getId(),
                node.getNodeCode(),
                node.getTitle(),
                node.getSectionPath(),
                node.getCanonicalPath(),
                node.getDepth(),
                node.getParentNodeId()
        );
    }

    /**
     * 是否为叶子节点（无子章节）。
     */
    public boolean isLeaf() {
        return children == null || children.isEmpty();
    }

    /**
     * 获取用于 LLM prompt 的展示路径。
     */
    public String getDisplayPath() {
        if (sectionPath != null && !sectionPath.isBlank()) {
            return sectionPath;
        }
        if (nodeCode != null && title != null) {
            return nodeCode + " " + title;
        }
        return title != null ? title : String.valueOf(id);
    }

    // ── Getters ──

    public Long getId() { return id; }
    public String getNodeCode() { return nodeCode; }
    public String getTitle() { return title; }
    public String getSectionPath() { return sectionPath; }
    public String getCanonicalPath() { return canonicalPath; }
    public int getDepth() { return depth; }
    public Long getParentId() { return parentId; }
    public List<SectionIntentNode> getChildren() { return children; }

    @Override
    public String toString() {
        return "SectionIntentNode{id=" + id + ", path='" + getDisplayPath() + "'}";
    }
}
