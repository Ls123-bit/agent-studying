package org.javaup.ai.chatagent.model;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一引用来源模型。
 *
 * <p>这个对象不再只服务于网页搜索结果，而是统一承载：</p>
 * <p>1. 文档切片引用。</p>
 * <p>2. 联网搜索引用。</p>
 * <p>3. 未来可能接入的工具 / MCP 动态数据引用。</p>
 *
 * <p>之所以继续沿用 {@code SearchReference} 这个类名，而不是直接新起一个完全不同的名字，
 * 是为了尽量减少现有会话归档、SSE 事件、前端展示和 JSON 反序列化的改动范围。</p>
 */
@Data
@NoArgsConstructor
public class SearchReference {

    /**
     * 引用唯一编号。
     *
     * <p>RAG Prompt 会用这个编号来显式标注证据，例如 [1]、[2]。</p>
     */
    private String referenceId;

    /**
     * 来源类型。
     *
     * <p>当前约定值：WEB / DOCUMENT / TOOL。</p>
     */
    private String sourceType;

    private String title;

    private String url;

    private String snippet;

    /**
     * 文档主键。
     *
     * <p>只有 DOCUMENT 类型会使用到这个字段。</p>
     */
    private Long documentId;

    /**
     * 文档名称。
     */
    private String documentName;

    /**
     * 文档切块主键。
     */
    private Long chunkId;

    /**
     * 父块主键。
     *
     * <p>当系统进入 Parent-Child 检索结构后，
     * 最终回答阶段真正引用的往往是 parent block，
     * 而不是最初命中的 child chunk。</p>
     */
    private Long parentBlockId;

    /**
     * 父块序号。
     */
    private Integer parentBlockNo;

    /**
     * 切块序号。
     */
    private Integer chunkNo;

    /**
     * 章节路径。
     */
    private String sectionPath;

    /**
     * 页码范围。
     */
    private String pageNo;

    /**
     * 检索得分。
     */
    private Double score;

    /**
     * 子问题下标。
     */
    private Integer subQuestionIndex;

    /**
     * 子问题文本。
     */
    private String subQuestion;

    /**
     * 命中的检索通道。
     *
     * <p>例如：vector / keyword / hybrid / web-search。</p>
     */
    private String channel;

    /**
     * 实际使用的工具名。
     *
     * <p>例如 Tavily 等外部工具会写入这里。</p>
     */
    private String toolName;

    /**
     * 业务知识域编码。
     */
    private String knowledgeScopeCode;

    /**
     * 业务知识域名称。
     */
    private String knowledgeScopeName;

    /**
     * 保留旧代码最常用的三参构造，避免现有网页搜索链路大面积改动。
     */
    public SearchReference(String title, String url, String snippet) {
        this.sourceType = "WEB";
        this.title = title;
        this.url = url;
        this.snippet = snippet;
        this.channel = "web-search";
        this.toolName = "tavily_search";
    }

    /**
     * 统一生成引用去重键。
     *
     * <p>网页引用优先按 URL 去重，文档引用优先按 chunkId 去重；
     * 如果两者都没有，再退回标题 + 摘要兜底。</p>
     */
    public String uniqueKey() {
        if (parentBlockId != null) {
            return "PARENT:" + parentBlockId;
        }
        if (chunkId != null) {
            return "DOCUMENT:" + chunkId;
        }
        if (url != null && !url.isBlank()) {
            return "WEB:" + url;
        }
        return (sourceType == null ? "UNKNOWN" : sourceType)
            + ":" + (title == null ? "" : title)
            + ":" + (snippet == null ? "" : snippet);
    }
}
