package org.javaup.ai.chatagent.support;

import cn.hutool.core.util.StrUtil;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 支撑组件
 * @author: 阿星不是程序员
 **/
/**
 * 时效性问题识别与绝对日期增强工具。
 *
 * <p>这个类专门解决一类很常见、但也很容易答错的问题：</p>
 * <p>用户问的是“今天”“现在”“当前”“最新”“本周”“本月”“今年”这类相对时间问题，
 * 但模型在总结搜索结果时，可能把网页里出现的旧日期误当成“今天”。</p>
 *
 * <p>因此这里统一做三类判断：</p>
 * <p>1. 当前问题是不是带有相对时间语义，需要用“当前绝对日期”来解释；</p>
 * <p>2. 当前问题是不是明显依赖最新外部事实，应该优先联网搜索；</p>
 * <p>3. 如果需要搜索，真正发给搜索引擎的 query 是否要追加绝对日期。</p>
 *
 * <p>这样修复的就不只是天气，而是统一覆盖：</p>
 * <p>- 今天北京限号</p>
 * <p>- 当前美元汇率</p>
 * <p>- 最新金价</p>
 * <p>- 本周票房</p>
 * <p>- 今年油价</p>
 */
public final class TimeSensitiveQueryHelper {

    private static final Pattern EXPLICIT_DATE_PATTERN = Pattern.compile(
        "(\\d{4}[-/.年]\\d{1,2}[-/.月]\\d{1,2}日?)|(\\d{1,2}月\\d{1,2}日)"
    );

    private static final List<String> RELATIVE_TIME_KEYWORDS = List.of(
        "今天", "今日", "明天", "明日", "昨天", "昨日", "后天", "前天",
        "现在", "当前", "目前", "此刻", "实时", "最新", "刚刚",
        "本周", "这周", "本月", "这个月", "今年", "本年度", "本季度",
        "周几", "星期几", "几号", "日期", "几月几号"
    );

    private static final List<String> FRESH_INFORMATION_KEYWORDS = List.of(
        "天气", "气温", "温度", "降雨", "下雨", "下雪", "空气质量", "aqi",
        "限号", "限行", "尾号限行",
        "汇率", "金价", "黄金价格", "银价", "油价",
        "股价", "行情", "大盘", "指数",
        "新闻", "头条", "热搜", "热榜",
        "路况", "拥堵",
        "票房", "排片",
        "航班", "班次", "列车", "高铁", "火车", "地铁运营",
        "比分", "赛果", "赛程", "比赛结果",
        "预警", "台风"
    );

    private static final List<String> CALENDAR_KEYWORDS = List.of(
        "周几", "星期几", "几号", "日期", "几月几号", "星期", "周"
    );

    private static final List<String> HISTORICAL_HINTS = List.of(
        "历史", "过去", "去年", "前年", "上周", "上个月", "上月", "上一周",
        "上一月", "往年", "历年", "当时", "之前", "回顾", "曾经"
    );

    private TimeSensitiveQueryHelper() {
    }

    /**
     * 当前问题是否需要用“当前绝对日期”来解释相对时间。
     *
     * <p>只要命中了下面任意一种情况，就认为需要日期锚定：</p>
     * <p>1. 问题里直接出现了“今天、现在、最新、本周、本月、今年”等相对时间词；</p>
     * <p>2. 问题虽然没写“今天”，但主题本身就是明显的时效性事实，比如天气、限号、汇率、股价；</p>
     * <p>3. 问题本身就是日期/星期类提问，例如“今天是周几”。</p>
     */
    public static boolean requiresCurrentDateAnchoring(String query) {
        if (StrUtil.isBlank(query)) {
            return false;
        }
        if (hasHistoricalIntent(query) && !hasRelativeTimeReference(query) && !looksCalendarQuestion(query)) {
            return false;
        }
        return hasRelativeTimeReference(query)
            || looksCurrentInfoDomain(query)
            || looksCalendarQuestion(query);
    }

    /**
     * 当前问题是否应该优先调用联网搜索核实时效性事实。
     *
     * <p>和 {@link #requiresCurrentDateAnchoring(String)} 的区别是：</p>
     * <p>- “今天是周几”需要日期锚定，但不需要联网搜索；</p>
     * <p>- “今天北京限号”“当前美元汇率”既需要日期锚定，也应该优先联网搜索。</p>
     */
    public static boolean requiresFreshSearch(String query) {
        if (StrUtil.isBlank(query)) {
            return false;
        }
        if (hasHistoricalIntent(query) || containsExplicitDate(query)) {
            return false;
        }
        if (looksCalendarQuestion(query)) {
            return false;
        }

        String normalized = normalize(query);
        return looksCurrentInfoDomain(normalized)
            || containsAny(normalized, "最新", "实时", "当前", "现在", "目前", "刚刚");
    }

    /**
     * 给时效性搜索词补上当前绝对日期。
     *
     * <p>例如：</p>
     * <p>- “查一下北京的天气” -> “查一下北京的天气 2026-03-28 今天”</p>
     * <p>- “北京限号” -> “北京限号 2026-03-28 今天”</p>
     * <p>- “最新美元汇率” -> “最新美元汇率 2026-03-28 最新”</p>
     *
     * <p>如果用户已经明确写了日期，或者问题明显是在问历史情况，就尊重原 query，不做追加。</p>
     */
    public static String buildEffectiveSearchQuery(String query, String currentDate) {
        if (StrUtil.isBlank(query)) {
            return query;
        }

        String trimmedQuery = query.trim();
        if (StrUtil.isBlank(currentDate)) {
            return trimmedQuery;
        }
        if (!requiresCurrentDateAnchoring(trimmedQuery)) {
            return trimmedQuery;
        }
        if (containsExplicitDate(trimmedQuery) || trimmedQuery.contains(currentDate) || hasHistoricalIntent(trimmedQuery)) {
            return trimmedQuery;
        }
        return trimmedQuery + " " + currentDate + " " + deriveTemporalHint(trimmedQuery);
    }

    public static boolean containsExplicitDate(String query) {
        return StrUtil.isNotBlank(query) && EXPLICIT_DATE_PATTERN.matcher(query).find();
    }

    public static boolean hasRelativeTimeReference(String query) {
        return containsAny(normalize(query), RELATIVE_TIME_KEYWORDS);
    }

    public static boolean looksCalendarQuestion(String query) {
        return containsAny(normalize(query), CALENDAR_KEYWORDS);
    }

    public static boolean looksCurrentInfoDomain(String query) {
        return containsAny(normalize(query), FRESH_INFORMATION_KEYWORDS);
    }

    public static boolean hasHistoricalIntent(String query) {
        return containsAny(normalize(query), HISTORICAL_HINTS);
    }

    private static String deriveTemporalHint(String query) {
        String normalized = normalize(query);
        if (containsAny(normalized, "明天", "明日")) {
            return "明天";
        }
        if (containsAny(normalized, "昨天", "昨日", "前天")) {
            return "昨天";
        }
        if (containsAny(normalized, "本周", "这周")) {
            return "本周";
        }
        if (containsAny(normalized, "本月", "这个月")) {
            return "本月";
        }
        if (containsAny(normalized, "今年", "本年度", "本季度")) {
            return "今年";
        }
        if (containsAny(normalized, "最新", "实时", "当前", "现在", "目前", "刚刚")) {
            return "最新";
        }
        return "今天";
    }

    private static String normalize(String query) {
        return StrUtil.isNotBlank(query) ? query.trim().toLowerCase(Locale.ROOT) : "";
    }

    private static boolean containsAny(String query, List<String> candidates) {
        if (StrUtil.isBlank(query)) {
            return false;
        }
        for (String candidate : candidates) {
            if (query.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String query, String... candidates) {
        if (StrUtil.isBlank(query)) {
            return false;
        }
        for (String candidate : candidates) {
            if (query.contains(candidate)) {
                return true;
            }
        }
        return false;
    }
}
