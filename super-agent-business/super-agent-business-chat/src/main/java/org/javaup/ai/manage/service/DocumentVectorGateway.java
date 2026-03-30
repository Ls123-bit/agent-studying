package org.javaup.ai.manage.service;

import org.javaup.ai.manage.data.SuperAgentDocumentChunk;

import java.util.List;

/**
 * 文档向量网关。
 *
 * <p>第一期先把“切块 -> 向量化 -> 回写状态”这条链路抽象成统一接口，
 * 后续如果替换成 Milvus、PGVector 或 ES，只需要更换实现类即可。</p>
 */
public interface DocumentVectorGateway {

    /**
     * 执行向量化。
     */
    void vectorize(List<SuperAgentDocumentChunk> chunkList);
}
