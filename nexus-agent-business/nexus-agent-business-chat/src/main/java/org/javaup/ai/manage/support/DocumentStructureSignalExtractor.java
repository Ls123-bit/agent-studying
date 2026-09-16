package org.javaup.ai.manage.support;

import cn.hutool.core.util.StrUtil;
import org.javaup.ai.manage.config.DocumentManageProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 支撑组件
 * @author: 阿星不是程序员
 **/


/*
第一阶段：信号提取
这是整条流水线中代码量最大的一个阶段。
DocumentStructureSignalExtractor 的职责是：
逐行扫描文档纯文本，通过正则模式匹配和启发式规则，把每一行分类为标题、列表项、步骤、表格行、引用、正文、噪声等结构信号。
 */
@Component
public class DocumentStructureSignalExtractor {

    //这些正则覆盖了中英文文档中常见的各种结构标记

    /** 匹配 Markdown 标题，如 "## 概述"，捕获 # 号数量（层级）和标题文本 */
    private static final Pattern MARKDOWN_HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$");
    /** 匹配多级数字编号标题，如 "1.2.3 配置说明"，捕获编号和标题文本 */
    private static final Pattern DECIMAL_HEADING_PATTERN = Pattern.compile("^(\\d+(?:\\.\\d+)+)\\s*[、.]?\\s*(.+)$");
    /** 匹配单级数字编号行，如 "1、概述" 或 "2. 安装"，可能是标题也可能是列表项 */
    private static final Pattern SINGLE_LEVEL_DIGIT_PATTERN = Pattern.compile("^(\\d+)\\s*[、.]\\s*(.+)$");
    /** 匹配中文章节标题，如 "第一章 绪论"、"第三节 方法" */
    private static final Pattern CHAPTER_PATTERN = Pattern.compile("^(第([一二三四五六七八九十百\\d]+)[章节条部分])\\s*(.+)$");
    /** 匹配附录标题，如 "附录A 术语表" */
    private static final Pattern APPENDIX_PATTERN = Pattern.compile("^(附录\\s*([A-Za-z一二三四五六七八九十百\\d]+))(?:\\s+(.+))?$");
    /** 匹配中文大纲编号，如 "一、项目背景"、"三、实施方案" */
    private static final Pattern CHINESE_OUTLINE_PATTERN = Pattern.compile("^([一二三四五六七八九十百]+)[、.]\\s*(.+)$");
    /** 匹配显式步骤标记，如 "第一步：安装" 或 "步骤2：配置" */
    private static final Pattern EXPLICIT_STEP_PATTERN = Pattern.compile("^(?:第\\s*([0-9一二三四五六七八九十百]+)\\s*步|步骤\\s*([0-9一二三四五六七八九十百]+))\\s*[:：、.]?\\s*(.+)$");
    /** 匹配无序列表项，如 "- 项目一"、"* 项目二"、"• 项目三" */
    private static final Pattern BULLET_PATTERN = Pattern.compile("^([-*+•])\\s+(.+)$");
    private static final Pattern CHECKBOX_PATTERN = Pattern.compile("^\\[(?: |x|X)]\\s+(.+)$");
    /** 匹配页码噪声行，如 "第 3 页"、"Page 5"、"3 / 10" */
    private static final Pattern PAGE_NOISE_PATTERN = Pattern.compile("^(?:第\\s*\\d+\\s*页|Page\\s*\\d+|\\d+\\s*/\\s*\\d+)$", Pattern.CASE_INSENSITIVE);
    /** 匹配版权声明噪声行 */
    private static final Pattern COPYRIGHT_NOISE_PATTERN = Pattern.compile(".*(?:版权所有|未经授权|内部使用|copyright|all rights reserved|保密).*", Pattern.CASE_INSENSITIVE);
    private static final Pattern VERSION_FOOTER_PATTERN = Pattern.compile(".*(?:\\bV\\d+(?:\\.\\d+)*\\b|版本|修订|Rev\\.?\\s*\\d+).*", Pattern.CASE_INSENSITIVE);
    private static final Pattern INLINE_EXPLICIT_STEP_BOUNDARY_PATTERN = Pattern.compile("(?=(?:第\\s*[0-9一二三四五六七八九十百]+\\s*步|步骤\\s*[0-9一二三四五六七八九十百]+)\\s*[:：、.])");
    private static final Pattern TABLE_SPLIT_PATTERN = Pattern.compile("\\|");

    private final DocumentManageProperties properties;
    private final DocumentLineClassifier documentLineClassifier;

    public DocumentStructureSignalExtractor(DocumentManageProperties properties,
                                            DocumentLineClassifier documentLineClassifier) {
        this.properties = properties;
        this.documentLineClassifier = documentLineClassifier;
    }


    /**
     * extract入口方法
     * 信号提取的入口方法。    做了三件事：先把文本拆成逻辑行，然后统计行频次（用于噪声检测），最后逐行调用 classify() 进行分类
     * 将文档纯文本拆分为逻辑行，逐行进行结构信号分类，返回信号批次。
     *
     * @param documentTitle 文档标题，用于去重和噪声检测
     * @param parsedText    文档解析后的纯文本
     * @return 信号批次，包含所有行的规范化文本列表和对应的结构信号列表
     */
    public DocumentStructureSignalBatch extract(String documentTitle, String parsedText) {
        //对标题做空值保护和去除首尾空白
        String normalizedTitle = safeText(documentTitle);
        // 将原始文本按换行符拆分，并处理行内步骤边界，生成逻辑行列表
        List<DocumentStructureLogicalLine> logicalLines = buildLogicalLines(parsedText);
        // 统计每行规范化文本的出现频次，用于检测重复的页眉/页脚噪声
        Map<String, Integer> lineFrequency = buildLineFrequency(logicalLines);
        // 预分配信号列表容量（逻辑行数 + 1 个文档标题信号）
        List<DocumentStructureSignal> signals = new ArrayList<>(logicalLines.size() + 1);
        // 如果文档标题非空，先插入一个 DOCUMENT_TITLE 类型的信号作为根信号（行号为 0）
        if (StrUtil.isNotBlank(normalizedTitle)) {
            signals.add(DocumentStructureSignal.builder()
                .lineNo(0)
                .rawText(normalizedTitle)
                .normalizedText(normalizedTitle)
                .kind(DocumentStructureSignalKind.DOCUMENT_TITLE)
                .title(normalizedTitle)
                .levelHint(0)
                .confidence(1.0D)
                .build());
        }

        // 遍历每一个逻辑行，构建上下文（前后非空行），然后进行分类
        for (int index = 0; index < logicalLines.size(); index++) {
            DocumentStructureLogicalLine logicalLine = logicalLines.get(index);
            // 构建当前行的上下文信息：前一个非空行、后一个非空行、前后是否有空行
            LineContext context = buildContext(logicalLines, index);
            // 对当前行进行分类，生成对应的结构信号
            signals.add(classify(normalizedTitle, logicalLine, context, lineFrequency));
        }
        // 提取所有逻辑行的规范化文本，作为后续歧义消解阶段的上下文窗口
        List<String> contextLines = logicalLines.stream()
            .map(DocumentStructureLogicalLine::normalizedText)
            .toList();
        return new DocumentStructureSignalBatch(contextLines, signals);
    }








    /*
    核心分类方法（这是整个信号提取阶段最核心的方法——按优先级从高到低依次尝试 15 种匹配规则，一旦命中就立即返回：）
    核心分类方法 —— 对单个逻辑行进行结构信号分类。
 * 按照优先级从高到低依次尝试匹配：空行 → 噪声 → Markdown标题 → 显式步骤 → 中文章节 →
 * 附录 → 多级数字编号 → 表格行 → 引用 → 复选框 → 无序列表 → 单级数字编号 → 中文大纲 → 兜底分类。
 * 一旦匹配成功立即返回，不再继续后续匹配。
 *
 * @param documentTitle 文档标题，用于检测重复标题噪声
 * @param logicalLine   当前待分类的逻辑行
 * @param context       当前行的上下文（前后非空行、前后是否有空行）
 * @param lineFrequency 行文本出现频次表，用于检测重复页眉/页脚
 * @return 分类后的结构信号

     */
    private DocumentStructureSignal classify(String documentTitle,
                                             DocumentStructureLogicalLine logicalLine,
                                             LineContext context,
                                             Map<String, Integer> lineFrequency) {
        int lineNo = logicalLine.lineNo();
        String rawText = logicalLine.rawText();
        String normalized = logicalLine.normalizedText();
        String previousNonBlank = context.previousNonBlank() == null ? "" : context.previousNonBlank().normalizedText();
        String nextNonBlank = context.nextNonBlank() == null ? "" : context.nextNonBlank().normalizedText();
        // ========== 1. 空行检测 ==========
        if (normalized.isBlank()) {
            return signal(lineNo, rawText, normalized, logicalLine.indentLevel(), DocumentStructureSignalKind.BLANK, "", "", 0, null, List.of(), 1.0D);
        }
        // ========== 2. 重复噪声检测（页眉/页脚/版权声明等在文档中多次出现的行）==========
        if (isRepeatedNoise(documentTitle, normalized, lineFrequency.getOrDefault(normalized, 0))) {
            return signal(lineNo, rawText, normalized, logicalLine.indentLevel(), DocumentStructureSignalKind.NOISE, "", "", 0, null,
                List.of("repeated-running-header-or-footer"), 0.99D);
        }
        // ========== 3. 页码噪声检测（如 "第3页"、"Page 5"、"3/10"）==========
        if (PAGE_NOISE_PATTERN.matcher(normalized).matches()) {
            return signal(lineNo, rawText, normalized, logicalLine.indentLevel(), DocumentStructureSignalKind.NOISE, "", "", 0, null,
                List.of("page-noise"), 0.98D);
        }
        // ========== 4. Markdown 标题匹配（如 "## 概述"）==========
        Matcher markdown = MARKDOWN_HEADING_PATTERN.matcher(normalized);
        if (markdown.matches()) {
            String title = markdown.group(2).trim();
            // 如果标题内容与文档标题相同，视为重复标题噪声而非有效标题
            if (sameDocumentTitle(documentTitle, title)) {
                return signal(lineNo, rawText, normalized, logicalLine.indentLevel(), DocumentStructureSignalKind.NOISE, "", title, 0, null,
                    List.of("duplicate-document-title"), 0.99D);
            }
            // # 号数量即为标题层级，同时尝试从标题文本中提取编号（如 "## 1.2 配置" 中的 "1.2"）
            DocumentStructureSignal signal = signal(lineNo, rawText, normalized, logicalLine.indentLevel(), DocumentStructureSignalKind.HEADING,
                extractCode(title), title, markdown.group(1).length(), null, List.of("markdown-heading"), 0.98D);
            // 从编号中解析出数字路径（如 "1.2" → [1, 2]），用于后续层级构建
            signal.setNumericPath(extractNumericPath(signal.getNodeCode()));
            return signal;
        }
        // ========== 5. 显式步骤匹配（如 "第一步：安装" 或 "步骤2：配置"）==========
        Matcher explicitStep = EXPLICIT_STEP_PATTERN.matcher(normalized);
        if (explicitStep.matches()) {
            // 从两个捕获组中取非空的那个作为步骤序号，转换为整数
            Integer itemIndex = parseLooseNumber(StrUtil.blankToDefault(explicitStep.group(1), explicitStep.group(2)));
            return signal(lineNo, rawText, normalized, logicalLine.indentLevel(), DocumentStructureSignalKind.STEP_ITEM, "", explicitStep.group(3).trim(), null, itemIndex,
                List.of("explicit-step"), 0.96D);
        }
        // ========== 6. 中文章节标题匹配（如 "第一章 绪论"、"第三节 方法"）==========
        Matcher chapter = CHAPTER_PATTERN.matcher(normalized);
        if (chapter.matches()) {
            // 章节编号部分，如 "第一章"
            String code = chapter.group(1).trim();
            // 标题文本部分，如 "绪论"
            String title = chapter.group(3).trim();
            // 与文档标题重复时标记为噪声
            if (sameDocumentTitle(documentTitle, title)) {
                return signal(lineNo, rawText, normalized, logicalLine.indentLevel(), DocumentStructureSignalKind.NOISE, code, title, 0, null,
                    List.of("duplicate-document-title"), 0.99D);
            }
            // 章节标题固定为深度 1（顶级标题）
            DocumentStructureSignal signal = signal(lineNo, rawText, normalized, logicalLine.indentLevel(), DocumentStructureSignalKind.HEADING,
                code, title, 1, null, List.of("chapter-heading"), 0.96D);
            // 将中文数字章节号转换为整数，设置数字路径（如 "第三章" → [3]）
            Integer chapterNo = parseLooseNumber(chapter.group(2));
            if (chapterNo != null && chapterNo > 0) {
                signal.setNumericPath(List.of(chapterNo));
            }
            return signal;
        }
        // ========== 7. 附录标题匹配（如 "附录A 术语表"）==========
        Matcher appendix = APPENDIX_PATTERN.matcher(normalized);
        if (appendix.matches()) {
            // 附录编号，如 "附录A"
            String code = appendix.group(1).trim();
            // 附录标题可选，为空时使用编号本身作为标题
            String title = StrUtil.blankToDefault(appendix.group(3), code).trim();
            return signal(lineNo, rawText, normalized, logicalLine.indentLevel(), DocumentStructureSignalKind.HEADING,
                code, title, 1, null, List.of("appendix-heading"), 0.92D);
        }
        // ========== 8. 多级数字编号标题匹配（如 "1.2.3 配置说明"）==========
        Matcher decimal = DECIMAL_HEADING_PATTERN.matcher(normalized);
        if (decimal.matches()) {
            // 编号部分，如 "1.2.3"
            String code = decimal.group(1).trim();
            // 标题文本部分
            String title = decimal.group(2).trim();
            // 层级由编号的段数决定（如 "1.2.3" → 3 级）
            DocumentStructureSignal signal = signal(lineNo, rawText, normalized, logicalLine.indentLevel(), DocumentStructureSignalKind.HEADING,
                code, title, Math.max(1, code.split("\\.").length), null, List.of("decimal-heading"), 0.95D);
            // 解析数字路径（如 "1.2.3" → [1, 2, 3]）
            signal.setNumericPath(extractNumericPath(code));
            return signal;
        }
        // ========== 9. 表格行检测（以 | 开头结尾、包含 Tab、或多个 | 分隔）==========
        if (isTableRow(normalized)) {
            return signal(lineNo, rawText, normalized, logicalLine.indentLevel(), DocumentStructureSignalKind.TABLE_ROW, "", normalized, null, null,
                List.of("table-row"), 0.90D);
        }
        // ========== 10. 引用行检测（以 > 开头的 Markdown 引用）==========
        if (normalized.startsWith(">")) {
            return signal(lineNo, rawText, normalized, logicalLine.indentLevel(), DocumentStructureSignalKind.QUOTE, "", normalized, null, null,
                List.of("quote"), 0.88D);
        }
        // ========== 11. 复选框列表项匹配（如 "[ ] 待办" 或 "[x] 已完成"）==========
        Matcher checkbox = CHECKBOX_PATTERN.matcher(normalized);
        if (checkbox.matches()) {
            return signal(lineNo, rawText, normalized, logicalLine.indentLevel(), DocumentStructureSignalKind.LIST_ITEM, "", checkbox.group(1).trim(), null, null,
                List.of("checkbox-list"), 0.92D);
        }
        // ========== 12. 无序列表项匹配（如 "- 项目一"、"* 项目二"）==========
        Matcher bullet = BULLET_PATTERN.matcher(normalized);
        if (bullet.matches()) {
            return signal(lineNo, rawText, normalized, logicalLine.indentLevel(), DocumentStructureSignalKind.LIST_ITEM, "", bullet.group(2).trim(), null, null,
                List.of("bullet-list"), 0.90D);
        }
        // ========== 13. 单级阿拉伯数字编号匹配（如 "1、概述" 或 "2. 安装"）==========
        // 这类行存在歧义：可能是标题（如 "1、项目背景"），也可能是列表项（如 "1、苹果"）
        /*
        判断逻辑的核心思路是：
1.如果前后行构成连续序列（1、2、3...），大概率是列表项
2.如果前一行以冒号结尾（如"包含以下内容："），大概率是列表项
3.如果都不是，再用 looksLikePlainHeading() 做启发式判断——文本短、前后有空行、不以句末标点结尾 → 更像标题
但即使判断为"像标题"，也只标记为 HEADING_CANDIDATE（置信度 0.62），而不是直接定性为 HEADING。最终裁定交给第二阶段的歧义消解器。
         */
        Matcher singleDigit = SINGLE_LEVEL_DIGIT_PATTERN.matcher(normalized);
        if (singleDigit.matches()) {
            String title = singleDigit.group(2).trim();
            // 将编号文本转换为整数序号
            Integer itemIndex = parseLooseNumber(singleDigit.group(1));
            // 检查前后行是否构成连续序列（如 1、2、3），如果是则更倾向于列表项
            boolean sequential = isNeighborSequence(itemIndex, OrderedMarkerFamily.ARABIC_SINGLE, context);
            // 检查前一行是否以冒号结尾（引导列表的标志，如 "包含以下内容："）
            boolean introducedByLeadIn = previousIntroducesList(context.previousNonBlank());
            // 综合判断：非连续序列、非引导列表、且文本看起来像标题 → 标记为候选标题
            boolean headingLike = !sequential
                && !introducedByLeadIn
                && looksLikePlainHeading(title, context);
            DocumentStructureSignal signal = signal(
                lineNo,
                rawText,
                normalized,
                logicalLine.indentLevel(),
                    // 候选标题使用 HEADING_CANDIDATE（低置信度），后续由歧义消解阶段二次判定
                headingLike ? DocumentStructureSignalKind.HEADING_CANDIDATE : DocumentStructureSignalKind.LIST_ITEM,
                singleDigit.group(1).trim(),
                title,
                headingLike ? 1 : null,
                itemIndex,
                    // 记录分类原因，便于调试和歧义消解参考
                List.of(headingLike ? "single-digit-ambiguous-heading"
                    : sequential ? "single-digit-sequence-list" : "single-digit-list"),
                    // 候选标题置信度较低（0.62），确定的列表项置信度较高
                headingLike ? 0.62D : sequential || introducedByLeadIn ? 0.93D : 0.88D
            );
            // 如果判定为候选标题，设置数字路径用于后续层级构建
            if (headingLike && itemIndex != null && itemIndex > 0) {
                signal.setNumericPath(List.of(itemIndex));
            }
            return signal;
        }
        // ========== 14. 中文大纲编号匹配（如 "一、项目背景"、"三、实施方案"）==========
        // 与单级数字编号类似，也存在标题/列表项的歧义
        Matcher chineseOutline = CHINESE_OUTLINE_PATTERN.matcher(normalized);
        if (chineseOutline.matches()) {
            String title = chineseOutline.group(2).trim();
            // 将中文数字转换为整数序号
            Integer index = parseLooseNumber(chineseOutline.group(1));
            // 检查前后行是否构成中文大纲的连续序列（如 一、二、三）
            boolean sequential = isNeighborSequence(index, OrderedMarkerFamily.CHINESE_OUTLINE, context);
            // 检查前一行是否为引导列表的行
            boolean introducedByLeadIn = previousIntroducesList(context.previousNonBlank());
            // 综合判断是否更像标题
            boolean headingLike = !sequential
                && !introducedByLeadIn
                && looksLikePlainHeading(title, context);
            DocumentStructureSignal signal = signal(
                lineNo,
                rawText,
                normalized,
                logicalLine.indentLevel(),
                headingLike ? DocumentStructureSignalKind.HEADING_CANDIDATE : DocumentStructureSignalKind.LIST_ITEM,
                chineseOutline.group(1).trim(),
                title,
                headingLike ? 1 : null,
                index,
                List.of(headingLike ? "chinese-outline-ambiguous-heading"
                    : sequential ? "chinese-outline-sequence-list" : "chinese-outline-list"),
                headingLike ? 0.60D : sequential || introducedByLeadIn ? 0.92D : 0.86D
            );
            if (headingLike && index != null && index > 0) {
                signal.setNumericPath(List.of(index));
            }
            return signal;
        }

        // ========== 15. 兜底分类：使用 DocumentLineClassifier 做最后尝试 ==========如果前面的所有正则都没命中，会调用DocumentLineClassifier 做最后一次尝试：
        DocumentLineClassifier.LineClassification fallback = documentLineClassifier.classify(normalized);
        // 如果行分类器未识别为标题，但启发式规则认为像标题（短文本、前后有空行、无句末标点）
        if (!fallback.isHeading() && looksLikePlainHeading(normalized, context)) {
            // 标记为候选标题，置信度最低（0.58），交由歧义消解阶段最终裁定
            return signal(lineNo, rawText, normalized, logicalLine.indentLevel(), DocumentStructureSignalKind.HEADING_CANDIDATE,
                "", normalized, inferPlainHeadingLevel(context), null, List.of("plain-heading-candidate"), 0.58D);
        }
        // 所有模式均未匹配，归类为普通正文
        return signal(lineNo, rawText, normalized, logicalLine.indentLevel(), DocumentStructureSignalKind.BODY,
            "", normalized, null, null, List.of("body"), 1.0D);
    }

    private DocumentStructureSignal signal(int lineNo,
                                           String rawText,
                                           String normalized,
                                           int indentLevel,
                                           DocumentStructureSignalKind kind,
                                           String code,
                                           String title,
                                           Integer levelHint,
                                           Integer itemIndex,
                                           List<String> reasons,
                                           double confidence) {
        return DocumentStructureSignal.builder()
            .lineNo(lineNo)
            .rawText(rawText)
            .normalizedText(normalized)
            .kind(kind)
            .nodeCode(StrUtil.blankToDefault(code, ""))
            .title(StrUtil.blankToDefault(title, normalized))
            .levelHint(levelHint)
            .indentLevel(indentLevel)
            .itemIndex(itemIndex)
            .reasons(new ArrayList<>(reasons))
            .confidence(confidence)
            .build();
    }

    private String extractCode(String title) {
        Matcher decimal = DECIMAL_HEADING_PATTERN.matcher(title);
        if (decimal.matches()) {
            return decimal.group(1).trim();
        }
        Matcher chapter = CHAPTER_PATTERN.matcher(title);
        if (chapter.matches()) {
            return chapter.group(1).trim();
        }
        Matcher appendix = APPENDIX_PATTERN.matcher(title);
        if (appendix.matches()) {
            return appendix.group(1).trim();
        }
        return "";
    }

    private List<Integer> extractNumericPath(String code) {
        String normalized = safeText(code);
        if (normalized.isBlank()) {
            return List.of();
        }
        if (normalized.contains(".")) {
            List<Integer> path = new ArrayList<>();
            for (String segment : normalized.split("\\.")) {
                if (!segment.chars().allMatch(Character::isDigit)) {
                    return List.of();
                }
                path.add(Integer.parseInt(segment));
            }
            return path;
        }
        Matcher chapter = CHAPTER_PATTERN.matcher(normalized + " 标题");
        if (chapter.find()) {
            Integer chapterNo = parseLooseNumber(chapter.group(2));
            if (chapterNo != null && chapterNo > 0) {
                return List.of(chapterNo);
            }
        }
        return List.of();
    }

    private boolean isTableRow(String normalized) {
        if (normalized.startsWith("|") && normalized.endsWith("|")) {
            return true;
        }
        if (normalized.contains("\t")) {
            return true;
        }
        if (TABLE_SPLIT_PATTERN.split(normalized).length >= 3 && normalized.contains("|")) {
            return true;
        }
        return normalized.matches("^[:\\-\\s|]+$");
    }

    /*启发式标题判断
    这个方法用了一系列排除法：太长的不是、有句末标点的不是、有 URL 的不是、有中文标点的不是……最后剩下的"短文本 + 前后有空行 + 后面有内容 + 像名词短语"才算疑似标题。
     */
    private boolean looksLikePlainHeading(String text,
                                          LineContext context) {
        String normalized = safeText(text);
        if (text.isBlank()) {
            return false;
        }
        // 超过配置的最大纯标题字符数，不太可能是标题
        if (normalized.length() > properties.getStructureParsing().getMaxPlainHeadingChars()) {
            return false;
        }
        // 以句末标点结尾的更像是正文句子而非标题
        if (endsWithSentencePunctuation(normalized)) {
            return false;
        }
        // 包含 URL 的行不太可能是标题
        if (normalized.contains("http://") || normalized.contains("https://")) {
            return false;
        }
        // 以 | 开头或结尾的行更像是表格行
        if (normalized.startsWith("|") || normalized.endsWith("|")) {
            return false;
        }
        // 仅由重复的 -、=、_ 组成的行是分隔线
        if (normalized.matches("^[\\-=_]{3,}$")) {
            return false;
        }
        // 标题的关键特征：前面或后面有空行（与正文隔开）
        boolean isolated = context.blankBefore() || context.blankAfter();
        // 标题后面应该有实际内容
        boolean nextLooksContent = context.nextNonBlank() != null
            && StrUtil.isNotBlank(context.nextNonBlank().normalizedText())
            && !context.nextNonBlank().normalizedText().matches("^[:\\-\\s|]+$");
        // 标题通常是名词短语，不包含中文逗号、分号、句号、冒号等
        boolean nounLike = !normalized.contains("，")
            && !normalized.contains("；")
            && !normalized.contains("。")
            && !normalized.contains("：")
            && !normalized.toLowerCase(Locale.ROOT).startsWith("http");
        // 三个条件同时满足才判定为疑似标题
        return isolated && nextLooksContent && nounLike;
    }

    private int inferPlainHeadingLevel(LineContext context) {
        if (context == null || context.blankBefore()) {
            return 1;
        }
        return 2;
    }

    private boolean endsWithSentencePunctuation(String text) {
        return text.endsWith("。")
            || text.endsWith("！")
            || text.endsWith("？")
            || text.endsWith("；")
            || text.endsWith(".")
            || text.endsWith("!")
            || text.endsWith("?")
            || text.endsWith(";");
    }




/*
前置步骤：构建逻辑行
将原始纯文本拆成"逻辑行"列表。
 * <p>
 * 这里之所以不直接把按换行切出来的物理行原样往下传，是因为一条物理行里可能内联写了多个步骤，
 * 例如"步骤1：下载 步骤2：安装"。如果不先拆开，后续分类器就只能把整行当成一个信号，
 * 会让步骤识别和层级解析都变得不准确。
 * </p>
 * <p>
 * 最终每个 DocumentStructureLogicalLine 都会携带：
 * 逻辑行号、原始物理行号、物理行内的片段序号、缩进级别、原始片段文本、规范化文本。
 * 这些信息后面会被上下文构建、列表层级恢复和兄弟关系排序复用。
 * </p>
 */
    private List<DocumentStructureLogicalLine> buildLogicalLines(String parsedText) {
        String[] rawLines = StrUtil.blankToDefault(parsedText, "").split("\n", -1);
        // 先尝试按"行内显式步骤边界"拆分；拆分后的一条物理行可能生成多条逻辑行。
        List<DocumentStructureLogicalLine> logicalLines = new ArrayList<>(rawLines.length);
        int logicalLineNo = 1;
        for (int index = 0; index < rawLines.length; index++) {
            String rawLine = StrUtil.blankToDefault(rawLines[index], "");
            List<String> segments = splitInlineSegments(rawLine);//细节：splitInlineSegments() 会用零宽前瞻正则检测行内的步骤边界（如"步骤1：下载 步骤2：安装"），把一条物理行拆成多条逻辑行。空行也会保留，因为"前后是否有空行"是后面标题判定的重要上下文特征。
            if (segments.isEmpty()) {
                // 空物理行也保留下来，因为"前后是否有空行"是标题判定的重要上下文特征。
                logicalLines.add(new DocumentStructureLogicalLine(
                    logicalLineNo++,
                    index + 1,
                    1,
                    0,
                    rawLine,
                    safeText(rawLine)
                ));
                continue;
            }
            for (int segmentIndex = 0; segmentIndex < segments.size(); segmentIndex++) {
                String segment = segments.get(segmentIndex);
                // 每个片段单独计算缩进级别，后续列表层级恢复会用到这个值。
                logicalLines.add(new DocumentStructureLogicalLine(
                    logicalLineNo++,
                    index + 1,
                    segmentIndex + 1,
                    countIndentLevel(segment),
                    segment,
                    safeText(segment)
                ));
            }
        }
        return logicalLines;
    }

    private List<String> splitInlineSegments(String rawLine) {
        if (rawLine == null) {
            return List.of();
        }
        if (rawLine.trim().isEmpty()) {
            return List.of();
        }
        String trimmed = rawLine.trim();
        if (trimmed.startsWith("#")
            || trimmed.startsWith("|")
            || trimmed.startsWith(">")
            || trimmed.matches("^[:\\-\\s|]+$")) {
            return List.of(rawLine);
        }

        List<Integer> boundaries = new ArrayList<>();
        boundaries.add(0);
        Matcher matcher = INLINE_EXPLICIT_STEP_BOUNDARY_PATTERN.matcher(rawLine);
        while (matcher.find()) {
            if (matcher.start() > 0) {
                boundaries.add(matcher.start());
            }
        }
        if (boundaries.size() == 1) {
            return List.of(rawLine);
        }
        List<String> segments = new ArrayList<>();
        for (int index = 0; index < boundaries.size(); index++) {
            int start = boundaries.get(index);
            int end = index == boundaries.size() - 1 ? rawLine.length() : boundaries.get(index + 1);
            String segment = rawLine.substring(start, end).trim();
            if (StrUtil.isNotBlank(segment)) {
                segments.add(segment);
            }
        }
        return segments.isEmpty() ? List.of(rawLine) : segments;
    }

    private int countIndentLevel(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int indent = 0;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == ' ') {
                indent++;
                continue;
            }
            if (current == '\t') {
                indent += 4;
                continue;
            }
            break;
        }
        return indent;
    }




    /*
    前置步骤:统计行频次
    统计每条规范化逻辑行在文档中出现的次数。
 * <p>
 * 这一步主要服务于"重复噪声识别"：
 * 如果某一短行在全文中频繁重复出现，它更可能是页眉、页脚、版本尾注或版权提示，
 * 而不是正常正文或标题。
 * </p>
 频次表后面会被 isRepeatedNoise() 使用——如果某行出现 3 次以上且长度不超过 120 字符，大概率是页眉页脚之类的噪声。
     */
    private Map<String, Integer> buildLineFrequency(List<DocumentStructureLogicalLine> logicalLines) {
        Map<String, Integer> frequency = new LinkedHashMap<>();
        for (DocumentStructureLogicalLine logicalLine : logicalLines) {
            if (logicalLine == null || StrUtil.isBlank(logicalLine.normalizedText())) {
                continue;
            }
            // 只按 normalizedText 计数，避免同一行因为前后空格差异被算成不同内容。
            frequency.merge(logicalLine.normalizedText(), 1, Integer::sum);
        }
        return frequency;
    }



    /*
    前置步骤：构建行上下文
    为当前逻辑行构造局部上下文。
 * <p>
 * 这里会向前找到最近一个非空行，向后找到最近一个非空行，
 * 同时记录当前行前后是否跨过空行。
 * 这些上下文信息会直接影响：
 * 1. 纯文本标题判定；
 * 2. 单级编号是否更像列表项；
 * 3. 上一行是否在引入一个列表。
 * </p>
     */
    private LineContext buildContext(List<DocumentStructureLogicalLine> logicalLines, int currentIndex) {
        DocumentStructureLogicalLine previousNonBlank = null;
        boolean blankBefore = false;
        for (int index = currentIndex - 1; index >= 0; index--) {
            DocumentStructureLogicalLine candidate = logicalLines.get(index);
            if (StrUtil.isBlank(candidate.normalizedText())) {
                // 只要中间跨过空行，就记住"前面有空行"，后面标题判定会依赖它。
                blankBefore = true;
                continue;
            }
            previousNonBlank = candidate;
            break;
        }
        DocumentStructureLogicalLine nextNonBlank = null;
        boolean blankAfter = false;
        for (int index = currentIndex + 1; index < logicalLines.size(); index++) {
            DocumentStructureLogicalLine candidate = logicalLines.get(index);
            if (StrUtil.isBlank(candidate.normalizedText())) {
                blankAfter = true;
                continue;
            }
            nextNonBlank = candidate;
            break;
        }
        return new LineContext(previousNonBlank, nextNonBlank, blankBefore, blankAfter);
    }

    private boolean isRepeatedNoise(String documentTitle,
                                    String normalized,
                                    int frequency) {
        if (frequency < 2 || StrUtil.isBlank(normalized)) {
            return false;
        }
        if (sameDocumentTitle(documentTitle, normalized)) {
            return true;
        }
        if (COPYRIGHT_NOISE_PATTERN.matcher(normalized).matches()) {
            return true;
        }
        return frequency >= 3
            && normalized.length() <= 120
            && (VERSION_FOOTER_PATTERN.matcher(normalized).matches() || normalized.contains("|"));
    }

    //辅助方法：文档标题重复检测
    /*
    判断某一行文本是否与文档标题本质相同。
 * <p>
 * 用于识别这种常见情况：
 * 文档名叫"部署手册"，正文第一行又重复写了一遍"部署手册"。
 * 这种行不应再被当成一个真实 section 节点，否则会多出一层无意义标题。
 （比较之前会先做标准化处理：去掉 Markdown # 前缀、去掉文件扩展名后缀、去掉所有空格、转小写。这样 ## 部署手册 和 部署手册.pdf 都能被识别为同一个标题。）
 * </p>
     */
    private boolean sameDocumentTitle(String documentTitle,
                                      String candidate) {
        String left = normalizeComparableTitle(documentTitle);
        String right = normalizeComparableTitle(candidate);
        return StrUtil.isNotBlank(left) && left.equals(right);
    }

    private String normalizeComparableTitle(String text) {
        String normalized = safeText(text);
        if (normalized.isBlank()) {
            return "";
        }
        return normalized
            .replaceAll("^#+\\s*", "")
            .replaceAll("\\.[A-Za-z0-9]{1,6}$", "")
            .replaceAll("\\s+", "")
            .toLowerCase(Locale.ROOT);
    }


    /*
    辅助方法：列表引导行检测
    判断上一条非空行是否在"引出一个列表"。
 * <p>
 * 典型特征是以中文或英文冒号结尾，例如"包含以下内容："。
 * 如果当前编号行前面正好出现这种引导句，那么当前行更可能是列表项，而不是章节标题。
 * </p>
 逻辑很简单：如果上一行以冒号结尾（如"系统要求如下："），那后面跟着的编号行大概率是列表项。
     */
    private boolean previousIntroducesList(DocumentStructureLogicalLine previousNonBlank) {
        if (previousNonBlank == null) {
            return false;
        }
        String previous = safeText(previousNonBlank.normalizedText());
        return previous.endsWith("：") || previous.endsWith(":");
    }

    /*
    辅助方法：邻居序列检测
    判断当前编号是否与前后相邻编号形成有序序列。
 * <p>
 * 这是区分"标题"与"列表项"的关键特征之一：
 * 如果当前是 2，前面是 1 或后面是 3，那么它更像有序列表项；
 * 如果找不到相邻序列证据，则它更有机会被当作标题候选。
 * </p>
 举个例子：
 如果当前行是 2、安装依赖，前一行是 1、下载源码，那 isNeighborSequence 返回 true，当前行就会被判定为列表项而不是标题。这个方法支持两种编号族：ARABIC_SINGLE（阿拉伯数字）和 CHINESE_OUTLINE（中文大纲）。
     */
    private boolean isNeighborSequence(Integer itemIndex,
                                       OrderedMarkerFamily family,
                                       LineContext context) {
        if (itemIndex == null || family == null) {
            return false;
        }
        // 只要前一个是 N-1 或后一个是 N+1，就认为当前编号处于一个连续序列中。
        return isSequenceNeighbor(context.previousNonBlank(), itemIndex, family, -1)
            || isSequenceNeighbor(context.nextNonBlank(), itemIndex, family, 1);
    }

    private boolean isSequenceNeighbor(DocumentStructureLogicalLine candidate,
                                       Integer itemIndex,
                                       OrderedMarkerFamily family,
                                       int offset) {
        if (candidate == null || itemIndex == null) {
            return false;
        }
        Integer candidateIndex = resolveOrderedIndex(candidate.normalizedText(), family);
        return candidateIndex != null && candidateIndex.intValue() == itemIndex.intValue() + offset;
    }

    private Integer resolveOrderedIndex(String text,
                                        OrderedMarkerFamily family) {
        String normalized = safeText(text);
        if (normalized.isBlank()) {
            return null;
        }
        return switch (family) {
            case ARABIC_SINGLE -> {
                Matcher matcher = SINGLE_LEVEL_DIGIT_PATTERN.matcher(normalized);
                yield matcher.matches() ? parseLooseNumber(matcher.group(1)) : null;
            }
            case CHINESE_OUTLINE -> {
                Matcher matcher = CHINESE_OUTLINE_PATTERN.matcher(normalized);
                yield matcher.matches() ? parseLooseNumber(matcher.group(1)) : null;
            }
        };
    }


    /*
    辅助方法：宽松数字解析
    宽松解析数字编号。
 * <p>
 * 这里既支持阿拉伯数字，也支持常见中文数字表达，
 * 例如："3" → 3、"十" → 10、"十二" → 12、"二十" → 20、"二十三" → 23
 * </p>
 * <p>
 * 它主要用于章节编号、步骤编号、中文大纲编号的统一数值化，
 * 让后续邻居序列判断和 numericPath 计算都能在整数层面进行。
 * </p>


 这个方法在整个信号提取阶段被大量使用——步骤编号、章节编号、中文大纲编号、邻居序列判断都要先把文本编号转成整数。它覆盖了 1~99 范围内的中文数字表达，对于文档结构编号来说完全够用。
     */
    private Integer parseLooseNumber(String text) {
        String normalized = safeText(text);
        if (normalized.isBlank()) {
            return null;
        }
        // 纯数字时直接走最简单路径。
        if (normalized.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(normalized);
        }
        Map<Character, Integer> digitMap = Map.of(
            '一', 1, '二', 2, '三', 3, '四', 4, '五', 5,
            '六', 6, '七', 7, '八', 8, '九', 9
        );
        // 下面按最常见的中文十位表达做有限支持，覆盖结构编号里高频出现的形式。
        if ("十".equals(normalized)) {
            return 10;
        }
        if (normalized.startsWith("十") && normalized.length() == 2) {
            return 10 + digitMap.getOrDefault(normalized.charAt(1), 0);
        }
        if (normalized.endsWith("十") && normalized.length() == 2) {
            return digitMap.getOrDefault(normalized.charAt(0), 0) * 10;
        }
        if (normalized.contains("十") && normalized.length() == 3) {
            return digitMap.getOrDefault(normalized.charAt(0), 0) * 10 + digitMap.getOrDefault(normalized.charAt(2), 0);
        }
        // 兜底处理"单个中文数字"。
        return digitMap.get(normalized.charAt(0));
    }

    private String safeText(String text) {
        return text == null ? "" : text.trim();
    }

    private enum OrderedMarkerFamily {
        ARABIC_SINGLE,
        CHINESE_OUTLINE
    }


    /*
    LineContext是一个record（**特殊类**，用来**快速定义纯数据载体类**，只存数据、不写业务逻辑，替代以前写大量模板代码的 POJO / 数据类。）
    包含四个字段：
    前一个非空行、后一个非空行、前面是否有空行、后面是否有空行。这些信息在后面的 looksLikePlainHeading()、isNeighborSequence()、previousIntroducesList() 中都会用到。
     */
    private record LineContext(
        DocumentStructureLogicalLine previousNonBlank,
        DocumentStructureLogicalLine nextNonBlank,
        boolean blankBefore,
        boolean blankAfter
    ) {
    }
}
