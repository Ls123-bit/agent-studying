package org.javaup.ai.chatagent.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 配置属性
 * @author: 阿星不是程序员
 **/
/**
 * 聊天侧 RAG 编排配置。
 *
 * <p>这些配置都属于“产品层编排参数”，
 * 不是底层向量库连接参数，因此统一挂在 {@code app.chat.rag} 下管理。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.chat.rag")
public class ChatRagProperties {

    /**
     * 是否开启聊天侧 RAG 编排。
     */
    private boolean enabled = true;

    /**
     * 是否启用问题改写和子问题拆分。
     */
    private boolean rewriteEnabled = true;

    /**
     * 改写阶段最多回看的历史轮数。
     */
    private int rewriteHistoryTurns = 4;

    /**
     * 改写阶段的独立模型参数。
     */
    private RewriteOptionsProperties rewriteOptions = new RewriteOptionsProperties();

    /**
     * 单次最多拆出的子问题数量。
     */
    private int maxSubQuestions = 4;

    /**
     * 向量检索候选数。
     */
    private int vectorTopK = 8;

    /**
     * 关键词检索候选数。
     */
    private int keywordTopK = 8;

    /**
     * 多通道合并后，在进入精排前最多保留多少候选。
     */
    private int candidateTopK = 10;

    /**
     * 最终送进 Prompt 的证据数量。
     */
    private int finalTopK = 5;

    /**
     * 向量候选最小相似度。
     */
    private double minVectorSimilarity = 0.45D;

    /**
     * 关键词通道相对分数下限。
     *
     * <p>当关键词分数跨度较大时，
     * 只保留接近 top score 的候选，避免弱命中一起混入。</p>
     */
    private double keywordRelativeScoreFloor = 0.35D;

    /**
     * Parent 证据块允许保留的最大字符数。
     */
    private int parentEvidenceMaxChars = 2200;

    /**
     * 编排阶段历史上下文最大字符数。
     */
    private int planningHistoryMaxChars = 1600;

    /**
     * 回答阶段历史上下文最大字符数。
     */
    private int answerHistoryMaxChars = 1000;

    /**
     * 回答阶段全部证据的总字符预算。
     */
    private int totalEvidenceMaxChars = 5200;

    /**
     * 单个子问题最多允许占用的证据字符预算。
     */
    private int perSubQuestionEvidenceMaxChars = 2200;

    /**
     * 单个检索通道超时时间。
     */
    private long channelTimeoutMs = 5000L;

    /**
     * 单个子问题整体检索超时时间。
     */
    private long subQuestionTimeoutMs = 12000L;

    /**
     * 是否启用关键词检索通道。
     */
    private boolean keywordChannelEnabled = true;

    /**
     * 是否启用重排序。
     */
    private boolean rerankEnabled = true;

    /**
     * 没有任何证据时直接返回的兜底文案。
     */
    private String noEvidenceReply = "当前没有从已接入文档中检索到足够证据，暂时不能给出可靠结论。";

    /**
     * RAG 回答阶段系统提示词。
     */
    private String answerSystemPrompt = "";

    /**
     * 会话长期摘要压缩配置。
     */
    private HistorySummaryProperties historySummary = new HistorySummaryProperties();

    /**
     * 外部 Rerank 配置。
     */
    private RerankProperties rerank = new RerankProperties();

    @Data
    public static class HistorySummaryProperties {

        /**
         * 是否启用生产级会话长期摘要。
         */
        private boolean enabled = true;

        /**
         * 最近多少轮保留原文，不进入长期摘要。
         */
        private int keepRecentTurns = 4;

        /**
         * 单次增量压缩最多处理多少轮。
         */
        private int compressionBatchTurns = 6;

        /**
         * 最近原文窗口最大字符数。
         */
        private int recentTranscriptMaxChars = 2200;

        /**
         * 长期摘要文本最大字符数。
         */
        private int summaryMaxChars = 1400;
    }

    @Data
    public static class RewriteOptionsProperties {

        /**
         * 是否为 rewrite 阶段单独覆盖模型参数。
         */
        private boolean enabled = true;

        /**
         * rewrite 阶段温度。
         */
        private Double temperature = 0.1D;

        /**
         * rewrite 阶段 topP。
         */
        private Double topP = 0.3D;

        /**
         * rewrite 阶段是否开启 thinking。
         *
         * <p>当前通过 OpenAI 兼容协议的 extraBody 透传给下游模型。</p>
         */
        private Boolean thinking = Boolean.FALSE;
    }

    @Data
    public static class RerankProperties {

        /**
         * 是否启用 HTTP Rerank。
         */
        private boolean enabled = false;

        /**
         * 外部 Rerank 服务地址。
         */
        private String url = "https://api.siliconflow.cn/v1/rerank";

        /**
         * API Key。
         */
        private String apiKey;

        /**
         * Rerank 模型名。
         */
        private String model = "BAAI/bge-reranker-v2-m3";

        /**
         * 单次精排保留的结果数。
         */
        private int topN = 5;

        /**
         * HTTP 连接超时时间。
         */
        private int connectTimeoutMs = 3000;

        /**
         * HTTP 读取超时时间。
         */
        private int readTimeoutMs = 6000;
    }
}
