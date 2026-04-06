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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

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

        List<Long> documentIds = distinctIds(request.getDocumentIdList());
        List<Long> taskIds = distinctIds(request.getTaskIdList());
        List<FieldValue> documentFieldValues = documentIds.stream().map(FieldValue::of).toList();
        List<FieldValue> taskFieldValues = taskIds.stream().map(FieldValue::of).toList();
        String question = request.getQuestion().trim();
        DocumentRetrieveFilters filters = request.getFilters();
        List<String> queryContextHints = request.getQueryContextHints() == null ? List.of() : request.getQueryContextHints();

        try {
            SearchResponse<DocumentKeywordIndexRecord> response = elasticsearchClient.search(search -> search
                    .index(properties.getElasticsearch().getIndexName())
                    .size(resolveTopK(request.getTopK()))
                    .query(query -> query.bool(bool -> {
                        /*
                         * 这里的 bool 查询结构对应的是“先做硬过滤，再做软打分”：
                         * 1. filter(documentId/taskId/pageNo)：这是硬边界，不参与相关性得分
                         * 2. should(matchPhrase/multiMatch)：这是软加权，负责把更像答案的 chunk 顶到前面
                         *
                         * 这种分层方式比“所有条件都塞进 must/should”更适合当前场景，
                         * 因为 documentId/taskId/pageNo 本质上是业务边界，不是语义相关性。
                         */
                        bool.filter(filter -> filter.terms(terms -> terms
                            .field("documentId")
                            .terms(values -> values.value(documentFieldValues))
                        ));
                        bool.filter(filter -> filter.terms(terms -> terms
                            .field("taskId")
                            .terms(values -> values.value(taskFieldValues))
                        ));
                        if (filters != null && CollUtil.isNotEmpty(filters.getPageHints())) {
                            /*
                             * 页码提示单独做 filter，而不是只作为 should boost，
                             * 是因为用户一旦明确说了“第 12 页”，
                             * 我们更希望把非该页的结果直接剔掉，而不是让模型在很多页里自己猜。
                             */
                            bool.filter(filter -> filter.bool(pageBool -> {
                                for (String pageHint : filters.getPageHints()) {
                                    pageBool.should(should -> should.wildcard(wildcard -> wildcard
                                        .field("pageNo")
                                        .value("*" + pageHint.toLowerCase(Locale.ROOT) + "*")
                                    ));
                                }
                                pageBool.minimumShouldMatch("1");
                                return pageBool;
                            }));
                        }
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
                        /*
                         * 短语命中优先覆盖章节路径。
                         * 像“协议配置”“设备模板”这种章节标题，应该被明确顶到更前面。
                         */
                        bool.should(should -> should.matchPhrase(matchPhrase -> matchPhrase
                            .field("sectionPath")
                            .query(question)
                            .boost(8.0f)
                        ));
                        bool.should(should -> should.matchPhrase(matchPhrase -> matchPhrase
                            .field("chunkText")
                            .query(question)
                            .boost(5.0f)
                        ));
                        bool.should(should -> should.matchPhrase(matchPhrase -> matchPhrase
                            .field("documentName")
                            .query(question)
                            .boost(4.0f)
                        ));
                        bool.should(should -> should.multiMatch(multiMatch -> multiMatch
                            .query(question)
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
            log.error("Elasticsearch 关键词检索失败, question={}", question, exception);
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
            .chunkNo(chunk.getChunkNo())
            .documentName(document == null ? "" : safeText(document.getDocumentName()))
            .sectionPath(safeText(chunk.getSectionPath()))
            .pageNo(safeText(chunk.getPageNo()))
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
        metadata.put(DocumentKnowledgeMetadataKeys.CHUNK_NO, source.getChunkNo());
        metadata.put(DocumentKnowledgeMetadataKeys.SECTION_PATH, safeText(source.getSectionPath()));
        metadata.put(DocumentKnowledgeMetadataKeys.PAGE_NO, safeText(source.getPageNo()));
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
            && CollUtil.isNotEmpty(request.getDocumentIdList())
            && CollUtil.isNotEmpty(request.getTaskIdList());
    }

    private List<Long> distinctIds(List<Long> ids) {
        return new ArrayList<>(new LinkedHashSet<>(ids));
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
