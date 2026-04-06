package org.javaup.ai.chatagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聊天侧可选知识文档。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeDocumentOptionView {

    private String documentId;
    private String documentName;
    private String knowledgeScopeName;
    private String businessCategory;
    private String documentTags;
}
