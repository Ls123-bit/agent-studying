package org.javaup.ai.manage.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.manage.config.DocumentManageProperties;
import org.javaup.ai.manage.data.SuperAgentDocument;
import org.javaup.ai.manage.data.SuperAgentDocumentChunk;
import org.javaup.ai.manage.data.SuperAgentDocumentParentBlock;
import org.javaup.ai.manage.mapper.SuperAgentDocumentMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentParentBlockMapper;
import org.javaup.ai.manage.model.DocumentRetrieveFilters;
import org.javaup.ai.manage.model.DocumentRetrieveRequest;
import org.javaup.ai.manage.model.KnowledgeDocumentDescriptor;
import org.javaup.ai.manage.service.DocumentKnowledgeService;
import org.javaup.ai.manage.service.keyword.DocumentKeywordSearchGateway;
import org.javaup.ai.manage.support.DocumentKnowledgeMetadataKeys;
import org.javaup.ai.manage.support.DocumentPgVectorConstants;
import org.javaup.enums.BusinessStatus;
import org.javaup.enums.DocumentIndexStatusEnum;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 默认文档知识检索服务。
 *
 * <p>这一层把“当前有哪些文档可检索”“怎样从 PGVector 拿候选片段”统一收口，
 * 避免聊天侧再直接拼 SQL 或自己理解索引状态。</p>
 *
 * <p>当前提供两种检索路径：</p>
 * <p>1. 向量检索：适合语义匹配。</p>
 * <p>2. 关键词检索：适合版本号、编号、专有名词和高精度词命中。</p>
 */
@Slf4j
@Service
public class DocumentKnowledgeServiceImpl implements DocumentKnowledgeService {

    /**
     * 向量检索 SQL。
     *
     * <p>这里仍然复用 cosine distance 做排序，
     * 并在结果中同时返回转换后的 similarity score，便于上层继续做融合和展示。</p>
     */
    private static final String VECTOR_RETRIEVE_SQL_TEMPLATE = """
        SELECT
            id,
            document_id,
            task_id,
            parent_block_id,
            chunk_no,
            section_path,
            chunk_text,
            1 - (embedding <=> CAST(? AS vector)) AS similarity_score
        FROM %s
        WHERE status = 1
          AND document_id IN (%s)
          AND task_id IN (%s)
        """;

    /**
     * 关键词检索 SQL。
     *
     * <p>当前项目没有额外引入 ES，因此这里使用轻量级的 SQL 方案：
     * 通过多个 LIKE 命中项累加一个 lexical score，
     * 作为“教学项目可直接跑通”的关键词检索通道。</p>
     */
    private static final String KEYWORD_RETRIEVE_SQL_TEMPLATE = """
        SELECT
            id,
            document_id,
            task_id,
            parent_block_id,
            chunk_no,
            section_path,
            chunk_text,
            (%s) AS keyword_score
        FROM %s
        WHERE status = 1
          AND document_id IN (%s)
          AND task_id IN (%s)
          AND (%s)
        """;

    /**
     * 关键词提取时识别英数词的正则。
     */
    private static final Pattern ALNUM_TOKEN_PATTERN = Pattern.compile("[a-z0-9._-]{2,}");

    /**
     * 关键词提取时识别中文连续片段的正则。
     */
    private static final Pattern CHINESE_TOKEN_PATTERN = Pattern.compile("[\\p{IsHan}]{2,}");

    /**
     * 中文问题里的弱语义噪音短语。
     *
     * <p>这里故意按“短语”而不是按“单个汉字”维护，
     * 避免把业务关键词的一部分误删掉。
     * 旧实现把“关于”里的“关”也当成停用字处理，就会把“网关”错误裁坏。</p>
     */
    private static final List<String> CHINESE_NOISE_PHRASES = List.of(
        "请问", "帮我", "一下子", "一下", "如何", "怎么", "什么", "哪个", "这个", "那个", "是否", "关于", "可以", "需要", "想问", "看看"
    );

    /**
     * 中文连续片段做二次分段时使用的连接词规则。
     */
    private static final Pattern CHINESE_SEGMENT_SPLIT_PATTERN = Pattern.compile("[的和及与或]");

    /**
     * 关键词通道最终最多保留的词项数。
     */
    private static final int MAX_KEYWORD_TERMS = 8;

    private final SuperAgentDocumentMapper documentMapper;
    private final SuperAgentDocumentParentBlockMapper parentBlockMapper;
    private final JdbcTemplate pgVectorJdbcTemplate;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final ObjectProvider<DocumentKeywordSearchGateway> keywordSearchGatewayProvider;
    private final DocumentManageProperties properties;

    public DocumentKnowledgeServiceImpl(SuperAgentDocumentMapper documentMapper,
                                        SuperAgentDocumentParentBlockMapper parentBlockMapper,
                                        @Qualifier("documentManagePgVectorJdbcTemplate") JdbcTemplate pgVectorJdbcTemplate,
                                        ObjectProvider<EmbeddingModel> embeddingModelProvider,
                                        ObjectProvider<DocumentKeywordSearchGateway> keywordSearchGatewayProvider,
                                        DocumentManageProperties properties) {
        this.documentMapper = documentMapper;
        this.parentBlockMapper = parentBlockMapper;
        this.pgVectorJdbcTemplate = pgVectorJdbcTemplate;
        this.embeddingModelProvider = embeddingModelProvider;
        this.keywordSearchGatewayProvider = keywordSearchGatewayProvider;
        this.properties = properties;
    }

    @Override
    public List<KnowledgeDocumentDescriptor> listRetrievableDocuments() {
        /*
         * 只有“已构建成功 + 存在 lastIndexTaskId”的文档，
         * 才能进入知识检索目录。
         */
        List<SuperAgentDocument> documents = documentMapper.selectList(new LambdaQueryWrapper<SuperAgentDocument>()
            .eq(SuperAgentDocument::getStatus, BusinessStatus.YES.getCode())
            .eq(SuperAgentDocument::getIndexStatus, DocumentIndexStatusEnum.BUILD_SUCCESS.getCode())
            .isNotNull(SuperAgentDocument::getLastIndexTaskId)
            .orderByDesc(SuperAgentDocument::getEditTime)
            .orderByDesc(SuperAgentDocument::getId));
        if (CollUtil.isEmpty(documents)) {
            return List.of();
        }

        return documents.stream()
            .map(document -> new KnowledgeDocumentDescriptor(
                document.getId(),
                document.getDocumentName(),
                document.getLastIndexTaskId(),
                document.getKnowledgeScopeCode(),
                document.getKnowledgeScopeName(),
                document.getBusinessCategory(),
                document.getDocumentTags()
            ))
            .toList();
    }

    @Override
    public List<Document> vectorSearch(DocumentRetrieveRequest request) {
        if (!isSearchableRequest(request)) {
            return List.of();
        }

        /*
         * 向量检索的第一步一定是把问题编码成 embedding。
         * 这里拿到的是 PostgreSQL vector 字面量，而不是直接给模型看的文本。
         */
        EmbeddingModel embeddingModel = requireEmbeddingModel();
        /*
         * 当前这里不再直接 embed 用户原问题，
         * 而是明确使用 retrievalQuery。
         *
         * 这一步是“短追问增强真正生效”的关键：
         * 如果这里仍然只吃原问题，那前面构造的上下文补全查询就只是摆设。
         */
        String questionVector = toVectorLiteral(embeddingModel.embed(request.getRetrievalQuery().trim()));
        List<Long> documentIds = List.of(request.getDocumentId());
        List<Long> taskIds = List.of(request.getTaskId());
        /*
         * descriptorMap 的作用不是检索，而是给后面的 Spring AI Document.metadata 补全文档级信息。
         */
        Map<Long, KnowledgeDocumentDescriptor> descriptorMap = listDescriptorMap(documentIds);
        /*
         * 当前教学版聊天链路已经要求前端显式选中“当前文档”，
         * 因此这里不再做跨文档范围的二次收缩。
         *
         * resolvedScope 现在只承担一件事：
         * 把固定文档范围和章节过滤提示一起传给底层检索。
         */
        ResolvedMetadataScope resolvedScope = resolveMetadataScope(request);
        if (resolvedScope.documentIds().isEmpty() || resolvedScope.taskIds().isEmpty()) {
            return List.of();
        }

        StringBuilder sqlBuilder = new StringBuilder(VECTOR_RETRIEVE_SQL_TEMPLATE.formatted(
            DocumentPgVectorConstants.EMBEDDING_TABLE_NAME,
            buildPlaceholders(resolvedScope.documentIds().size()),
            buildPlaceholders(resolvedScope.taskIds().size())
        ));
        appendSectionFilters(sqlBuilder, resolvedScope.filters());
        /*
         * section 过滤故意放在文档级范围收紧之后、真正相似度排序之前。
         * 这样顺序上会变成：
         * 1. 先把文档集合收紧到更可信的一小撮
         * 2. 再在这一小撮里按章节定位
         * 3. 最后才做向量距离排序
         *
         * 这比“先全量向量召回，再靠模型自己理解章节提示”稳定得多。
         */
        sqlBuilder.append("""
            
            ORDER BY embedding <=> CAST(? AS vector)
            LIMIT ?
            """);

        List<Object> params = new ArrayList<>();
        /*
         * 同一个 queryVector 会在 SQL 里用两次：
         * 1. 计算 similarity_score
         * 2. ORDER BY 向量距离
         */
        params.add(questionVector);
        params.addAll(resolvedScope.documentIds());
        params.addAll(resolvedScope.taskIds());
        appendSectionFilterParams(params, resolvedScope.filters());
        params.add(questionVector);
        params.add(resolveTopK(request.getTopK()));

        return pgVectorJdbcTemplate.query(sqlBuilder.toString(), params.toArray(), (resultSet, rowNum) -> {
            long chunkId = resultSet.getLong("id");
            long documentId = resultSet.getLong("document_id");
            double score = resultSet.getDouble("similarity_score");
            KnowledgeDocumentDescriptor descriptor = descriptorMap.get(documentId);
            return buildRetrievedDocument(
                chunkId,
                resultSet.getString("chunk_text"),
                resultSet.getLong("task_id"),
                resultSet.getLong("parent_block_id"),
                resultSet.getInt("chunk_no"),
                resultSet.getString("section_path"),
                descriptor,
                "vector",
                score
            );
        });
    }

    @Override
    public List<Document> keywordSearch(DocumentRetrieveRequest request) {
        if (!isSearchableRequest(request)) {
            return List.of();
        }

        /*
         * 关键词检索的主路径现在切到 Elasticsearch。
         * 这样章节标题、专有名词、型号和短语匹配会比 SQL LIKE 稳定得多。
         *
         * 当前仍然保留下面的 SQL fallback，
         * 目的是在 ES 暂时不可用或被显式关闭时，系统仍然有一条可运行的兜底路径。
         */
        List<Long> documentIds = List.of(request.getDocumentId());
        List<Long> taskIds = List.of(request.getTaskId());
        Map<Long, KnowledgeDocumentDescriptor> descriptorMap = listDescriptorMap(documentIds);
        ResolvedMetadataScope resolvedScope = resolveMetadataScope(request);
        if (resolvedScope.documentIds().isEmpty() || resolvedScope.taskIds().isEmpty()) {
            return List.of();
        }
        /*
         * 关键词主路径走 ES 时，也要把固定文档范围和章节过滤一起下沉。
         * 这样像“附录 A”“第 3 章”这类显式定位线索，才能真正作用到倒排检索层。
         */
        DocumentRetrieveRequest filteredRequest = new DocumentRetrieveRequest(
            request.getQuestion(),
            request.getRetrievalQuery(),
            resolvedScope.documentIds().get(0),
            resolvedScope.taskIds().get(0),
            request.getTopK(),
            resolvedScope.filters(),
            request.getQueryContextHints()
        );

        DocumentKeywordSearchGateway keywordSearchGateway = keywordSearchGatewayProvider.getIfAvailable();
        if (Boolean.TRUE.equals(properties.getElasticsearch().getEnabled()) && keywordSearchGateway != null) {
            return keywordSearchGateway.search(filteredRequest);
        }

        /*
         * 关键词检索先把问题拆成“适合 LIKE 命中”的词项。
         * 如果一个词项都提不出来，就说明当前问题不适合走这条通道，直接返回空结果。
         */
        /*
         * SQL fallback 也要和 ES / 向量检索保持同一口径：
         * 主查询词项统一从 retrievalQuery 提取，而不是退回到原问题。
         * 这样“这个呢 / 那第二条呢”这类短追问在 fallback 路径里也不会重新掉上下文。
         */
        List<String> terms = new ArrayList<>(extractKeywordTerms(request.getRetrievalQuery()));
        terms.addAll(extractAuxiliaryKeywordTerms(request.getQueryContextHints()));
        terms = new ArrayList<>(new LinkedHashSet<>(terms));
        if (terms.isEmpty()) {
            return List.of();
        }

        String scoreExpression = buildKeywordScoreExpression(terms.size());
        String whereExpression = buildKeywordWhereExpression(terms.size());
        StringBuilder sqlBuilder = new StringBuilder(KEYWORD_RETRIEVE_SQL_TEMPLATE.formatted(
            scoreExpression,
            DocumentPgVectorConstants.EMBEDDING_TABLE_NAME,
            buildPlaceholders(resolvedScope.documentIds().size()),
            buildPlaceholders(resolvedScope.taskIds().size()),
            whereExpression
        ));
        appendSectionFilters(sqlBuilder, resolvedScope.filters());
        sqlBuilder.append("""

            ORDER BY keyword_score DESC, chunk_no ASC, id ASC
            LIMIT ?
            """);

        List<Object> params = new ArrayList<>();

        /*
         * scoreExpression 需要“命中模式 + 权重”两类参数，
         * 顺序必须和 CASE WHEN 片段保持完全一致。
         */
        for (int index = 0; index < terms.size(); index++) {
            String pattern = likePattern(terms.get(index));
            /*
             * 这里不是简单传一个 LIKE 模式，而是同时传：
             * 1. 正文命中模式与权重
             * 2. 章节路径命中模式与权重
             *
             * 这样 SQL 层就能先做一轮粗粒度 lexical score 排序。
             */
            params.add(pattern);
            params.add(keywordWeight(index));
            params.add(pattern);
            params.add(sectionKeywordWeight(index));
        }

        params.addAll(resolvedScope.documentIds());
        params.addAll(resolvedScope.taskIds());

        /*
         * WHERE 子句里的 OR 条件和前面的 scoreExpression 是分开的，
         * 因此模式参数需要再追加一遍。
         */
        for (String term : terms) {
            params.add(likePattern(term));
            params.add(likePattern(term));
        }
        appendSectionFilterParams(params, resolvedScope.filters());
        params.add(resolveTopK(request.getTopK()));

        return pgVectorJdbcTemplate.query(sqlBuilder.toString(), params.toArray(), (resultSet, rowNum) -> {
            long chunkId = resultSet.getLong("id");
            long documentId = resultSet.getLong("document_id");
            double score = resultSet.getDouble("keyword_score");
            KnowledgeDocumentDescriptor descriptor = descriptorMap.get(documentId);
            return buildRetrievedDocument(
                chunkId,
                resultSet.getString("chunk_text"),
                resultSet.getLong("task_id"),
                resultSet.getLong("parent_block_id"),
                resultSet.getInt("chunk_no"),
                resultSet.getString("section_path"),
                descriptor,
                "keyword",
                score
            );
        });
    }

    @Override
    public List<Document> elevateToParentBlocks(List<Document> childDocuments, int maxChars) {
        if (CollUtil.isEmpty(childDocuments)) {
            return List.of();
        }

        /*
         * Parent-Child 结构里的核心切换点就在这里：
         * - Child 负责召回
         * - Parent 负责回答
         *
         * 所以这一步不再像旧版那样去“回捞邻近 chunk 拼大一点”，
         * 而是直接把命中的 child 提升成稳定的 parent block。
         */
        Map<Long, List<Document>> childGroupsByParent = new LinkedHashMap<>();
        List<Document> fallbackDocuments = new ArrayList<>();
        for (Document childDocument : childDocuments) {
            if (childDocument == null) {
                continue;
            }
            Long parentBlockId = asLong(childDocument.getMetadata().get(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID));
            if (parentBlockId == null) {
                fallbackDocuments.add(childDocument);
                continue;
            }
            childGroupsByParent.computeIfAbsent(parentBlockId, ignored -> new ArrayList<>()).add(childDocument);
        }

        if (childGroupsByParent.isEmpty()) {
            return fallbackDocuments;
        }

        List<Long> parentBlockIds = new ArrayList<>(childGroupsByParent.keySet());
        Map<Long, SuperAgentDocumentParentBlock> parentBlockMap = parentBlockMapper.selectList(
                new LambdaQueryWrapper<SuperAgentDocumentParentBlock>()
                    .in(SuperAgentDocumentParentBlock::getId, parentBlockIds)
                    .eq(SuperAgentDocumentParentBlock::getStatus, BusinessStatus.YES.getCode())
                    .orderByAsc(SuperAgentDocumentParentBlock::getParentNo)
            ).stream()
            .collect(Collectors.toMap(
                SuperAgentDocumentParentBlock::getId,
                parent -> parent,
                (left, right) -> left,
                LinkedHashMap::new
            ));

        List<Document> elevatedDocuments = new ArrayList<>(childGroupsByParent.size() + fallbackDocuments.size());
        for (Map.Entry<Long, List<Document>> entry : childGroupsByParent.entrySet()) {
            SuperAgentDocumentParentBlock parentBlock = parentBlockMap.get(entry.getKey());
            if (parentBlock == null) {
                elevatedDocuments.addAll(entry.getValue());
                continue;
            }
            elevatedDocuments.add(buildParentEvidenceDocument(parentBlock, entry.getValue(), maxChars));
        }
        elevatedDocuments.addAll(fallbackDocuments);
        return elevatedDocuments;
    }

    /**
     * 把数据库行统一映射成 Spring AI {@link Document}。
     *
     * <p>后续混合检索、重排序和 Prompt 装配都会直接消费这个对象，
     * 因此 metadata 必须一次性补齐，避免上层再回数据库补查。</p>
     */
    private Document buildRetrievedDocument(long chunkId,
                                            String chunkText,
                                            long taskId,
                                            long parentBlockId,
                                            int chunkNo,
                                            String sectionPath,
                                            KnowledgeDocumentDescriptor descriptor,
                                            String channel,
                                            double score) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        /*
         * Spring AI 的 Document.metadata 明确不允许出现 null value。
         * 文档切片里的 sectionPath / knowledgeScopeName 这类字段在数据库里本来就是可空的，
         * 如果这里直接 put(null)，就会在 Document.builder().metadata(...) 阶段抛出：
         * “metadata cannot have null values”。
         *
         * 因此这里统一做一次“无 null 元数据”规整：
         * - 数值型且业务上必填的字段直接写入
         * - 字符串型可空字段统一降级成空串
         */
        metadata.put(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "DOCUMENT");
        metadata.put(DocumentKnowledgeMetadataKeys.CHANNEL, channel);
        metadata.put(DocumentKnowledgeMetadataKeys.SCORE, score);
        metadata.put(DocumentKnowledgeMetadataKeys.CHUNK_ID, chunkId);
        metadata.put(DocumentKnowledgeMetadataKeys.TASK_ID, taskId);
        metadata.put(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID, parentBlockId);
        metadata.put(DocumentKnowledgeMetadataKeys.CHUNK_NO, chunkNo);
        metadata.put(DocumentKnowledgeMetadataKeys.SECTION_PATH, safeText(sectionPath));
        metadata.put(DocumentKnowledgeMetadataKeys.ORIGINAL_SNIPPET, chunkText);
        if (descriptor != null) {
            /*
             * 文档级 metadata 统一在这里一次性写全，
             * 后面的检索引擎、Prompt 装配和前端展示都只读 metadata，不再回表补查。
             */
            metadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_ID, descriptor.getDocumentId());
            metadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME, safeText(descriptor.getDocumentName()));
            metadata.put(DocumentKnowledgeMetadataKeys.KNOWLEDGE_SCOPE_CODE, safeText(descriptor.getKnowledgeScopeCode()));
            metadata.put(DocumentKnowledgeMetadataKeys.KNOWLEDGE_SCOPE_NAME, safeText(descriptor.getKnowledgeScopeName()));
            metadata.put(DocumentKnowledgeMetadataKeys.BUSINESS_CATEGORY, safeText(descriptor.getBusinessCategory()));
            metadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_TAGS, safeText(descriptor.getDocumentTags()));
        }

        return Document.builder()
            .id(String.valueOf(chunkId))
            .text(chunkText)
            .metadata(metadata)
            .score(score)
            .build();
    }

    /**
     * 请求是否具备最小检索条件。
     */
    private boolean isSearchableRequest(DocumentRetrieveRequest request) {
        /*
         * 这里做的是“最小必要条件校验”，而不是完整业务校验。
         * 只要原问题、retrievalQuery、documentId、taskId 任一缺失，
         * 就说明这次检索请求根本没法执行。
         */
        if (request == null || StrUtil.isBlank(request.getQuestion()) || StrUtil.isBlank(request.getRetrievalQuery())) {
            return false;
        }
        return request.getDocumentId() != null && request.getTaskId() != null;
    }

    /**
     * 把请求中的文档主键映射成检索目录，便于后续补全文档级 metadata。
     */
    private Map<Long, KnowledgeDocumentDescriptor> listDescriptorMap(List<Long> requestedDocumentIds) {
        List<KnowledgeDocumentDescriptor> descriptors = listRetrievableDocuments();
        if (descriptors.isEmpty()) {
            return Map.of();
        }
        /*
         * 这里只保留“当前请求真的会查到的文档描述对象”，
         * 避免把整个知识目录都塞进内存映射里浪费空间。
         */
        return descriptors.stream()
            .filter(descriptor -> requestedDocumentIds.contains(descriptor.getDocumentId()))
            .collect(Collectors.toMap(
                KnowledgeDocumentDescriptor::getDocumentId,
                descriptor -> descriptor,
                (left, right) -> left,
                LinkedHashMap::new
            ));
    }

    private ResolvedMetadataScope resolveMetadataScope(DocumentRetrieveRequest request) {
        List<Long> baseDocumentIds = request.getDocumentId() == null ? List.of() : List.of(request.getDocumentId());
        List<Long> baseTaskIds = request.getTaskId() == null ? List.of() : List.of(request.getTaskId());
        return new ResolvedMetadataScope(baseDocumentIds, baseTaskIds, request.getFilters());
    }

    private void appendSectionFilters(StringBuilder sqlBuilder, DocumentRetrieveFilters filters) {
        boolean hasSectionHints = filters != null && CollUtil.isNotEmpty(filters.getSectionPathHints());
        if (!hasSectionHints) {
            return;
        }
        /*
         * 章节过滤对应的是“显式定位到某个章节 / 附录 / 条款”的强线索。
         * 一旦命中这类线索，我们更希望在检索层先把范围收窄，
         * 而不是把所有候选都交给后面的语义排序去碰运气。
         */
        sqlBuilder.append("\n  AND (");
        for (int index = 0; index < filters.getSectionPathHints().size(); index++) {
            if (index > 0) {
                sqlBuilder.append(" OR ");
            }
            sqlBuilder.append("LOWER(COALESCE(section_path, '')) LIKE ?");
        }
        sqlBuilder.append(")");
    }

    private void appendSectionFilterParams(List<Object> params, DocumentRetrieveFilters filters) {
        if (filters != null && CollUtil.isNotEmpty(filters.getSectionPathHints())) {
            for (String sectionHint : filters.getSectionPathHints()) {
                params.add("%" + sectionHint.toLowerCase(Locale.ROOT) + "%");
            }
        }
    }

    private Document buildParentEvidenceDocument(SuperAgentDocumentParentBlock parentBlock,
                                                 List<Document> childDocuments,
                                                 int maxChars) {
        Document bestChild = childDocuments.stream()
            .max(Comparator.comparingDouble(document -> {
                Double score = resolveScore(document);
                return score == null ? 0D : score;
            }))
            .orElseThrow();

        double parentScore = aggregateParentScore(childDocuments);
        Map<String, Object> metadata = new LinkedHashMap<>(bestChild.getMetadata());
        metadata.put(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID, parentBlock.getId());
        metadata.put(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_NO, parentBlock.getParentNo());
        metadata.put(DocumentKnowledgeMetadataKeys.SECTION_PATH, safeText(parentBlock.getSectionPath()));
        metadata.put(DocumentKnowledgeMetadataKeys.SCORE, parentScore);
        metadata.put(DocumentKnowledgeMetadataKeys.ORIGINAL_SNIPPET, safeText(parentBlock.getParentText()));

        LinkedHashSet<String> channels = childDocuments.stream()
            .map(document -> asText(document.getMetadata().get(DocumentKnowledgeMetadataKeys.CHANNEL)))
            .filter(StrUtil::isNotBlank)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        metadata.put(DocumentKnowledgeMetadataKeys.CHANNEL,
            channels.size() > 1 ? "hybrid" : channels.stream().findFirst().orElse("vector"));

        return Document.builder()
            .id("parent-" + parentBlock.getId())
            .text(renderParentEvidenceText(parentBlock, childDocuments, maxChars))
            .metadata(metadata)
            .score(parentScore)
            .build();
    }

    private double aggregateParentScore(List<Document> childDocuments) {
        double bestChildScore = childDocuments.stream()
            .map(this::resolveScore)
            .filter(Objects::nonNull)
            .max(Double::compareTo)
            .orElse(0D);
        int supportCount = Math.max(0, childDocuments.size() - 1);
        LinkedHashSet<String> channels = childDocuments.stream()
            .map(document -> asText(document.getMetadata().get(DocumentKnowledgeMetadataKeys.CHANNEL)))
            .filter(StrUtil::isNotBlank)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        double supportBonus = Math.min(0.24D, supportCount * 0.06D);
        double multiChannelBonus = channels.size() > 1 ? 0.08D : 0D;
        return bestChildScore + supportBonus + multiChannelBonus;
    }

    private String renderParentEvidenceText(SuperAgentDocumentParentBlock parentBlock,
                                            List<Document> childDocuments,
                                            int maxChars) {
        String parentText = safeText(parentBlock.getParentText());
        if (StrUtil.isBlank(parentText)) {
            return childDocuments.isEmpty() ? "" : StrUtil.blankToDefault(childDocuments.get(0).getText(), "");
        }

        StringBuilder hitSummaryBuilder = new StringBuilder();
        for (Document childDocument : childDocuments) {
            if (childDocument == null) {
                continue;
            }
            if (!hitSummaryBuilder.isEmpty()) {
                hitSummaryBuilder.append('\n');
            }
            hitSummaryBuilder.append("- child#")
                .append(asInteger(childDocument.getMetadata().get(DocumentKnowledgeMetadataKeys.CHUNK_NO)))
                .append("：")
                .append(trimText(safeText(childDocument.getText()), 140));
        }

        String composed = joinSections(
            "[父块内容]\n" + parentText,
            hitSummaryBuilder.isEmpty() ? "" : "[命中子片段]\n" + hitSummaryBuilder
        );
        return trimText(composed, Math.max(1, maxChars));
    }

    private Double resolveScore(Document document) {
        if (document == null) {
            return null;
        }
        Object metadataScore = document.getMetadata().get(DocumentKnowledgeMetadataKeys.SCORE);
        if (metadataScore instanceof Number number) {
            return number.doubleValue();
        }
        return document.getScore();
    }

    private String joinSections(String... sections) {
        List<String> parts = new ArrayList<>();
        for (String section : sections) {
            if (StrUtil.isNotBlank(section)) {
                parts.add(section.trim());
            }
        }
        return String.join("\n\n", parts);
    }

    private String trimText(String text, int maxChars) {
        if (StrUtil.isBlank(text) || text.length() <= maxChars) {
            return StrUtil.blankToDefault(text, "");
        }
        return text.substring(0, Math.max(0, maxChars - 1)) + "…";
    }

    /**
     * 提取关键词检索使用的查询项。
     *
     * <p>因为当前项目没有引入专门的中文检索引擎，
     * 这里用的是“轻量可运行”的启发式方案：</p>
     * <p>1. 先抓英文、数字、版本号这类强关键词。</p>
     * <p>2. 再抓中文连续片段，并切出 2~4 字的窗口词。</p>
     * <p>3. 最终只保留少量最值得用于 LIKE 命中的词项。</p>
     */
    private List<String> extractKeywordTerms(String question) {
        String normalized = normalizeQuestion(question);
        if (StrUtil.isBlank(normalized)) {
            return List.of();
        }

        LinkedHashSet<String> terms = new LinkedHashSet<>();
        /*
         * 第一轮先抓英文、数字、版本号、路径片段这类强关键词。
         * 这些内容通常是向量检索最容易弱化、但关键词检索最擅长命中的信息。
         */
        Matcher alnumMatcher = ALNUM_TOKEN_PATTERN.matcher(normalized);
        while (alnumMatcher.find()) {
            terms.add(alnumMatcher.group());
        }

        /*
         * 第二轮再抓中文连续片段。
         * 和旧实现不同的是，这里不再直接从整段文本头部一路切 2~4 字窗口，
         * 而是先做“问句噪音清洗 + 连词分段”，再提取候选词。
         *
         * 这样像“智能网关产品的协议配置”会优先得到：
         * - 智能网关产品
         * - 协议配置
         * 而不是“能网 / 网产 / 品协”这类被截坏的词。
         */
        Matcher chineseMatcher = CHINESE_TOKEN_PATTERN.matcher(normalized);
        while (chineseMatcher.find()) {
            for (String segment : splitChineseSegments(chineseMatcher.group())) {
                addChineseSegmentTerms(segment, terms);
                if (terms.size() >= MAX_KEYWORD_TERMS * 2) {
                    break;
                }
            }
            if (terms.size() >= MAX_KEYWORD_TERMS * 2) {
                break;
            }
        }

        return terms.stream()
            .filter(term -> term.length() >= 2)
            /*
             * 最终仍然要控制词项总数，避免 SQL 条件膨胀过长。
             * 但这里从旧实现的 6 提升到 8，
             * 防止真正关键的后置业务词被前面的泛词挤掉。
             */
            .limit(MAX_KEYWORD_TERMS)
            .toList();
    }

    /**
     * 把一段中文连续文本切成更适合关键词检索的语义片段。
     */
    private List<String> splitChineseSegments(String chineseToken) {
        String cleanedToken = removeChineseNoisePhrases(chineseToken);
        if (cleanedToken.length() < 2) {
            return List.of();
        }
        LinkedHashSet<String> segments = new LinkedHashSet<>();
        segments.add(cleanedToken);
        for (String segment : CHINESE_SEGMENT_SPLIT_PATTERN.split(cleanedToken)) {
            String normalizedSegment = segment == null ? "" : segment.trim();
            if (normalizedSegment.length() >= 2) {
                segments.add(normalizedSegment);
            }
        }
        return new ArrayList<>(segments);
    }

    private List<String> extractAuxiliaryKeywordTerms(List<String> hints) {
        if (CollUtil.isEmpty(hints)) {
            return List.of();
        }
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String hint : hints) {
            if (StrUtil.isBlank(hint)) {
                continue;
            }
            terms.addAll(extractKeywordTerms(hint));
            if (terms.size() >= MAX_KEYWORD_TERMS) {
                break;
            }
        }
        return new ArrayList<>(terms);
    }

    /**
     * 为单个中文片段追加一组按优先级排列的候选词。
     */
    private void addChineseSegmentTerms(String segment, LinkedHashSet<String> terms) {
        if (StrUtil.isBlank(segment) || segment.length() < 2) {
            return;
        }
        /*
         * 优先保留完整短语。
         * 这类词通常最接近用户真正想查的配置项或章节名。
         */
        if (segment.length() <= 12) {
            terms.add(segment);
        }
        addTailNgrams(segment, terms);
        addHeadNgrams(segment, terms);
        addSlidingNgrams(segment, terms);
    }

    /**
     * 生成关键词得分表达式。
     *
     * <p>命中越靠前的词，权重越大。
     * 这样像版本号、缩写、系统名这类强特征词会优先把结果顶上来。</p>
     */
    private String buildKeywordScoreExpression(int termCount) {
        return java.util.stream.IntStream.range(0, termCount)
            /*
             * 关键词粗排不再只看正文 chunk_text，
             * 还会同步利用 section_path 里的章节标题信号。
             * 对“3.2 协议配置”“第五章 Modbus 配置”这类场景，会明显更稳。
             */
            .mapToObj(index -> "("
                + "CASE WHEN LOWER(chunk_text) LIKE ? THEN ? ELSE 0 END + "
                + "CASE WHEN LOWER(COALESCE(section_path, '')) LIKE ? THEN ? ELSE 0 END"
                + ")")
            .collect(Collectors.joining(" + "));
    }

    /**
     * 生成关键词命中过滤条件。
     */
    private String buildKeywordWhereExpression(int termCount) {
        return java.util.stream.IntStream.range(0, termCount)
            .mapToObj(index -> "(LOWER(chunk_text) LIKE ? OR LOWER(COALESCE(section_path, '')) LIKE ?)")
            .collect(Collectors.joining(" OR "));
    }

    /**
     * 根据词的位置给一个简单的递减权重。
     */
    private int keywordWeight(int index) {
        /*
         * 越靠前的词项通常越“原始、完整”，所以给更高权重。
         * 这是一个非常轻量的启发式排序，不追求学术最优，但足够支撑当前粗排。
         */
        return Math.max(1, 6 - index);
    }

    /**
     * 章节标题命中通常比正文普通命中更强，因此给一档额外权重。
     */
    private int sectionKeywordWeight(int index) {
        return keywordWeight(index) + 2;
    }

    /**
     * 构造 LIKE 查询模式。
     */
    private String likePattern(String term) {
        return "%" + term.toLowerCase(Locale.ROOT) + "%";
    }

    /**
     * 规范化用户问题。
     */
    private String normalizeQuestion(String question) {
        if (StrUtil.isBlank(question)) {
            return "";
        }
        /*
         * 这里不做复杂 NLP 预处理，只做最必要的统一格式化。
         * 目的是让英文、数字、标点在关键词提取阶段更稳定。
         */
        return question.trim()
            .toLowerCase(Locale.ROOT)
            .replaceAll("[\\r\\n\\t]+", " ")
            .replaceAll("\\s+", " ");
    }

    /**
     * 去掉中文问题里的问句噪音短语，但不破坏业务关键词本体。
     */
    private String removeChineseNoisePhrases(String text) {
        if (StrUtil.isBlank(text)) {
            return "";
        }
        /*
         * 旧实现是字符级删除，副作用是会把“网关”的“关”误删。
         * 这里改成短语级清洗，只移除真正的问句噪音，不拆坏业务词。
         */
        String normalized = text.trim();
        for (String phrase : CHINESE_NOISE_PHRASES) {
            normalized = normalized.replace(phrase, "");
        }
        return normalized.trim();
    }

    private void addTailNgrams(String segment, LinkedHashSet<String> terms) {
        int maxGram = Math.min(4, segment.length());
        for (int size = maxGram; size >= 2 && terms.size() < MAX_KEYWORD_TERMS * 2; size--) {
            terms.add(segment.substring(segment.length() - size));
        }
    }

    private void addHeadNgrams(String segment, LinkedHashSet<String> terms) {
        int maxGram = Math.min(4, segment.length());
        for (int size = maxGram; size >= 2 && terms.size() < MAX_KEYWORD_TERMS * 2; size--) {
            terms.add(segment.substring(0, size));
        }
    }

    private void addSlidingNgrams(String segment, LinkedHashSet<String> terms) {
        int maxGram = Math.min(4, segment.length());
        for (int size = maxGram; size >= 2 && terms.size() < MAX_KEYWORD_TERMS * 2; size--) {
            for (int index = 0; index <= segment.length() - size && terms.size() < MAX_KEYWORD_TERMS * 2; index++) {
                terms.add(segment.substring(index, index + size));
            }
        }
    }

    /**
     * 把可空字符串字段规整成 Spring AI Document.metadata 可接受的非 null 值。
     */
    private String safeText(String text) {
        return text == null ? "" : text;
    }

    private Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private Integer asInteger(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private String asText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 统一处理 topK，防止单次检索拉太多候选。
     */
    private int resolveTopK(int topK) {
        /*
         * topK 对上层来说是“建议值”，最终仍然要在底层做一次保护：
         * 太小就兜底，太大就截断，避免单次召回量失控。
         */
        return topK <= 0 ? 10 : Math.min(topK, 50);
    }

    /**
     * 获取当前可用的 EmbeddingModel。
     */
    private EmbeddingModel requireEmbeddingModel() {
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            /*
             * 向量检索对 EmbeddingModel 是硬依赖。
             * 这里不做静默降级，是因为静默返回空结果会让排查问题更困难。
             */
            throw new IllegalStateException("当前未找到可用的 EmbeddingModel，无法执行向量检索。");
        }
        return embeddingModel;
    }

    /**
     * 把 float[] 转换成 PostgreSQL vector 字面量。
     */
    private String toVectorLiteral(float[] embedding) {
        if (embedding == null || embedding.length == 0) {
            throw new IllegalStateException("问题向量生成失败，无法执行检索。");
        }
        StringBuilder vectorBuilder = new StringBuilder("[");
        for (int index = 0; index < embedding.length; index++) {
            /*
             * PostgreSQL vector 列要求的是形如 [0.1,0.2,0.3] 的字面量格式，
             * 所以这里逐维拼接，而不是直接走数组 toString()。
             */
            if (index > 0) {
                vectorBuilder.append(',');
            }
            vectorBuilder.append(embedding[index]);
        }
        vectorBuilder.append(']');
        return vectorBuilder.toString();
    }

    /**
     * 组装 SQL IN 占位符。
     */
    private String buildPlaceholders(int size) {
        return java.util.stream.IntStream.range(0, size)
            .mapToObj(index -> "?")
            .collect(Collectors.joining(","));
    }

    private int defaultInteger(Integer value) {
        return Objects.requireNonNullElse(value, 0);
    }

    private record ResolvedMetadataScope(
        List<Long> documentIds,
        List<Long> taskIds,
        DocumentRetrieveFilters filters
    ) {
    }
}
