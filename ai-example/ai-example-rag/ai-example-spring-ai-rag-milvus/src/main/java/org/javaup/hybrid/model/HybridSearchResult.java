package org.javaup.hybrid.model;

/**
 * 混合检索的返回结果。
 * <p>
 * 和 ai 包下的 MilvusSearchResult 不同，这里额外携带了检索模式信息，
 * 方便调用方区分当前结果是来自 Dense、Sparse 还是 Hybrid 融合。
 *
 * @param id       Milvus 中的主键
 * @param content  原始文本内容
 * @param score    检索得分（Dense/Sparse 是原始分数，Hybrid 是 RRF 融合分数）
 * @param mode     本次使用的检索模式：DENSE_ONLY / SPARSE_ONLY / HYBRID
 */
public record HybridSearchResult(
        String id,
        String content,
        float score,
        String mode
) {
}
