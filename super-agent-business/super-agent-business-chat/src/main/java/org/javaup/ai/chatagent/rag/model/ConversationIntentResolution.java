package org.javaup.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 多轮会话关系解析结果。
 *
 * <p>它不是最终检索请求，而是“当前问题与上文关系”的结构化判断结果。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationIntentResolution {

    /**
     * 关系类型。
     */
    private ConversationIntentRelationType relationType;

    /**
     * 当前轮真正围绕的主题。
     */
    private String resolvedTopic;

    /**
     * 当前轮想问的面向，例如：现象 / 可能原因 / 处理步骤 / 检查顺序 / 观察时长。
     */
    private String resolvedFacet;

    /**
     * LLM 规划出的最终检索问题。
     *
     * <p>它应该尽量复用用户原词和文档标题词，
     * 避免使用“内容结构”这类过于抽象、对检索不友好的表述。</p>
     */
    private String retrievalQuery;

    /**
     * LLM 规划出的软章节提示。
     *
     * <p>这类提示用于帮助程序理解“更可能靠近哪些章节/目录词”，
     * 但默认不应直接充当硬过滤条件。</p>
     */
    @Builder.Default
    private List<String> softSectionHints = new ArrayList<>();

    /**
     * LLM 规划出的上下文提示词。
     */
    @Builder.Default
    private List<String> queryContextHints = new ArrayList<>();

    /**
     * 当前若在追问某个编号项，它对应的序号。
     */
    private Integer referencedItemIndex;

    /**
     * 解析置信度。
     */
    private Double confidence;

    /**
     * 调试或教学解释。
     */
    private String rationale;

    public boolean confident(double threshold) {
        return confidence != null && confidence >= threshold && relationType != null && relationType != ConversationIntentRelationType.UNKNOWN;
    }
}
