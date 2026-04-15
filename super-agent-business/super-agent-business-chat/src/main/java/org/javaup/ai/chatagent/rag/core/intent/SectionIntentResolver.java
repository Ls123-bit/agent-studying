package org.javaup.ai.chatagent.rag.core.intent;

import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.core.rewrite.RewriteResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 章节意图解析器，编排子问题的并行分类。
 *
 * <p>对标 ragent 的 IntentResolver。
 * 对每个子问题并行调用 SectionIntentClassifier，
 * 然后执行全局意图数上限控制（capTotalIntents），
 * 保证每个子问题至少保留 top-1 意图。</p>
 */
@Slf4j
@Service
public class SectionIntentResolver {

    /** 每个子问题最多保留的意图数。 */
    private static final int MAX_INTENT_PER_QUESTION = 3;

    /** 全局最大意图数。 */
    private static final int MAX_TOTAL_INTENTS = 3;

    /** 分类超时（毫秒）。 */
    private static final long CLASSIFY_TIMEOUT_MS = 8000;

    private final SectionIntentClassifier classifier;
    private final ExecutorService executorService;
    private final ChatRagProperties properties;

    public SectionIntentResolver(SectionIntentClassifier classifier,
                                 @Qualifier("chatRagExecutorService") ExecutorService executorService,
                                 ChatRagProperties properties) {
        this.classifier = classifier;
        this.executorService = executorService;
        this.properties = properties;
    }

    /**
     * 对改写结果中的每个子问题并行执行章节意图分类。
     *
     * @param documentId    文档主键
     * @param rewriteResult 改写结果（包含子问题列表）
     * @return 每个子问题的意图分类结果
     */
    public List<SubQuestionIntent> resolve(Long documentId, RewriteResult rewriteResult) {
        if (rewriteResult == null || rewriteResult.subQuestions() == null || rewriteResult.subQuestions().isEmpty()) {
            return List.of();
        }

        List<String> subQuestions = rewriteResult.subQuestions();

        // 单子问题时直接同步调用，避免线程池开销
        if (subQuestions.size() == 1) {
            List<SectionNodeScore> scores = classifyWithLimit(documentId, subQuestions.get(0));
            return List.of(new SubQuestionIntent(subQuestions.get(0), scores));
        }

        // 多子问题并行分类
        List<CompletableFuture<SubQuestionIntent>> futures = subQuestions.stream()
                .map(sq -> CompletableFuture.supplyAsync(() -> {
                    List<SectionNodeScore> scores = classifyWithLimit(documentId, sq);
                    return new SubQuestionIntent(sq, scores);
                }, executorService))
                .toList();

        List<SubQuestionIntent> results = futures.stream()
                .map(f -> {
                    try {
                        return f.get(CLASSIFY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    } catch (Exception e) {
                        log.warn("[意图解析] 子问题分类超时或失败: {}", e.getMessage());
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();

        // 全局意图数上限控制
        return capTotalIntents(results);
    }

    /**
     * 分类并限制每个子问题的意图数。
     */
    private List<SectionNodeScore> classifyWithLimit(Long documentId, String question) {
        List<SectionNodeScore> scores = classifier.classifyTargets(documentId, question);
        if (scores.size() > MAX_INTENT_PER_QUESTION) {
            return scores.subList(0, MAX_INTENT_PER_QUESTION);
        }
        return scores;
    }

    /**
     * 全局意图数上限控制。
     *
     * <p>对标 ragent 的 capTotalIntents 策略：</p>
     * <ol>
     *   <li>如果总意图数 <= MAX_TOTAL_INTENTS，直接返回</li>
     *   <li>保证每个子问题至少保留 top-1 意图</li>
     *   <li>剩余配额按全局分数降序分配</li>
     * </ol>
     */
    private List<SubQuestionIntent> capTotalIntents(List<SubQuestionIntent> subIntents) {
        int totalIntents = subIntents.stream()
                .mapToInt(si -> si.sectionScores().size())
                .sum();
        if (totalIntents <= MAX_TOTAL_INTENTS) {
            return subIntents;
        }

        // 每个子问题先保留 top-1
        List<SubQuestionIntent> capped = new ArrayList<>();
        List<ScoredEntry> remaining = new ArrayList<>();

        for (SubQuestionIntent si : subIntents) {
            if (si.sectionScores().isEmpty()) {
                capped.add(si);
                continue;
            }
            List<SectionNodeScore> kept = new ArrayList<>();
            kept.add(si.sectionScores().get(0));
            // 其余的放入候选池
            for (int i = 1; i < si.sectionScores().size(); i++) {
                remaining.add(new ScoredEntry(si.subQuestion(), si.sectionScores().get(i)));
            }
            capped.add(new SubQuestionIntent(si.subQuestion(), kept));
        }

        // 剩余配额按分数降序分配
        int usedSlots = (int) capped.stream()
                .mapToLong(si -> si.sectionScores().size())
                .sum();
        int remainingSlots = MAX_TOTAL_INTENTS - usedSlots;

        if (remainingSlots > 0 && !remaining.isEmpty()) {
            remaining.sort(Comparator.comparingDouble(e -> -e.score.score()));
            List<ScoredEntry> bonus = remaining.subList(0, Math.min(remainingSlots, remaining.size()));

            // 按子问题分组，追加到对应的 SubQuestionIntent
            Map<String, List<SectionNodeScore>> bonusByQuestion = bonus.stream()
                    .collect(Collectors.groupingBy(
                            e -> e.subQuestion,
                            Collectors.mapping(e -> e.score, Collectors.toList())));

            capped = capped.stream()
                    .map(si -> {
                        List<SectionNodeScore> extra = bonusByQuestion.get(si.subQuestion());
                        if (extra == null || extra.isEmpty()) {
                            return si;
                        }
                        List<SectionNodeScore> merged = new ArrayList<>(si.sectionScores());
                        merged.addAll(extra);
                        return new SubQuestionIntent(si.subQuestion(), merged);
                    })
                    .toList();
        }

        return capped;
    }

    private record ScoredEntry(String subQuestion, SectionNodeScore score) {}
}
