package org.javaup.ai.manage.service.keyword;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.manage.config.DocumentManageProperties;
import org.javaup.ai.manage.data.SuperAgentDocument;
import org.javaup.ai.manage.data.SuperAgentDocumentChunk;
import org.javaup.ai.manage.mapper.SuperAgentDocumentMapper;
import org.javaup.ai.manage.model.DocumentRetrieveFilters;
import org.javaup.ai.manage.model.DocumentRetrieveRequest;
import org.javaup.ai.manage.model.es.DocumentKeywordIndexRecord;
import org.javaup.ai.manage.support.DocumentKnowledgeMetadataKeys;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 服务层
 * @author: 阿星不是程序员
 **/
/**
 * 基于 Elasticsearch 的文档关键词检索网关。
 *
 * <p>这层承担三件事：</p>
 * <p>1. 把 chunk 写入 ES 倒排索引。</p>
 * <p>2. 基于 BM25 做关键词召回。</p>
 * <p>3. 删除文档时同步清理 ES 索引数据，保证跨存储一致性。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.manage.elasticsearch", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ElasticsearchDocumentKeywordSearchGateway implements DocumentKeywordSearchGateway {

    private final ElasticsearchClient elasticsearchClient;
    private final SuperAgentDocumentMapper documentMapper;
    private final DocumentManageProperties properties;

    public ElasticsearchDocumentKeywordSearchGateway(
        @Qualifier("documentManageElasticsearchClient") ElasticsearchClient elasticsearchClient,
        SuperAgentDocumentMapper documentMapper,
        DocumentManageProperties properties) {
        this.elasticsearchClient = elasticsearchClient;
        this.documentMapper = documentMapper;
        this.properties = properties;
    }

    @Override
    public void indexChunks(List<SuperAgentDocumentChunk> chunkList) {
        if (CollUtil.isEmpty(chunkList)) {
            return;
        }

        Map<Long, SuperAgentDocument> documentMap = loadDocumentMap(chunkList);
        BulkRequest.Builder bulkBuilder = new BulkRequest.Builder()
            .index(properties.getElasticsearch().getIndexName())
            .refresh(Refresh.WaitFor);

        for (SuperAgentDocumentChunk chunk : chunkList) {
            SuperAgentDocument document = documentMap.get(chunk.getDocumentId());
            DocumentKeywordIndexRecord indexRecord = toIndexRecord(chunk, document);
            bulkBuilder.operations(operation -> operation
                .index(index -> index
                    .id(indexRecord.getChunkId())
                    .document(indexRecord)
                )
            );
        }

        try {
            BulkResponse response = elasticsearchClient.bulk(bulkBuilder.build());
            if (response.errors()) {
                String errorMessage = response.items().stream()
                    .filter(item -> item.error() != null)
                    .map(item -> item.id() + ":" + item.error().reason())
                    .collect(Collectors.joining("; "));
                throw new IllegalStateException("批量写入 Elasticsearch 失败: " + errorMessage);
            }
            log.info("文档 chunk 已同步写入 Elasticsearch: chunkCount={}, index={}",
                chunkList.size(), properties.getElasticsearch().getIndexName());
        }
        catch (IOException exception) {
            throw new IllegalStateException("写入 Elasticsearch 失败", exception);
        }
    }

    @Override
    public List<Document> search(DocumentRetrieveRequest request) {
        if (!isSearchableRequest(request)) {
            return List.of();
        }

        List<FieldValue> documentFieldValues = request.resolvedDocumentIds().stream()
            .map(FieldValue::of)
            .toList();
        List<FieldValue> taskFieldValues = request.resolvedTaskIds().stream()
            .map(FieldValue::of)
            .toList();
        /*
         * ES 主查询也统一基于 retrievalQuery，而不是用户原问题。
         * 这样关键词主路径才能真正吃到短追问增强后的上下文补全结果。
         */
        String retrievalQuery = request.getRetrievalQuery().trim();
        DocumentRetrieveFilters filters = request.getFilters();
        List<String> queryContextHints = request.getQueryContextHints() == null ? List.of() : request.getQueryContextHints();

        try {
            SearchResponse<DocumentKeywordIndexRecord> response = elasticsearchClient.search(search -> search
                    .index(properties.getElasticsearch().getIndexName())
                    .size(resolveTopK(request.getTopK()))
                    .query(query -> query.bool(bool -> {
                        /*
                         * 这里的 bool 查询结构对应的是“先做硬过滤，再做软打分”：
                         * 1. filter(documentId/taskId/sectionPath)：这是硬边界，不参与相关性得分
                         * 2. should(matchPhrase/multiMatch)：这是软加权，负责把更像答案的 chunk 顶到前面
                         *
                         * 这种分层方式比“所有条件都塞进 must/should”更适合当前场景，
                         * 因为 documentId/taskId/sectionPath 本质上是业务边界，不是语义相关性。
                         */
                        bool.filter(filter -> filter.terms(terms -> terms
                            .field("documentId")
                            .terms(values -> values.value(documentFieldValues))
                        ));
                        bool.filter(filter -> filter.terms(terms -> terms
                            .field("taskId")
                            .terms(values -> values.value(taskFieldValues))
                        ));
                        if (filters != null && CollUtil.isNotEmpty(filters.getSectionPathHints())) {
                            bool.filter(filter -> filter.bool(sectionBool -> {
                                for (String sectionHint : filters.getSectionPathHints()) {
                                    sectionBool.should(should -> should.wildcard(wildcard -> wildcard
                                        .field("sectionPath")
                                        .value("*" + sectionHint.toLowerCase(Locale.ROOT) + "*")
                                    ));
                                }
                                sectionBool.minimumShouldMatch("1");
                                return sectionBool;
                            }));
                        }
                        if (filters != null && CollUtil.isNotEmpty(filters.getCanonicalPathHints())) {
                            bool.filter(filter -> filter.bool(pathBool -> {
                                for (String pathHint : filters.getCanonicalPathHints()) {
                                    pathBool.should(should -> should.wildcard(wildcard -> wildcard
                                        .field("canonicalPath")
                                        .value(pathHint + "*")
                                    ));
                                }
                                pathBool.minimumShouldMatch("1");
                                return pathBool;
                            }));
                        }
                        if (filters != null && CollUtil.isNotEmpty(filters.getStructureNodeIdHints())) {
                            List<FieldValue> structureNodeValues = filters.getStructureNodeIdHints().stream()
                                .map(FieldValue::of)
                                .toList();
                            bool.filter(filter -> filter.terms(terms -> terms
                                .field("structureNodeId")
                                .terms(values -> values.value(structureNodeValues))
                            ));
                        }
                        if (filters != null && CollUtil.isNotEmpty(filters.getItemIndexHints())) {
                            List<FieldValue> itemIndexValues = filters.getItemIndexHints().stream()
                                .map(FieldValue::of)
                                .toList();
                            bool.filter(filter -> filter.terms(terms -> terms
                                .field("itemIndex")
                                .terms(values -> values.value(itemIndexValues))
                            ));
                        }
                        /*
                         * 短语命中优先覆盖章节路径。
                         * 像“协议配置”“设备模板”这种章节标题，应该被明确顶到更前面。
                         */
                        bool.should(should -> should.matchPhrase(matchPhrase -> matchPhrase
                            .field("sectionPath")
                            .query(retrievalQuery)
                            .boost(8.0f)
                        ));
                        bool.should(should -> should.matchPhrase(matchPhrase -> matchPhrase
                            .field("chunkText")
                            .query(retrievalQuery)
                            .boost(5.0f)
                        ));
                        bool.should(should -> should.matchPhrase(matchPhrase -> matchPhrase
                            .field("documentName")
                            .query(retrievalQuery)
                            .boost(4.0f)
                        ));
                        bool.should(should -> should.multiMatch(multiMatch -> multiMatch
                            .query(retrievalQuery)
                            .fields("sectionPath^6", "documentName^4", "knowledgeScopeName^3", "chunkText")
                            .type(TextQueryType.BestFields)
                        ));
                        if (filters != null && CollUtil.isNotEmpty(filters.getBusinessCategoryHints())) {
                            /*
                             * 业务分类 / 标签 / 文档名 / 章节提示都只作为 should boost，
                             * 是因为这些线索很多时候是“提升精度的提示”，不是百分百硬约束。
                             * 这样能在不误杀结果的前提下，把更合适的候选稳定顶上来。
                             */
                            bool.should(should -> should.multiMatch(multiMatch -> multiMatch
                                .query(String.join(" ", filters.getBusinessCategoryHints()))
                                .fields("businessCategory^5", "knowledgeScopeName^2")
                                .type(TextQueryType.BestFields)
                            ));
                        }
                        if (filters != null && CollUtil.isNotEmpty(filters.getDocumentTagHints())) {
                            bool.should(should -> should.multiMatch(multiMatch -> multiMatch
                                .query(String.join(" ", filters.getDocumentTagHints()))
                                .fields("documentTags^4", "documentName^2", "chunkText")
                                .type(TextQueryType.BestFields)
                            ));
                        }
                        if (filters != null && CollUtil.isNotEmpty(filters.getDocumentNameHints())) {
                            bool.should(should -> should.multiMatch(multiMatch -> multiMatch
                                .query(String.join(" ", filters.getDocumentNameHints()))
                                .fields("documentName^6", "sectionPath^2", "chunkText")
                                .type(TextQueryType.BestFields)
                            ));
                        }
                        if (filters != null && CollUtil.isNotEmpty(filters.getSectionPathHints())) {
                            bool.should(should -> should.multiMatch(multiMatch -> multiMatch
                                .query(String.join(" ", filters.getSectionPathHints()))
                                .fields("sectionPath^7", "chunkText")
                                .type(TextQueryType.BestFields)
                            ));
                        }
                        if (CollUtil.isNotEmpty(queryContextHints)) {
                            bool.should(should -> should.multiMatch(multiMatch -> multiMatch
                                .query(String.join(" ", queryContextHints))
                                .fields("documentName^2", "knowledgeScopeName^2", "sectionPath^2", "chunkText")
                                .type(TextQueryType.BestFields)
                            ));
                        }
                        bool.minimumShouldMatch("1");
                        return bool;
                    })),
                DocumentKeywordIndexRecord.class);

            List<Document> result = new ArrayList<>();
            for (Hit<DocumentKeywordIndexRecord> hit : response.hits().hits()) {
                DocumentKeywordIndexRecord source = hit.source();
                if (source == null) {
                    continue;
                }
                result.add(toSpringDocument(source, hit.score()));
            }
            return result;
        }
        catch (IOException exception) {
            log.error("Elasticsearch 关键词检索失败, retrievalQuery={}", retrievalQuery, exception);
            return List.of();
        }
    }

    @Override
    public void deleteByDocumentId(Long documentId) {
        if (documentId == null) {
            return;
        }
        try {
            elasticsearchClient.deleteByQuery(delete -> delete
                .index(properties.getElasticsearch().getIndexName())
                .refresh(true)
                .query(query -> query.term(term -> term
                    .field("documentId")
                    .value(documentId)
                ))
            );
        }
        catch (IOException exception) {
            throw new IllegalStateException("删除 Elasticsearch 文档失败", exception);
        }
    }

    private Map<Long, SuperAgentDocument> loadDocumentMap(List<SuperAgentDocumentChunk> chunkList) {
        List<Long> documentIds = chunkList.stream()
            .map(SuperAgentDocumentChunk::getDocumentId)
            .filter(id -> id != null)
            .distinct()
            .toList();
        if (documentIds.isEmpty()) {
            return Map.of();
        }
        List<SuperAgentDocument> documents = documentMapper.selectBatchIds(documentIds);
        Map<Long, SuperAgentDocument> documentMap = new LinkedHashMap<>();
        for (SuperAgentDocument document : documents) {
            documentMap.put(document.getId(), document);
        }
        return documentMap;
    }

    private DocumentKeywordIndexRecord toIndexRecord(SuperAgentDocumentChunk chunk, SuperAgentDocument document) {
        return DocumentKeywordIndexRecord.builder()
            .chunkId(String.valueOf(chunk.getId()))
            .documentId(chunk.getDocumentId())
            .taskId(chunk.getTaskId())
            .parentBlockId(chunk.getParentBlockId())
            .chunkNo(chunk.getChunkNo())
            .documentName(document == null ? "" : safeText(document.getDocumentName()))
            .sectionPath(safeText(chunk.getSectionPath()))
            .structureNodeId(chunk.getStructureNodeId())
            .structureNodeType(chunk.getStructureNodeType())
            .canonicalPath(safeText(chunk.getCanonicalPath()))
            .itemIndex(chunk.getItemIndex())
            .knowledgeScopeCode(document == null ? "" : safeText(document.getKnowledgeScopeCode()))
            .knowledgeScopeName(document == null ? "" : safeText(document.getKnowledgeScopeName()))
            .businessCategory(document == null ? "" : safeText(document.getBusinessCategory()))
            .documentTags(splitTags(document == null ? "" : document.getDocumentTags()))
            .chunkText(safeText(chunk.getChunkText()))
            .build();
    }

    private Document toSpringDocument(DocumentKeywordIndexRecord source, Double score) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "DOCUMENT");
        metadata.put(DocumentKnowledgeMetadataKeys.CHANNEL, "keyword");
        metadata.put(DocumentKnowledgeMetadataKeys.SCORE, score == null ? 0D : score.doubleValue());
        metadata.put(DocumentKnowledgeMetadataKeys.CHUNK_ID, parseLong(source.getChunkId()));
        metadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_ID, source.getDocumentId());
        metadata.put(DocumentKnowledgeMetadataKeys.TASK_ID, source.getTaskId());
        metadata.put(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID, source.getParentBlockId());
        metadata.put(DocumentKnowledgeMetadataKeys.CHUNK_NO, source.getChunkNo());
        metadata.put(DocumentKnowledgeMetadataKeys.SECTION_PATH, safeText(source.getSectionPath()));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.STRUCTURE_NODE_ID, source.getStructureNodeId());
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.STRUCTURE_NODE_TYPE, source.getStructureNodeType());
        metadata.put(DocumentKnowledgeMetadataKeys.CANONICAL_PATH, safeText(source.getCanonicalPath()));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.ITEM_INDEX, source.getItemIndex());
        metadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME, safeText(source.getDocumentName()));
        metadata.put(DocumentKnowledgeMetadataKeys.KNOWLEDGE_SCOPE_CODE, safeText(source.getKnowledgeScopeCode()));
        metadata.put(DocumentKnowledgeMetadataKeys.KNOWLEDGE_SCOPE_NAME, safeText(source.getKnowledgeScopeName()));
        metadata.put(DocumentKnowledgeMetadataKeys.BUSINESS_CATEGORY, safeText(source.getBusinessCategory()));
        metadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_TAGS, String.join(",", source.getDocumentTags()));

        return Document.builder()
            .id(source.getChunkId())
            .text(source.getChunkText())
            .metadata(metadata)
            .score(score == null ? 0D : score.doubleValue())
            .build();
    }

    private boolean isSearchableRequest(DocumentRetrieveRequest request) {
        return request != null
            && StrUtil.isNotBlank(request.getQuestion())
            && StrUtil.isNotBlank(request.getRetrievalQuery())
            && !request.resolvedDocumentIds().isEmpty()
            && !request.resolvedTaskIds().isEmpty();
    }

    private void putIfNotNull(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }

    private int resolveTopK(int topK) {
        return topK <= 0 ? 10 : Math.min(topK, 50);
    }

    private List<String> splitTags(String documentTags) {
        if (StrUtil.isBlank(documentTags)) {
            return List.of();
        }
        return java.util.Arrays.stream(documentTags.split(","))
            .map(String::trim)
            .filter(StrUtil::isNotBlank)
            .distinct()
            .toList();
    }

    private Long parseLong(String text) {
        if (StrUtil.isBlank(text)) {
            return null;
        }
        try {
            return Long.parseLong(text);
        }
        catch (NumberFormatException exception) {
            return null;
        }
    }

    private String safeText(String text) {
        return text == null ? "" : text;
    }
}
