package org.javaup.ai.manage.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.manage.config.DocumentManageProperties;
import org.javaup.ai.manage.data.SuperAgentDocument;
import org.javaup.ai.manage.data.SuperAgentDocumentChunk;
import org.javaup.ai.manage.mapper.SuperAgentDocumentMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentChunkMapper;
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
            chunk_no,
            section_path,
            page_no,
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
            chunk_no,
            section_path,
            page_no,
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
    private final SuperAgentDocumentChunkMapper documentChunkMapper;
    private final JdbcTemplate pgVectorJdbcTemplate;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final ObjectProvider<DocumentKeywordSearchGateway> keywordSearchGatewayProvider;
    private final DocumentManageProperties properties;

    public DocumentKnowledgeServiceImpl(SuperAgentDocumentMapper documentMapper,
                                        SuperAgentDocumentChunkMapper documentChunkMapper,
                                        @Qualifier("documentManagePgVectorJdbcTemplate") JdbcTemplate pgVectorJdbcTemplate,
                                        ObjectProvider<EmbeddingModel> embeddingModelProvider,
                                        ObjectProvider<DocumentKeywordSearchGateway> keywordSearchGatewayProvider,
                                        DocumentManageProperties properties) {
        this.documentMapper = documentMapper;
        this.documentChunkMapper = documentChunkMapper;
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
        String questionVector = toVectorLiteral(embeddingModel.embed(request.getQuestion().trim()));
        /*
         * documentIds / taskIds 都要先做一次去重，避免后面的 IN (...) 和参数列表出现重复值。
         */
        List<Long> documentIds = distinctIds(request.getDocumentIdList());
        List<Long> taskIds = distinctIds(request.getTaskIdList());
        /*
         * descriptorMap 的作用不是检索，而是给后面的 Spring AI Document.metadata 补全文档级信息。
         */
        Map<Long, KnowledgeDocumentDescriptor> descriptorMap = listDescriptorMap(documentIds);
        /*
         * 这里的 resolvedScope 不是“锦上添花的 metadata 优化”，
         * 而是本轮为了提升命中精度新增的核心收缩步骤。
         *
         * 它会先基于问题中的显式提示词，对候选文档做一次文档级再筛选：
         * - 文档名
         * - 业务分类
         * - 标签 / 年份
         *
         * 只有筛完之后真正保留下来的 documentId/taskId 才会进入底层 SQL / ES 检索。
         * 这样像“2024 部署手册第 12 页”这类问题，就不会还在所有已选 scope 里盲搜。
         */
        ResolvedMetadataScope resolvedScope = resolveMetadataScope(request, descriptorMap);
        if (resolvedScope.documentIds().isEmpty() || resolvedScope.taskIds().isEmpty()) {
            return List.of();
        }

        StringBuilder sqlBuilder = new StringBuilder(VECTOR_RETRIEVE_SQL_TEMPLATE.formatted(
            DocumentPgVectorConstants.EMBEDDING_TABLE_NAME,
            buildPlaceholders(resolvedScope.documentIds().size()),
            buildPlaceholders(resolvedScope.taskIds().size())
        ));
        appendPageFilters(sqlBuilder, resolvedScope.filters());
        /*
         * page/section 过滤故意放在文档级范围收紧之后、真正相似度排序之前。
         * 这样顺序上会变成：
         * 1. 先把文档集合收紧到更可信的一小撮
         * 2. 再在这一小撮里按页码/章节定位
         * 3. 最后才做向量距离排序
         *
         * 这比“先全量向量召回，再靠模型自己理解页码提示”稳定得多。
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
        appendPageFilterParams(params, resolvedScope.filters());
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
                resultSet.getInt("chunk_no"),
                resultSet.getString("section_path"),
                resultSet.getString("page_no"),
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
        List<Long> documentIds = distinctIds(request.getDocumentIdList());
        List<Long> taskIds = distinctIds(request.getTaskIdList());
        Map<Long, KnowledgeDocumentDescriptor> descriptorMap = listDescriptorMap(documentIds);
        ResolvedMetadataScope resolvedScope = resolveMetadataScope(request, descriptorMap);
        if (resolvedScope.documentIds().isEmpty() || resolvedScope.taskIds().isEmpty()) {
            return List.of();
        }
        /*
         * 关键词主路径走 ES 时，也要带着过滤后的 documentId/taskId 和 filters 一起下沉。
         * 否则就会出现：
         * Java 侧已经识别出“这是 2024 部署手册第 12 页”，
         * 但 ES 侧仍然按原始大范围去搜，导致前面的 metadata filter 白做了。
         */
        DocumentRetrieveRequest filteredRequest = new DocumentRetrieveRequest(
            request.getQuestion(),
            resolvedScope.documentIds(),
            resolvedScope.taskIds(),
            request.getTopK(),
            resolvedScope.filters()
        );

        DocumentKeywordSearchGateway keywordSearchGateway = keywordSearchGatewayProvider.getIfAvailable();
        if (Boolean.TRUE.equals(properties.getElasticsearch().getEnabled()) && keywordSearchGateway != null) {
            return keywordSearchGateway.search(filteredRequest);
        }

        /*
         * 关键词检索先把问题拆成“适合 LIKE 命中”的词项。
         * 如果一个词项都提不出来，就说明当前问题不适合走这条通道，直接返回空结果。
         */
        List<String> terms = new ArrayList<>(extractKeywordTerms(request.getQuestion()));
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
        appendPageFilters(sqlBuilder, resolvedScope.filters());
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
        appendPageFilterParams(params, resolvedScope.filters());
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
                resultSet.getInt("chunk_no"),
                resultSet.getString("section_path"),
                resultSet.getString("page_no"),
                descriptor,
                "keyword",
                score
            );
        });
    }

    @Override
    public List<Document> expandContext(List<Document> documents, int neighborWindow, int maxChars) {
        if (CollUtil.isEmpty(documents) || neighborWindow <= 0 || maxChars <= 0) {
            return documents;
        }
        List<Document> expanded = new ArrayList<>(documents.size());
        for (Document document : documents) {
            /*
             * 这里只对内部文档证据做邻近扩展：
             * - WEB 证据本来就是“搜索引擎摘要”，没有稳定 chunk 邻居可回查
             * - DOCUMENT 证据则有 documentId/taskId/chunkNo，可用来定位前后片段
             */
            if (document == null || !"DOCUMENT".equalsIgnoreCase(String.valueOf(document.getMetadata().get(DocumentKnowledgeMetadataKeys.SOURCE_TYPE)))) {
                expanded.add(document);
                continue;
            }
            Long documentId = asLong(document.getMetadata().get(DocumentKnowledgeMetadataKeys.DOCUMENT_ID));
            Long taskId = asLong(document.getMetadata().get(DocumentKnowledgeMetadataKeys.TASK_ID));
            Integer chunkNo = asInteger(document.getMetadata().get(DocumentKnowledgeMetadataKeys.CHUNK_NO));
            String sectionPath = asText(document.getMetadata().get(DocumentKnowledgeMetadataKeys.SECTION_PATH));
            if (documentId == null || taskId == null || chunkNo == null) {
                expanded.add(document);
                continue;
            }
            List<SuperAgentDocumentChunk> neighbors = documentChunkMapper.selectList(
                new LambdaQueryWrapper<SuperAgentDocumentChunk>()
                    .eq(SuperAgentDocumentChunk::getDocumentId, documentId)
                    .eq(SuperAgentDocumentChunk::getTaskId, taskId)
                    /*
                     * 这里优先在同 sectionPath 下扩展邻居。
                     * 这样拿回来的上下文更像“同一章节里的自然上下文”，
                     * 比单纯按 chunkNo 前后硬取更不容易跨到完全不同的主题段落。
                     */
                    .eq(StrUtil.isNotBlank(sectionPath), SuperAgentDocumentChunk::getSectionPath, sectionPath)
                    .between(SuperAgentDocumentChunk::getChunkNo, Math.max(0, chunkNo - neighborWindow), chunkNo + neighborWindow)
                    .orderByAsc(SuperAgentDocumentChunk::getChunkNo)
            );
            if (neighbors == null || neighbors.isEmpty()) {
                expanded.add(document);
                continue;
            }
            String expandedText = buildExpandedContext(neighbors, chunkNo, maxChars, document.getText());
            if (StrUtil.isBlank(expandedText) || expandedText.equals(document.getText())) {
                expanded.add(document);
                continue;
            }
            Map<String, Object> metadata = new LinkedHashMap<>(document.getMetadata());
            metadata.put(DocumentKnowledgeMetadataKeys.ORIGINAL_SNIPPET, document.getText());
            expanded.add(Document.builder()
                .id(document.getId())
                .text(expandedText)
                .metadata(metadata)
                .score(document.getScore())
                .build());
        }
        return expanded;
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
                                            int chunkNo,
                                            String sectionPath,
                                            String pageNo,
                                            KnowledgeDocumentDescriptor descriptor,
                                            String channel,
                                            double score) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        /*
         * Spring AI 的 Document.metadata 明确不允许出现 null value。
         * 文档切片里的 sectionPath / pageNo / knowledgeScopeName 这类字段在数据库里本来就是可空的，
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
        metadata.put(DocumentKnowledgeMetadataKeys.CHUNK_NO, chunkNo);
        metadata.put(DocumentKnowledgeMetadataKeys.SECTION_PATH, safeText(sectionPath));
        metadata.put(DocumentKnowledgeMetadataKeys.PAGE_NO, safeText(pageNo));
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
         * 只要 question / documentIds / taskIds 任一缺失，就说明这次检索请求根本没法执行。
         */
        if (request == null || StrUtil.isBlank(request.getQuestion())) {
            return false;
        }
        return !CollUtil.isEmpty(request.getDocumentIdList()) && !CollUtil.isEmpty(request.getTaskIdList());
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

    private ResolvedMetadataScope resolveMetadataScope(DocumentRetrieveRequest request,
                                                       Map<Long, KnowledgeDocumentDescriptor> descriptorMap) {
        List<Long> baseDocumentIds = distinctIds(request.getDocumentIdList());
        List<Long> baseTaskIds = distinctIds(request.getTaskIdList());
        if (descriptorMap.isEmpty()) {
            return new ResolvedMetadataScope(baseDocumentIds, baseTaskIds, request.getFilters());
        }
        /*
         * 这个方法解决的是“selectedDocumentIds 仍然太大”的问题。
         * 前面的 KnowledgeScopeResolver 把范围收到了某几个知识域，
         * 但一个知识域下面仍然可能挂很多文档版本、手册、FAQ、部署说明。
         *
         * 因此这里再利用 metadata hints 做一层文档级重打分：
         * - 如果 hints 很明确，就只保留最接近的那一簇文档
         * - 如果 hints 不明确，就回退到原始范围，不做过度裁剪
         */
        Map<Long, Integer> scoreMap = new LinkedHashMap<>();
        DocumentRetrieveFilters filters = request.getFilters();
        String normalizedQuestion = normalizeQuestion(request.getQuestion());
        for (KnowledgeDocumentDescriptor descriptor : descriptorMap.values()) {
            int score = scoreDescriptor(normalizedQuestion, descriptor, filters);
            if (score > 0) {
                scoreMap.put(descriptor.getDocumentId(), score);
            }
        }
        if (scoreMap.isEmpty()) {
            return new ResolvedMetadataScope(baseDocumentIds, baseTaskIds, filters);
        }
        /*
         * 这里不用“只保留第一名”而是保留接近 top score 的候选簇，
         * 是为了兼顾两个目标：
         * 1. 避免一条轻微误判就把真正相关文档全裁掉
         * 2. 避免弱命中文档继续大面积混入后续检索
         */
        int topScore = scoreMap.values().stream().max(Integer::compareTo).orElse(0);
        int acceptedFloor = Math.max(2, (int) Math.ceil(topScore * 0.7D));
        List<Long> filteredDocumentIds = scoreMap.entrySet().stream()
            .filter(entry -> entry.getValue() >= acceptedFloor)
            .map(Map.Entry::getKey)
            .toList();
        List<Long> filteredTaskIds = descriptorMap.values().stream()
            .filter(descriptor -> filteredDocumentIds.contains(descriptor.getDocumentId()))
            .map(KnowledgeDocumentDescriptor::getLastIndexTaskId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        return new ResolvedMetadataScope(filteredDocumentIds, filteredTaskIds, filters);
    }

    private int scoreDescriptor(String normalizedQuestion,
                                KnowledgeDocumentDescriptor descriptor,
                                DocumentRetrieveFilters filters) {
        int score = 0;
        if (descriptor == null || StrUtil.isBlank(normalizedQuestion)) {
            return score;
        }
        /*
         * 这里不是做“完美意图分类”，而是做一个很务实的文档级粗筛分：
         * - 问题直接提到了业务分类 / 文档名 / 标签，就给高权重
         * - filters 里解析出来的文档名、年份、标签提示，再额外加分
         *
         * 只要能把“明显更像这一问对应的文档”推到前面，
         * 后面的向量 / BM25 细排就会轻松很多。
         */
        String normalizedDocumentName = normalizeQuestion(descriptor.getDocumentName());
        String normalizedBusinessCategory = normalizeQuestion(descriptor.getBusinessCategory());
        List<String> descriptorTags = splitTags(descriptor.getDocumentTags());
        if (StrUtil.isNotBlank(normalizedBusinessCategory) && normalizedQuestion.contains(normalizedBusinessCategory)) {
            score += 5;
        }
        if (StrUtil.isNotBlank(normalizedDocumentName) && normalizedQuestion.contains(normalizedDocumentName)) {
            score += 5;
        }
        for (String tag : descriptorTags) {
            String normalizedTag = normalizeQuestion(tag);
            if (StrUtil.isNotBlank(normalizedTag) && normalizedQuestion.contains(normalizedTag)) {
                score += 4;
            }
        }
        if (filters != null) {
            score += countMatches(normalizedDocumentName, filters.getDocumentNameHints(), 4);
            score += countMatches(normalizedBusinessCategory, filters.getBusinessCategoryHints(), 4);
            score += countMatches(descriptorTags, filters.getDocumentTagHints(), 3);
            score += countMatches(normalizedDocumentName, filters.getYearHints(), 2);
            score += countMatches(descriptorTags, filters.getYearHints(), 2);
        }
        return score;
    }

    private int countMatches(String target, List<String> hints, int weight) {
        if (StrUtil.isBlank(target) || CollUtil.isEmpty(hints)) {
            return 0;
        }
        int score = 0;
        for (String hint : hints) {
            String normalizedHint = normalizeQuestion(hint);
            if (StrUtil.isNotBlank(normalizedHint) && target.contains(normalizedHint)) {
                score += weight;
            }
        }
        return score;
    }

    private int countMatches(List<String> targets, List<String> hints, int weight) {
        if (CollUtil.isEmpty(targets) || CollUtil.isEmpty(hints)) {
            return 0;
        }
        int score = 0;
        for (String target : targets) {
            score += countMatches(normalizeQuestion(target), hints, weight);
        }
        return score;
    }

    private void appendPageFilters(StringBuilder sqlBuilder, DocumentRetrieveFilters filters) {
        boolean hasPageHints = filters != null && CollUtil.isNotEmpty(filters.getPageHints());
        boolean hasSectionHints = filters != null && CollUtil.isNotEmpty(filters.getSectionPathHints());
        if (!hasPageHints && !hasSectionHints) {
            return;
        }
        /*
         * 页码和章节过滤拆开写，是因为它们语义上是“两个可独立命中的定位线索”：
         * - pageHints 解决“第 12 页 / p12”
         * - sectionPathHints 解决“第 3 章 / 附录 A / 第十条”
         *
         * 两者都命中时，相当于进一步把候选收得更准。
         */
        if (hasPageHints) {
            sqlBuilder.append("\n  AND (");
            for (int index = 0; index < filters.getPageHints().size(); index++) {
                if (index > 0) {
                    sqlBuilder.append(" OR ");
                }
                sqlBuilder.append("LOWER(COALESCE(page_no, '')) LIKE ?");
            }
            sqlBuilder.append(")");
        }
        if (hasSectionHints) {
            sqlBuilder.append("\n  AND (");
            for (int index = 0; index < filters.getSectionPathHints().size(); index++) {
                if (index > 0) {
                    sqlBuilder.append(" OR ");
                }
                sqlBuilder.append("LOWER(COALESCE(section_path, '')) LIKE ?");
            }
            sqlBuilder.append(")");
        }
    }

    private void appendPageFilterParams(List<Object> params, DocumentRetrieveFilters filters) {
        if (filters != null && CollUtil.isNotEmpty(filters.getPageHints())) {
            for (String pageHint : filters.getPageHints()) {
                params.add("%" + pageHint.toLowerCase(Locale.ROOT) + "%");
            }
        }
        if (filters != null && CollUtil.isNotEmpty(filters.getSectionPathHints())) {
            for (String sectionHint : filters.getSectionPathHints()) {
                params.add("%" + sectionHint.toLowerCase(Locale.ROOT) + "%");
            }
        }
    }

    private String buildExpandedContext(List<SuperAgentDocumentChunk> neighbors,
                                        int hitChunkNo,
                                        int maxChars,
                                        String fallbackText) {
        List<SuperAgentDocumentChunk> sorted = neighbors.stream()
            .filter(chunk -> chunk != null && StrUtil.isNotBlank(chunk.getChunkText()))
            .sorted(Comparator.comparingInt(chunk -> defaultInteger(chunk.getChunkNo())))
            .toList();
        if (sorted.isEmpty()) {
            return fallbackText;
        }
        String hitText = fallbackText;
        StringBuilder before = new StringBuilder();
        StringBuilder after = new StringBuilder();
        /*
         * 这里不是简单把邻居全拼起来，而是显式区分：
         * - before：命中块之前的邻居
         * - hitText：真正命中的块
         * - after：命中块之后的邻居
         *
         * 这样后面在 prompt 里保留下来的结构更清楚，
         * 模型也更容易知道哪段是“命中核心证据”、哪段只是上下文补充。
         */
        for (SuperAgentDocumentChunk chunk : sorted) {
            if (defaultInteger(chunk.getChunkNo()) < hitChunkNo) {
                if (!before.isEmpty()) {
                    before.append('\n');
                }
                before.append(chunk.getChunkText().trim());
                continue;
            }
            if (defaultInteger(chunk.getChunkNo()) == hitChunkNo) {
                hitText = StrUtil.blankToDefault(chunk.getChunkText(), fallbackText).trim();
                continue;
            }
            if (!after.isEmpty()) {
                after.append('\n');
            }
            after.append(chunk.getChunkText().trim());
        }
        return trimExpandedContext(before.toString(), hitText, after.toString(), maxChars);
    }

    private String trimExpandedContext(String before, String hit, String after, int maxChars) {
        String hitSection = "[命中片段]\n" + StrUtil.blankToDefault(hit, "");
        String beforeSection = StrUtil.isBlank(before) ? "" : "[上文]\n" + before.trim();
        String afterSection = StrUtil.isBlank(after) ? "" : "[下文]\n" + after.trim();
        String candidate = joinNonBlank(beforeSection, hitSection, afterSection);
        /*
         * 扩展上下文不是“越长越好”。
         * 这里优先保证命中片段本身尽量完整，再把剩余预算分给上下文：
         * 1. 如果整体没超长，直接保留
         * 2. 如果超长，先给 hitSection 留更大的预算
         * 3. before/after 只保留头尾摘要，避免上下文把命中片段淹掉
         */
        if (candidate.length() <= maxChars) {
            return candidate.trim();
        }
        int hitBudget = Math.min(Math.max(300, maxChars / 2), maxChars);
        String clippedHit = clipMiddle(hitSection, hitBudget);
        int remainingBudget = Math.max(0, maxChars - clippedHit.length());
        int beforeBudget = remainingBudget / 2;
        int afterBudget = remainingBudget - beforeBudget;
        String clippedBefore = clipTail(beforeSection, beforeBudget);
        String clippedAfter = clipHead(afterSection, afterBudget);
        return joinNonBlank(clippedBefore, clippedHit, clippedAfter).trim();
    }

    private String clipTail(String text, int maxChars) {
        if (StrUtil.isBlank(text) || text.length() <= maxChars) {
            return StrUtil.blankToDefault(text, "");
        }
        return "…" + text.substring(Math.max(0, text.length() - Math.max(0, maxChars - 1)));
    }

    private String clipHead(String text, int maxChars) {
        if (StrUtil.isBlank(text) || text.length() <= maxChars) {
            return StrUtil.blankToDefault(text, "");
        }
        return text.substring(0, Math.max(0, maxChars - 1)) + "…";
    }

    private String clipMiddle(String text, int maxChars) {
        if (StrUtil.isBlank(text) || text.length() <= maxChars) {
            return StrUtil.blankToDefault(text, "");
        }
        int head = Math.max(1, maxChars / 2);
        int tail = Math.max(1, maxChars - head - 1);
        return text.substring(0, head) + "…" + text.substring(text.length() - tail);
    }

    private String joinNonBlank(String left, String middle, String right) {
        List<String> parts = new ArrayList<>();
        if (StrUtil.isNotBlank(left)) {
            parts.add(left);
        }
        if (StrUtil.isNotBlank(middle)) {
            parts.add(middle);
        }
        if (StrUtil.isNotBlank(right)) {
            parts.add(right);
        }
        return String.join("\n\n", parts);
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
     * 去重并保持原顺序。
     */
    private List<Long> distinctIds(List<Long> ids) {
        return new ArrayList<>(new LinkedHashSet<>(ids));
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
