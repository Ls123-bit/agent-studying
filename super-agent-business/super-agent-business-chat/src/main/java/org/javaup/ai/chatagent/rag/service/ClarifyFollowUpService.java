package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import org.javaup.ai.chatagent.model.ConversationExchangeView;
import org.javaup.ai.chatagent.model.debug.ChatDebugTrace;
import org.javaup.ai.chatagent.rag.model.KnowledgeScopeOption;
import org.javaup.ai.chatagent.service.ConversationArchiveStore;
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
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(question)) {
            return Optional.empty();
        }
        List<ConversationExchangeView> recentExchanges = conversationArchiveStore.listRecentExchanges(conversationId, 6);
        if (recentExchanges == null || recentExchanges.isEmpty()) {
            return Optional.empty();
        }
        ConversationExchangeView latestExchange = recentExchanges.get(recentExchanges.size() - 1);
        if (!isPendingClarifyExchange(latestExchange)) {
            return Optional.empty();
        }

        List<KnowledgeScopeOption> options = latestExchange.getDebugTrace().getScopeOptions();
        Optional<KnowledgeScopeOption> selectedOption = matchClarifyOption(question, options);
        if (selectedOption.isPresent()) {
            return Optional.of(ClarifyFollowUpDecision.selected(
                latestExchange.getQuestion(),
                selectedOption.get(),
                options
            ));
        }
        if (isExplicitReask(question)) {
            return Optional.of(ClarifyFollowUpDecision.reask(
                latestExchange.getQuestion(),
                options,
                buildReaskPrompt(options, "这些候选里还没有命中你的意思。你可以直接回复序号、候选名称，或者补充更具体的系统名称、模块名称、协议名。")
            ));
        }
        if (looksLikeClarifyContinuation(question)) {
            return Optional.of(ClarifyFollowUpDecision.reask(
                latestExchange.getQuestion(),
                options,
                buildReaskPrompt(options, "我还在等你选择上一轮候选。可以直接回复序号、候选名称，或者补充更具体关键词。")
            ));
        }
        return Optional.empty();
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
