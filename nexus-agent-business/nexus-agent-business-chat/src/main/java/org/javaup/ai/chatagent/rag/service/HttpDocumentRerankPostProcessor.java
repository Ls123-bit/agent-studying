package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.collection.CollectionUtil;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.support.RestClientFactorySupport;
import org.jetbrains.annotations.NotNull;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/* Http Rerank实现
这个类实现了 Spring AI 的 DocumentPostProcessor 接口，通过 HTTP 调用外部 Rerank 服务来完成重排序。
Rerank 重排序
工作方式：
1.把查询和候选文档发送到外部 Rerank API（兼容 SiliconFlow 接口）
2.API 返回每个文档的 relevance_score
3.按分数降序重排
4.给每个文档附加元数据：rerankScore、rerankModel、rerankQuery、rerankDurationMs
5.API 调用失败时降级为原始排序
 */

@Slf4j
@Component
public class HttpDocumentRerankPostProcessor implements DocumentPostProcessor {

    private final ChatRagProperties properties;
    private final RestClient restClient;
    //构造方法里创建了一个 RestClient，配置了连接超时和读取超时。这个 RestClient 只创建一次，后续每次 rerank 调用都复用它。
    public HttpDocumentRerankPostProcessor(ChatRagProperties properties) {
        // RAG 配置中包含 rerank 服务地址、模型名、apiKey、超时时间和 topN 等参数。
        this.properties = properties;
        // RestClient 在构造阶段创建一次，后续每次 rerank 复用同一个客户端配置。
        this.restClient = RestClientFactorySupport.create(
            null,
                // 连接超时控制 TCP/HTTP 建连等待时间，避免外部服务不可达时卡住检索链路。
            properties.getRerank().getConnectTimeoutMs(),
                // 读取超时控制 rerank 服务处理和返回响应的最长等待时间。
            properties.getRerank().getReadTimeoutMs()
        );
    }



    /*
    核心重排序逻辑
     */
    @NotNull
    @Override
    @SuppressWarnings("unchecked")
    public List<Document> process(@NotNull Query query, @NotNull List<Document> documents) {//调用外部 Rerank API 重新排序
        /*
        第一阶段：前置校验
        三道防线：
        1.候选为空 → 直接返回
        2.rerank 未开启 → 直接返回
        3.缺少 apiKey → 告警并返回
        topN 的计算用了双重限制：Math.min(Math.max(topN, 1), documents.size())，保证至少为 1、最多不超过候选数量。
         */
        // 候选为空时没有任何可重排内容，直接返回原列表。
        if (CollectionUtil.isEmpty(documents)) {
            return documents;
        }
        // 读取 rerank 子配置。
        ChatRagProperties.RerankProperties rerankProperties = properties.getRerank();
        // topN 需要同时满足：至少为 1，最多不超过当前候选数量。
        int topN = Math.min(Math.max(rerankProperties.getTopN(), 1), documents.size());
        // rerank 未开启时不改变原始候选顺序。
        if (!rerankProperties.isEnabled()) {
            return documents;
        }
        // rerank 已开启但缺少 apiKey 时，HTTP 调用必然失败；提前回退并输出告警。
        if (rerankProperties.getApiKey() == null || rerankProperties.getApiKey().isBlank()) {
            log.warn("Rerank 已开启但未配置 apiKey，自动回退为原始排序");
            return documents;
        }


        /*
        第二阶段：构造请求体
        为什么 return_documents 设为 false？
        因为文档内容本地已经有了（就是传入的 documents 列表），没必要让 rerank 服务再把文本传回来。
        只需要返回每个文档的下标（index）和相关性分数（relevance_score），本地根据 index 找到原始 Document 对象即可。这样可以减少网络传输量，加快响应速度。
         */
        // rerank 服务通常只需要文档文本本身；metadata 会在本地按返回 index 再映射回原 Document。
        List<String> texts = documents.stream().map(Document::getText).toList();
        // 构造 HTTP 请求体。
        Map<String, Object> body = Map.of(
            "model", rerankProperties.getModel(),//rerank 模型名称（如 bge-reranker-v2-m3）
            "query", query.text(),//当前子问题文本
            "documents", texts,//候选文档的纯文本列表
            "top_n", topN,//希望返回的最相关文档数量
            "return_documents", false//设为 false，只返回 index 和分数，不返回文档内容
        );

        long startTime = System.currentTimeMillis();
        try {
            /*
            第三阶段：发起HTTP调用
            用 Spring 的 RestClient 发起 POST 请求，认证方式是 Bearer Token。响应解析为 Map，兼容不同 rerank 服务的协议（只要求里面有 results 字段）。
             */
            // 向外部 rerank 服务发起 POST 请求。
            Map<String, Object> response = restClient.post()
                .uri(rerankProperties.getUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + rerankProperties.getApiKey())
                .body(body)
                .retrieve()
                .body(Map.class);
            long durationMs = System.currentTimeMillis() - startTime;
            // 期望响应结构：{"results":[{"index":0,"relevance_score":0.98}, ...]}
            if (response == null || !(response.get("results") instanceof List<?> resultList)) {
                log.warn("Rerank 返回结果为空或格式错误，自动回退为原始排序");
                return documents;
            }

            /*
            第四阶段：解析响应并重排  rerank 服务返回的响应格式是这样的：
                        {
              "results": [
                {"index": 2, "relevance_score": 0.98},
                {"index": 0, "relevance_score": 0.85},
                {"index": 4, "relevance_score": 0.72},
                {"index": 1, "relevance_score": 0.45},
                {"index": 3, "relevance_score": 0.31}
              ]
            }
             */
            // 将 rerank 结果按 relevance_score 降序排列，再映射回原始 Document。
            return ((List<Map<String, Object>>) resultList).stream()
                .sorted(Comparator.comparingDouble(result -> -((Number) result.get("relevance_score")).doubleValue()))
                .map(result -> {
                    int index = ((Number) result.get("index")).intValue();
                    double score = ((Number) result.get("relevance_score")).doubleValue();
                    Document document = documents.get(index);
                    // 把 rerank 相关信息写入 metadata。
                    document.getMetadata().put("rerankScore", score);
                    document.getMetadata().put("rerankModel", rerankProperties.getModel());
                    document.getMetadata().put("rerankQuery", query.text());
                    document.getMetadata().put("rerankDurationMs", durationMs);
                    document.getMetadata().put("rerankOriginalIndex", index);
                    return document;
                })
                .limit(topN)
                .collect(Collectors.toList());
        }
        /*
        第五阶段：异常兜底
        设计原则：rerank 是增强，不是硬依赖
        整个 process 方法的设计原则是：rerank 失败不能打断主检索流程。无论是网络超时、服务不可用、响应格式错误，还是任何其他异常，都会被 catch 住，
        然后回退为原始排序（也就是 RRF 融合 + 父块提升后的顺序）。这样即使 rerank 服务挂了，用户依然能得到一个还不错的检索结果。
         */
        catch (Exception exception) {
            // rerank 是可选增强环节，任何异常都不能打断主检索流程。
            long durationMs = System.currentTimeMillis() - startTime;
            log.warn("Rerank 调用失败（耗时 {} ms），自动回退为原始排序: {}", durationMs, exception.getMessage());
            return documents;
        }
    }
}
