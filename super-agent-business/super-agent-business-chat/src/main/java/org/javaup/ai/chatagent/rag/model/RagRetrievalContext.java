package org.javaup.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.javaup.ai.chatagent.model.SearchReference;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识检索上下文。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RagRetrievalContext {

    /**
     * 改写后的主问题。
     */
    private String rewrittenQuestion;

    /**
     * 子问题级证据集合。
     */
    private List<SubQuestionEvidence> subQuestionEvidenceList = new ArrayList<>();

    /**
     * 检索过程摘要，用于写入 thinking steps。
     */
    private List<String> retrievalNotes = new ArrayList<>();

    /**
     * 本轮实际使用到的检索通道。
     */
    private List<String> usedChannels = new ArrayList<>();

    public boolean isEmpty() {
        return subQuestionEvidenceList == null
            || subQuestionEvidenceList.stream().allMatch(item -> item.getReferences() == null || item.getReferences().isEmpty());
    }

    public List<SearchReference> flattenReferences() {
        if (subQuestionEvidenceList == null || subQuestionEvidenceList.isEmpty()) {
            return List.of();
        }
        List<SearchReference> references = new ArrayList<>();
        for (SubQuestionEvidence item : subQuestionEvidenceList) {
            if (item.getReferences() == null || item.getReferences().isEmpty()) {
                continue;
            }
            references.addAll(item.getReferences());
        }
        return references;
    }
}
