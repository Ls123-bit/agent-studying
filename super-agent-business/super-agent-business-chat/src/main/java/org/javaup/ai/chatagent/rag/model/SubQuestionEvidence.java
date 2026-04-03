package org.javaup.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.javaup.ai.chatagent.model.SearchReference;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 单个子问题的证据容器。
 *
 * <p>保留“子问题 -> 文档证据”的边界，
 * 是这次 RAG 改造的关键点之一。
 * Prompt 装配阶段可以据此明确要求模型逐题回答，
 * 同时每条引用也能回溯到它属于哪个子问题。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubQuestionEvidence {

    /**
     * 子问题下标，从 1 开始。
     */
    private int subQuestionIndex;

    /**
     * 子问题文本。
     */
    private String subQuestion;

    /**
     * 最终保留下来的证据文档。
     */
    private List<Document> documents;

    /**
     * 对应的引用对象。
     */
    private List<SearchReference> references;
}
