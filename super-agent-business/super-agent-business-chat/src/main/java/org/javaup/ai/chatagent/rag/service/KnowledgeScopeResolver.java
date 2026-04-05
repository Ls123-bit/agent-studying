package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.KnowledgeScopeOption;
import org.javaup.ai.chatagent.rag.model.KnowledgeScopeResolution;
import org.javaup.ai.manage.model.KnowledgeDocumentDescriptor;
import org.javaup.ai.manage.service.DocumentKnowledgeService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 知识域解析服务。
 *
 * <p>当前实现不做完整的意图树和节点配置中心，
 * 而是走一个更轻量、也更贴合当前项目的方案：
 * 直接基于文档主表上的知识域字段，对问题做命中和收缩。</p>
 */
@Service
public class KnowledgeScopeResolver {

    private static final List<String> AMBIGUOUS_HINTS = List.of(
        "系统", "流程", "这个", "那个", "入口", "在哪", "怎么走", "怎么用", "怎么配", "支持吗"
    );
    private static final List<String> GENERIC_SCOPE_SUFFIXES = List.of(
        "业务系统", "管理系统", "服务平台", "管理平台", "工作台", "子系统", "客户端", "服务端",
        "系统", "平台", "中心", "模块", "服务", "门户", "应用"
    );

    private final DocumentKnowledgeService documentKnowledgeService;
    private final ChatRagProperties properties;

    public KnowledgeScopeResolver(DocumentKnowledgeService documentKnowledgeService,
                                  ChatRagProperties properties) {
        this.documentKnowledgeService = documentKnowledgeService;
        this.properties = properties;
    }

    /**
     * 解析当前问题应该落到哪些知识域上。
     */
    public KnowledgeScopeResolution resolve(String rewrittenQuestion, String historySummary) {
        return resolve(rewrittenQuestion, historySummary, documentKnowledgeService.listRetrievableDocuments());
    }

    public KnowledgeScopeResolution resolve(String rewrittenQuestion,
                                            String historySummary,
                                            List<KnowledgeDocumentDescriptor> descriptors) {
        /*
         * 先取出当前系统里所有“索引可用”的文档目录。
         * 没有目录就没法做知识域收缩，也没必要继续往后算。
         */
        if (CollUtil.isEmpty(descriptors)) {
            return KnowledgeScopeResolution.builder().build();
        }

        /*
         * 先按 knowledge scope 聚成候选项。
         * 这一步把“文档粒度”提升成“知识域粒度”，后面澄清和收缩都围绕这个层级进行。
         */
        List<KnowledgeScopeOption> options = groupScopes(descriptors);
        if (options.size() <= 1) {
            /*
             * 如果系统里天然只有一个知识域，或者当前有效文档最终只聚成了一个候选，
             * 那就不需要再做歧义判断，直接把它选为检索范围。
             */
            return selectOptions(options);
        }

        /*
         * 有多个知识域候选时，先根据“改写后的问题 + 历史摘要”做轻量打分。
         * 只要命中明确，就直接收缩到高分候选，不再追问。
         */
        List<KnowledgeScopeOption> matchedOptions = scoreOptions(options, rewrittenQuestion, historySummary);
        if (!matchedOptions.isEmpty()) {
            return selectOptions(matchedOptions);
        }

        if (properties.isClarifyEnabled() && seemsAmbiguous(rewrittenQuestion)) {
            /*
             * 到这里说明：当前确实存在多个知识域候选，但问题本身又模糊，无法自动收缩。
             * 这种场景下，继续检索大概率会答偏，所以直接生成澄清提示更稳。
             */
            List<KnowledgeScopeOption> clarifyOptions = options.stream()
                .limit(properties.getClarifyMaxOptions())
                .toList();
            return KnowledgeScopeResolution.builder()
                .clarifyRequired(true)
                .clarifyPrompt(buildClarifyPrompt(clarifyOptions))
                .options(clarifyOptions)
                .build();
        }

        /*
         * 如果既没有明显命中，也不满足“必须澄清”的条件，
         * 当前策略是保守地把所有候选都纳入检索范围，后续再由检索结果自己做区分。
         */
        return selectOptions(options);
    }

    /**
     * 以知识域为单位聚合同一批文档。
     */
    private List<KnowledgeScopeOption> groupScopes(List<KnowledgeDocumentDescriptor> descriptors) {
        Map<String, KnowledgeScopeOption> grouped = new LinkedHashMap<>();
        for (KnowledgeDocumentDescriptor descriptor : descriptors) {
            /*
             * 文档没有填知识域时，也不能直接丢掉。
             * 这里会退回到一个“以文档自身为 scope”的兜底编码，保证每份文档仍然可参与检索。
             */
            String scopeCode = StrUtil.blankToDefault(StrUtil.trim(descriptor.getKnowledgeScopeCode()), "DOC-" + descriptor.getDocumentId());
            String scopeName = StrUtil.blankToDefault(StrUtil.trim(descriptor.getKnowledgeScopeName()), descriptor.getDocumentName());
            KnowledgeScopeOption option = grouped.computeIfAbsent(scopeCode, key -> new KnowledgeScopeOption(
                scopeCode,
                scopeName,
                new ArrayList<>(),
                new ArrayList<>(),
                0D,
                new ArrayList<>()
            ));
            option.getDocumentIds().add(descriptor.getDocumentId());
            option.getTaskIds().add(descriptor.getLastIndexTaskId());
            option.getDocumentNames().add(descriptor.getDocumentName());
        }
        return new ArrayList<>(grouped.values());
    }

    /**
     * 根据问题内容给候选知识域打分。
     */
    private List<KnowledgeScopeOption> scoreOptions(List<KnowledgeScopeOption> options,
                                                    String rewrittenQuestion,
                                                    String historySummary) {
        /*
         * 这里把“当前问题”和“历史上下文”拆开评分，而不是像旧实现那样先拼成一段再统一 contains。
         * 这样可以做到：
         * - 当前问题是主证据，权重大
         * - 历史上下文只是补充线索，权重小
         * 从而减少“历史里碰巧提到过某系统，当前问题却在问另一个系统”的误导。
         */
        String normalizedQuestion = normalize(rewrittenQuestion);
        String normalizedHistory = normalize(historySummary);
        List<KnowledgeScopeOption> matched = new ArrayList<>();
        for (KnowledgeScopeOption option : options) {
            double score = scoreOption(option, normalizedQuestion, normalizedHistory);
            if (score <= 0D) {
                continue;
            }
            matched.add(new KnowledgeScopeOption(
                option.getScopeCode(),
                option.getScopeName(),
                option.getDocumentIds(),
                option.getTaskIds(),
                score,
                option.getDocumentNames()
            ));
        }
        matched.sort((left, right) -> Double.compare(right.getScore(), left.getScore()));
        return retainHighConfidenceOptions(matched, normalizedQuestion);
    }

    private double scoreOption(KnowledgeScopeOption option, String normalizedQuestion, String normalizedHistory) {
        double score = 0D;
        /*
         * 当前打分是一个非常轻量的启发式模型：
         * - scopeName 权重最高，因为用户通常更容易直接提系统名
         * - scopeCode 次之，适合命中英文编码或简称
         * - documentName 权重最低，只作为补充命中
         */
        score += matchScore(normalizedQuestion, option.getScopeName(), 8D);
        score += matchScore(normalizedQuestion, option.getScopeCode(), 6D);
        for (String documentName : option.getDocumentNames()) {
            score += matchScore(normalizedQuestion, documentName, 3D);
        }
        /*
         * 历史上下文只作为补充证据，不应压过当前问题本身。
         * 因此这里显式降权，避免因为历史里碰巧提到过某个系统名就把本轮问题带偏。
         */
        score += matchScore(normalizedHistory, option.getScopeName(), 2.5D);
        score += matchScore(normalizedHistory, option.getScopeCode(), 1.5D);
        for (String documentName : option.getDocumentNames()) {
            score += matchScore(normalizedHistory, documentName, 0.8D);
        }
        return score;
    }

    private double matchScore(String normalizedContext, String candidate, double weight) {
        if (StrUtil.isBlank(candidate) || StrUtil.isBlank(normalizedContext)) {
            return 0D;
        }
        String normalizedCandidate = normalize(candidate);
        if (normalizedCandidate.length() < 2) {
            return 0D;
        }
        String normalizedContextCore = normalizeCore(normalizedContext);
        String normalizedCandidateCore = normalizeCore(normalizedCandidate);
        double lengthBonus = specificityBonus(normalizedCandidateCore);

        /*
         * 这里的权重顺序刻意拉得比较开：
         * - 完整相等最强
         * - 去掉“系统 / 平台 / 模块”这类泛尾词后的核心词相等次之
         * - 候选包含用户问题、或用户问题包含候选，再往下
         *
         * 目标是让“订单退款系统”优先于“订单系统”这类更宽泛的候选，
         * 同时又不至于完全丢掉文档名、英文编码等补充信息。
         */
        if (normalizedContext.equals(normalizedCandidate)) {
            return weight * 2.4D + lengthBonus;
        }
        if (normalizedContextCore.length() >= 2 && normalizedContextCore.equals(normalizedCandidateCore)) {
            return weight * 2.1D + lengthBonus;
        }

        double bestScore = 0D;
        if (normalizedCandidateCore.length() >= 2 && normalizedCandidateCore.contains(normalizedContextCore)) {
            bestScore = Math.max(bestScore, weight * (1.15D + coverageRatio(normalizedContextCore, normalizedCandidateCore) * 0.30D) + lengthBonus);
        }
        if (normalizedCandidate.contains(normalizedContext) && normalizedContext.length() >= 2) {
            bestScore = Math.max(bestScore, weight * (1.05D + coverageRatio(normalizedContext, normalizedCandidate) * 0.25D) + lengthBonus);
        }
        if (normalizedContext.contains(normalizedCandidate) && normalizedCandidate.length() >= 2) {
            bestScore = Math.max(bestScore, weight * (0.35D + coverageRatio(normalizedCandidate, normalizedContext) * 0.20D) + lengthBonus * 0.5D);
        }
        if (normalizedContextCore.contains(normalizedCandidateCore) && normalizedCandidateCore.length() >= 2) {
            bestScore = Math.max(bestScore, weight * (0.12D + coverageRatio(normalizedCandidateCore, normalizedContextCore) * 0.20D) + lengthBonus * 0.35D);
        }

        int longestCommonSubstringLength = longestCommonSubstringLength(normalizedContextCore, normalizedCandidateCore);
        if (longestCommonSubstringLength >= 2) {
            double overlapScore = weight * 0.35D
                * ((double) longestCommonSubstringLength / normalizedContextCore.length())
                * ((double) longestCommonSubstringLength / normalizedCandidateCore.length());
            bestScore = Math.max(bestScore, overlapScore);
        }
        return bestScore;
    }

    private List<KnowledgeScopeOption> retainHighConfidenceOptions(List<KnowledgeScopeOption> matched,
                                                                   String normalizedQuestion) {
        if (CollUtil.isEmpty(matched)) {
            return List.of();
        }
        double topScore = matched.get(0).getScore();
        double minAcceptedScore = normalizedQuestion.length() <= 4
            ? Math.max(1.2D, topScore * 0.55D)
            : Math.max(2.0D, topScore * 0.72D);

        /*
         * 旧实现是“只要大于 0 分就全部纳入检索范围”，
         * 这会让多个弱命中的知识域一起进入后续 RAG，最终把答案上下文冲淡。
         *
         * 现在这里先做一轮分数截断，只保留真正接近 top score 的候选簇。
         * 问题越短，阈值会更宽一点；问题越长，阈值会更严格一点。
         */
        List<KnowledgeScopeOption> retained = new ArrayList<>();
        for (KnowledgeScopeOption option : matched) {
            if (option.getScore() >= minAcceptedScore) {
                retained.add(option);
            }
        }
        return pruneCoveredOptions(retained);
    }

    private List<KnowledgeScopeOption> pruneCoveredOptions(List<KnowledgeScopeOption> retained) {
        if (CollUtil.isEmpty(retained) || retained.size() == 1) {
            return retained;
        }
        /*
         * 这一步专门处理“强候选完全覆盖弱候选”的场景。
         * 例如：
         * - 强候选：订单退款系统
         * - 弱候选：订单系统
         *
         * 如果更具体的候选已经明显更强，继续把宽泛候选保留下来只会扩大检索范围，
         * 让后面的证据召回和提示词注入都更嘈杂。
         */
        List<KnowledgeScopeOption> result = new ArrayList<>();
        for (KnowledgeScopeOption candidate : retained) {
            boolean covered = false;
            for (KnowledgeScopeOption stronger : result) {
                if (isCoveredByStronger(stronger, candidate)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                result.add(candidate);
            }
        }
        return result;
    }

    private boolean isCoveredByStronger(KnowledgeScopeOption stronger, KnowledgeScopeOption candidate) {
        if (stronger == null || candidate == null || stronger.getScore() < candidate.getScore()) {
            return false;
        }
        String strongerCore = normalizeCore(stronger.getScopeName());
        String candidateCore = normalizeCore(candidate.getScopeName());
        if (strongerCore.length() < 2 || candidateCore.length() < 2) {
            return false;
        }
        return !strongerCore.equals(candidateCore)
            && strongerCore.contains(candidateCore)
            && strongerCore.length() >= candidateCore.length() + 2
            && stronger.getScore() >= candidate.getScore() + 1.0D;
    }

    private double coverageRatio(String fragment, String text) {
        if (StrUtil.isBlank(fragment) || StrUtil.isBlank(text)) {
            return 0D;
        }
        return Math.min(1D, (double) fragment.length() / Math.max(1, text.length()));
    }

    private double specificityBonus(String normalizedCandidateCore) {
        if (StrUtil.isBlank(normalizedCandidateCore)) {
            return 0D;
        }
        return Math.min(0.6D, normalizedCandidateCore.length() * 0.06D);
    }

    private int longestCommonSubstringLength(String left, String right) {
        if (StrUtil.isBlank(left) || StrUtil.isBlank(right)) {
            return 0;
        }
        int[][] dp = new int[left.length() + 1][right.length() + 1];
        int max = 0;
        for (int leftIndex = 1; leftIndex <= left.length(); leftIndex++) {
            for (int rightIndex = 1; rightIndex <= right.length(); rightIndex++) {
                if (left.charAt(leftIndex - 1) == right.charAt(rightIndex - 1)) {
                    dp[leftIndex][rightIndex] = dp[leftIndex - 1][rightIndex - 1] + 1;
                    max = Math.max(max, dp[leftIndex][rightIndex]);
                }
            }
        }
        return max;
    }

    private String normalizeCore(String text) {
        String normalized = normalize(text);
        if (StrUtil.isBlank(normalized)) {
            return "";
        }
        /*
         * normalizeCore(...) 的目标不是生成“可展示文本”，而是生成“更适合做匹配的核心词”。
         * 因此它会把“管理系统 / 平台 / 模块 / 服务”这类泛化尾巴去掉，
         * 让候选之间的对比更聚焦到真正区分业务域的那部分关键词。
         */
        String current = normalized;
        boolean stripped;
        do {
            stripped = false;
            for (String suffix : GENERIC_SCOPE_SUFFIXES) {
                String normalizedSuffix = normalize(suffix);
                if (current.endsWith(normalizedSuffix) && current.length() - normalizedSuffix.length() >= 2) {
                    current = current.substring(0, current.length() - normalizedSuffix.length());
                    stripped = true;
                    break;
                }
            }
        }
        while (stripped);
        return current;
    }

    /**
     * 是否看起来像一个需要补充系统名的模糊问题。
     */
    private boolean seemsAmbiguous(String question) {
        if (StrUtil.isBlank(question)) {
            return true;
        }
        /*
         * 问题太短时通常缺少足够的业务指向信息，
         * 当前策略直接认为它需要用户补充上下文。
         */
        if (question.trim().length() <= 8) {
            return true;
        }
        /*
         * 这些词本身不代表具体系统，只代表一种模糊诉求。
         * 一旦命中，就优先认为问题可能需要澄清。
         */
        return AMBIGUOUS_HINTS.stream().anyMatch(question::contains);
    }

    /**
     * 构造澄清追问。
     */
    private String buildClarifyPrompt(List<KnowledgeScopeOption> options) {
        /*
         * 这里故意把候选项做成明确的序号列表，而不是一段自然语言描述。
         * 对用户来说更容易直接回复“第一个”或直接说出系统名。
         */
        StringBuilder prompt = new StringBuilder("我需要先确认你想问的是哪个业务系统，请在下面这些知识域里选一个：\n");
        for (int index = 0; index < options.size(); index++) {
            prompt.append(index + 1)
                .append(". ")
                .append(options.get(index).getScopeName())
                .append("\n");
        }
        prompt.append("\n你也可以直接补充更具体的系统名称、模块名称或业务关键词。");
        return prompt.toString().trim();
    }

    /**
     * 把命中的知识域候选项收束成真正的检索范围。
     */
    private KnowledgeScopeResolution selectOptions(List<KnowledgeScopeOption> options) {
        if (CollUtil.isEmpty(options)) {
            return KnowledgeScopeResolution.builder().build();
        }

        /*
         * 这里会把多个候选 knowledge scope 下的文档和 task 聚合成一组去重后的检索范围。
         * 后面的检索引擎不再理解“知识域候选”，只理解最终应该查哪些 documentId / taskId。
         */
        LinkedHashSet<Long> documentIds = new LinkedHashSet<>();
        LinkedHashSet<Long> taskIds = new LinkedHashSet<>();
        for (KnowledgeScopeOption option : options) {
            documentIds.addAll(option.getDocumentIds());
            taskIds.addAll(option.getTaskIds());
        }

        return KnowledgeScopeResolution.builder()
            .clarifyRequired(false)
            .options(options)
            .selectedDocumentIds(new ArrayList<>(documentIds))
            .selectedTaskIds(new ArrayList<>(taskIds))
            .build();
    }

    private String normalize(String text) {
        if (StrUtil.isBlank(text)) {
            return "";
        }
        /*
         * 这里采用最简单但很实用的归一化方式：
         * 去掉标点和空白后做 contains 匹配，避免系统名里带空格、括号、连字符导致命中失败。
         */
        return text.trim().toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\s]+", "");
    }
}
