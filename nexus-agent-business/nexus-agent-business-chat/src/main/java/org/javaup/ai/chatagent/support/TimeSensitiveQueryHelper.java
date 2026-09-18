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
/*
时间敏感性识别 关键词列表设计
在看两个方法的逻辑之前，先了解一下 TimeSensitiveQueryHelper 里定义的几组关键词列表，整个判断逻辑都是围绕这些列表展开的。
 时间敏感问题识别工具。
 * 用于判断一个问题是否需要：
 * 1. 以"当前日期"为锚点理解相对时间表达
 * 2. 优先查询最新外部事实
 * 3. 在搜索词中补充时间提示，减少把旧事实误当成今天的风险
 */
public final class TimeSensitiveQueryHelper {
    // 匹配显式日期，比如 2024-05-01、2024年5月1日、5月1日
    private static final Pattern EXPLICIT_DATE_PATTERN = Pattern.compile(
        "(\\d{4}[-/.年]\\d{1,2}[-/.月]\\d{1,2}日?)|(\\d{1,2}月\\d{1,2}日)"
    );
    // 相对时间关键词：今天、本周、最新、实时……     识别相对时间词，需要知道"今天"是哪天才能理解
    private static final List<String> RELATIVE_TIME_KEYWORDS = List.of(
        "今天", "今日", "明天", "明日", "昨天", "昨日", "后天", "前天",
        "现在", "当前", "目前", "此刻", "实时", "最新", "刚刚",
        "本周", "这周", "本月", "这个月", "今年", "本年度", "本季度",
        "周几", "星期几", "几号", "日期", "几月几号"
    );
    // 典型"需要实时数据"的领域词：天气、股价、汇率、新闻……     识别实时数据领域，必须查最新数据
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
    // 日历类关键词：周几、几号……    识别日历类问题，需要日期锚定但不一定要搜索
    private static final List<String> CALENDAR_KEYWORDS = List.of(
        "周几", "星期几", "几号", "日期", "几月几号", "星期", "周"
    );
    // 历史意图关键词：历史、去年、上周……    识别历史回顾类问题，这类问题反而不需要锚定到今天  （HISTORICAL_HINTS 是一个"排除"列表，用来避免误判。比如"去年的新闻"虽然包含"新闻"，但它是历史问题，不应该触发实时搜索。）
    private static final List<String> HISTORICAL_HINTS = List.of(
        "历史", "过去", "去年", "前年", "上周", "上个月", "上月", "上一周",
        "上一月", "往年", "历年", "当时", "之前", "回顾", "曾经"
    );

    private TimeSensitiveQueryHelper() {
    }


    /*
    需要日期锚定吗？
    这个方法判断的是：模型在回答这个问题时，是否需要知道"今天"是哪一天？
    断问题是否需要"当前日期锚定"。
    @param query 用户问题
    @return true 表示应以当前日期解释相对时间语义
    ——————————————————————————————————————————————————————————————————————————————
    整个判断分三步走
    第一步：空值检查，问题为空直接返回 false。
    第二步：排除纯历史问题。如果问题带有历史意图（比如"去年"、"上周"），同时又没有相对时间词、也不是日历类问题，那就认为这是一个纯历史回顾问题，不需要锚定到今天。
    第三步：命中任意一个条件就返回 true：
    包含相对时间词（今天、本周、最新……）
    属于实时信息领域（天气、股价、新闻……）
    是日历类问题（今天周几、几号……）

     */
    public static boolean requiresCurrentDateAnchoring(String query) {
        if (StrUtil.isBlank(query)) {
            return false;
        }
        if (hasHistoricalIntent(query) && !hasRelativeTimeReference(query) && !looksCalendarQuestion(query)) {
            // 纯历史回顾类问题不应被强行锚定到"今天"。
            return false;
        }
        // 只要命中相对时间、明显的最新信息领域，或日历类问题，就需要日期锚定。
        return hasRelativeTimeReference(query)
            || looksCurrentInfoDomain(query)
            || looksCalendarQuestion(query);
    }
/*
需要搜索最新数据吗
这个方法判断的是：系统是否应该去外部搜索最新数据，而不是依赖模型已有的知识？
和 requiresCurrentDateAnchoring 不同，这个方法更激进——它直接决定要不要触发实时搜索。
 */
    /**
     * 判断问题是否需要优先搜索最新外部事实。
     *
     * @param query 用户问题
     * @return true 表示需要新鲜事实
     */
    /*
    判断逻辑分四步：
第一步：空值检查，同上。
第二步：排除历史问题和显式日期问题。这里比 requiresCurrentDateAnchoring 多了一个排除条件——containsExplicitDate。如果用户问的是"2023年5月1日的新闻"，已经明确指定了日期，就不需要去搜"最新"数据了。
第三步：排除日历类问题。"今天周几"这类问题只需要知道当前日期就能回答，不需要触发外部搜索。
第四步：命中实时信息领域或明确的实时词汇就返回 true：
属于实时信息领域（天气、股价、新闻……）
包含"最新"、"实时"、"当前"、"现在"、"目前"、"刚刚"这些词
     */
    public static boolean requiresFreshSearch(String query) {
        if (StrUtil.isBlank(query)) {
            return false;
        }
        if (hasHistoricalIntent(query) || containsExplicitDate(query)) {
            // 历史问题或已经显式指向某个日期的问题，不一定需要"最新"搜索。
            return false;
        }
        if (looksCalendarQuestion(query)) {
            // 日历类问题更多是日期解释，不必默认触发外部最新搜索。
            return false;
        }

        String normalized = normalize(query);
        // 最新信息领域或"最新/实时/当前"等表述，都应优先搜索。
        return looksCurrentInfoDomain(normalized)
            || containsAny(normalized, "最新", "实时", "当前", "现在", "目前", "刚刚");
    }

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
/*
这个方法在 requiresFreshSearch 里作为排除条件出现：
判断文本中是否包含显式日期。
 *
 * @param query 查询文本
 * @return true 表示包含明确日期
 */
    public static boolean containsExplicitDate(String query) {
        return StrUtil.isNotBlank(query) && EXPLICIT_DATE_PATTERN.matcher(query).find();
    }

    /**
     * 判断是否包含相对时间指代。
     *
     * @param query 查询文本
     * @return true 表示命中相对时间关键词
     * 先调 normalize 把文本 trim 并转小写，再用 containsAny 逐个检查 RELATIVE_TIME_KEYWORDS 里的关键词是否出现在问题中。
     */
    public static boolean hasRelativeTimeReference(String query) {
        return containsAny(normalize(query), RELATIVE_TIME_KEYWORDS);
    }

    /**
     * 判断是否更像日历 / 日期类问题。
     *
     * @param query 查询文本
     * @return true 表示命中日历关键词
     * 专门处理"今天周几"、"几号了"这类日历查询，这类问题必须知道当前日期才能回答。
     */
    public static boolean looksCalendarQuestion(String query) {
        return containsAny(normalize(query), CALENDAR_KEYWORDS);
    }

    /**
     * 判断是否命中典型"当前信息领域"。
     *
     * @param query 查询文本
     * @return true 表示更像天气、新闻、股价等最新信息问题
     * 逻辑一样，只是换成了 FRESH_INFORMATION_KEYWORDS 列表。比如问题里有"天气"、"股价"、"汇率"，就认为这是实时信息领域，需要日期锚定。
     */
    public static boolean looksCurrentInfoDomain(String query) {
        return containsAny(normalize(query), FRESH_INFORMATION_KEYWORDS);
    }

    /**
     * 判断是否带有明显历史意图。
     *
     * @param query 查询文本
     * @return true 表示更像历史回顾问题
     * 检查问题里是否有"历史"、"去年"、"上周"等词。这个方法在两个地方都被用作"排除条件"，防止历史问题被误判为需要实时数据。
     */
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


    //上面几个方法都依赖两个底层工具：
    /*
    底层工具方法之一
    normalize 做了两件事：去掉首尾空格、转小写。转小写是为了让 "AQI" 和 "aqi" 都能命中关键词列表里的 "aqi"
    归一化查询文本。
 * @param query 原始文本
 * @return trim 且转小写后的文本
     */
    private static String normalize(String query) {
        return StrUtil.isNotBlank(query) ? query.trim().toLowerCase(Locale.ROOT) : "";
    }

    /*
    底层工具方法之二
    containsAny 就是简单的线性扫描，只要有一个关键词命中就返回 true。
    判断文本是否命中给定候选列表中的任意一个关键词。
 * @param query 查询文本
 * @param candidates 候选关键词列表
 * @return true 表示命中
     */
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
