package org.javaup.ai.manage.service.impl;

import lombok.AllArgsConstructor;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.manage.data.SuperAgentDocumentChunk;
import org.javaup.ai.manage.service.DocumentVectorGateway;
import org.javaup.ai.manage.support.DocumentPgVectorConstants;
import org.javaup.enums.DocumentManageCode;
import org.javaup.enums.DocumentVectorStatusEnum;
import org.javaup.enums.DocumentVectorStoreTypeEnum;
import org.javaup.exception.SuperAgentFrameException;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;


@Slf4j
@AllArgsConstructor
@Service
/*
向量化入库
 */
public class DefaultDocumentVectorGateway implements DocumentVectorGateway {

    public static final int EMBEDDING_BATCH_SIZE_LIMIT = 10;

    private static final String UPSERT_SQL_TEMPLATE = """
        INSERT INTO %s
        (id, document_id, task_id, plan_id, parent_block_id, chunk_no, source_type, section_path, structure_node_id,
         structure_node_type, canonical_path, item_index, chunk_text, char_count, token_count, embedding_model,
         metadata_json, embedding, create_time, edit_time, status)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS vector), NOW(), NOW(), ?)
        ON CONFLICT (id) DO UPDATE SET
            document_id = EXCLUDED.document_id,
            task_id = EXCLUDED.task_id,
            plan_id = EXCLUDED.plan_id,
            parent_block_id = EXCLUDED.parent_block_id,
            chunk_no = EXCLUDED.chunk_no,
            source_type = EXCLUDED.source_type,
            section_path = EXCLUDED.section_path,
            structure_node_id = EXCLUDED.structure_node_id,
            structure_node_type = EXCLUDED.structure_node_type,
            canonical_path = EXCLUDED.canonical_path,
            item_index = EXCLUDED.item_index,
            chunk_text = EXCLUDED.chunk_text,
            char_count = EXCLUDED.char_count,
            token_count = EXCLUDED.token_count,
            embedding_model = EXCLUDED.embedding_model,
            metadata_json = EXCLUDED.metadata_json,
            embedding = EXCLUDED.embedding,
            edit_time = NOW(),
            status = EXCLUDED.status
        """;

    private static final String DELETE_BY_DOCUMENT_SQL_TEMPLATE = "DELETE FROM %s WHERE document_id = ?";

    @Qualifier("documentManagePgVectorJdbcTemplate")
    private final JdbcTemplate pgVectorJdbcTemplate;

    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;

    private final ObjectMapper objectMapper;

    @Value("${spring.ai.openai.embedding.options.model:}")
    private String embeddingModelName;







    /*
    向量化入库
    实现细节：
批次大小：EMBEDDING_BATCH_SIZE_LIMIT = 10
用 Spring AI 的 EmbeddingModel 做向量化
写入时用 ON CONFLICT (id) DO UPDATE 保证幂等
元数据包含：documentId、taskId、planId、parentBlockId、chunkNo、sourceType、sectionPath、structureNodeId 等
     */

    /**
     * 批量执行 chunk 向量化，并将 embedding 写入 PGVector。
     * <p>
     * 这个方法负责把异步索引链已经落库的 child chunk 转成真正可检索的向量数据：
     * 先过滤空文本 chunk，再按固定批次调用 EmbeddingModel 生成向量，
     * 最后把 chunk 元数据和 embedding 一起 upsert 到 PGVector 表中。
     * </p>
     * <p>
     * 方法返回后，传入的 chunk 实体会被原地回填向量状态、向量 ID 和向量库类型，
     * 调用方再负责把这些状态更新回业务表。
     * </p>
     * 核心流程就三步：过滤空 chunk → 按批次调用 EmbeddingModel → 写入 PGVector。每批最多处理 10 个 chunk。
     */

    @Override
    public void vectorize(List<SuperAgentDocumentChunk> chunkList) {//批量向量化（批量向量化主方法）

        if (CollUtil.isEmpty(chunkList)) {// 没有任何 chunk 时直接返回，避免无意义地初始化模型和拼装 SQL。
            return;
        }
        // 向量化必须依赖具体的 EmbeddingModel，缺失时直接失败，避免写出半成品状态。
        EmbeddingModel embeddingModel = requireEmbeddingModel();
        // 只保留真正有文本内容的 chunk，空块没有 embedding 意义，也不应写入向量库。
        List<SuperAgentDocumentChunk> validChunkList = chunkList.stream()
            .filter(chunk -> chunk != null && StrUtil.isNotBlank(chunk.getChunkText()))
            .toList();
        if (validChunkList.isEmpty()) {// 全部都是空文本 chunk 时，向量链路不应继续执行。
            return;
        }
        // 预先计算本轮执行所需的 SQL、批大小和模型名，便于日志输出与后续 metadata 复用。
        String upsertSql = UPSERT_SQL_TEMPLATE.formatted(DocumentPgVectorConstants.EMBEDDING_TABLE_NAME);
        int batchSize = EMBEDDING_BATCH_SIZE_LIMIT;
        String currentEmbeddingModelName = resolveEmbeddingModelName();
        int totalBatchCount = (validChunkList.size() + batchSize - 1) / batchSize;

        log.info("开始执行文档向量化，chunkCount={}, batchSize={}, batchCount={}, embeddingModel={}",
            validChunkList.size(), batchSize, totalBatchCount, currentEmbeddingModelName);

        for (int startIndex = 0; startIndex < validChunkList.size(); startIndex += batchSize) {
            int endIndex = Math.min(startIndex + batchSize, validChunkList.size());
            List<SuperAgentDocumentChunk> currentBatch = validChunkList.subList(startIndex, endIndex);
            int currentBatchIndex = (startIndex / batchSize) + 1;

            log.info("开始处理 embedding 批次，batchIndex={}/{}, chunkRange=[{}, {}], currentBatchSize={}",
                currentBatchIndex, totalBatchCount, startIndex + 1, endIndex, currentBatch.size());
            // 只把 chunk 文本传给 EmbeddingModel，返回顺序必须与输入顺序严格一一对应。
            List<float[]> embeddingList = embeddingModel.embed(currentBatch.stream()
                .map(SuperAgentDocumentChunk::getChunkText)
                .toList());
            if (embeddingList.size() != currentBatch.size()) {
                throw new IllegalStateException("EmbeddingModel 返回的向量数量与 chunk 数量不一致。");
            }
            // 批量写入 PGVector，并在内存实体上标记本批次成功状态。
            batchUpsert(upsertSql, currentBatch, embeddingList, currentEmbeddingModelName);
            markSuccess(currentBatch);

            log.info("embedding 批次处理完成，batchIndex={}/{}, currentBatchSize={}",
                currentBatchIndex, totalBatchCount, currentBatch.size());
        }

        log.info("文档向量化执行完成，chunkCount={}, batchSize={}, batchCount={}, embeddingModel={}",
            validChunkList.size(), batchSize, totalBatchCount, currentEmbeddingModelName);
    }

    @Override
    public void deleteByDocumentId(Long documentId) {//按文档ID删除所有向量
        if (documentId == null) {
            return;
        }

        try {
            String deleteSql = DELETE_BY_DOCUMENT_SQL_TEMPLATE.formatted(DocumentPgVectorConstants.EMBEDDING_TABLE_NAME);
            pgVectorJdbcTemplate.update(deleteSql, documentId);
        }
        catch (Exception exception) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_VECTOR_FAILED.getCode(),
                "删除 PGVector 数据失败: " + exception.getMessage(), exception);
        }
    }



    /*
    批量写入PGVector
    这里用的是 Spring JDBC 的 batchUpdate，通过 BatchPreparedStatementSetter 实现批量写入。SQL 用的是 INSERT ... ON CONFLICT DO UPDATE（upsert），这样即使重复执行也不会报错。
     */
    private void batchUpsert(String upsertSql,
                             List<SuperAgentDocumentChunk> chunkBatch,
                             List<float[]> embeddingBatch,
                             String embeddingModelName) {
        pgVectorJdbcTemplate.batchUpdate(upsertSql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int index) throws SQLException {
                SuperAgentDocumentChunk chunk = chunkBatch.get(index);
                float[] embedding = embeddingBatch.get(index);

                // 在真正落库前，先把内存对象状态切为"向量化中"
                chunk.setVectorStatus(DocumentVectorStatusEnum.VECTORIZING.getCode());
                // metadata 会和向量一起写入 PGVector
                String metadataJson = buildMetadataJson(chunk, embeddingModelName);

                ps.setLong(1, chunk.getId());
                ps.setLong(2, chunk.getDocumentId());
                ps.setLong(3, chunk.getTaskId());
                if (chunk.getPlanId() == null) {
                    ps.setNull(4, Types.BIGINT);
                }
                else {
                    ps.setLong(4, chunk.getPlanId());
                }
                if (chunk.getParentBlockId() == null) {
                    ps.setNull(5, Types.BIGINT);
                }
                else {
                    ps.setLong(5, chunk.getParentBlockId());
                }
                ps.setInt(6, chunk.getChunkNo());
                ps.setInt(7, defaultInteger(chunk.getSourceType()));
                ps.setString(8, chunk.getSectionPath());
                if (chunk.getStructureNodeId() == null) {
                    ps.setNull(9, Types.BIGINT);
                }
                else {
                    ps.setLong(9, chunk.getStructureNodeId());
                }
                ps.setInt(10, defaultInteger(chunk.getStructureNodeType()));
                ps.setString(11, chunk.getCanonicalPath());
                ps.setInt(12, defaultInteger(chunk.getItemIndex()));
                ps.setString(13, chunk.getChunkText());
                ps.setInt(14, defaultInteger(chunk.getCharCount()));
                ps.setInt(15, defaultInteger(chunk.getTokenCount()));
                ps.setString(16, embeddingModelName);
                ps.setString(17, metadataJson);

                // PGVector JDBC 层以字符串字面量形式接收向量内容
                ps.setString(18, toVectorLiteral(embedding));
                ps.setInt(19, 1);
            }

            @Override
            public int getBatchSize() {
                return chunkBatch.size();
            }
        });
    }

    /*
    标记向量化成功

     */
    private void markSuccess(List<SuperAgentDocumentChunk> chunkBatch) {
        for (SuperAgentDocumentChunk chunk : chunkBatch) {

            // 当前实现直接以 chunk 主键作为向量记录 ID
            chunk.setVectorId(String.valueOf(chunk.getId()));
            chunk.setVectorStoreType(DocumentVectorStoreTypeEnum.PG_VECTOR.getCode());
            chunk.setVectorStatus(DocumentVectorStatusEnum.VECTOR_SUCCESS.getCode());
        }
    }

    /*
    构造元数据

     */
    private String buildMetadataJson(SuperAgentDocumentChunk chunk, String embeddingModelName) {
        Map<String, Object> metadata = new LinkedHashMap<>();

        // metadata 既服务于检索过滤，也方便后续排查向量与业务 chunk 的映射关系
        //metadata 里存了很多维度的信息，后续做向量检索时可以用这些字段做过滤条件，比如只搜某个文档的 chunk、只搜某个章节下的 chunk 等等。
        metadata.put("documentId", chunk.getDocumentId());
        metadata.put("taskId", chunk.getTaskId());
        metadata.put("planId", chunk.getPlanId());
        metadata.put("parentBlockId", chunk.getParentBlockId());
        metadata.put("chunkNo", chunk.getChunkNo());
        metadata.put("sourceType", chunk.getSourceType());
        metadata.put("sectionPath", chunk.getSectionPath());
        metadata.put("structureNodeId", chunk.getStructureNodeId());
        metadata.put("structureNodeType", chunk.getStructureNodeType());
        metadata.put("canonicalPath", chunk.getCanonicalPath());
        metadata.put("itemIndex", chunk.getItemIndex());
        metadata.put("charCount", chunk.getCharCount());
        metadata.put("tokenCount", chunk.getTokenCount());
        metadata.put("embeddingModel", embeddingModelName);
        try {
            return objectMapper.writeValueAsString(metadata);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("序列化 PGVector metadata 失败。", exception);
        }
    }

    private String toVectorLiteral(float[] embedding) {
        if (embedding == null || embedding.length == 0) {
            throw new IllegalStateException("EmbeddingModel 返回了空向量。");
        }
        StringBuilder vectorBuilder = new StringBuilder("[");
        for (int index = 0; index < embedding.length; index++) {

            if (index > 0) {
                vectorBuilder.append(",");
            }
            vectorBuilder.append(embedding[index]);
        }
        vectorBuilder.append("]");
        return vectorBuilder.toString();
    }

    private EmbeddingModel requireEmbeddingModel() {

        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            throw new IllegalStateException("当前未找到可用的 EmbeddingModel，无法执行向量化。");
        }
        return embeddingModel;
    }

    private String resolveEmbeddingModelName() {

        return StrUtil.isNotBlank(embeddingModelName)
            ? embeddingModelName
            : "default";
    }

    private int defaultInteger(Integer value) {

        return Objects.requireNonNullElse(value, 0);
    }
}
