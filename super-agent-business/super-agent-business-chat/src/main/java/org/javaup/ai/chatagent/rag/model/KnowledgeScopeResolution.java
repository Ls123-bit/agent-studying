package org.javaup.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识域解析结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeScopeResolution {

    /**
     * 是否需要先做澄清。
     */
    private boolean clarifyRequired;

    /**
     * 澄清提示语。
     */
    private String clarifyPrompt;

    /**
     * 知识域候选项。
     */
    @Builder.Default
    private List<KnowledgeScopeOption> options = new ArrayList<>();

    /**
     * 真正用于检索的文档主键。
     */
    @Builder.Default
    private List<Long> selectedDocumentIds = new ArrayList<>();

    /**
     * 与 selectedDocumentIds 对应的有效任务。
     */
    @Builder.Default
    private List<Long> selectedTaskIds = new ArrayList<>();
}
