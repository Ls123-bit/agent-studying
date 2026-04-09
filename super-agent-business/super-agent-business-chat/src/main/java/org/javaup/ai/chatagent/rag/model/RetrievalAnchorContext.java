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
     * 供规划和调试使用的软章节提示。
     *
     * <p>这类提示表达的是“更可能相关的章节/目录词”，
     * 会用于日志、调试轨迹以及必要时的软提示补强，
     * 但默认不应直接充当硬过滤条件。</p>
     */
    @Builder.Default
    private List<String> softSectionHints = new ArrayList<>();

    /**
     * 供检索层真正执行硬过滤时使用的可信章节提示。
     *
     * <p>只有程序可验证、语义明确的章节编码或标题提示，才应该进入这组字段。</p>
     */
    @Builder.Default
    private List<String> strictSectionHints = new ArrayList<>();

    /**
     * 兼容旧代码最常用的章节提示读取口径。
     *
     * <p>当前默认返回 strictSectionHints，目的是让旧的检索过滤逻辑自动转向“只消费硬过滤提示”。</p>
     */
    public List<String> getSectionHints() {
        return strictSectionHints == null ? List.of() : strictSectionHints;
    }

    public boolean isEmpty() {
        return !anchorApplied
            && (resolvedQuestion == null || resolvedQuestion.isBlank())
            && (softSectionHints == null || softSectionHints.isEmpty())
            && (strictSectionHints == null || strictSectionHints.isEmpty())
            && queryContextHints.isEmpty();
    }
}
