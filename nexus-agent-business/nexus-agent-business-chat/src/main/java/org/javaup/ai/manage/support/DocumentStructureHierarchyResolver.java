package org.javaup.ai.manage.support;

import cn.hutool.core.util.StrUtil;
import org.javaup.enums.DocumentStructureNodeTypeEnum;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;


/*
层级构建
经过前两个阶段，我们拿到了一份"每行都有明确信号类型"的扁平列表。第三阶段要做的事情是：把这些扁平信号组装成一棵带父子关系的草稿树。
 将信号列表解析成结构草稿树。
 * <p>
 * 处理方式是一次线性扫描：
 * 标题会切换当前 section，
 * 列表会维护缩进栈，
 * 正文/表格/引用则附着到当前 section 或当前列表项。
 * </p>
 */
@Component
public class DocumentStructureHierarchyResolver {



    /*
    整个方法是一次线性扫描，维护着几个关键的"当前上下文"：
currentSection：当前所在的章节节点，遇到新标题就切换
currentListItem：当前所在的列表项节点，遇到空行就清空
listStack：列表嵌套的缩进栈，用于处理多级列表的父子关系
latestHeadingByDepth / latestHeadingByNumericPath：标题索引表，用于恢复数字编号标题的父子关系
     */
    public List<DocumentStructureNodeDraft> resolve(String documentTitle,
                                                    List<DocumentStructureSignal> signals) {
        List<DocumentStructureNodeDraft> drafts = new ArrayList<>();
        // 所有文档都从根节点开始，后续 section/list/body 都挂在它下面。
        DocumentStructureNodeDraft root = new DocumentStructureNodeDraft();
        root.setNodeNo(1);
        root.setLineNo(0);
        root.setNodeType(DocumentStructureNodeTypeEnum.DOCUMENT.getCode());
        root.setParentNodeNo(null);
        root.setDepth(0);
        root.setNodeCode("");
        root.setTitle(StrUtil.blankToDefault(documentTitle, "文档"));
        root.setAnchorText(StrUtil.blankToDefault(documentTitle, "文档"));
        root.setCanonicalPath("/document");
        root.setSectionPath("");
        root.setSourceFamily("document");
        root.setConfidence(1.0D);
        drafts.add(root);

        int nextNodeNo = 2;
        DocumentStructureNodeDraft currentSection = root;
        DocumentStructureNodeDraft currentListItem = null;
        // listStack 用于维护当前列表嵌套关系，主要依据缩进层级。
        Deque<ListContext> listStack = new ArrayDeque<>();
        // 这两个索引表帮助数字标题恢复更合理的父子关系。
        Map<Integer, Integer> latestHeadingByDepth = new LinkedHashMap<>();
        Map<String, Integer> latestHeadingByNumericPath = new LinkedHashMap<>();

        for (DocumentStructureSignal signal : signals) {
            if (signal == null || signal.getLineNo() == 0) {
                continue;
            }
            switch (signal.getKind()) {
                case BLANK -> {
                    // 空行会打断列表上下文，但不会结束当前 section。
                    currentListItem = null;
                    listStack.clear();
                }
                case NOISE -> {
                }
                case TABLE_ROW, QUOTE, BODY -> {
                    // 正文类信号不新建节点，而是附着到当前上下文。
                    appendBody(signal, currentSection, currentListItem, root, drafts);
                }
                case STEP_ITEM, LIST_ITEM -> {
                    // 列表/步骤会新建 list-like 节点，并根据缩进找到父节点。
                    DocumentStructureNodeDraft listParent = resolveListParent(signal, currentSection == null ? root : currentSection, listStack, root);
                    DocumentStructureNodeDraft listNode = buildListNode(signal, nextNodeNo++, listParent);
                    drafts.add(listNode);
                    currentListItem = listNode;
                    registerListContext(signal, listNode, listStack);
                    if (currentSection != null) {
                        currentSection.appendLine(signal.getNormalizedText());
                    }
                }
                case HEADING, HEADING_CANDIDATE -> {
                    // 标题会切换当前 section，并清空当前列表上下文。
                    DocumentStructureNodeDraft headingNode = buildHeadingNode(
                        signal,
                        nextNodeNo++,
                        drafts,
                        latestHeadingByDepth,
                        latestHeadingByNumericPath
                    );
                    drafts.add(headingNode);
                    currentSection = headingNode;
                    currentListItem = null;
                    listStack.clear();
                }
                default -> appendBody(signal, currentSection, currentListItem, root, drafts);
            }
        }

        drafts.sort(Comparator.comparing(DocumentStructureNodeDraft::getNodeNo));
        return drafts;
    }



    /*
    正文附着逻辑
    正文不会新建节点，而是"附着"到当前上下文。如果当前在列表项里，正文归属列表项；同时也会追加到所在的 section，保证 section 级别的内容是完整的。
     */
    private void appendBody(DocumentStructureSignal signal,
                            DocumentStructureNodeDraft currentSection,
                            DocumentStructureNodeDraft currentListItem,
                            DocumentStructureNodeDraft root,
                            List<DocumentStructureNodeDraft> drafts) {
        String line = signal == null ? "" : signal.getNormalizedText();
        if (StrUtil.isBlank(line)) {
            return;
        }
        // 如果当前在列表项内部，正文优先归属列表项
        DocumentStructureNodeDraft target = currentListItem != null ? currentListItem : (currentSection == null ? root : currentSection);
        target.appendLine(line);
        // 同时为了保留 section 级大语义内容，也会同步追加到当前 section
        if (currentListItem != null && currentSection != null && !Objects.equals(currentSection.getNodeNo(), currentListItem.getNodeNo())) {
            currentSection.appendLine(line);
        }
        if (currentSection == null && target != root) {
            root.appendLine(line);
        }
    }

    private DocumentStructureNodeDraft buildListNode(DocumentStructureSignal signal,
                                                     int nodeNo,
                                                     DocumentStructureNodeDraft parent) {
        DocumentStructureNodeDraft draft = new DocumentStructureNodeDraft();
        draft.setNodeNo(nodeNo);
        draft.setLineNo(signal.getLineNo());
        draft.setNodeType(signal.getKind() == DocumentStructureSignalKind.STEP_ITEM
            ? DocumentStructureNodeTypeEnum.STEP.getCode()
            : DocumentStructureNodeTypeEnum.LIST_ITEM.getCode());
        draft.setParentNodeNo(parent == null ? 1 : parent.getNodeNo());
        draft.setDepth((parent == null ? 0 : parent.getDepth()) + 1);
        draft.setNodeCode(StrUtil.blankToDefault(signal.getNodeCode(), signal.getItemIndex() == null ? "" : String.valueOf(signal.getItemIndex())));
        draft.setTitle(signal.getTitle());
        draft.setAnchorText(StrUtil.blankToDefault(signal.getNormalizedText(), signal.getTitle()));
        draft.setItemIndex(signal.getItemIndex());
        draft.setSourceFamily(signal.getKind() == DocumentStructureSignalKind.STEP_ITEM ? "step" : "list");
        draft.setConfidence(signal.getConfidence());
        draft.appendLine(signal.getNormalizedText());
        return draft;
    }

    private DocumentStructureNodeDraft resolveListParent(DocumentStructureSignal signal,
                                                         DocumentStructureNodeDraft currentSection,
                                                         Deque<ListContext> listStack,
                                                         DocumentStructureNodeDraft root) {
        int indentLevel = safeIndentLevel(signal);
        while (!listStack.isEmpty() && listStack.peekLast().indentLevel() >= indentLevel) {
            listStack.removeLast();
        }
        if (!listStack.isEmpty() && indentLevel > listStack.peekLast().indentLevel()) {
            return listStack.peekLast().node();
        }
        return currentSection == null ? root : currentSection;
    }

    private void registerListContext(DocumentStructureSignal signal,
                                     DocumentStructureNodeDraft listNode,
                                     Deque<ListContext> listStack) {
        int indentLevel = safeIndentLevel(signal);
        while (!listStack.isEmpty() && listStack.peekLast().indentLevel() >= indentLevel) {
            listStack.removeLast();
        }
        listStack.addLast(new ListContext(listNode, indentLevel));
    }

    private DocumentStructureNodeDraft buildHeadingNode(DocumentStructureSignal signal,
                                                        int nodeNo,
                                                        List<DocumentStructureNodeDraft> drafts,
                                                        Map<Integer, Integer> latestHeadingByDepth,
                                                        Map<String, Integer> latestHeadingByNumericPath) {
        int depth = resolveHeadingDepth(signal, drafts, latestHeadingByDepth, latestHeadingByNumericPath);
        Integer parentNodeNo = resolveHeadingParentNodeNo(signal, depth, drafts, latestHeadingByDepth, latestHeadingByNumericPath);

        DocumentStructureNodeDraft draft = new DocumentStructureNodeDraft();
        draft.setNodeNo(nodeNo);
        draft.setLineNo(signal.getLineNo());
        draft.setNodeType(DocumentStructureNodeTypeEnum.SECTION.getCode());
        draft.setParentNodeNo(parentNodeNo);
        draft.setDepth(depth);
        draft.setNodeCode(StrUtil.blankToDefault(signal.getNodeCode(), ""));
        draft.setTitle(signal.getTitle());
        draft.setAnchorText(buildHeadingAnchorText(signal));
        draft.setNumericPath(signal.getNumericPath() == null ? List.of() : new ArrayList<>(signal.getNumericPath()));
        draft.setSourceFamily(resolveHeadingFamily(signal));
        draft.setConfidence(signal.getConfidence());
        draft.appendLine(signal.getNormalizedText());

        latestHeadingByDepth.entrySet().removeIf(entry -> entry.getKey() >= depth);
        latestHeadingByDepth.put(depth, nodeNo);
        String numericKey = numericKey(draft.getNumericPath());
        if (StrUtil.isNotBlank(numericKey)) {
            latestHeadingByNumericPath.put(numericKey, nodeNo);
        }
        return draft;
    }


    /*
    标题深度推断
    标题节点的深度推断是层级构建中最关键的逻辑：
    不同来源的标题用不同策略推断深度：
Markdown：# 号数量就是层级
中文章节 / 附录：固定为 1 级
多级数字编号（如 1.2.3）：先找已有的父级编号节点，找到了就 父级深度 + 1；找不到就用编号段数

     */
    private int resolveHeadingDepth(DocumentStructureSignal signal,
                                    List<DocumentStructureNodeDraft> drafts,
                                    Map<Integer, Integer> latestHeadingByDepth,
                                    Map<String, Integer> latestHeadingByNumericPath) {
        String family = resolveHeadingFamily(signal);
        List<Integer> numericPath = signal.getNumericPath() == null ? List.of() : signal.getNumericPath();
        if ("markdown".equals(family)) {
            // Markdown 直接用 # 号数量作为层级
            return Math.max(1, safeLevel(signal.getLevelHint(), 1));
        }
        if ("chapter".equals(family) || "appendix".equals(family)) {
            // 中文章节和附录强制视为一级标题
            return 1;
        }
        if ("decimal".equals(family)) {
            // 多级数字编号优先依赖 numericPath 恢复层级
            if (numericPath.size() <= 1) {
                // 先找直接上级编号（如 1.2 的父级是 1）
                return 1;
            }
            // 找不到直接上级，退到同章节点
            Integer parentNodeNo = latestHeadingByNumericPath.get(numericKey(numericPath.subList(0, numericPath.size() - 1)));
            if (parentNodeNo != null) {
                DocumentStructureNodeDraft parent = findByNodeNo(drafts, parentNodeNo);
                if (parent != null) {
                    return parent.getDepth() + 1;
                }
            }
            Integer chapterParent = latestHeadingByNumericPath.get(numericKey(List.of(numericPath.get(0))));
            if (chapterParent != null) {
                DocumentStructureNodeDraft parent = findByNodeNo(drafts, chapterParent);
                if (parent != null) {
                    return parent.getDepth() + 1;
                }
            }
            // 都找不到，用编号段数作为层级
            return numericPath.size();
        }
        return Math.max(1, safeLevel(signal.getLevelHint(), 1));
    }

    private Integer resolveHeadingParentNodeNo(DocumentStructureSignal signal,
                                               int depth,
                                               List<DocumentStructureNodeDraft> drafts,
                                               Map<Integer, Integer> latestHeadingByDepth,
                                               Map<String, Integer> latestHeadingByNumericPath) {
        String family = resolveHeadingFamily(signal);
        List<Integer> numericPath = signal.getNumericPath() == null ? List.of() : signal.getNumericPath();
        if ("chapter".equals(family) || "appendix".equals(family)) {
            return 1;
        }
        if ("decimal".equals(family) && numericPath.size() > 1) {
            Integer exactParent = latestHeadingByNumericPath.get(numericKey(numericPath.subList(0, numericPath.size() - 1)));
            if (exactParent != null) {
                return exactParent;
            }
            Integer chapterParent = latestHeadingByNumericPath.get(numericKey(List.of(numericPath.get(0))));
            if (chapterParent != null) {
                return chapterParent;
            }
        }
        return findNearestParentByDepth(depth, latestHeadingByDepth);
    }

    private Integer findNearestParentByDepth(int depth,
                                             Map<Integer, Integer> latestHeadingByDepth) {
        for (int candidateDepth = depth - 1; candidateDepth >= 1; candidateDepth--) {
            Integer parentNodeNo = latestHeadingByDepth.get(candidateDepth);
            if (parentNodeNo != null) {
                return parentNodeNo;
            }
        }
        return 1;
    }

    private String resolveHeadingFamily(DocumentStructureSignal signal) {
        if (signal == null || signal.getReasons() == null) {
            return "plain";
        }
        if (signal.getReasons().contains("markdown-heading")) {
            return "markdown";
        }
        if (signal.getReasons().contains("chapter-heading")) {
            return "chapter";
        }
        if (signal.getReasons().contains("appendix-heading")) {
            return "appendix";
        }
        if (signal.getReasons().contains("decimal-heading")) {
            return "decimal";
        }
        if (signal.getReasons().contains("single-digit-ambiguous-heading")) {
            return "decimal";
        }
        return "plain";
    }

    private String buildHeadingAnchorText(DocumentStructureSignal signal) {
        String code = StrUtil.blankToDefault(signal.getNodeCode(), "").trim();
        String title = StrUtil.blankToDefault(signal.getTitle(), "").trim();
        if (StrUtil.isBlank(code)) {
            return title;
        }
        if (title.startsWith(code)) {
            return title;
        }
        return code + " " + title;
    }

    private String numericKey(List<Integer> numericPath) {
        if (numericPath == null || numericPath.isEmpty()) {
            return "";
        }
        return numericPath.stream().map(String::valueOf).reduce((left, right) -> left + "." + right).orElse("");
    }

    private int safeLevel(Integer levelHint, int defaultValue) {
        return levelHint == null || levelHint <= 0 ? defaultValue : levelHint;
    }

    private int safeIndentLevel(DocumentStructureSignal signal) {
        if (signal == null || signal.getIndentLevel() == null || signal.getIndentLevel() < 0) {
            return 0;
        }
        return signal.getIndentLevel();
    }

    private DocumentStructureNodeDraft findByNodeNo(List<DocumentStructureNodeDraft> drafts, Integer nodeNo) {
        if (nodeNo == null) {
            return null;
        }
        for (DocumentStructureNodeDraft draft : drafts) {
            if (draft != null && nodeNo.equals(draft.getNodeNo())) {
                return draft;
            }
        }
        return null;
    }

    private record ListContext(
        DocumentStructureNodeDraft node,
        int indentLevel
    ) {
    }
}
