package org.javaup.ai.manage.support;

import lombok.Data;
import org.javaup.enums.DocumentStructureNodeTypeEnum;

import java.util.ArrayList;
import java.util.List;

/**
 * 结构节点草稿。
 *
 * <p>它位于“信号识别”和“最终结构节点候选”之间，
 * 允许层级解析器和树校验器多轮修复父子关系。</p>
 */
@Data
public class DocumentStructureNodeDraft {

    private Integer nodeNo;

    private Integer lineNo;

    private Integer nodeType;

    private Integer parentNodeNo;

    private Integer prevSiblingNodeNo;

    private Integer nextSiblingNodeNo;

    private Integer depth;

    private String nodeCode;

    private String title;

    private String anchorText;

    private String canonicalPath;

    private String sectionPath;

    private Integer itemIndex;

    @Data
    private static final class ContentHolder {
        private final StringBuilder builder = new StringBuilder();
    }

    private final ContentHolder content = new ContentHolder();

    private List<Integer> numericPath = new ArrayList<>();

    private String sourceFamily;

    private double confidence;

    public void appendLine(String line) {
        String normalized = line == null ? "" : line.trim();
        if (normalized.isBlank()) {
            return;
        }
        if (!content.builder.isEmpty()) {
            content.builder.append('\n');
        }
        content.builder.append(normalized);
    }

    public String contentText() {
        return content.builder.toString().trim();
    }

    public boolean isSection() {
        return DocumentStructureNodeTypeEnum.SECTION.getCode().equals(nodeType);
    }

    public boolean isListLike() {
        return DocumentStructureNodeTypeEnum.STEP.getCode().equals(nodeType)
            || DocumentStructureNodeTypeEnum.LIST_ITEM.getCode().equals(nodeType);
    }
}
