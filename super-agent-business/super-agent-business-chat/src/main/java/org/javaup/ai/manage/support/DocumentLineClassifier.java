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
/**
 * 文档行级分类器。
 *
 * <p>它不直接负责切块，只负责判断单行文本更像：</p>
 * <p>1. 章节标题</p>
 * <p>2. 编号/项目列表项</p>
 * <p>3. 普通正文</p>
 *
 * <p>把这层单独抽出来的目的，是让解析阶段的标题统计和结构切块阶段
 * 共用同一套规则，避免出现“推荐时看成标题、切块时又按另一套规则处理”的偏差。</p>
 */
@Component
public class DocumentLineClassifier {

    private static final Pattern MARKDOWN_HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern MULTI_LEVEL_DIGIT_HEADING_PATTERN = Pattern.compile("^(\\d+(?:\\.\\d+)+)\\s*[、.]?\\s*(.+)$");
    private static final Pattern SINGLE_LEVEL_DIGIT_LINE_PATTERN = Pattern.compile("^(\\d+)\\s*[、.]\\s*(.+)$");
    private static final Pattern CHINESE_CHAPTER_PATTERN = Pattern.compile("^(第[一二三四五六七八九十百\\d]+[章节条部分])\\s*(.+)$");
    private static final Pattern CHINESE_OUTLINE_PATTERN = Pattern.compile("^([一二三四五六七八九十百]+)[、.]\\s*(.+)$");
    private static final Pattern APPENDIX_PATTERN = Pattern.compile("^(附录\\s*[A-Za-z一二三四五六七八九十百\\d]+)(?:\\s+(.+))?$");
    private static final Pattern EXPLICIT_STEP_PATTERN = Pattern.compile("^(?:第\\s*([0-9一二三四五六七八九十百]+)\\s*步|步骤\\s*([0-9一二三四五六七八九十百]+))\\s*[:：、.]?\\s*(.+)$");

    /**
     * 对单行文本做结构分类。
     */
    public LineClassification classify(String line) {
        String normalized = safeText(line);
        if (normalized.isBlank()) {
            return new LineClassification(LineKind.BODY, 0, normalized, normalized);
        }

        Matcher markdownMatcher = MARKDOWN_HEADING_PATTERN.matcher(normalized);
        if (markdownMatcher.matches()) {
            int level = markdownMatcher.group(1).length();
            return heading(level, markdownMatcher.group(2).trim(), normalized);
        }

        Matcher appendixMatcher = APPENDIX_PATTERN.matcher(normalized);
        if (appendixMatcher.matches()) {
            return heading(1, normalized, normalized);
        }

        Matcher explicitStepMatcher = EXPLICIT_STEP_PATTERN.matcher(normalized);
        if (explicitStepMatcher.matches()) {
            return listItem(normalized);
        }

        Matcher chapterMatcher = CHINESE_CHAPTER_PATTERN.matcher(normalized);
        if (chapterMatcher.matches()) {
            return heading(2, normalized, normalized);
        }

        Matcher multiLevelDigitMatcher = MULTI_LEVEL_DIGIT_HEADING_PATTERN.matcher(normalized);
        if (multiLevelDigitMatcher.matches()) {
            String prefix = multiLevelDigitMatcher.group(1);
            return heading(prefix.split("\\.").length, normalized, normalized);
        }

        Matcher chineseOutlineMatcher = CHINESE_OUTLINE_PATTERN.matcher(normalized);
        if (chineseOutlineMatcher.matches()) {
            String content = chineseOutlineMatcher.group(2).trim();
            if (looksLikeHeadingContent(content)) {
                return heading(1, normalized, normalized);
            }
            return listItem(normalized);
        }

        Matcher singleLevelDigitMatcher = SINGLE_LEVEL_DIGIT_LINE_PATTERN.matcher(normalized);
        if (singleLevelDigitMatcher.matches()) {
            String content = singleLevelDigitMatcher.group(2).trim();
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
            return listItem(normalized);
        }

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
        /*
         * 这里用保守启发式区分“编号标题”和“编号列表项”：
         * - 短、像标签名的文本，更可能是标题
         * - 带明显句末标点或解释语气的文本，更可能是列表项正文
         *
         * 这样“1. 编制目的”仍会识别成标题，
         * 而“1. 新知识版本切块异常。”会回落成列表项。
         */
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

    /**
     * 单行分类结果。
     */
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
