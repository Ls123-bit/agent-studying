package org.javaup.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 多轮追问在检索阶段使用的锚点上下文。
 *
 * <p>它表达的不是“历史原文长什么样”，
 * 而是“为了让当前这轮检索承接上文，我们已经解析出了哪些结构化锚点”。</p>
 *
 * <p>典型锚点包括：</p>
 * <p>1. 当前对话围绕的根主题。</p>
 * <p>2. 当前追问想切到的面向，例如现象/原因/处理步骤。</p>
 * <p>3. 当前最值得优先命中的章节提示。</p>
 * <p>4. 当前若在追问某个编号条目，该条目的下标和文本。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalAnchorContext {

    /**
     * 当前问题是否被识别为承接式追问。
     */
    private boolean followUpQuestion;

    /**
     * 本轮是否真的应用了检索锚点。
     */
    private boolean anchorApplied;

    /**
     * 作为锚点来源的上一轮 exchangeId。
     */
    private Long anchorExchangeId;

    /**
     * 上一轮用于承接的明确问题。
     */
    private String anchorSourceQuestion;

    /**
     * 当前对话的根主题。
     *
     * <p>例如：检索命中率突然下降。</p>
     */
    private String rootTopic;

    /**
     * 根主题所在章节编码。
     *
     * <p>例如：14.1。</p>
     */
    private String rootSectionCode;

    /**
     * 根主题所在章节标题。
     */
    private String rootSectionTitle;

    /**
     * 当前追问面向。
     *
     * <p>例如：现象 / 可能原因 / 处理步骤。</p>
     */
    private String targetFacet;

    /**
     * 当前优先命中的章节提示。
     *
     * <p>例如：14.1.1 现象。</p>
     */
    private String targetSectionHint;

    /**
     * 当前若在追问某个编号项，它对应的序号。
     */
    private Integer referencedItemIndex;

    /**
     * 当前若在追问某个编号项，它对应的文本。
     */
    private String referencedItemText;

    /**
     * 最终用于检索的锚点改写问题。
     */
    private String resolvedQuestion;

    /**
     * 供检索层补强使用的上下文提示词。
     */
    @Builder.Default
    private List<String> queryContextHints = new ArrayList<>();

    /**
     * 供检索层过滤/boost 使用的章节提示。
     */
    @Builder.Default
    private List<String> sectionHints = new ArrayList<>();

    public boolean isEmpty() {
        return !anchorApplied
            && (resolvedQuestion == null || resolvedQuestion.isBlank())
            && sectionHints.isEmpty()
            && queryContextHints.isEmpty();
    }
}
