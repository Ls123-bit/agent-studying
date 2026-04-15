package org.javaup.ai.chatagent.rag.core.intent;

/**
 * 章节意图打分结果。
 *
 * <p>对标 ragent 的 NodeScore。
 * LLM 对每个候选章节节点输出一个 0.0~1.0 的相关性分数，
 * 该分数仅用于软路由（boost），不用于硬过滤（filter）。</p>
 */
public record SectionNodeScore(

        /**
         * 被打分的章节节点。
         */
        SectionIntentNode node,

        /**
         * LLM 给出的相关性分数，范围 0.0~1.0。
         */
        double score
) {
}
