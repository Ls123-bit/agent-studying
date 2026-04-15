package org.javaup.ai.chatagent.rag.core.intent;

import java.util.List;

/**
 * 章节意图分类器接口。
 *
 * <p>对标 ragent 的 IntentClassifier。
 * 给定一个文档和一个问题，对文档的章节结构节点进行相关性打分。
 * 打分结果用于软路由，引导检索通道优先搜索高分章节，
 * 但不排除其他章节的检索结果。</p>
 */
public interface SectionIntentClassifier {

    /**
     * 对文档的章节节点进行相关性打分。
     *
     * @param documentId 文档主键
     * @param question   用户问题（已改写的独立问题）
     * @return 按分数降序排列的章节打分列表，仅包含分数高于最低阈值的节点
     */
    List<SectionNodeScore> classifyTargets(Long documentId, String question);
}
