package org.javaup.ai.manage.support;

import cn.hutool.core.util.StrUtil;
import org.javaup.enums.DocumentStructureNodeTypeEnum;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档结构节点提取器。
 *
 * <p>第一阶段的目标不是一次性做完整的知识图谱抽取，
 * 而是先把“章节树 + 列表/步骤节点”稳定提取出来，
 * 给后续的多轮导航和结构化检索提供可解释的结构底座。</p>
 */
@Component
public class DocumentStructureNodeExtractor {

    private static final Pattern MULTI_LEVEL_CODE_PATTERN = Pattern.compile("^(\\d+(?:\\.\\d+)+)\\s*[、.]?\\s*(.+)$");
    private static final Pattern SINGLE_LEVEL_CODE_PATTERN = Pattern.compile("^(\\d+)\\s*[、.]\\s*(.+)$");
    private static final Pattern CHAPTER_PATTERN = Pattern.compile("^(第[一二三四五六七八九十百\\d]+[章节条部分])\\s*(.+)$");
    private static final Pattern CHINESE_OUTLINE_PATTERN = Pattern.compile("^([一二三四五六七八九十百]+)[、.]\\s*(.+)$");
    private static final Pattern APPENDIX_PATTERN = Pattern.compile("^(附录\\s*[A-Za-z一二三四五六七八九十百\\d]+)(?:\\s+(.+))?$");
    private static final Pattern EXPLICIT_STEP_PATTERN = Pattern.compile("^(?:第\\s*([0-9一二三四五六七八九十百]+)\\s*步|步骤\\s*([0-9一二三四五六七八九十百]+))\\s*[:：、.]?\\s*(.+)$");
    private static final Pattern LIST_INDEX_PATTERN = Pattern.compile("^(\\d+)\\s*[、.]\\s*(.+)$");
    private static final Pattern CHINESE_LIST_INDEX_PATTERN = Pattern.compile("^([一二三四五六七八九十百]+)[、.]\\s*(.+)$");
    private static final Pattern BULLET_PATTERN = Pattern.compile("^[-*+]\\s+(.+)$");

    private final DocumentLineClassifier documentLineClassifier;

    public DocumentStructureNodeExtractor(DocumentLineClassifier documentLineClassifier) {
        this.documentLineClassifier = documentLineClassifier;
    }

    /**
     * 从清洗后的正文中提取结构节点列表。
     */
    public List<DocumentStructureNodeCandidate> extract(String documentTitle, String parsedText) {
        String normalizedTitle = StrUtil.blankToDefault(documentTitle, "文档").trim();
        String normalizedText = StrUtil.blankToDefault(parsedText, "").trim();
        List<MutableNode> nodes = new ArrayList<>();
        MutableNode documentNode = new MutableNode(
            1,
            DocumentStructureNodeTypeEnum.DOCUMENT.getCode(),
            null,
            0,
            "",
            normalizedTitle,
            normalizedTitle,
            "/document",
            "",
            null
        );
        nodes.add(documentNode);

        Deque<MutableNode> sectionStack = new ArrayDeque<>();
        sectionStack.push(documentNode);
        MutableNode currentSection = documentNode;
        MutableNode currentListNode = null;
        Map<Integer, Integer> parentLastChild = new LinkedHashMap<>();
        Map<Integer, Integer> parentNextItemIndex = new LinkedHashMap<>();
        int nextNodeNo = 2;

        for (String rawLine : normalizedText.split("\n")) {
            String trimmed = safeText(rawLine);
            if (trimmed.isBlank()) {
                currentListNode = null;
                continue;
            }
            DocumentLineClassifier.LineClassification classification = documentLineClassifier.classify(trimmed);
            if (classification.isHeading()) {
                HeadingInfo headingInfo = parseHeadingInfo(trimmed);
                int headingLevel = Math.max(classification.level(), 1);
                while (sectionStack.size() > headingLevel) {
                    sectionStack.pop();
                }
                MutableNode parentNode = sectionStack.peek() == null ? documentNode : sectionStack.peek();
                String sectionPath = composeSectionPath(parentNode.sectionPath, headingInfo.rawTitle);
                MutableNode headingNode = new MutableNode(
                    nextNodeNo++,
                    DocumentStructureNodeTypeEnum.SECTION.getCode(),
                    parentNode.nodeNo,
                    headingLevel,
                    headingInfo.code,
                    headingInfo.title,
                    headingInfo.anchorText,
                    buildCanonicalPath(parentNode.canonicalPath, headingInfo.code, headingInfo.title, nextNodeNo),
                    sectionPath,
                    null
                );
                linkSibling(parentNode, headingNode, nodes, parentLastChild);
                headingNode.appendLine(trimmed);
                nodes.add(headingNode);
                sectionStack.push(headingNode);
                currentSection = headingNode;
                currentListNode = null;
                continue;
            }
            if (classification.isListItem()) {
                MutableNode parentNode = currentSection == null ? documentNode : currentSection;
                Integer itemIndex = resolveItemIndex(trimmed, parentNode.nodeNo, parentNextItemIndex);
                ItemInfo itemInfo = parseItemInfo(trimmed);
                int itemDepth = Math.max(parentNode.depth + 1, 1);
                int nodeType = itemInfo.explicitOrdered
                    ? DocumentStructureNodeTypeEnum.STEP.getCode()
                    : DocumentStructureNodeTypeEnum.LIST_ITEM.getCode();
                MutableNode itemNode = new MutableNode(
                    nextNodeNo++,
                    nodeType,
                    parentNode.nodeNo,
                    itemDepth,
                    itemIndex == null ? "" : String.valueOf(itemIndex),
                    itemInfo.title,
                    itemInfo.anchorText,
                    buildCanonicalPath(parentNode.canonicalPath, itemIndex == null ? "" : "item-" + itemIndex, itemInfo.title, nextNodeNo),
                    parentNode.sectionPath,
                    itemIndex
                );
                linkSibling(parentNode, itemNode, nodes, parentLastChild);
                itemNode.appendLine(trimmed);
                parentNode.appendLine(trimmed);
                nodes.add(itemNode);
                currentListNode = itemNode;
                continue;
            }

            if (currentListNode != null) {
                currentListNode.appendLine(trimmed);
            }
            if (currentSection != null) {
                currentSection.appendLine(trimmed);
            }
            else {
                documentNode.appendLine(trimmed);
            }
        }

        finalizeSiblingLinks(nodes);
        return nodes.stream()
            .map(MutableNode::toCandidate)
            .toList();
    }

    private void linkSibling(MutableNode parentNode,
                             MutableNode currentNode,
                             List<MutableNode> nodes,
                             Map<Integer, Integer> parentLastChild) {
        Integer previousNodeNo = parentLastChild.get(parentNode.nodeNo);
        if (previousNodeNo != null && previousNodeNo > 0 && previousNodeNo <= nodes.size()) {
            MutableNode previous = nodes.get(previousNodeNo - 1);
            previous.nextSiblingNodeNo = currentNode.nodeNo;
            currentNode.prevSiblingNodeNo = previous.nodeNo;
        }
        parentLastChild.put(parentNode.nodeNo, currentNode.nodeNo);
    }

    private void finalizeSiblingLinks(List<MutableNode> nodes) {
        for (MutableNode node : nodes) {
            if (node.nextSiblingNodeNo == null) {
                node.nextSiblingNodeNo = 0;
            }
            if (node.prevSiblingNodeNo == null) {
                node.prevSiblingNodeNo = 0;
            }
        }
    }

    private HeadingInfo parseHeadingInfo(String line) {
        String normalized = safeText(line);
        if (normalized.startsWith("#")) {
            normalized = normalized.replaceFirst("^#{1,6}\\s+", "").trim();
        }
        Matcher multi = MULTI_LEVEL_CODE_PATTERN.matcher(normalized);
        if (multi.matches()) {
            return new HeadingInfo(multi.group(1).trim(), multi.group(2).trim(), normalized, normalized);
        }
        Matcher chapter = CHAPTER_PATTERN.matcher(normalized);
        if (chapter.matches()) {
            return new HeadingInfo(chapter.group(1).trim(), chapter.group(2).trim(), normalized, normalized);
        }
        Matcher chinese = CHINESE_OUTLINE_PATTERN.matcher(normalized);
        if (chinese.matches()) {
            return new HeadingInfo(chinese.group(1).trim(), chinese.group(2).trim(), normalized, normalized);
        }
        Matcher appendix = APPENDIX_PATTERN.matcher(normalized);
        if (appendix.matches()) {
            String code = appendix.group(1).trim();
            String title = StrUtil.blankToDefault(appendix.group(2), code).trim();
            return new HeadingInfo(code, title, normalized, normalized);
        }
        Matcher single = SINGLE_LEVEL_CODE_PATTERN.matcher(normalized);
        if (single.matches()) {
            return new HeadingInfo(single.group(1).trim(), single.group(2).trim(), normalized, normalized);
        }
        return new HeadingInfo("", normalized, normalized, normalized);
    }

    private ItemInfo parseItemInfo(String line) {
        String normalized = safeText(line);
        Matcher explicitStep = EXPLICIT_STEP_PATTERN.matcher(normalized);
        if (explicitStep.matches()) {
            return new ItemInfo(explicitStep.group(3).trim(), explicitStep.group(3).trim(), true);
        }
        Matcher numeric = LIST_INDEX_PATTERN.matcher(normalized);
        if (numeric.matches()) {
            return new ItemInfo(numeric.group(2).trim(), numeric.group(2).trim(), true);
        }
        Matcher chinese = CHINESE_LIST_INDEX_PATTERN.matcher(normalized);
        if (chinese.matches()) {
            return new ItemInfo(chinese.group(2).trim(), chinese.group(2).trim(), true);
        }
        Matcher bullet = BULLET_PATTERN.matcher(normalized);
        if (bullet.matches()) {
            return new ItemInfo(bullet.group(1).trim(), bullet.group(1).trim(), false);
        }
        return new ItemInfo(normalized, normalized, false);
    }

    private Integer resolveItemIndex(String line,
                                     Integer parentNodeNo,
                                     Map<Integer, Integer> parentNextItemIndex) {
        String normalized = safeText(line);
        Matcher explicitStep = EXPLICIT_STEP_PATTERN.matcher(normalized);
        if (explicitStep.matches()) {
            String explicitNumber = StrUtil.blankToDefault(explicitStep.group(1), explicitStep.group(2));
            return parseChineseNumber(explicitNumber);
        }
        Matcher numeric = LIST_INDEX_PATTERN.matcher(normalized);
        if (numeric.matches()) {
            return Integer.parseInt(numeric.group(1));
        }
        Matcher chinese = CHINESE_LIST_INDEX_PATTERN.matcher(normalized);
        if (chinese.matches()) {
            return parseChineseNumber(chinese.group(1));
        }
        int nextIndex = parentNextItemIndex.getOrDefault(parentNodeNo, 0) + 1;
        parentNextItemIndex.put(parentNodeNo, nextIndex);
        return nextIndex;
    }

    private int parseChineseNumber(String text) {
        if (StrUtil.isBlank(text)) {
            return 0;
        }
        if (text.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(text);
        }
        Map<Character, Integer> digitMap = Map.of(
            '一', 1, '二', 2, '三', 3, '四', 4, '五', 5,
            '六', 6, '七', 7, '八', 8, '九', 9
        );
        if ("十".equals(text)) {
            return 10;
        }
        if (text.startsWith("十")) {
            return 10 + digitMap.getOrDefault(text.charAt(1), 0);
        }
        if (text.endsWith("十")) {
            return digitMap.getOrDefault(text.charAt(0), 0) * 10;
        }
        if (text.contains("十") && text.length() == 3) {
            return digitMap.getOrDefault(text.charAt(0), 0) * 10 + digitMap.getOrDefault(text.charAt(2), 0);
        }
        return digitMap.getOrDefault(text.charAt(0), 0);
    }

    private String composeSectionPath(String parentSectionPath, String currentTitle) {
        if (StrUtil.isBlank(parentSectionPath)) {
            return safeText(currentTitle);
        }
        if (StrUtil.isBlank(currentTitle)) {
            return safeText(parentSectionPath);
        }
        return parentSectionPath + " > " + currentTitle.trim();
    }

    private String buildCanonicalPath(String parentPath, String code, String title, int fallbackSeed) {
        String segment = safeText(code);
        if (segment.isBlank()) {
            segment = safeText(title)
                .replaceAll("\\s+", "-")
                .replaceAll("[^\\p{IsHan}A-Za-z0-9_.-]", "");
        }
        if (segment.isBlank()) {
            segment = "node-" + fallbackSeed;
        }
        if (StrUtil.isBlank(parentPath)) {
            return "/" + segment;
        }
        return parentPath + "/" + segment;
    }

    private String safeText(String text) {
        return text == null ? "" : text.trim();
    }

    private record HeadingInfo(
        String code,
        String title,
        String anchorText,
        String rawTitle
    ) {
    }

    private record ItemInfo(
        String title,
        String anchorText,
        boolean explicitOrdered
    ) {
    }

    private static final class MutableNode {

        private final Integer nodeNo;
        private final Integer nodeType;
        private final Integer parentNodeNo;
        private Integer prevSiblingNodeNo;
        private Integer nextSiblingNodeNo;
        private final Integer depth;
        private final String nodeCode;
        private final String title;
        private final String anchorText;
        private final String canonicalPath;
        private final String sectionPath;
        private final Integer itemIndex;
        private final StringBuilder contentBuilder = new StringBuilder();

        private MutableNode(Integer nodeNo,
                            Integer nodeType,
                            Integer parentNodeNo,
                            Integer depth,
                            String nodeCode,
                            String title,
                            String anchorText,
                            String canonicalPath,
                            String sectionPath,
                            Integer itemIndex) {
            this.nodeNo = nodeNo;
            this.nodeType = nodeType;
            this.parentNodeNo = parentNodeNo;
            this.depth = depth;
            this.nodeCode = nodeCode;
            this.title = title;
            this.anchorText = anchorText;
            this.canonicalPath = canonicalPath;
            this.sectionPath = sectionPath;
            this.itemIndex = itemIndex;
        }

        private void appendLine(String line) {
            String normalized = line == null ? "" : line.trim();
            if (normalized.isBlank()) {
                return;
            }
            if (!contentBuilder.isEmpty()) {
                contentBuilder.append('\n');
            }
            contentBuilder.append(normalized);
        }

        private DocumentStructureNodeCandidate toCandidate() {
            return new DocumentStructureNodeCandidate(
                nodeNo,
                nodeType,
                parentNodeNo,
                prevSiblingNodeNo,
                nextSiblingNodeNo,
                depth,
                nodeCode,
                title,
                anchorText,
                canonicalPath,
                sectionPath,
                contentBuilder.toString().trim(),
                itemIndex
            );
        }
    }
}
