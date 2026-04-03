package org.javaup.ai.chatagent.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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
     * 单次最多拆出的子问题数量。
     */
    private int maxSubQuestions = 4;

    /**
     * 是否启用歧义澄清。
     */
    private boolean clarifyEnabled = true;

    /**
     * 单次澄清最多展示的候选知识域数量。
     */
    private int clarifyMaxOptions = 5;

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
     * 是否启用关键词检索通道。
     */
    private boolean keywordChannelEnabled = true;

    /**
     * 是否启用重排序。
     */
    private boolean rerankEnabled = false;

    /**
     * 没有任何证据时直接返回的兜底文案。
     */
    private String noEvidenceReply = "当前没有从已接入文档中检索到足够证据，暂时不能给出可靠结论。";

    /**
     * RAG 回答阶段系统提示词。
     */
    private String answerSystemPrompt = "";

    /**
     * 外部 Rerank 配置。
     */
    private RerankProperties rerank = new RerankProperties();

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
    }
}
