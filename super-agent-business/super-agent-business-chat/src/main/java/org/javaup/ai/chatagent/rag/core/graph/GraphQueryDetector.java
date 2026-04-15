package org.javaup.ai.chatagent.rag.core.graph;

import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 图查询模式检测器。
 *
 * <p>基于改写后的问题文本，用正则模式判断是否适合走图查询路径。
 * 这是一个纯规则组件，不依赖 LLM、不依赖导航引擎、不依赖意图分类结果。</p>
 *
 * <p>设计原则：图查询的触发由两个独立信号的交集决定：</p>
 * <ol>
 *   <li>本检测器判断问题模式（正则）</li>
 *   <li>意图分类提供目标章节（软路由）</li>
 * </ol>
 * <p>两者都满足才走图查询，否则降级到 RAG_CHAT。</p>
 */
@Component
public class GraphQueryDetector {

    /**
     * 图查询类型。
     */
    public enum GraphQueryType {
        /** 不走图查询，走正常 RAG 检索。 */
        NONE,
        /** 章节邻接查询："上一节/下一节/属于哪个章节"。 */
        SECTION_ADJACENCY,
        /** 章节子节点查询："包含哪些章节/子章节列表"。 */
        SECTION_CHILDREN,
        /** 步骤/条目精确引用："第五步是什么"。 */
        ITEM_REFERENCE,
        /** 步骤/条目搜索："哪一步要求修改密码"。 */
        ITEM_SEARCH
    }

    // ── 章节邻接模式 ──
    private static final Pattern ADJACENCY_PATTERN = Pattern.compile(
            "上一[节章]|下一[节章]|前一[节章]|后一[节章]|属于哪个章节|属于哪一章|它的上一|它的下一");

    // ── 章节子节点模式 ──
    private static final Pattern CHILDREN_PATTERN = Pattern.compile(
            "包含哪些章节|有哪些子章节|有哪些小节|章节列表|都有哪些章节|包含哪些内容");

    // ── 步骤/条目精确引用模式：第N步/条/项/个 ──
    private static final Pattern ITEM_REF_PATTERN = Pattern.compile(
            "第\\s*[一二三四五六七八九十百零0-9]+\\s*[步条项个点]");

    // ── 步骤/条目搜索模式：哪一步/条/项 ──
    private static final Pattern ITEM_SEARCH_PATTERN = Pattern.compile(
            "哪一?[步条项]");

    // ── 从问题中提取步骤编号 ──
    private static final Pattern ITEM_INDEX_PATTERN = Pattern.compile(
            "第\\s*([一二三四五六七八九十百零0-9]+)\\s*[步条项个点]");

    private static final List<String> CN_NUMBERS = List.of(
            "零", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十");

    /**
     * 检测问题是否适合走图查询路径。
     */
    public GraphQueryType detect(String rewrittenQuestion) {
        if (StrUtil.isBlank(rewrittenQuestion)) {
            return GraphQueryType.NONE;
        }
        String q = rewrittenQuestion.trim();

        if (ADJACENCY_PATTERN.matcher(q).find()) {
            return GraphQueryType.SECTION_ADJACENCY;
        }
        if (CHILDREN_PATTERN.matcher(q).find()) {
            return GraphQueryType.SECTION_CHILDREN;
        }
        if (ITEM_SEARCH_PATTERN.matcher(q).find()) {
            return GraphQueryType.ITEM_SEARCH;
        }
        if (ITEM_REF_PATTERN.matcher(q).find()) {
            return GraphQueryType.ITEM_REFERENCE;
        }
        return GraphQueryType.NONE;
    }

    /**
     * 从问题中提取步骤编号（如"第五步" → 5）。
     *
     * @return 步骤编号，未找到返回 null
     */
    public Integer extractItemIndex(String question) {
        if (StrUtil.isBlank(question)) {
            return null;
        }
        Matcher matcher = ITEM_INDEX_PATTERN.matcher(question.trim());
        if (!matcher.find()) {
            return null;
        }
        String numStr = matcher.group(1).trim();
        // 先尝试阿拉伯数字
        try {
            return Integer.parseInt(numStr);
        } catch (NumberFormatException ignored) {
        }
        // 中文数字转换（简单处理1-10）
        int idx = CN_NUMBERS.indexOf(numStr);
        if (idx > 0) {
            return idx;
        }
        return null;
    }

    /**
     * 从问题中提取搜索关键词（"哪一步要求修改密码" → "修改密码"）。
     */
    public String extractItemSearchKeyword(String question) {
        if (StrUtil.isBlank(question)) {
            return null;
        }
        Matcher matcher = ITEM_SEARCH_PATTERN.matcher(question.trim());
        if (!matcher.find()) {
            return null;
        }
        // 取匹配位置之后的文本作为关键词
        String after = question.substring(matcher.end()).trim();
        // 去掉常见的疑问词尾
        after = after.replaceAll("[？?。]$", "").trim();
        return StrUtil.isBlank(after) ? null : after;
    }
}
