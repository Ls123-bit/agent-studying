package org.javaup.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一导航内核输出。
 *
 * <p>它是当前轮文档问答中唯一可信的导航决策结果，
 * 后续检索请求构造、证据校验和调试展示都应只消费它，而不再各自重做导航判断。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentNavigationDecision {

    private ConversationIntentRelationType relationType;

    private DocumentNavigationAction navigationAction;

    private ConversationRetrievalMode retrievalMode;

    private ConversationSubjectAnchor subjectAnchor;

    private ConversationTopicAnchor topicAnchor;

    private ConversationStructureAnchor structureAnchor;

    private ConversationItemAnchor itemAnchor;

    private RetrievalQuestionPlan retrievalPlan;

    private DocumentEvidencePolicy evidencePolicy;

    private Long anchorExchangeId;

    private String anchorSourceQuestion;

    private boolean anchorApplied;

    private boolean missingRequestedStructure;

    private String summaryText;

    @Builder.Default
    private List<String> queryContextHints = new ArrayList<>();

    @Builder.Default
    private List<String> softSectionHints = new ArrayList<>();

    @Builder.Default
    private List<String> strictSectionHints = new ArrayList<>();

    @Builder.Default
    private List<String> strictCanonicalPathHints = new ArrayList<>();

    @Builder.Default
    private List<Long> strictStructureNodeIds = new ArrayList<>();

    @Builder.Default
    private List<Integer> strictItemIndexes = new ArrayList<>();

    @Builder.Default
    private List<String> notes = new ArrayList<>();
}
