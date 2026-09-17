package org.javaup.ai.manage.service.keyword;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 服务层
 * @author: 阿星不是程序员
 **/

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



    /*
    将文档切片批量写入 Elasticsearch 关键词索引。*
 * <p>这个方法负责把业务库里的 {@link SuperAgentDocumentChunk} 转换成
 * {@link DocumentKeywordIndexRecord}，再通过 Elasticsearch Bulk API 一次性写入索引。
 * 写入前会先批量查询切片所属的文档主表信息，因为索引里除了切片正文，还需要冗余文档名称、
 * 知识库范围、业务分类、标签等字段，方便后续关键词召回时按这些字段过滤和加权。</p>
 * @param chunkList 待写入关键词索引的文档切片列表
     */
    /*
    这段方法可以按执行顺序拆成四步看：
1.空列表直接返回：如果 chunkEntityList 为空，说明前面没有生成有效子块，关键词索引没有任何可写内容。
2.批量加载文档主表：chunk 表里有正文、章节路径、父块 ID 等信息，但文档名称、知识库范围、业务分类、标签在文档主表里，所以要先把这些文档信息批量查出来。
3.组装 BulkRequest：每个 chunk 转成一条 DocumentKeywordIndexRecord，再作为一个 Elasticsearch index 操作放进 Bulk 请求里。
4.统一提交并检查失败项：Elasticsearch Bulk API 有一个细节：HTTP 请求成功不代表每一条 item 都成功，所以必须看 response.errors()。如果有失败项，就把失败 chunk id 和原因拼出来，抛给外层任务处理。
其中 Refresh.WaitFor 也很关键。它表示写入请求会等待 Elasticsearch refresh 后再返回，这样关键词索引写完后更快能被检索看见。代价是写入延迟会比完全异步刷新更高，但对“文档索引构建完成后立即可查”这个场景更友好。

     */
    @Override
    public void indexChunks(List<SuperAgentDocumentChunk> chunkList) {
        // 没有待索引的切片时直接返回，避免构造空的 BulkRequest，也避免无意义访问 Elasticsearch。
        if (CollUtil.isEmpty(chunkList)) {
            return;
        }

        // 先按 documentId 批量加载文档主表数据，后续每个 chunk 转索引记录时都可以从 Map 中 O(1) 取到文档元数据。
        Map<Long, SuperAgentDocument> documentMap = loadDocumentMap(chunkList);
        // 构造 BulkRequest：统一指定目标索引，并使用 WaitFor 刷新策略，让写入在刷新可见后再返回，提升后续检索的实时性。
        BulkRequest.Builder bulkBuilder = new BulkRequest.Builder()
            .index(properties.getElasticsearch().getIndexName())
            .refresh(Refresh.WaitFor);

        for (SuperAgentDocumentChunk chunk : chunkList) {
            // 通过 chunk 上的 documentId 关联文档主表；如果文档已被删除或查不到，后面会用空字符串兜底，不阻断 chunk 索引。
            SuperAgentDocument document = documentMap.get(chunk.getDocumentId());
            // 将数据库实体转换成 Elasticsearch 索引结构，完成字段清洗、空值兜底和标签拆分等写入前准备。
            DocumentKeywordIndexRecord indexRecord = toIndexRecord(chunk, document);
            // 每个 chunk 对应一条 index 操作；使用 chunkId 作为文档 id，重复写入同一 chunk 时会覆盖旧索引，保持幂等。
            bulkBuilder.operations(operation -> operation
                .index(index -> index
                    .id(indexRecord.getChunkId())//很重要的幂等设计：每条 Elasticsearch 文档的 ID 使用 chunkId。这样同一个 chunk 重复写入时不是新增重复记录，而是覆盖原有记录。异步任务重试、重复执行索引构建时，这个设计可以减少脏数据。
                    .document(indexRecord)
                )
            );
        }

        try {
            // 一次性提交所有 index 操作，减少逐条写入 Elasticsearch 的网络开销。
            BulkResponse response = elasticsearchClient.bulk(bulkBuilder.build());
            // Bulk API 可能整体请求成功但部分 item 失败，因此必须显式检查 response.errors()。
            if (response.errors()) {
                // 汇总每个失败 item 的 id 和失败原因，抛出异常时方便定位具体是哪些 chunk 写入失败。
                String errorMessage = response.items().stream()
                    .filter(item -> item.error() != null)
                    .map(item -> item.id() + ":" + item.error().reason())
                    .collect(Collectors.joining("; "));
                throw new IllegalStateException("批量写入 Elasticsearch 失败: " + errorMessage);
            }
            // 记录成功写入的 chunk 数和索引名，便于观察异步文档处理链路是否完成关键词索引同步。
            log.info("文档 chunk 已同步写入 Elasticsearch: chunkCount={}, index={}",
                chunkList.size(), properties.getElasticsearch().getIndexName());
        }
        catch (IOException exception) {
            // 客户端通信、序列化或服务端连接异常统一包装成运行时异常，让上层异步处理链路感知本次索引失败。
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

        String retrievalQuery = request.getRetrievalQuery().trim();
        DocumentRetrieveFilters filters = request.getFilters();
        List<String> queryContextHints = request.getQueryContextHints() == null ? List.of() : request.getQueryContextHints();

        try {
            SearchResponse<DocumentKeywordIndexRecord> response = elasticsearchClient.search(search -> search
                    .index(properties.getElasticsearch().getIndexName())
                    .size(resolveTopK(request.getTopK()))
                    .query(query -> query.bool(bool -> {

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



    /*
    接下来继续看indexChunks()调用的多层子方法
    loadDocumentMap：一次性加载文档元数据
    indexChunks（）的第一层子方法是loadDocumentMap()。
     */
    /**
     * 根据切片列表中的 documentId 批量加载文档主表，并组装成以文档 id 为 key 的 Map。
     *
     * <p>这是 {@link #indexChunks(List)} 的第一层子方法。之所以单独批量查询，是为了避免在
     * for 循环中按 chunk 逐条查询文档，导致 N+1 查询问题；同时索引记录需要文档层面的名称、
     * 知识库范围、业务分类和标签字段，这些信息不在 chunk 表里。</p>
     *
     * @param chunkList 待写入索引的切片列表
     * @return 文档 id 到文档实体的映射；当切片里没有有效 documentId 时返回空 Map
     */
    //这里的重点不是“查文档”本身，而是避免 N+1 查询。如果一个文档切出了 100 个 chunk，不能在循环里查 100 次文档主表，而是先从 chunk 列表中提取 documentId，去重后一次性查出所有文档，再转成 Map。
    /*
    转成Map以后，主循环里就可以通过：
    SuperAgentDocument document = documentMap.get(chunk.getDocumentId());
    快速拿到文档元数据。这样每条 Elasticsearch 索引记录都可以冗余这些字段：
documentName：用于文档名命中和展示。
knowledgeScopeCode / knowledgeScopeName：用于知识范围过滤或加权。
businessCategory：用于业务分类召回。
documentTags：用于标签匹配。
     */
    private Map<Long, SuperAgentDocument> loadDocumentMap(List<SuperAgentDocumentChunk> chunkList) {
        // 从所有 chunk 中提取 documentId，过滤空值并去重，确保数据库只查询必要的文档记录。
        List<Long> documentIds = chunkList.stream()
            .map(SuperAgentDocumentChunk::getDocumentId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        // 如果所有 chunk 都没有 documentId，就没有文档主表可查，直接返回空 Map 交给后续转换逻辑做空值兜底。
        if (documentIds.isEmpty()) {
            return Map.of();
        }
        // 使用 MyBatis-Plus 的批量主键查询，一次性拿到所有关联文档，避免在 indexChunks 循环中反复访问数据库。
        List<SuperAgentDocument> documents = documentMapper.selectBatchIds(documentIds);
        // 使用 LinkedHashMap 保留查询结果遍历顺序，虽然当前只按 key 读取，但有助于调试时保持输出稳定。
        Map<Long, SuperAgentDocument> documentMap = new LinkedHashMap<>();
        for (SuperAgentDocument document : documents) {
            // 按文档 id 建立索引，后续处理每个 chunk 时可以通过 chunk.documentId 快速拿到文档元数据。
            documentMap.put(document.getId(), document);
        }
        return documentMap;
    }




    /*toIndexRecord：chunk 实体转 Elasticsearch 索引记录
    indexChunks() 的第二层子方法是 toIndexRecord()，它是真正决定“写进 Elasticsearch 的文档长什么样”的地方。
    将数据库中的文档切片实体转换为 Elasticsearch 关键词索引记录。
 * <p>这是 {@link #indexChunks(List)} 的第二层子方法。索引记录会同时包含 chunk 自身字段
 * 和 document 主表冗余字段：chunk 字段用于定位和展示原文片段，document 字段用于后续关键词检索时做过滤、
 * 权重匹配和结果解释。这里会调用 {@link #safeText(String)} 做空字符串兜底，并调用
 * {@link #splitTags(String)} 把逗号分隔的标签字符串拆成 Elasticsearch 更容易匹配的数组字段。</p>
 * @param chunk 当前要写入 Elasticsearch 的文档切片
 * @param document chunk 所属的文档主表记录；当主表缺失时允许为 null
 * @return 可直接写入 Elasticsearch 的关键词索引记录

     */
    /*这一步可以理解为“把关系型数据库里的两类信息摊平成一条搜索文档”：
来自 chunk 的字段：chunkId、documentId、taskId、parentBlockId、chunkNo、sectionPath、canonicalPath、chunkText 等。
来自 document 的字段：documentName、knowledgeScopeCode、knowledgeScopeName、businessCategory、documentTags 等。
为什么要把 document 字段冗余到每一条 chunk 索引里？因为 Elasticsearch 检索时最好直接在一条索引文档中完成过滤和打分。如果每次命中 chunk 后还要回数据库查文档名称、分类、标签，再重新参与排序，链路会变长，性能和实现复杂度都会上升。

     */
    private DocumentKeywordIndexRecord toIndexRecord(SuperAgentDocumentChunk chunk, SuperAgentDocument document) {
        // 使用 builder 明确写入索引的字段来源：一部分来自 chunk，一部分来自 document，一部分经过清洗转换。
        return DocumentKeywordIndexRecord.builder()
                // Elasticsearch 文档 id 使用字符串类型；这里由 chunk 主键转换而来，保证同一 chunk 重复写入时覆盖同一条索引。
            .chunkId(String.valueOf(chunk.getId()))
                // documentId/taskId 等数字字段保留原始类型，便于后续 terms filter 精确过滤。
            .documentId(chunk.getDocumentId())
            .taskId(chunk.getTaskId())
            .parentBlockId(chunk.getParentBlockId())
            .chunkNo(chunk.getChunkNo())
                // document 可能查询不到，因此文档层面的文本字段都要先判空，再通过 safeText 统一转成非 null 字符串。
            .documentName(document == null ? "" : safeText(document.getDocumentName()))
                // sectionPath/canonicalPath 用于章节路径过滤和加权匹配，写入前转为空字符串可避免序列化 null 带来的检索分支处理。
            .sectionPath(safeText(chunk.getSectionPath()))
                // 结构化节点信息来自解析后的 chunk，可帮助检索结果回溯到标题、列表项、表格等具体结构位置。
            .structureNodeId(chunk.getStructureNodeId())
            .structureNodeType(chunk.getStructureNodeType())
            .canonicalPath(safeText(chunk.getCanonicalPath()))
            .itemIndex(chunk.getItemIndex())
                // 知识库范围和业务分类属于文档维度信息，冗余到每条 chunk 索引里可以避免检索时再回表关联。
            .knowledgeScopeCode(document == null ? "" : safeText(document.getKnowledgeScopeCode()))
            .knowledgeScopeName(document == null ? "" : safeText(document.getKnowledgeScopeName()))
            .businessCategory(document == null ? "" : safeText(document.getBusinessCategory()))
                // 标签在数据库中是逗号分隔字符串，写入 Elasticsearch 前拆成数组，便于 multi_match 对单个标签命中。
            .documentTags(splitTags(document == null ? "" : document.getDocumentTags()))
                // chunkText 是关键词召回的核心正文内容，统一用 safeText 保证索引记录里没有 null 文本。
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


    /*splitTags：标签字符串转标签数组
    toIndexRecord() 还会继续调用 splitTags()，这是更下一层的子方法。
    将文档标签字符串拆分为去重后的标签列表。
 * <p>这是 {@link #toIndexRecord(SuperAgentDocumentChunk, SuperAgentDocument)} 的下层子方法。
 * 数据库中的 documentTags 以英文逗号拼接存储，而 Elasticsearch 更适合把标签作为数组字段写入，
 * 这样后续关键词检索可以针对单个标签独立匹配和加权。</p>
 * @param documentTags 数据库中逗号分隔的标签字符串
 * @return 清洗、过滤空白并去重后的标签列表

     */
    /*
    这个清洗过程做了三件事：
trim()：去掉标签前后的空格。
filter(StrUtil::isNotBlank)：过滤连续逗号、尾部逗号产生的空标签。
distinct()：去重，避免同一个标签重复写入。
这样后续关键词检索针对 documentTags 做 multi_match 时，命中会更干净。
     */
    private List<String> splitTags(String documentTags) {
        // 标签为空时直接返回不可变空列表，避免后续 builder 写入 null，也表示该文档没有可检索标签。
        if (StrUtil.isBlank(documentTags)) {
            return List.of();
        }
        // 按英文逗号切分标签，逐个 trim 去掉用户录入或拼接时产生的前后空格。
        return Arrays.stream(documentTags.split(","))
            .map(String::trim)
                // 过滤掉连续逗号、尾部逗号等情况产生的空标签，避免无意义 token 进入 Elasticsearch。
            .filter(StrUtil::isNotBlank)
                // 去重可以降低索引体积，也避免同一标签重复出现影响后续匹配解释。
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


    /*
    统一处理null文本
    toIndexRecord() 还会多次调用 safeText()。
     将可能为 null 的文本统一转换为非 null 字符串。
 * <p>这是 {@link #toIndexRecord(SuperAgentDocumentChunk, SuperAgentDocument)} 的下层子方法。
 * 索引写入阶段统一把 null 文本字段转为空字符串，可以让 Elasticsearch 文档结构更稳定，
 * 也能减少检索、元数据组装阶段对 null 的重复判断。</p>
 * @param text 原始文本，允许为 null
 * @return 原始文本本身，或在原始文本为 null 时返回空字符串
     */
    /*
    这个方法看起来很小，但它保证了 Elasticsearch 索引结构稳定：文档名、章节路径、规范路径、业务分类、正文等文本字段不会出现 Java 层面的 null。后续检索 DSL 里就不用为了每个字段反复写 null 分支。
注意它只把 null 变成空字符串，并不做 trim()。原因是这些字段有些来自用户原文、有些来自结构化路径，索引层不应该随意改写文本语义。
     */
    private String safeText(String text) {
        // 只处理 null，不额外 trim 或改写内容，避免改变用户文档正文、标题、路径等原始文本语义。
        return text == null ? "" : text;
    }
}
