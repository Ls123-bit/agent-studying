package org.javaup.ai.manage.support;

import cn.hutool.core.util.StrUtil;
import org.javaup.enums.DocumentStructureNodeTypeEnum;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 支撑组件
 * @author: 阿星不是程序员
 **/

/*
树校验
层级构建阶段产出的 draft 已经有了初步的树形关系，但仍然可能存在一些问题：重复标题节点、编号父链断裂、父节点缺失、section 被错挂在列表节点下面等。第四阶段就是做最终收口，输出稳定的、可落库的候选节点。
 */
@Component
public class DocumentStructureTreeValidator {

    public List<DocumentStructureNodeCandidate> validateAndBuild(String documentTitle,
                                                                 List<DocumentStructureNodeDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return List.of();
        }
        Map<Integer, DocumentStructureNodeDraft> draftMap = new LinkedHashMap<>();
        for (DocumentStructureNodeDraft draft : drafts) {
            if (draft != null && draft.getNodeNo() != null) {
                draftMap.put(draft.getNodeNo(), draft);
            }
        }

        /*
        处理顺序很关键
        这六个步骤的执行顺序不能随意调换：必须先折叠重复标题、修复父链，然后才能安全地重算深度、重建路径和兄弟关系。如果顺序反了，后面的步骤会基于错误的父子关系计算出错误的结果。
         */
        // 下面这些步骤共同把"草稿树"收敛成最终结构树。
        collapseSyntheticTitleSection(documentTitle, draftMap);// 1. 折叠重复标题
        repairNumberedHierarchy(draftMap);// 2. 修复数字编号父链
        repairInvalidParents(draftMap); // 3. 修复非法父节点
        recomputeDepths(draftMap); // 4. 重算深度
        rebuildPaths(documentTitle, draftMap);// 5. 重建路径
        rebuildSiblingLinks(draftMap);// 6. 重建兄弟关系

        return draftMap.values().stream()
            .sorted(Comparator.comparingInt(DocumentStructureNodeDraft::getNodeNo))
            .map(this::toCandidate)
            .toList();
    }


    /*
    步骤一：折叠重复标题
    折叠与文档标题重复的伪一级标题。
    有些文档的正文里会重复出现一次文档标题（比如 PDF 提取出来的第一行就是标题），这会导致树里出现一个多余的一级 section。这一步就是找到这种重复标题，把它的子节点提升到根节点下，然后删掉它。
     */
    private void collapseSyntheticTitleSection(String documentTitle,
                                               Map<Integer, DocumentStructureNodeDraft> draftMap) {
        String normalizedTitle = normalizeComparableTitle(documentTitle);
        if (normalizedTitle.isBlank()) {
            return;
        }
        Integer duplicateNodeNo = null;
        for (DocumentStructureNodeDraft draft : draftMap.values()) {
            if (draft == null
                || draft.getNodeNo() == null
                || draft.getNodeNo() == 1
                || !draft.isSection()
                || !Objects.equals(draft.getParentNodeNo(), 1)
                || StrUtil.isNotBlank(draft.getNodeCode())) {
                continue;
            }
            if (normalizedTitle.equals(normalizeComparableTitle(draft.getTitle()))) {
                duplicateNodeNo = draft.getNodeNo();
                break;
            }
        }
        if (duplicateNodeNo == null) {
            return;
        }
        // 把重复标题的子节点全部挂到根节点下，然后删除重复标题本身
        for (DocumentStructureNodeDraft draft : draftMap.values()) {
            if (draft != null && Objects.equals(draft.getParentNodeNo(), duplicateNodeNo)) {
                draft.setParentNodeNo(1);
            }
        }
        draftMap.remove(duplicateNodeNo);
    }


/*
步骤二：修复数字编号父链
根据 numericPath 修复数字编号 section 的父链。
比如文档里有 1.1、1.2、1.2.1 这样的编号标题，层级构建阶段可能因为扫描顺序的原因没有正确建立父子关系。这一步通过 numericPath 索引表重新修复：1.2.1 的父级应该是 1.2，1.2 的父级应该是 1。

 */
    private void repairNumberedHierarchy(Map<Integer, DocumentStructureNodeDraft> draftMap) {
        Map<String, Integer> numericPathMap = new LinkedHashMap<>();
        for (DocumentStructureNodeDraft draft : draftMap.values()) {
            if (draft == null || !draft.isSection()) {
                continue;
            }
            String key = numericKey(draft.getNumericPath());
            if (StrUtil.isNotBlank(key)) {
                numericPathMap.putIfAbsent(key, draft.getNodeNo());
            }
        }

        for (DocumentStructureNodeDraft draft : draftMap.values()) {
            if (draft == null || !draft.isSection()) {
                continue;
            }
            List<Integer> numericPath = draft.getNumericPath();
            if (numericPath == null || numericPath.isEmpty()) {
                continue;
            }
            if (numericPath.size() == 1) {
                // 一级编号（如 "1"）直接挂到根节点
                draft.setParentNodeNo(1);
                continue;
            }
            // 多级编号（如 "1.2.3"）先找直接上级（"1.2"）
            String directParentKey = numericKey(numericPath.subList(0, numericPath.size() - 1));
            Integer directParent = numericPathMap.get(directParentKey);
            if (directParent != null) {
                draft.setParentNodeNo(directParent);
                continue;
            }
            // 找不到直接上级，退到同章节点（"1"）
            String chapterParentKey = numericKey(List.of(numericPath.get(0)));
            Integer chapterParent = numericPathMap.get(chapterParentKey);
            if (chapterParent != null) {
                draft.setParentNodeNo(chapterParent);
            }
        }
    }



    /*
    步骤三到六：修复+重算+重建
    修复非法父节点关系。
    例如父节点不存在，或者 section 被错误挂在 list-like 节点下面。
     */
    private void repairInvalidParents(Map<Integer, DocumentStructureNodeDraft> draftMap) {
        for (DocumentStructureNodeDraft draft : draftMap.values()) {
            if (draft == null || draft.getNodeNo() == 1) {
                continue;
            }
            DocumentStructureNodeDraft parent = draft.getParentNodeNo() == null ? null : draftMap.get(draft.getParentNodeNo());
            if (parent == null) {
                draft.setParentNodeNo(1);// 父节点不存在，挂到根节点
                continue;
            }
            if (draft.isSection() && parent.isListLike()) {
                // section 不应该挂在列表项下面，提升到列表项的父节点
                draft.setParentNodeNo(parent.getParentNodeNo() == null ? 1 : parent.getParentNodeNo());
            }
        }
    }

    /**
     * 基于最终 parentNodeNo 重新计算 depth。
     */
    private void recomputeDepths(Map<Integer, DocumentStructureNodeDraft> draftMap) {
        DocumentStructureNodeDraft root = draftMap.get(1);
        if (root == null) {
            return;
        }
        root.setDepth(0);
        List<DocumentStructureNodeDraft> ordered = draftMap.values().stream()
            .sorted(Comparator.comparingInt(DocumentStructureNodeDraft::getNodeNo))
            .toList();
        for (DocumentStructureNodeDraft draft : ordered) {
            if (draft == null || draft.getNodeNo() == 1) {
                continue;
            }
            DocumentStructureNodeDraft parent = draftMap.get(draft.getParentNodeNo());
            draft.setDepth(parent == null ? 1 : parent.getDepth() + 1);
        }
    }

    /*
rebuildPaths 会为每个节点重建两种路径：
canonicalPath：机器定位用的路径，如 /document/1.2/1.2.1
sectionPath：人类可读的章节链，如 第一章 绪论 > 1.1 背景
rebuildSiblingLinks 会在同一父节点下的子节点之间建立前后兄弟关系（prevSiblingNodeNo / nextSiblingNodeNo）。
     */
    private void rebuildPaths(String documentTitle,
                              Map<Integer, DocumentStructureNodeDraft> draftMap) {
        for (DocumentStructureNodeDraft draft : draftMap.values()) {
            if (draft == null) {
                continue;
            }
            if (draft.getNodeNo() == 1) {
                draft.setCanonicalPath("/document");
                draft.setSectionPath("");
                continue;
            }
            DocumentStructureNodeDraft parent = draftMap.get(draft.getParentNodeNo());
            String parentCanonicalPath = parent == null ? "/document" : StrUtil.blankToDefault(parent.getCanonicalPath(), "/document");
            String parentSectionPath = parent == null ? "" : StrUtil.blankToDefault(parent.getSectionPath(), "");
            String segment = buildPathSegment(draft);
            draft.setCanonicalPath(parentCanonicalPath + "/" + segment);
            if (draft.isSection()) {
                draft.setSectionPath(joinSectionPath(parentSectionPath, displayTitle(draft)));
            }
            else {
                draft.setSectionPath(parentSectionPath);
            }
        }
    }

    private void rebuildSiblingLinks(Map<Integer, DocumentStructureNodeDraft> draftMap) {
        Map<Integer, List<DocumentStructureNodeDraft>> childrenByParent = new LinkedHashMap<>();
        for (DocumentStructureNodeDraft draft : draftMap.values()) {
            if (draft == null || draft.getNodeNo() == 1) {
                continue;
            }
            childrenByParent.computeIfAbsent(draft.getParentNodeNo(), ignored -> new ArrayList<>()).add(draft);
        }
        for (List<DocumentStructureNodeDraft> siblings : childrenByParent.values()) {
            siblings.sort(Comparator.comparingInt(DocumentStructureNodeDraft::getLineNo));
            for (int index = 0; index < siblings.size(); index++) {
                DocumentStructureNodeDraft current = siblings.get(index);
                current.setPrevSiblingNodeNo(index == 0 ? 0 : siblings.get(index - 1).getNodeNo());
                current.setNextSiblingNodeNo(index == siblings.size() - 1 ? 0 : siblings.get(index + 1).getNodeNo());
            }
        }
    }



    /*
    最终输出：
    六个步骤全部完成后，把draft转成最终的 DocumentStructureNodeCandidate：

     */
    private DocumentStructureNodeCandidate toCandidate(DocumentStructureNodeDraft draft) {
        return new DocumentStructureNodeCandidate(
            draft.getNodeNo(),
            draft.getNodeType(),
            draft.getParentNodeNo(),
            normalizeSibling(draft.getPrevSiblingNodeNo()),
            normalizeSibling(draft.getNextSiblingNodeNo()),
            draft.getDepth(),
            draft.getNodeCode(),
            draft.getTitle(),
            draft.getAnchorText(),
            draft.getCanonicalPath(),
            draft.getSectionPath(),
            draft.contentText(),
            draft.getItemIndex()
        );
    }

    private Integer normalizeSibling(Integer siblingNodeNo) {
        return siblingNodeNo == null ? 0 : siblingNodeNo;
    }

    private String joinSectionPath(String parentSectionPath, String currentTitle) {
        if (StrUtil.isBlank(parentSectionPath)) {
            return StrUtil.blankToDefault(currentTitle, "");
        }
        if (StrUtil.isBlank(currentTitle)) {
            return parentSectionPath;
        }
        return parentSectionPath + " > " + currentTitle;
    }

    private String buildPathSegment(DocumentStructureNodeDraft draft) {
        if (draft == null) {
            return "node";
        }
        if (draft.isListLike()) {
            if (draft.getItemIndex() != null && draft.getItemIndex() > 0) {
                return "item-" + draft.getItemIndex();
            }
            return slug(displayTitle(draft));
        }
        String code = StrUtil.blankToDefault(draft.getNodeCode(), "").trim();
        if (StrUtil.isNotBlank(code)) {
            return slug(code);
        }
        return slug(displayTitle(draft));
    }

    private String displayTitle(DocumentStructureNodeDraft draft) {
        String code = StrUtil.blankToDefault(draft.getNodeCode(), "").trim();
        String title = StrUtil.blankToDefault(draft.getTitle(), "").trim();
        if (StrUtil.isBlank(code)) {
            return title;
        }
        if (title.startsWith(code)) {
            return title;
        }
        return code + " " + title;
    }

    private String slug(String value) {
        String normalized = StrUtil.blankToDefault(value, "").trim();
        if (normalized.isBlank()) {
            return "node";
        }
        String slug = normalized
            .replaceAll("\\s+", "-")
            .replaceAll("[^\\p{IsHan}A-Za-z0-9_.-]", "");
        return slug.isBlank() ? "node" : slug;
    }

    private String numericKey(List<Integer> numericPath) {
        if (numericPath == null || numericPath.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < numericPath.size(); index++) {
            if (index > 0) {
                builder.append('.');
            }
            Integer segment = numericPath.get(index);
            if (segment != null) {
                builder.append(segment);
            }
        }
        return builder.toString();
    }

    private String normalizeComparableTitle(String text) {
        String normalized = StrUtil.blankToDefault(text, "").trim();
        if (normalized.isBlank()) {
            return "";
        }
        return normalized
            .replaceAll("^#+\\s*", "")
            .replaceAll("\\.[A-Za-z0-9]{1,6}$", "")
            .replaceAll("\\s+", "")
            .toLowerCase();
    }
}
