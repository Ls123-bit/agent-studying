package org.javaup.ai.chatagent.rag.core.intent;

import java.util.List;

/**
 * 单个子问题的意图分类结果。
 *
 * <p>对标 ragent 的 SubQuestionIntent。
 * 将一个子问题与其匹配到的章节意图列表绑定在一起，
 * 供检索通道决定搜索范围和优先级。</p>
 */
public record SubQuestionIntent(

        /**
         * 子问题文本（已改写的独立问题）。
         */
        String subQuestion,

        /**
         * 该子问题匹配到的章节意图列表，按分数降序排列。
         */
        List<SectionNodeScore> sectionScores
) {

    /**
     * 是否有高置信度的章节意图。
     */
    public boolean hasConfidentIntent(double threshold) {
        return sectionScores != null
                && !sectionScores.isEmpty()
                && sectionScores.get(0).score() >= threshold;
    }

    /**
     * 获取最高分。
     */
    public double maxScore() {
        if (sectionScores == null || sectionScores.isEmpty()) {
            return 0.0;
        }
        return sectionScores.get(0).score();
    }
}
