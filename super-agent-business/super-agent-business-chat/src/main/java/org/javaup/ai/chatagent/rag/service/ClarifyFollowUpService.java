package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import org.javaup.ai.chatagent.model.ConversationExchangeView;
import org.javaup.ai.chatagent.model.debug.ChatDebugTrace;
import org.javaup.ai.chatagent.rag.model.KnowledgeScopeOption;
import org.javaup.ai.chatagent.service.ConversationArchiveStore;
import org.javaup.enums.ChatTurnStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 澄清追答解释器。
 *
 * <p>当上一轮刚向用户展示过知识域候选项时，
 * 这一层负责把用户的追答解释成“继续选择上一轮候选”还是“放弃这些候选并继续澄清”。</p>
 */
@Service
public class ClarifyFollowUpService {

    private static final Pattern PURE_DIGIT_SELECTION_PATTERN = Pattern.compile("^\\s*([1-9]\\d*)\\s*[.、。]?\\s*$");
    private static final Pattern SELECTION_WITH_DIGIT_PATTERN = Pattern.compile("^(?:请)?(?:帮我)?(?:就|选|选择|用|要|看|给我|上面的?|前面的?|刚才的?)?\\s*([1-9]\\d*)\\s*(?:个|项|条|号|份|本)?\\s*$");
    private static final Pattern ORDINAL_SELECTION_PATTERN = Pattern.compile("^(?:请)?(?:帮我)?(?:就|选|选择|用|要|看|给我|上面的?|前面的?|刚才的?)?\\s*第\\s*([一二三四五六七八九十百两零〇\\d]+)\\s*(?:个|项|条|号|份|本)?\\s*$");

    private static final Set<String> REASK_WORDS = Set.of(
        "都不是", "都不对", "都不行", "没有一个", "没一个对", "都没有", "换一个", "重新选", "再列一次", "重新给我列一下"
    );

    private static final Set<String> FOLLOW_UP_HINT_WORDS = Set.of(
        "选", "选择", "第", "上面", "前面", "刚才", "那个", "这个", "手册", "文档", "pdf", "md"
    );

    private static final List<String> FILLER_PREFIXES = List.of(
        "请", "帮我", "麻烦", "我选", "选择", "选", "就", "用", "要", "看", "给我", "上面", "前面", "刚才", "那个", "这个", "里面", "文档", "资料"
    );

    private static final List<String> GENERIC_SUFFIXES = List.of(
        "产品手册", "说明文档", "操作手册", "部署手册", "配置手册", "快速开始", "用户手册",
        "业务系统", "管理系统", "服务平台", "管理平台", "工作台", "子系统", "客户端", "服务端",
        "系统", "平台", "中心", "模块", "服务", "门户", "应用",
        "pdf", "md", "markdown", "docx", "doc", "txt"
    );

    private final ConversationArchiveStore conversationArchiveStore;

    public ClarifyFollowUpService(ConversationArchiveStore conversationArchiveStore) {
        this.conversationArchiveStore = conversationArchiveStore;
    }

    public Optional<ClarifyFollowUpDecision> resolve(String conversationId, String question) {
        /*
         * 这个入口的职责不是“重新理解整轮对话”，而是专门判断：
         * 当前用户输入是不是在承接上一轮澄清候选做选择。
         *
         * 如果判断是，就直接把它转成结构化选择结果；
         * 如果判断不是，就返回 empty，让上层按普通新问题继续编排。
         */
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(question)) {
            return Optional.empty();
        }
        List<ConversationExchangeView> recentExchanges = conversationArchiveStore.listRecentExchanges(conversationId, 6);
        if (recentExchanges == null || recentExchanges.isEmpty()) {
            return Optional.empty();
        }
        ConversationExchangeView latestClarifyExchange = findLatestPendingClarifyExchange(recentExchanges);
        if (latestClarifyExchange == null) {
            return Optional.empty();
        }

        List<KnowledgeScopeOption> options = latestClarifyExchange.getDebugTrace().getScopeOptions();
        Optional<KnowledgeScopeOption> selectedOption = matchClarifyOption(question, options);
        if (selectedOption.isPresent()) {
            /*
             * 一旦命中候选，就把“当前输入”解释成一条选择动作，
             * 真正继续回答的问题仍然是上一轮原问题，而不是用户这次回复的“1 / 第十个 / 那个手册”。
             */
            return Optional.of(ClarifyFollowUpDecision.selected(
                latestClarifyExchange.getQuestion(),
                selectedOption.get(),
                options
            ));
        }
        if (isExplicitReask(question)) {
            /*
             * “都不是 / 重新选 / 换一个”这类回复本质上不是新问题，
             * 而是对上一轮候选集的否定反馈，所以继续留在澄清态最符合用户预期。
             */
            return Optional.of(ClarifyFollowUpDecision.reask(
                latestClarifyExchange.getQuestion(),
                options,
                buildReaskPrompt(options, "这些候选里还没有命中你的意思。你可以直接回复序号、候选名称，或者补充更具体的系统名称、模块名称、协议名。")
            ));
        }
        if (looksLikeClarifyContinuation(question)) {
            /*
             * 这里处理的是“看起来像在回应上一轮澄清，但又没法唯一选中候选”的场景。
             * 例如用户只回“那个”“手册那个”“上面的”，这类输入不应该立刻当成全新问题，
             * 否则路由层很容易再次给出一个泛化的追问，用户体验会断层。
             */
            return Optional.of(ClarifyFollowUpDecision.reask(
                latestClarifyExchange.getQuestion(),
                options,
                buildReaskPrompt(options, "我还在等你选择上一轮候选。可以直接回复序号、候选名称，或者补充更具体关键词。")
            ));
        }
        return Optional.empty();
    }

    private ConversationExchangeView findLatestPendingClarifyExchange(List<ConversationExchangeView> recentExchanges) {
        if (recentExchanges == null || recentExchanges.isEmpty()) {
            return null;
        }
        for (int index = recentExchanges.size() - 1; index >= 0; index--) {
            ConversationExchangeView exchange = recentExchanges.get(index);
            if (exchange == null || exchange.getStatus() == null) {
                continue;
            }
            /*
             * 当前轮用户回复在 prepare(...) 之前已经先落了一条 RUNNING 记录，
             * 这里必须显式跳过它，否则会把真正上一轮待澄清状态挤掉。
             */
            if (exchange.getStatus() == ChatTurnStatus.RUNNING) {
                continue;
            }
            /*
             * 这里一旦遇到最近一条“稳定轮次”不是 CLARIFY，就立即停止向前追溯。
             * 这是一个很重要的边界控制：
             * 我们只承认“紧邻当前轮的那次澄清”仍然处于待选择状态，
             * 不会把几轮之前的旧澄清误当成当前上下文，避免用户已经切换话题时被旧候选污染。
             */
            return isPendingClarifyExchange(exchange) ? exchange : null;
        }
        return null;
    }

    private boolean isPendingClarifyExchange(ConversationExchangeView exchange) {
        if (exchange == null || exchange.getDebugTrace() == null) {
            return false;
        }
        ChatDebugTrace debugTrace = exchange.getDebugTrace();
        return "CLARIFY".equalsIgnoreCase(StrUtil.blankToDefault(debugTrace.getRouteType(), ""))
            && debugTrace.getScopeOptions() != null
            && !debugTrace.getScopeOptions().isEmpty();
    }

    private Optional<KnowledgeScopeOption> matchClarifyOption(String question, List<KnowledgeScopeOption> scopeOptions) {
        if (scopeOptions == null || scopeOptions.isEmpty()) {
            return Optional.empty();
        }
        /*
         * effectiveSuffixes 是“静态后缀表 + 当前候选动态公共后缀”的并集。
         *
         * 这样做的原因是：
         * 1. 静态表负责处理那些跨领域都很稳定的泛词，例如“系统 / 平台 / 手册 / pdf”
         * 2. 动态后缀负责适配这轮候选里临时出现的新公共尾词，例如“接入指引 / 兼容清单”
         *
         * 两者结合后，既不完全依赖手工维护，也不完全依赖动态猜测，稳健性会更好。
         */
        List<String> effectiveSuffixes = resolveEffectiveSuffixes(scopeOptions);
        Integer numericSelection = parseSelectionIndex(question);
        if (numericSelection != null && numericSelection >= 1 && numericSelection <= scopeOptions.size()) {
            return Optional.of(scopeOptions.get(numericSelection - 1));
        }

        String normalizedQuestion = normalize(question);
        String coreQuestion = normalizeCore(question, effectiveSuffixes);
        List<CandidateMatch> matches = new ArrayList<>();
        for (int index = 0; index < scopeOptions.size(); index++) {
            KnowledgeScopeOption option = scopeOptions.get(index);
            double score = scoreOptionSelection(normalizedQuestion, coreQuestion, option, effectiveSuffixes);
            if (score > 0D) {
                matches.add(new CandidateMatch(option, score, index));
            }
        }
        matches.sort((left, right) -> {
            int scoreCompare = Double.compare(right.score(), left.score());
            if (scoreCompare != 0) {
                return scoreCompare;
            }
            return Integer.compare(left.index(), right.index());
        });
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        if (matches.size() == 1) {
            return Optional.of(matches.get(0).option());
        }
        /*
         * 多个候选同时命中时，不是简单取分数最高那个，而是要求第一名和第二名至少拉开一个可信差值。
         * 这样可以避免“那个手册”刚好同时蹭到两个候选时，被系统草率地选中错误目标。
         * 一旦差距不够，就回退成 REASK，让用户再明确一点。
         */
        if (matches.get(0).score() >= matches.get(1).score() + 1.0D) {
            return Optional.of(matches.get(0).option());
        }
        return Optional.empty();
    }

    private double scoreOptionSelection(String normalizedQuestion,
                                        String coreQuestion,
                                        KnowledgeScopeOption option,
                                        List<String> effectiveSuffixes) {
        double bestScore = 0D;
        /*
         * 这里不是做“语义检索”，而是在上一轮候选已经确定的前提下，
         * 尽量把用户这次的短回复和候选别名做一个低成本、高确定性的对齐。
         *
         * 因此评分顺序刻意设计成：
         * - 完整别名相等：最高优先级
         * - 核心词相等：次高优先级
         * - “候选包含用户短语” / “用户短语包含候选” / “核心词子串包含”：逐级降权
         *
         * 这样既能支持“产品手册.pdf”这种完整选择，也能支持“那个手册”“接入指引那个”这类口语化表达。
         */
        for (String alias : aliases(option, effectiveSuffixes)) {
            String normalizedAlias = normalize(alias);
            String coreAlias = normalizeCore(alias, effectiveSuffixes);
            if (StrUtil.isBlank(normalizedAlias)) {
                continue;
            }
            if (normalizedQuestion.equals(normalizedAlias)) {
                bestScore = Math.max(bestScore, 12D);
            }
            if (StrUtil.isNotBlank(coreQuestion) && coreQuestion.equals(coreAlias)) {
                bestScore = Math.max(bestScore, 10D);
            }
            if (StrUtil.isNotBlank(coreQuestion) && coreAlias.contains(coreQuestion) && coreQuestion.length() >= 2) {
                bestScore = Math.max(bestScore, 7D + coverageRatio(coreQuestion, coreAlias));
            }
            if (normalizedAlias.contains(normalizedQuestion) && normalizedQuestion.length() >= 2) {
                bestScore = Math.max(bestScore, 6D + coverageRatio(normalizedQuestion, normalizedAlias));
            }
            if (normalizedQuestion.contains(normalizedAlias) && normalizedAlias.length() >= 2) {
                bestScore = Math.max(bestScore, 4D + coverageRatio(normalizedAlias, normalizedQuestion));
            }
            if (StrUtil.isNotBlank(coreQuestion) && coreQuestion.contains(coreAlias) && coreAlias.length() >= 2) {
                bestScore = Math.max(bestScore, 3D + coverageRatio(coreAlias, coreQuestion));
            }
        }
        return bestScore;
    }

    private List<String> aliases(KnowledgeScopeOption option, List<String> effectiveSuffixes) {
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        if (option == null) {
            return List.of();
        }
        addAlias(aliases, option.getScopeName());
        addAlias(aliases, normalizeCore(option.getScopeName(), effectiveSuffixes));
        if (option.getDocumentNames() != null) {
            for (String documentName : option.getDocumentNames()) {
                addAlias(aliases, documentName);
                addAlias(aliases, normalizeCore(documentName, effectiveSuffixes));
            }
        }
        return new ArrayList<>(aliases);
    }

    private void addAlias(Set<String> aliases, String alias) {
        if (StrUtil.isBlank(alias)) {
            return;
        }
        String normalized = normalize(alias);
        if (normalized.length() >= 2) {
            aliases.add(alias);
        }
    }

    private Integer parseSelectionIndex(String question) {
        Matcher pureDigitMatcher = PURE_DIGIT_SELECTION_PATTERN.matcher(question);
        if (pureDigitMatcher.matches()) {
            return Integer.parseInt(pureDigitMatcher.group(1));
        }
        Matcher digitMatcher = SELECTION_WITH_DIGIT_PATTERN.matcher(normalizeLoose(question));
        if (digitMatcher.matches()) {
            return Integer.parseInt(digitMatcher.group(1));
        }
        Matcher ordinalMatcher = ORDINAL_SELECTION_PATTERN.matcher(normalizeLoose(question));
        if (ordinalMatcher.matches()) {
            return parseChineseOrArabicNumber(ordinalMatcher.group(1));
        }
        return null;
    }

    private Integer parseChineseOrArabicNumber(String raw) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        if (raw.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(raw);
        }
        return parseChineseNumber(raw);
    }

    private Integer parseChineseNumber(String raw) {
        String normalized = raw.replace("兩", "两").replace("零", "〇");
        Map<Character, Integer> digits = Map.ofEntries(
            Map.entry('〇', 0),
            Map.entry('一', 1),
            Map.entry('二', 2),
            Map.entry('两', 2),
            Map.entry('三', 3),
            Map.entry('四', 4),
            Map.entry('五', 5),
            Map.entry('六', 6),
            Map.entry('七', 7),
            Map.entry('八', 8),
            Map.entry('九', 9)
        );
        if ("十".equals(normalized)) {
            return 10;
        }
        if (normalized.contains("百")) {
            String[] parts = normalized.split("百", -1);
            int hundreds = parseSimpleChineseDigit(parts[0], digits);
            if (hundreds < 0) {
                return null;
            }
            int remainder = parseChineseNumber(parts.length > 1 ? parts[1] : "");
            return hundreds * 100 + remainder;
        }
        if (normalized.contains("十")) {
            String[] parts = normalized.split("十", -1);
            int tens = StrUtil.isBlank(parts[0]) ? 1 : parseSimpleChineseDigit(parts[0], digits);
            if (tens < 0) {
                return null;
            }
            int ones = StrUtil.isBlank(parts.length > 1 ? parts[1] : "") ? 0 : parseSimpleChineseDigit(parts[1], digits);
            if (ones < 0) {
                return null;
            }
            return tens * 10 + ones;
        }
        return parseSimpleChineseDigit(normalized, digits);
    }

    private int parseSimpleChineseDigit(String raw, Map<Character, Integer> digits) {
        if (StrUtil.isBlank(raw)) {
            return 0;
        }
        if (raw.length() == 1 && digits.containsKey(raw.charAt(0))) {
            return digits.get(raw.charAt(0));
        }
        return -1;
    }

    private boolean isExplicitReask(String question) {
        String normalized = normalize(question);
        return REASK_WORDS.contains(normalized);
    }

    private boolean looksLikeClarifyContinuation(String question) {
        String normalized = normalize(question);
        if (StrUtil.isBlank(normalized)) {
            return false;
        }
        if (normalized.length() <= 6) {
            return true;
        }
        return FOLLOW_UP_HINT_WORDS.stream().anyMatch(normalized::contains);
    }

    private String buildReaskPrompt(List<KnowledgeScopeOption> options, String tailHint) {
        StringBuilder prompt = new StringBuilder("我还在等待你确认上一轮的知识域选择，请从下面这些候选里选一个：\n");
        for (int index = 0; index < options.size(); index++) {
            prompt.append(index + 1)
                .append(". ")
                .append(options.get(index).getScopeName())
                .append('\n');
        }
        prompt.append('\n').append(tailHint);
        return prompt.toString().trim();
    }

    private double coverageRatio(String fragment, String text) {
        if (StrUtil.isBlank(fragment) || StrUtil.isBlank(text)) {
            return 0D;
        }
        return Math.min(1D, (double) fragment.length() / Math.max(1, text.length()));
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\s]+", "");
    }

    private String normalizeLoose(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    List<String> resolveEffectiveSuffixes(List<KnowledgeScopeOption> scopeOptions) {
        LinkedHashSet<String> suffixes = new LinkedHashSet<>();
        for (String suffix : GENERIC_SUFFIXES) {
            suffixes.add(normalize(suffix));
        }
        suffixes.addAll(resolveDynamicSuffixes(scopeOptions));
        /*
         * 后缀按长度倒序排序非常关键：
         * “用户手册” 应该先于 “手册” 被裁掉，
         * “管理系统” 应该先于 “系统” 被裁掉。
         *
         * 否则短后缀先命中，会让更具体的公共尾词失效，导致 normalizeCore(...) 把核心词截坏。
         */
        return suffixes.stream()
            .filter(StrUtil::isNotBlank)
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();
    }

    private List<String> resolveDynamicSuffixes(List<KnowledgeScopeOption> scopeOptions) {
        List<String> normalizedAliases = collectNormalizedAliases(scopeOptions);
        if (normalizedAliases.size() < 2) {
            return List.of();
        }
        Map<String, Integer> occurrenceMap = new LinkedHashMap<>();
        for (int leftIndex = 0; leftIndex < normalizedAliases.size(); leftIndex++) {
            for (int rightIndex = leftIndex + 1; rightIndex < normalizedAliases.size(); rightIndex++) {
                String left = normalizedAliases.get(leftIndex);
                String right = normalizedAliases.get(rightIndex);
                String commonSuffix = longestCommonSuffix(left, right);
                if (!isUsableDynamicSuffix(commonSuffix, left, right)) {
                    continue;
                }
                /*
                 * 这里不直接按出现次数打分，而是先把所有“候选对之间共享的后缀”收集出来。
                 * 后面再统一校验“至少被多少个候选真正共享”，是为了避免两两比较时的偶发巧合
                 * 直接把一个不稳定的后缀错误提拔成全局规则。
                 */
                occurrenceMap.merge(commonSuffix, 1, Integer::sum);
            }
        }
        return occurrenceMap.keySet().stream()
            .filter(candidate -> normalizedAliases.stream().filter(alias -> alias.endsWith(candidate)).count() >= 2)
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();
    }

    private List<String> collectNormalizedAliases(List<KnowledgeScopeOption> scopeOptions) {
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        if (scopeOptions == null) {
            return List.of();
        }
        for (KnowledgeScopeOption option : scopeOptions) {
            addNormalizedAlias(aliases, option == null ? null : option.getScopeName());
            if (option != null && option.getDocumentNames() != null) {
                for (String documentName : option.getDocumentNames()) {
                    addNormalizedAlias(aliases, documentName);
                }
            }
        }
        return new ArrayList<>(aliases);
    }

    private void addNormalizedAlias(Set<String> aliases, String alias) {
        String normalized = normalize(alias);
        if (normalized.length() >= 4) {
            aliases.add(normalized);
        }
    }

    private boolean isUsableDynamicSuffix(String suffix, String left, String right) {
        if (StrUtil.isBlank(suffix) || suffix.length() < 2 || suffix.length() > 8) {
            return false;
        }
        if (GENERIC_SUFFIXES.stream().map(this::normalize).anyMatch(suffix::equals)) {
            return false;
        }
        return left.length() - suffix.length() >= 2
            && right.length() - suffix.length() >= 2;
    }

    private String longestCommonSuffix(String left, String right) {
        if (StrUtil.isBlank(left) || StrUtil.isBlank(right)) {
            return "";
        }
        /*
         * 这里故意求“最长公共后缀”而不是最长公共子串。
         * 因为我们想识别的是“接入指引 / 产品手册 / 管理系统”这种命名尾部模式，
         * 而不是名称中间偶然重复的一段词。
         */
        int leftIndex = left.length() - 1;
        int rightIndex = right.length() - 1;
        StringBuilder builder = new StringBuilder();
        while (leftIndex >= 0 && rightIndex >= 0 && left.charAt(leftIndex) == right.charAt(rightIndex)) {
            builder.append(left.charAt(leftIndex));
            leftIndex--;
            rightIndex--;
        }
        return builder.reverse().toString();
    }

    private String normalizeCore(String text) {
        return normalizeCore(text, resolveEffectiveSuffixes(List.of()));
    }

    private String normalizeCore(String text, List<String> effectiveSuffixes) {
        String current = normalize(text);
        if (StrUtil.isBlank(current)) {
            return "";
        }
        boolean stripped;
        do {
            stripped = false;
            for (String normalizedSuffix : effectiveSuffixes) {
                if (current.endsWith(normalizedSuffix) && current.length() - normalizedSuffix.length() >= 2) {
                    current = current.substring(0, current.length() - normalizedSuffix.length());
                    stripped = true;
                    break;
                }
            }
        }
        while (stripped);
        /*
         * 前缀裁剪只处理“我选 / 那个 / 上面 / 文档 / 资料”这类口语填充词，
         * 目的是把“那个手册”“上面的第十个”还原成更接近候选核心词的表达。
         *
         * 这里不做激进裁剪，必须保证裁掉前缀后仍然至少剩 2 个字符，
         * 避免把真正业务关键字也一起误删。
         */
        for (String prefix : FILLER_PREFIXES) {
            String normalizedPrefix = normalize(prefix);
            if (current.startsWith(normalizedPrefix) && current.length() - normalizedPrefix.length() >= 2) {
                current = current.substring(normalizedPrefix.length());
            }
        }
        return current;
    }

    public record ClarifyFollowUpDecision(
        ClarifyFollowUpAction action,
        String originalQuestion,
        KnowledgeScopeOption selectedOption,
        List<KnowledgeScopeOption> scopeOptions,
        String clarifyPrompt
    ) {

        public static ClarifyFollowUpDecision selected(String originalQuestion,
                                                       KnowledgeScopeOption selectedOption,
                                                       List<KnowledgeScopeOption> scopeOptions) {
            return new ClarifyFollowUpDecision(
                ClarifyFollowUpAction.SELECTED,
                originalQuestion,
                selectedOption,
                scopeOptions,
                ""
            );
        }

        public static ClarifyFollowUpDecision reask(String originalQuestion,
                                                    List<KnowledgeScopeOption> scopeOptions,
                                                    String clarifyPrompt) {
            return new ClarifyFollowUpDecision(
                ClarifyFollowUpAction.REASK,
                originalQuestion,
                null,
                scopeOptions,
                clarifyPrompt
            );
        }
    }

    public enum ClarifyFollowUpAction {
        SELECTED,
        REASK
    }

    private record CandidateMatch(
        KnowledgeScopeOption option,
        double score,
        int index
    ) {
    }
}
