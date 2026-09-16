package org.javaup.ai.manage.support;

import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 支撑组件
 * @author: 阿星不是程序员
 **/

@Component
public class DocumentLineClassifier {

    private static final Pattern MARKDOWN_HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern MULTI_LEVEL_DIGIT_HEADING_PATTERN = Pattern.compile("^(\\d+(?:\\.\\d+)+)\\s*[、.]?\\s*(.+)$");
    private static final Pattern SINGLE_LEVEL_DIGIT_LINE_PATTERN = Pattern.compile("^(\\d+)\\s*[、.]\\s*(.+)$");
    private static final Pattern CHINESE_CHAPTER_PATTERN = Pattern.compile("^(第[一二三四五六七八九十百\\d]+[章节条部分])\\s*(.+)$");
    private static final Pattern CHINESE_OUTLINE_PATTERN = Pattern.compile("^([一二三四五六七八九十百]+)[、.]\\s*(.+)$");
    private static final Pattern APPENDIX_PATTERN = Pattern.compile("^(附录\\s*[A-Za-z一二三四五六七八九十百\\d]+)(?:\\s+(.+))?$");
    private static final Pattern EXPLICIT_STEP_PATTERN = Pattern.compile("^(?:第\\s*([0-9一二三四五六七八九十百]+)\\s*步|步骤\\s*([0-9一二三四五六七八九十百]+))\\s*[:：、.]?\\s*(.+)$");




    //兜底行分类器
    /*
    对单行文本做轻量级结构分类。
 * <p>
 * 判定顺序遵循“高确定性规则优先，模糊规则后置”的原则：
 * Markdown 标题、附录、显式步骤、中文章节、多级数字编号等模式优先；
 * 单级数字编号和中文大纲这种有歧义的模式，则进一步看内容是否像标题；
 * 都不命中时回退成 BODY。
 * </p>


 DocumentLineClassifier 和 DocumentStructureSignalExtractor 的区别在于：它不依赖上下文（前后行、行频次），只看当前这一行的文本形态做判断。所以它更像一个"轻量级兜底分类器"——在主分类器的 15 条规则都没命中时，再用它做最后一次尝试。
它内部的 looksLikeHeadingContent() 也是一个启发式判断：文本不超过 24 个字符、不以句末标点结尾、不包含中文逗号/分号/句号/冒号，就认为更像标题内容。
     */
    public LineClassification classify(String line) {
        String normalized = safeText(line);
        if (normalized.isBlank()) {
            return new LineClassification(LineKind.BODY, 0, normalized, normalized);
        }

        Matcher markdownMatcher = MARKDOWN_HEADING_PATTERN.matcher(normalized);
        if (markdownMatcher.matches()) {
            // Markdown 标题是最明确的 heading 形态，# 数量直接对应层级。
            int level = markdownMatcher.group(1).length();
            return heading(level, markdownMatcher.group(2).trim(), normalized);
        }

        Matcher appendixMatcher = APPENDIX_PATTERN.matcher(normalized);
        if (appendixMatcher.matches()) {
            // 附录天然视为一级标题。
            return heading(1, normalized, normalized);
        }

        Matcher explicitStepMatcher = EXPLICIT_STEP_PATTERN.matcher(normalized);
        if (explicitStepMatcher.matches()) {
            // “步骤1 / 第一步” 这类模式优先视为列表项或步骤项，而不是章节标题。
            return listItem(normalized);
        }

        Matcher chapterMatcher = CHINESE_CHAPTER_PATTERN.matcher(normalized);
        if (chapterMatcher.matches()) {
            // 中文章节标题通常是较高层级标题，这里先统一给出 heading 判定。
            return heading(2, normalized, normalized);
        }

        Matcher multiLevelDigitMatcher = MULTI_LEVEL_DIGIT_HEADING_PATTERN.matcher(normalized);
        if (multiLevelDigitMatcher.matches()) {
            // 1.2 / 2.3.4 这种多级编号，大概率就是标题层级结构。
            String prefix = multiLevelDigitMatcher.group(1);
            return heading(prefix.split("\\.").length, normalized, normalized);
        }

        Matcher chineseOutlineMatcher = CHINESE_OUTLINE_PATTERN.matcher(normalized);
        if (chineseOutlineMatcher.matches()) {
            String content = chineseOutlineMatcher.group(2).trim();
            // “一、xxx” 既可能是标题也可能是列表项，因此只做一层启发式判断。
            if (looksLikeHeadingContent(content)) {
                return heading(1, normalized, normalized);
            }
            return listItem(normalized);
        }

        Matcher singleLevelDigitMatcher = SINGLE_LEVEL_DIGIT_LINE_PATTERN.matcher(normalized);
        if (singleLevelDigitMatcher.matches()) {
            String content = singleLevelDigitMatcher.group(2).trim();
            // “1、xxx / 2. xxx” 同样存在标题/列表歧义，借助内容形态做轻量判断。
            if (looksLikeHeadingContent(content)) {
                return heading(1, normalized, normalized);
            }
            return listItem(normalized);
        }

        if (normalized.startsWith("- ")
            || normalized.startsWith("* ")
            || normalized.startsWith("+ ")
            || normalized.startsWith("- [")
            || normalized.startsWith("* [")
            || normalized.startsWith("+ [")) {
            // 常见无序列表和任务列表标记，统一视为列表项。
            return listItem(normalized);
        }

        // 其余情况统一作为正文。
        return new LineClassification(LineKind.BODY, 0, normalized, normalized);
    }

    private LineClassification heading(int level, String title, String rawText) {
        return new LineClassification(LineKind.HEADING, Math.max(level, 1), safeText(title), safeText(rawText));
    }

    private LineClassification listItem(String rawText) {
        return new LineClassification(LineKind.LIST_ITEM, 0, safeText(rawText), safeText(rawText));
    }

    private boolean looksLikeHeadingContent(String content) {
        String normalized = safeText(content);
        if (normalized.isBlank()) {
            return false;
        }

        if (endsWithSentencePunctuation(normalized)) {
            return false;
        }
        if (normalized.length() > 24) {
            return false;
        }
        return !normalized.contains("，")
            && !normalized.contains("；")
            && !normalized.contains("。")
            && !normalized.contains("：");
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

    private String safeText(String text) {
        return text == null ? "" : text.trim();
    }

    public enum LineKind {
        HEADING,
        LIST_ITEM,
        BODY
    }

    public record LineClassification(
        LineKind kind,
        int level,
        String title,
        String rawText
    ) {
        public boolean isHeading() {
            return kind == LineKind.HEADING;
        }

        public boolean isListItem() {
            return kind == LineKind.LIST_ITEM;
        }
    }
}
