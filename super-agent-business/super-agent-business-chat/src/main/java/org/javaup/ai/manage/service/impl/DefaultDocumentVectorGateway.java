package org.javaup.ai.manage.service.impl;

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

/**
 * 默认文档向量网关实现。
 *
 * <p>当前实现会完成以下动作：</p>
 * <p>1. 调用 Spring AI 注入的 EmbeddingModel 生成 embedding。</p>
 * <p>2. 把 embedding 与 chunk 元数据批量写入 PostgreSQL + PGVector。</p>
 * <p>3. 回写 chunk 的向量状态、向量主键和向量库存储类型。</p>
 *
 * <p>这样上层索引构建服务只关心“把 chunk 送去向量化”，
 * 不需要关心底层到底是 PGVector、Milvus 还是其他向量库。</p>
 */
@Slf4j
@Service
public class DefaultDocumentVectorGateway implements DocumentVectorGateway {

    /**
     * PGVector 表名。
     *
     * <p>这一期表结构固定为单表写入，不再额外提供 manage 自定义配置，
     * 方便让业务端直接复用统一的 PostgreSQL 建表脚本。</p>
     */
    /**
     * 向量化默认批大小。
     *
     * <p>这里使用代码内默认值，避免和 Spring AI 的 embedding 配置重复维护。</p>
     */
    /*
     * 当前项目接入的是阿里 DashScope 兼容 embedding 接口，
     * text-embedding-v4 单次 input.contents 最大只允许 10 条。
     *
     * 这里把批大小固定控制在 10，避免 chunk 数量稍多时直接触发
     * “batch size is invalid, it should not be larger than 10” 的 400 错误。
     *
     * 后续如果切换到别的 embedding 服务，再把这里抽成可配置项即可。
     */
    public static final int EMBEDDING_BATCH_SIZE_LIMIT = 10;

    /**
     * PGVector 入库 SQL 模板。
     *
     * <p>这里使用 PostgreSQL 的 ON CONFLICT 机制保证重复写入时可以幂等覆盖，
     * 便于后续处理 Kafka 消息重复消费或同一批 chunk 重试的场景。</p>
     */
    private static final String UPSERT_SQL_TEMPLATE = """
        INSERT INTO %s
        (id, document_id, task_id, plan_id, parent_block_id, chunk_no, source_type, section_path, chunk_text,
         char_count, token_count, embedding_model, metadata_json, embedding, create_time, edit_time, status)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS vector), NOW(), NOW(), ?)
        ON CONFLICT (id) DO UPDATE SET
            document_id = EXCLUDED.document_id,
            task_id = EXCLUDED.task_id,
            plan_id = EXCLUDED.plan_id,
            parent_block_id = EXCLUDED.parent_block_id,
            chunk_no = EXCLUDED.chunk_no,
            source_type = EXCLUDED.source_type,
            section_path = EXCLUDED.section_path,
            chunk_text = EXCLUDED.chunk_text,
            char_count = EXCLUDED.char_count,
            token_count = EXCLUDED.token_count,
            embedding_model = EXCLUDED.embedding_model,
            metadata_json = EXCLUDED.metadata_json,
            embedding = EXCLUDED.embedding,
            edit_time = NOW(),
            status = EXCLUDED.status
        """;

    /**
     * PGVector 物理删除 SQL 模板。
     */
    private static final String DELETE_BY_DOCUMENT_SQL_TEMPLATE = "DELETE FROM %s WHERE document_id = ?";

    private final JdbcTemplate pgVectorJdbcTemplate;

    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;

    private final ObjectMapper objectMapper;

    /**
     * Spring AI 当前使用的 embedding 模型名称。
     */
    private final String embeddingModelName;

    public DefaultDocumentVectorGateway(
        @Qualifier("documentManagePgVectorJdbcTemplate") JdbcTemplate pgVectorJdbcTemplate,
        ObjectProvider<EmbeddingModel> embeddingModelProvider,
        ObjectMapper objectMapper,
        @Value("${spring.ai.openai.embedding.options.model:}") String embeddingModelName) {
        this.pgVectorJdbcTemplate = pgVectorJdbcTemplate;
        this.embeddingModelProvider = embeddingModelProvider;
        this.objectMapper = objectMapper;
        this.embeddingModelName = embeddingModelName;
    }

    @Override
    public void vectorize(List<SuperAgentDocumentChunk> chunkList) {
        // 空列表直接返回，避免后续 embedding 调用和批处理逻辑做无意义工作。
        if (CollUtil.isEmpty(chunkList)) {
            return;
        }

        // 向量化是硬依赖 EmbeddingModel 的，没有模型就不能继续往下走。
        EmbeddingModel embeddingModel = requireEmbeddingModel();

        // 只保留真正有正文内容的 chunk，空文本 chunk 不应该进入向量库。
        List<SuperAgentDocumentChunk> validChunkList = chunkList.stream()
            .filter(chunk -> chunk != null && StrUtil.isNotBlank(chunk.getChunkText()))
            .toList();
        if (validChunkList.isEmpty()) {
            return;
        }

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

            // 先把这一批 chunk 文本送给 EmbeddingModel，拿回与 chunk 一一对应的向量结果。
            List<float[]> embeddingList = embeddingModel.embed(currentBatch.stream()
                .map(SuperAgentDocumentChunk::getChunkText)
                .toList());
            if (embeddingList.size() != currentBatch.size()) {
                throw new IllegalStateException("EmbeddingModel 返回的向量数量与 chunk 数量不一致。");
            }

            // 向量和元数据批量写入 PGVector，成功后再把业务 chunk 状态标记为成功。
            batchUpsert(upsertSql, currentBatch, embeddingList, currentEmbeddingModelName);
            markSuccess(currentBatch);

            log.info("embedding 批次处理完成，batchIndex={}/{}, currentBatchSize={}",
                currentBatchIndex, totalBatchCount, currentBatch.size());
        }

        log.info("文档向量化执行完成，chunkCount={}, batchSize={}, batchCount={}, embeddingModel={}",
            validChunkList.size(), batchSize, totalBatchCount, currentEmbeddingModelName);
    }

    @Override
    public void deleteByDocumentId(Long documentId) {
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

    /**
     * 分批把向量与元数据写入 PGVector。
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

                // 在真正写入向量库前，先把 chunk 状态推进到“向量化中”。
                chunk.setVectorStatus(DocumentVectorStatusEnum.VECTORIZING.getCode());
                String metadataJson = buildMetadataJson(chunk, embeddingModelName);

                // 前半段字段是业务主数据，用来支持后续按文档、任务、方案做过滤和追踪。
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
                ps.setString(9, chunk.getChunkText());
                ps.setInt(10, defaultInteger(chunk.getCharCount()));
                ps.setInt(11, defaultInteger(chunk.getTokenCount()));
                ps.setString(12, embeddingModelName);
                ps.setString(13, metadataJson);

                // embedding 列最终以 PostgreSQL vector 字面量格式写入 PGVector。
                ps.setString(14, toVectorLiteral(embedding));
                ps.setInt(15, 1);
            }

            @Override
            public int getBatchSize() {
                return chunkBatch.size();
            }
        });
    }

    /**
     * 向量写入成功后回写 chunk 的向量状态。
     */
    private void markSuccess(List<SuperAgentDocumentChunk> chunkBatch) {
        for (SuperAgentDocumentChunk chunk : chunkBatch) {
            // 当前设计下，PGVector 主键和业务 chunk 主键保持一致，便于跨表定位。
            chunk.setVectorId(String.valueOf(chunk.getId()));
            chunk.setVectorStoreType(DocumentVectorStoreTypeEnum.PG_VECTOR.getCode());
            chunk.setVectorStatus(DocumentVectorStatusEnum.VECTOR_SUCCESS.getCode());
        }
    }

    /**
     * 组装写入 PGVector 的 metadata。
     */
    private String buildMetadataJson(SuperAgentDocumentChunk chunk, String embeddingModelName) {
        Map<String, Object> metadata = new LinkedHashMap<>();

        // metadata 既是向量库侧的附加检索信息，也是排查召回结果时的重要上下文。
        metadata.put("documentId", chunk.getDocumentId());
        metadata.put("taskId", chunk.getTaskId());
        metadata.put("planId", chunk.getPlanId());
        metadata.put("parentBlockId", chunk.getParentBlockId());
        metadata.put("chunkNo", chunk.getChunkNo());
        metadata.put("sourceType", chunk.getSourceType());
        metadata.put("sectionPath", chunk.getSectionPath());
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

    /**
     * 把 float[] 转换成 PostgreSQL vector 字面量。
     */
    private String toVectorLiteral(float[] embedding) {
        if (embedding == null || embedding.length == 0) {
            throw new IllegalStateException("EmbeddingModel 返回了空向量。");
        }
        StringBuilder vectorBuilder = new StringBuilder("[");
        for (int index = 0; index < embedding.length; index++) {
            // 各维度之间用英文逗号拼接，形成 PGVector 能直接识别的字面量格式。
            if (index > 0) {
                vectorBuilder.append(",");
            }
            vectorBuilder.append(embedding[index]);
        }
        vectorBuilder.append("]");
        return vectorBuilder.toString();
    }

    /**
     * 获取当前可用的 EmbeddingModel。
     */
    private EmbeddingModel requireEmbeddingModel() {
        // 这里做硬校验，是因为索引构建阶段不应该“悄悄跳过向量化”。
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            throw new IllegalStateException("当前未找到可用的 EmbeddingModel，无法执行向量化。");
        }
        return embeddingModel;
    }

    /**
     * 获取当前向量化模型名称，仅用于记录元数据。
     */
    private String resolveEmbeddingModelName() {
        // 模型名主要用于写 metadata 和日志，不影响真正的 embedding 计算。
        return StrUtil.isNotBlank(embeddingModelName)
            ? embeddingModelName
            : "default";
    }

    /**
     * 处理可能为空的整型字段。
     */
    private int defaultInteger(Integer value) {
        // PGVector 表中这几个数值字段不希望出现 null，这里统一转成 0。
        return Objects.requireNonNullElse(value, 0);
    }
}
