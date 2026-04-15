package org.javaup.ai.chatagent.rag.core.guidance;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.core.intent.SectionIntentNode;
import org.javaup.ai.chatagent.rag.core.intent.SectionNodeScore;
import org.javaup.ai.chatagent.rag.core.intent.SubQuestionIntent;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 章节级歧义检测服务。
 *
 * <p>对标 ragent 的 IntentGuidanceService。
 * 当意图分类发现用户问题可能指向多个不同顶级章节且得分接近时，
 * 系统不猜测，而是生成一个澄清提示让用户选择。</p>
 *
 * <p>触发条件（全部满足才触发）：</p>
 * <ol>
 *   <li>只有单个子问题</li>
 *   <li>至少有 2 个章节得分 >= intentMinScore</li>
 *   <li>第二名/第一名的分数比 >= ambiguityScoreRatio (0.8)</li>
 *   <li>这些章节属于不同的顶级章节（depth=1 的祖先不同）</li>
 *   <li>用户问题中没有直接提到章节名称</li>
 * </ol>
 */
@Slf4j
@Service
public class SectionGuidanceService {

    private final ChatRagProperties properties;

    public SectionGuidanceService(ChatRagProperties properties) {
        this.properties = properties;
    }

    /**
     * 检测是否存在章节歧义。
     */
    public GuidanceDecision detectAmbiguity(String question, List<SubQuestionIntent> subIntents) {
        if (!properties.isGuidanceEnabled()) {
            return GuidanceDecision.none();
        }
        // 只对单子问题触发
        if (subIntents == null || subIntents.size() != 1) {
            return GuidanceDecision.none();
        }

        SubQuestionIntent intent = subIntents.get(0);
        List<SectionNodeScore> scores = intent.sectionScores();
        if (scores == null || scores.size() < 2) {
            return GuidanceDecision.none();
        }

        double topScore = scores.get(0).score();
        double secondScore = scores.get(1).score();
        if (topScore <= 0) {
            return GuidanceDecision.none();
        }

        // 分数比不够接近，不算歧义
        double ratio = secondScore / topScore;
        if (ratio < properties.getGuidanceAmbiguityScoreRatio()) {
            return GuidanceDecision.none();
        }

        // 检查是否属于不同的顶级章节
        List<SectionNodeScore> ambiguousCandidates = scores.stream()
                .filter(s -> s.score() / topScore >= properties.getGuidanceAmbiguityScoreRatio())
                .toList();

        long distinctTopLevelCount = ambiguousCandidates.stream()
                .map(s -> resolveTopLevelTitle(s.node()))
                .distinct()
                .count();
        if (distinctTopLevelCount < 2) {
            return GuidanceDecision.none();
        }

        // 用户问题中已包含章节名称时跳过
        String normalizedQuestion = question.toLowerCase();
        boolean alreadyMentioned = ambiguousCandidates.stream()
                .anyMatch(s -> {
                    String title = s.node().getTitle();
                    return StrUtil.isNotBlank(title) && normalizedQuestion.contains(title.toLowerCase());
                });
        if (alreadyMentioned) {
            return GuidanceDecision.none();
        }

        // 构建澄清提示
        String prompt = buildGuidancePrompt(ambiguousCandidates);
        log.info("[歧义检测] 触发: question='{}', candidates={}",
                question,
                ambiguousCandidates.stream().map(s -> s.node().getDisplayPath()).toList());
        return GuidanceDecision.prompt(prompt);
    }

    /**
     * 向上追溯到顶级章节（depth=1）的标题。
     */
    private String resolveTopLevelTitle(SectionIntentNode node) {
        // 简单策略：用 sectionPath 的第一段作为顶级章节标识
        String path = node.getSectionPath();
        if (StrUtil.isNotBlank(path) && path.contains(">")) {
            return path.substring(0, path.indexOf(">")).trim();
        }
        return node.getTitle() != null ? node.getTitle() : String.valueOf(node.getId());
    }

    private String buildGuidancePrompt(List<SectionNodeScore> candidates) {
        StringBuilder sb = new StringBuilder("您的问题可能涉及以下章节，请问您想了解哪个？\n\n");
        int index = 1;
        for (SectionNodeScore candidate : candidates) {
            sb.append(index++).append(". ").append(candidate.node().getDisplayPath()).append('\n');
            if (index > 6) break; // 最多展示6个选项
        }
        return sb.toString();
    }
}
