package org.javaup.ai.manage.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.manage.config.DocumentManageProperties;
import org.javaup.ai.manage.data.SuperAgentDocument;
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

import java.sql.ResultSet;
import java.sql.SQLException;
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
import java.util.stream.IntStream;


@Slf4j
@AllArgsConstructor
@Service
public class DocumentKnowledgeServiceImpl implements DocumentKnowledgeService {


    /*
    模板中有 3 个 %s 占位符：
    1.第一个 %s → 表名（DocumentPgVectorConstants.EMBEDDING_TABLE_NAME，通常是 document_embedding）
    2.第二个 %s → document_id IN (...) 的占位符（?,?,?...）
    3.第三个 %s → task_id IN (...) 的占位符（?,?,?...）
    ————————————————————————————————————————————————————————————————
    关键点：
    embedding <=> CAST(? AS vector)：<=> 是 pgvector 的余弦距离操作符，返回 0~2 之间的值（0 表示完全相同，2 表示完全相反）
    1 - distance = 相似度分数，范围 -11，实际上大多数情况在 01 之间
    %s 占位符会被替换成实际的表名和 IN 参数占位符
     */
    private static final String VECTOR_RETRIEVE_SQL_TEMPLATE = """
        SELECT
            id,
            document_id,
            task_id,
            parent_block_id,
            chunk_no,
            section_path,
            structure_node_id,
            structure_node_type,
            canonical_path,
            item_index,
            chunk_text,
            1 - (embedding <=> CAST(? AS vector)) AS similarity_score
        FROM %s
        WHERE status = 1
          AND document_id IN (%s)
          AND task_id IN (%s)
        """;

    private static final String KEYWORD_RETRIEVE_SQL_TEMPLATE = """
        SELECT
            id,
            document_id,
            task_id,
            parent_block_id,
            chunk_no,
            section_path,
            structure_node_id,
            structure_node_type,
            canonical_path,
            item_index,
            chunk_text,
            (%s) AS keyword_score
        FROM %s
        WHERE status = 1
          AND document_id IN (%s)
          AND task_id IN (%s)
          AND (%s)
        """;

    private static final Pattern ALNUM_TOKEN_PATTERN = Pattern.compile("[a-z0-9._-]{2,}");

    private static final Pattern CHINESE_TOKEN_PATTERN = Pattern.compile("[\\p{IsHan}]{2,}");

    private static final List<String> CHINESE_NOISE_PHRASES = List.of(
        "请问", "帮我", "一下子", "一下", "如何", "怎么", "什么", "哪个", "这个", "那个", "是否", "关于", "可以", "需要", "想问", "看看"
    );

    private static final Pattern CHINESE_SEGMENT_SPLIT_PATTERN = Pattern.compile("[的和及与或]");

    private static final int MAX_KEYWORD_TERMS = 8;

    private final SuperAgentDocumentMapper documentMapper;
    
    private final SuperAgentDocumentParentBlockMapper parentBlockMapper;
    
    @Qualifier("documentManagePgVectorJdbcTemplate")
    private final JdbcTemplate pgVectorJdbcTemplate;
    
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    
    private final ObjectProvider<DocumentKeywordSearchGateway> keywordSearchGatewayProvider;
    
    private final DocumentManageProperties properties;


    /*
    调用listRetrievableDocuments() 获取所有可检索文档
    这个方法从数据库中查询所有可检索的文档，条件是：
    status = 1（状态正常）
    indexStatus = BUILD_SUCCESS（索引构建成功）
    lastIndexTaskId IS NOT NULL（有最新索引任务 ID）
    1.按 requestedDocumentIds 过滤，只保留本次检索需要的文档
    用 LinkedHashMap 保持顺序，key 是文档 ID，value 是文档描述对象。
    为什么要预加载文档描述信息？
    因为向量检索的 SQL 只查询 embedding_table，这个表只有 chunk 级别的数据（chunk_text、section_path 等），没有文档级别的元数据（文档名、业务分类、标签）。
    预加载文档描述信息后，在 buildRetrievedDocument 时可以直接从 descriptorMap 中取出对应的文档信息，补充到 Document.metadata 中
     */
    @Override
    public List<KnowledgeDocumentDescriptor> listRetrievableDocuments() {

        // 查询所有状态正常、索引构建成功、有最新索引任务 ID 的文档。
        List<SuperAgentDocument> documents = documentMapper.selectList(new LambdaQueryWrapper<SuperAgentDocument>()
            .eq(SuperAgentDocument::getStatus, BusinessStatus.YES.getCode())
            .eq(SuperAgentDocument::getIndexStatus, DocumentIndexStatusEnum.BUILD_SUCCESS.getCode())
            .isNotNull(SuperAgentDocument::getLastIndexTaskId)
            .orderByDesc(SuperAgentDocument::getEditTime)
            .orderByDesc(SuperAgentDocument::getId));
        if (CollUtil.isEmpty(documents)) {
            return List.of();
        }

        // 查询所有状态正常、索引构建成功、有最新索引任务 ID 的文档。
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


    /*
    向量检索的核心逻辑
     */
    @Override
    public List<Document> vectorSearch(DocumentRetrieveRequest request) {
        // 校验问题、检索 query、文档范围和任务范围是否齐全；缺失时直接返回空结果。
        if (!isSearchableRequest(request)) {
            return List.of();
        }

        // 向量检索必须依赖 EmbeddingModel，把文本 query 转成向量。
        EmbeddingModel embeddingModel = requireEmbeddingModel();

        /*生成向量问题
        这里有两步：
            第一步：embeddingModel.embed
            调用嵌入模型（通常是 OpenAI text-embedding-ada-002 或国产同类模型），把文本转成一个高维浮点向量，比如 1536 维。
            注意这里用的是 retrievalQuery，不是原始的 question。retrievalQuery 已经经过上一篇讲的 buildQueryAugmentation 增强，可能包含了历史上下文关键词，语义更丰富。
            第二步：toVectorLiteral
            把浮点数组转成 pgvector 能识别的字符串格式。我们来看这个方法的实现：

         */
        // 使用增强后的 retrievalQuery 生成 embedding，并转成 pgvector 可识别的 '[...]' 字面量。  生成向量问题
        String questionVector = toVectorLiteral(embeddingModel.embed(request.getRetrievalQuery().trim()));


        // resolvedDocumentIds 会优先使用批量范围，缺失时回退到单个 documentId。
        List<Long> documentIds = request.resolvedDocumentIds();
        List<Long> taskIds = request.resolvedTaskIds();

        // 预先加载文档描述信息，后续构造 Document metadata 时需要文档名、分类、标签等字段。  加载文档描述信息： listDescriptorMap  在执行向量检索之前，需要先加载文档的描述信息（文档名、业务分类、标签等），这些信息会被写入最终返回的 Document 对象的 metadata 中。
        Map<Long, KnowledgeDocumentDescriptor> descriptorMap = listDescriptorMap(documentIds);

        // 解析最终下推到 SQL 的元数据范围，目前主要承接文档、任务和结构过滤条件。   解析元数据范围
        ResolvedMetadataScope resolvedScope = resolveMetadataScope(request);
        // 如果过滤后没有任何可检索文档或任务，直接返回空结果。
        if (resolvedScope.documentIds().isEmpty() || resolvedScope.taskIds().isEmpty()) {
            return List.of();
        }

        /*
        构建pgvector SQL
        SQL模板的动态构建过程
        在生成问题向量和加载元数据之后，接下来要构建实际执行的 SQL 语句。这个过程分为几个步骤：
         */
        // 根据文档数和任务数动态生成 IN 占位符，避免直接拼接参数值。  步骤1：格式化 SQL 模板
        StringBuilder sqlBuilder = new StringBuilder(VECTOR_RETRIEVE_SQL_TEMPLATE.formatted(
            DocumentPgVectorConstants.EMBEDDING_TABLE_NAME,
            buildPlaceholders(resolvedScope.documentIds().size()),
            buildPlaceholders(resolvedScope.taskIds().size())
        ));
        /*
        步骤二：追加可选过滤条件
        这个方法会根据 filters 中的内容，动态追加章节、年份、结构节点等过滤条件。我们来详细看这个方法的实现。
         */
        // 追加章节、结构节点、canonicalPath、itemIndex 等可选过滤条件。
        appendSectionFilters(sqlBuilder, resolvedScope.filters());

       /*
       步骤三：追加ORDER BY 和LIMIT
       这一步很简单，直接追加排序和限制条件。注意这里又用了一次问题向量，所以参数列表里要加两次 questionVector。
        */
        // ORDER BY 再次使用同一个 questionVector，按向量距离从近到远排序。
        sqlBuilder.append("""

            ORDER BY embedding <=> CAST(? AS vector)
            LIMIT ?
            """);

        /*SQL 参数绑定顺序
        SQL 构建完成后，需要按照 SQL 中 ? 占位符的顺序，依次绑定参数值。这个顺序必须严格对应，否则会出现参数错位的问题。
        参数绑定顺序分析：
        1.第1个参数：questionVector（用于 SELECT 中的 similarity_score 计算）
        2.第2-N个参数：documentIds（用于 document_id IN (?,...)）
        3.第N+1-M个参数：taskIds（用于 task_id IN (?,...)）
        4.第M+1-K个参数：过滤条件参数（章节、结构节点、条目索引等）
        5.第K+1个参数：questionVector（用于 ORDER BY 的距离计算）
        6.第K+2个参数：topK（用于 LIMIT）
         */
        // SQL 参数顺序必须和模板中的 ? 顺序严格一致。
        List<Object> params = new ArrayList<>();
        // SELECT 中 similarity_score 需要第一个向量参数。
        params.add(questionVector);
        // WHERE document_id IN (...) 和 task_id IN (...) 的参数。
        params.addAll(resolvedScope.documentIds());
        params.addAll(resolvedScope.taskIds());
        // 可选结构过滤参数，例如 sectionPath LIKE、structureNodeId IN 等。
        appendSectionFilterParams(params, resolvedScope.filters());
        // ORDER BY 距离计算再次绑定同一个问题向量。
        params.add(questionVector);
        // topK 做上限保护后再传入 LIMIT。
        params.add(resolveTopK(request.getTopK()));
        // 执行 pgvector 查询，并把每一行 chunk 转成 Spring AI Document。
        return pgVectorJdbcTemplate.query(sqlBuilder.toString(), params.toArray(), (resultSet, rowNum) -> {
            long chunkId = resultSet.getLong("id");
            long documentId = resultSet.getLong("document_id");
            // similarity_score = 1 - distance，后续作为向量通道分数写入 metadata。
            double score = resultSet.getDouble("similarity_score");
            KnowledgeDocumentDescriptor descriptor = descriptorMap.get(documentId);
            return buildRetrievedDocument(
                chunkId,
                resultSet.getString("chunk_text"),
                resultSet.getLong("task_id"),
                resultSet.getLong("parent_block_id"),
                resultSet.getInt("chunk_no"),
                resultSet.getString("section_path"),
                getNullableLong(resultSet, "structure_node_id"),
                getNullableInteger(resultSet, "structure_node_type"),
                resultSet.getString("canonical_path"),
                getNullableInteger(resultSet, "item_index"),
                descriptor,
                "vector",
                score
            );
        });
    }


    /*
    这里有一个关键的分支判断：ES 优先策略。
    如果满足两个条件：
    1.配置中启用了 Elasticsearch（properties.getElasticsearch().getEnabled() == true）
    2.ES 网关可用（keywordSearchGateway != null）
    就会优先使用 ES 进行关键词检索，否则降级到 SQL LIKE fallback。
     */
    @Override
    public List<Document> keywordSearch(DocumentRetrieveRequest request) {
        // 关键词检索同样要求问题、检索 query、文档范围和任务范围都有效。
        if (!isSearchableRequest(request)) {
            return List.of();
        }

        List<Long> documentIds = request.resolvedDocumentIds();
        List<Long> taskIds = request.resolvedTaskIds();
        // 文档描述信息用于给最终 Document 补充来源元数据。
        Map<Long, KnowledgeDocumentDescriptor> descriptorMap = listDescriptorMap(documentIds);
        // 统一解析结构化过滤条件，供 ES 分支和 SQL fallback 分支共用。
        ResolvedMetadataScope resolvedScope = resolveMetadataScope(request);
        if (resolvedScope.documentIds().isEmpty() || resolvedScope.taskIds().isEmpty()) {
            return List.of();
        }

        // 构造一个带 resolved 范围的新请求，确保 ES 网关拿到的是最终过滤后的范围。
        DocumentRetrieveRequest filteredRequest = new DocumentRetrieveRequest(
            request.getQuestion(),
            request.getRetrievalQuery(),
            resolvedScope.documentIds().isEmpty() ? null : resolvedScope.documentIds().get(0),
            resolvedScope.taskIds().isEmpty() ? null : resolvedScope.taskIds().get(0),
            request.getTopK(),
            resolvedScope.filters(),
            request.getQueryContextHints()
        );
        filteredRequest.setDocumentIds(resolvedScope.documentIds());
        filteredRequest.setTaskIds(resolvedScope.taskIds());

        // 如果项目启用了 Elasticsearch 且对应网关可用，则优先使用 ES 关键词检索。
        DocumentKeywordSearchGateway keywordSearchGateway = keywordSearchGatewayProvider.getIfAvailable();
        if (Boolean.TRUE.equals(properties.getElasticsearch().getEnabled()) && keywordSearchGateway != null) {
            return keywordSearchGateway.search(filteredRequest);
        }



        /*
        SQL lIKE fallback（ES不可用时）
        当 ES 不可用时，系统会降级到 SQL LIKE fallback。
         */
        //当ES不可用时，使用数据库LIKE fallback ；先从retrievalQuery 抽取核心关键词。
        List<String> terms = new ArrayList<>(extractKeywordTerms(request.getRetrievalQuery()));
        // queryContextHints 里可能有历史上下文或导航关键词，也加入辅助关键词池。
        terms.addAll(extractAuxiliaryKeywordTerms(request.getQueryContextHints()));
        // 去重并保留顺序，避免重复 LIKE 条件影响打分。
        terms = new ArrayList<>(new LinkedHashSet<>(terms));
        // 没有关键词时无法做 LIKE 检索，直接返回空结果。
        if (terms.isEmpty()) {
            return List.of();
        }
        // 构造 keyword_score 表达式：chunk_text 命中和 section_path 命中分别加权。
        String scoreExpression = buildKeywordScoreExpression(terms.size());
        // 构造 WHERE 表达式：任意关键词命中 chunk_text 或 section_path 即可进入候选。
        String whereExpression = buildKeywordWhereExpression(terms.size());
        // 根据关键词数量、文档范围和任务范围动态构造 fallback SQL。
        StringBuilder sqlBuilder = new StringBuilder(KEYWORD_RETRIEVE_SQL_TEMPLATE.formatted(
            scoreExpression,
            DocumentPgVectorConstants.EMBEDDING_TABLE_NAME,
            buildPlaceholders(resolvedScope.documentIds().size()),
            buildPlaceholders(resolvedScope.taskIds().size()),
            whereExpression
        ));
        // 追加章节和结构过滤，保证关键词结果仍然落在路由器判定的范围内。
        appendSectionFilters(sqlBuilder, resolvedScope.filters());
        sqlBuilder.append("""

            ORDER BY keyword_score DESC, chunk_no ASC, id ASC
            LIMIT ?
            """);

        List<Object> params = new ArrayList<>();

        // 第一批参数用于 scoreExpression，每个 term 需要 chunk_text LIKE、chunk 权重、section LIKE、section 权重。
        for (int index = 0; index < terms.size(); index++) {
            String pattern = likePattern(terms.get(index));

            params.add(pattern);
            params.add(keywordWeight(index));
            params.add(pattern);
            params.add(sectionKeywordWeight(index));
        }

        // 文档范围和任务范围参数。
        params.addAll(resolvedScope.documentIds());
        params.addAll(resolvedScope.taskIds());

        // 第二批参数用于 WHERE 条件，每个 term 分别匹配正文和章节路径。
        for (String term : terms) {
            params.add(likePattern(term));
            params.add(likePattern(term));
        }
        // 追加可选结构过滤参数。
        appendSectionFilterParams(params, resolvedScope.filters());
        // 限制 fallback 召回数量。
        params.add(resolveTopK(request.getTopK()));

        // 执行关键词 fallback SQL，并把每一行 chunk 转成统一 Document。
        return pgVectorJdbcTemplate.query(sqlBuilder.toString(), params.toArray(), (resultSet, rowNum) -> {
            long chunkId = resultSet.getLong("id");
            long documentId = resultSet.getLong("document_id");
            // keyword_score 是动态打分表达式结果，会作为关键词通道分数写入 metadata。
            double score = resultSet.getDouble("keyword_score");
            KnowledgeDocumentDescriptor descriptor = descriptorMap.get(documentId);
            return buildRetrievedDocument(
                chunkId,
                resultSet.getString("chunk_text"),
                resultSet.getLong("task_id"),
                resultSet.getLong("parent_block_id"),
                resultSet.getInt("chunk_no"),
                resultSet.getString("section_path"),
                getNullableLong(resultSet, "structure_node_id"),
                getNullableInteger(resultSet, "structure_node_type"),
                resultSet.getString("canonical_path"),
                getNullableInteger(resultSet, "item_index"),
                descriptor,
                "keyword",
                score
            );
        });
    }



    /*
    这个方法的流程可以分为四个阶段：
    第一阶段：按父块 ID 分组
    遍历所有子证据（也就是 RRF 融合后的候选文档），按 parentBlockId 分组。每个子证据在入库时就记录了它属于哪个父块。如果某个子证据没有 parentBlockId（比如来自特殊数据源），就放到 fallbackDocuments 里，后面原样保留。
    第二阶段：批量查询父块记录
    拿到所有需要的 parentBlockId 后，一次性从数据库查出对应的父块记录。这里用了 MyBatis-Plus 的 LambdaQueryWrapper，只查状态正常的父块，并按 parentNo 排序。
    父块表结构
    super_agent_document_parent_block 表存储了父块的完整信息，包括：
    parentText：父块的完整正文内容
    parentNo：父块在文档中的序号
    sectionPath：章节路径（如"第一章/1.2节"）
    startChunkNo / endChunkNo：这个父块包含的子块范围
    charCount / tokenCount：字符数和 token 数
    第三阶段：逐组构建父块证据
    对每个分组，调用 buildParentEvidenceDocument 生成一条父块证据。如果某个父块在数据库中找不到（可能被删了），就保留原始子证据，不丢失信息。
    第四阶段：合并排序
    把父块证据和 fallback 证据合并，然后统一排序。
     */
    @Override
    public List<Document> elevateToParentBlocks(List<Document> childDocuments, int maxChars) {
        // 没有子证据时，没有必要做父块提升。
        if (CollUtil.isEmpty(childDocuments)) {
            return List.of();
        }
        // 先按 parent_block_id 对子证据分组，便于后面批量找到对应父块。
        Map<Long, List<Document>> childGroupsByParent = new LinkedHashMap<>();
        // 没有 parentBlockId 的子证据无法提升到父块，先暂存为 fallback。
        List<Document> fallbackDocuments = new ArrayList<>();
        for (Document childDocument : childDocuments) {
            if (childDocument == null) {
                continue;
            }
            // parentBlockId 是父块提升的分组键。
            Long parentBlockId = asLong(childDocument.getMetadata().get(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID));
            if (parentBlockId == null) {
                // 没有父块信息的证据直接保留原样。
                fallbackDocuments.add(childDocument);
                continue;
            }
            childGroupsByParent.computeIfAbsent(parentBlockId, ignored -> new ArrayList<>()).add(childDocument);
        }
        // 如果所有子证据都无法归属父块，就直接返回原始证据列表。
        if (childGroupsByParent.isEmpty()) {
            return fallbackDocuments;
        }
        // 根据 parentBlockId 一次性取出父块记录，避免逐条查询。
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
        // 父块提升后的结果集合，前面放父块证据，后面补原样 fallback 证据。
        List<Document> elevatedDocuments = new ArrayList<>(childGroupsByParent.size() + fallbackDocuments.size());
        for (Map.Entry<Long, List<Document>> entry : childGroupsByParent.entrySet()) {
            SuperAgentDocumentParentBlock parentBlock = parentBlockMap.get(entry.getKey());
            if (parentBlock == null) {
                // 如果父块缺失，则保留原始子证据，不强行丢失信息。
                elevatedDocuments.addAll(entry.getValue());
                continue;
            }
            // 生成一条包含父块正文和命中子片段摘要的父块证据。
            elevatedDocuments.add(buildParentEvidenceDocument(parentBlock, entry.getValue(), maxChars));
        }
        // 把没有父块信息的原始子证据追加回结果集。
        elevatedDocuments.addAll(fallbackDocuments);
        // 按分数、父块序号和 chunk 序号排序，保持结果稳定。
        elevatedDocuments.sort(this::compareEvidenceDocument);
        return elevatedDocuments;
    }


    /*执行查询并转换结果
    查询结果的每一行都会调用 buildRetrievedDocument 转成 Spring AI 的 Document 对象

    这个方法把数据库里的一行 chunk 记录转成了一个带完整元数据的 Document 对象。后续的 RRF 融合、父块提升、rerank 都依赖这些 metadata 字段。
    channelType 字段标记了这个文档是从哪个通道召回的（"vector" 或 "keyword"），方便后续调试和观测。
     */
    private Document buildRetrievedDocument(long chunkId,
                                            String chunkText,
                                            long taskId,
                                            long parentBlockId,
                                            int chunkNo,
                                            String sectionPath,
                                            Long structureNodeId,
                                            Integer structureNodeType,
                                            String canonicalPath,
                                            Integer itemIndex,
                                            KnowledgeDocumentDescriptor descriptor,
                                            String channel,
                                            double score) {
        // 构建 metadata，把所有结构化字段都存进去，供后续 rerank、父块提升、引用生成使用。
        Map<String, Object> metadata = new LinkedHashMap<>();

        metadata.put(DocumentKnowledgeMetadataKeys.SOURCE_TYPE, "DOCUMENT");
        metadata.put(DocumentKnowledgeMetadataKeys.CHANNEL, channel);
        metadata.put(DocumentKnowledgeMetadataKeys.SCORE, score);
        metadata.put(DocumentKnowledgeMetadataKeys.CHUNK_ID, chunkId);
        metadata.put(DocumentKnowledgeMetadataKeys.TASK_ID, taskId);
        metadata.put(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID, parentBlockId);
        metadata.put(DocumentKnowledgeMetadataKeys.CHUNK_NO, chunkNo);
        metadata.put(DocumentKnowledgeMetadataKeys.SECTION_PATH, safeText(sectionPath));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.STRUCTURE_NODE_ID, structureNodeId);
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.STRUCTURE_NODE_TYPE, structureNodeType);
        metadata.put(DocumentKnowledgeMetadataKeys.CANONICAL_PATH, safeText(canonicalPath));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.ITEM_INDEX, itemIndex);
        metadata.put(DocumentKnowledgeMetadataKeys.ORIGINAL_SNIPPET, chunkText);
        // 如果有文档描述信息，补充文档名、业务分类、标签等字段。
        if (descriptor != null) {

            metadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_ID, descriptor.getDocumentId());
            metadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME, safeText(descriptor.getDocumentName()));
            metadata.put(DocumentKnowledgeMetadataKeys.KNOWLEDGE_SCOPE_CODE, safeText(descriptor.getKnowledgeScopeCode()));
            metadata.put(DocumentKnowledgeMetadataKeys.KNOWLEDGE_SCOPE_NAME, safeText(descriptor.getKnowledgeScopeName()));
            metadata.put(DocumentKnowledgeMetadataKeys.BUSINESS_CATEGORY, safeText(descriptor.getBusinessCategory()));
            metadata.put(DocumentKnowledgeMetadataKeys.DOCUMENT_TAGS, safeText(descriptor.getDocumentTags()));
        }

        // 用 chunkId 作为 Document 的唯一标识，方便后续去重和父块提升。
        return Document.builder()
            .id(String.valueOf(chunkId))
            .text(chunkText)
            .metadata(metadata)
            .score(score)
            .build();
    }

    private boolean isSearchableRequest(DocumentRetrieveRequest request) {

        if (request == null || StrUtil.isBlank(request.getQuestion()) || StrUtil.isBlank(request.getRetrievalQuery())) {
            return false;
        }
        return !request.resolvedDocumentIds().isEmpty() && !request.resolvedTaskIds().isEmpty();
    }


    /**
     * 根据 request 中限定的 documentIds 读取文档描述信息。
     */
    private Map<Long, KnowledgeDocumentDescriptor> listDescriptorMap(List<Long> requestedDocumentIds) {
        // 先拉出所有可检索文档，再按请求文档 ID 过滤。
        List<KnowledgeDocumentDescriptor> descriptors = listRetrievableDocuments();
        if (descriptors.isEmpty()) {
            return Map.of();
        }

        // 用 LinkedHashMap 保持文档描述的稳定顺序。
        return descriptors.stream()
            .filter(descriptor -> requestedDocumentIds.contains(descriptor.getDocumentId()))
            .collect(Collectors.toMap(
                KnowledgeDocumentDescriptor::getDocumentId,
                descriptor -> descriptor,
                (left, right) -> left,
                LinkedHashMap::new
            ));
    }


    /**
     * 将 request 中的基础范围和过滤条件封装成一个解析后的 scope。
     * 这个方法负责解析最终下推到 SQL 的元数据范围，包括文档范围、任务范围和过滤条件。
     * 这个方法看起来很简单，只是把 request 中的三个字段封装成一个 ResolvedMetadataScope 对象。但它的作用是统一元数据范围的解析逻辑，方便后续扩展。
     */
    private ResolvedMetadataScope resolveMetadataScope(DocumentRetrieveRequest request) {
        // 当前实现只需要文档范围、任务范围和 filters；后续如果需要扩展可在这里统一处理。
        List<Long> baseDocumentIds = request.resolvedDocumentIds();
        List<Long> baseTaskIds = request.resolvedTaskIds();
        return new ResolvedMetadataScope(baseDocumentIds, baseTaskIds, request.getFilters());
    }


    /**
     * 根据过滤提示拼接 section_path / canonical_path / structure 字段约束。
     *
     */
    private void appendSectionFilters(StringBuilder sqlBuilder, DocumentRetrieveFilters filters) {
        // 只有 sectionPathHints 存在时才拼 section_path LIKE 条件。
        boolean hasSectionHints = filters != null && CollUtil.isNotEmpty(filters.getSectionPathHints());
        if (!hasSectionHints) {
            appendStructureFilters(sqlBuilder, filters);
            return;
        }
        // 多个 section hint 之间使用 OR，任意一个命中即可。
        sqlBuilder.append("\n  AND (");
        for (int index = 0; index < filters.getSectionPathHints().size(); index++) {
            if (index > 0) {
                sqlBuilder.append(" OR ");
            }
            sqlBuilder.append("LOWER(COALESCE(section_path, '')) LIKE ?");
        }
        sqlBuilder.append(")");
        appendStructureFilters(sqlBuilder, filters);
    }






    /**
     * 拼接结构维度的 SQL 过滤条件。
     * 这个方法处理三类结构过滤：
     * 1.structure_node_id：结构图节点 ID，用 IN 精确匹配
     * 2.canonical_path：规范化路径，用 LIKE 前缀匹配
     * 3.item_index：条目索引，用 IN 精确匹配
     */
    private void appendStructureFilters(StringBuilder sqlBuilder, DocumentRetrieveFilters filters) {
        // 先判断 filters 是否携带三类结构硬约束。
        boolean hasStructureNodeIds = filters != null && CollUtil.isNotEmpty(filters.getStructureNodeIdHints());
        boolean hasCanonicalPathHints = filters != null && CollUtil.isNotEmpty(filters.getCanonicalPathHints());
        boolean hasItemIndexes = filters != null && CollUtil.isNotEmpty(filters.getItemIndexHints());
        // 三类结构过滤都不存在时，说明本次检索只需要按文档/任务/章节提示过滤，直接返回。
        if (!hasStructureNodeIds && !hasCanonicalPathHints && !hasItemIndexes) {
            return;
        }
        if (hasStructureNodeIds) {
            // structure_node_id 是最强定位条件，通常来自结构图锚点，能直接锁定一个或多个章节节点。
            sqlBuilder.append("\n  AND structure_node_id IN (")
                .append(buildPlaceholders(filters.getStructureNodeIdHints().size()))
                .append(")");
        }
        if (hasCanonicalPathHints) {
            // canonical_path 是规范化后的结构路径，适合做"限定在某个章节路径前缀下"的过滤。  章节路径过滤：sectionPath LIKE '%第3章%'
            sqlBuilder.append("\n  AND (");
            for (int index = 0; index < filters.getCanonicalPathHints().size(); index++) {
                if (index > 0) {
                    sqlBuilder.append(" OR ");
                }
                sqlBuilder.append("LOWER(COALESCE(canonical_path, '')) LIKE ?");
            }
            sqlBuilder.append(")");
        }
        if (hasItemIndexes) {
            // item_index 用于"第几项/哪一步/编号项"类问题，限定同一章节下的具体条目。
            sqlBuilder.append("\n  AND item_index IN (")
                .append(buildPlaceholders(filters.getItemIndexHints().size()))
                .append(")");
        }
    }






//这两个方法负责把过滤条件的参数值按照 SQL 中的顺序追加到参数列表中。
    /**
     * 按照 appendSectionFilters 的顺序追加参数值。
     */
    private void appendSectionFilterParams(List<Object> params, DocumentRetrieveFilters filters) {
        // sectionPathHints 对应 appendSectionFilters 中的 LOWER(COALESCE(section_path, '')) LIKE ?。
        if (filters != null && CollUtil.isNotEmpty(filters.getSectionPathHints())) {
            for (String sectionHint : filters.getSectionPathHints()) {
                // SQL 左侧已经 LOWER(section_path)，参数也统一转小写，保证大小写不影响匹配。
                // 前后都加 % 是"包含匹配"，适合"第3章""附录A""配置流程"等只出现为路径片段的提示。
                params.add("%" + sectionHint.toLowerCase(Locale.ROOT) + "%");
            }
        }
        // section_path 参数追加完成后，再追加结构硬约束参数。
        appendStructureFilterParams(params, filters);
    }
    /**
     * 追加结构过滤参数。
     */
    private void appendStructureFilterParams(List<Object> params, DocumentRetrieveFilters filters) {
        if (filters == null) {
            return;
        }
        // structure_node_id IN (...) 的参数
        if (CollUtil.isNotEmpty(filters.getStructureNodeIdHints())) {
            params.addAll(filters.getStructureNodeIdHints());
        }
        // canonical_path LIKE ? 的参数
        if (CollUtil.isNotEmpty(filters.getCanonicalPathHints())) {
            for (String canonicalPathHint : filters.getCanonicalPathHints()) {
                // 前缀匹配，用于匹配某个路径下的所有子节点
                params.add(canonicalPathHint.toLowerCase(Locale.ROOT) + "%");
            }
        }
        // item_index IN (...) 的参数
        if (CollUtil.isNotEmpty(filters.getItemIndexHints())) {
            params.addAll(filters.getItemIndexHints());
        }
    }



















    /*
    构建父块证据
     */
    private Document buildParentEvidenceDocument(SuperAgentDocumentParentBlock parentBlock,
                                                 List<Document> childDocuments,
                                                 int maxChars) {

        /*
        第一步：选出最佳子片段
        从同一父块下的所有命中子片段中，选分数最高的那个。为什么要选最佳子片段？因为后面要用它的 metadata 作为父块证据的基础模板——最佳子片段的 metadata 里包含了文档 ID、任务 ID、知识库范围等信息，这些信息对父块证据同样适用。
         */
        // 从子片段里选一个分数最高的文档，用它的 metadata 作为父块证据的基础。
        Document bestChild = childDocuments.stream()
            .max(Comparator.comparingDouble(document -> {
                Double score = resolveScore(document);
                return score == null ? 0D : score;
            }))
            .orElseThrow();
        /*
        第二步：计算父块综合分数   这里调用 aggregateParentScore 计算父块的综合分数（下面会详细讲）。
         */
        // 父块分数会结合最佳子片段分数和命中支持数做轻量增强。
        double parentScore = aggregateParentScore(childDocuments);
        /*
        第三步：组装 metadata
        先复制最佳子片段的 metadata（new LinkedHashMap<>(bestChild.getMetadata())），然后用父块的信息覆盖掉子块级别的字段。这样做的好处是：子块和父块共有的字段（如 documentId、taskId）不用重新设置，只需要覆盖那些父块特有的字段。
        其中 ORIGINAL_SNIPPET 存的是父块的完整原文，后续调试页面可以用它来展示原始内容。
         */
        // 先复制最佳子片段的 metadata，再覆盖成父块级别字段。
        Map<String, Object> metadata = new LinkedHashMap<>(bestChild.getMetadata());
        metadata.put(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID, parentBlock.getId());
        metadata.put(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_NO, parentBlock.getParentNo());
        metadata.put(DocumentKnowledgeMetadataKeys.SECTION_PATH, safeText(parentBlock.getSectionPath()));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.STRUCTURE_NODE_ID, parentBlock.getStructureNodeId());
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.STRUCTURE_NODE_TYPE, parentBlock.getStructureNodeType());
        metadata.put(DocumentKnowledgeMetadataKeys.CANONICAL_PATH, safeText(parentBlock.getCanonicalPath()));
        putIfNotNull(metadata, DocumentKnowledgeMetadataKeys.ITEM_INDEX, parentBlock.getItemIndex());
        metadata.put(DocumentKnowledgeMetadataKeys.SCORE, parentScore);
        metadata.put(DocumentKnowledgeMetadataKeys.ORIGINAL_SNIPPET, safeText(parentBlock.getParentText()));
        /*
        第四步：确定来源通道标记
        收集所有子片段的来源通道。如果子片段来自多个不同通道（比如有的来自 vector，有的来自 keyword），就标记为 "hybrid"；如果都来自同一个通道，就标记那个通道名。
         */
        // 父块来源通道保持来自子片段的通道集合；多通道命中时标记为 hybrid。
        LinkedHashSet<String> channels = childDocuments.stream()
            .map(document -> asText(document.getMetadata().get(DocumentKnowledgeMetadataKeys.CHANNEL)))
            .filter(StrUtil::isNotBlank)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        metadata.put(DocumentKnowledgeMetadataKeys.CHANNEL,
            channels.size() > 1 ? "hybrid" : channels.stream().findFirst().orElse("vector"));

        /*
        第五步：构建最终 Document
        注意 Document 的 id 是 "parent-" + parentBlock.getId()，这样可以和原始子块的 id 区分开来，后续在调试页面也能一眼看出这是一条父块证据。
         */
        return Document.builder()
            .id("parent-" + parentBlock.getId())
            .text(renderParentEvidenceText(parentBlock, childDocuments, maxChars))
            .metadata(metadata)
            .score(parentScore)
            .build();
    }





    /**  父块分数计算
     * 计算父块证据的综合分数。
     * 不是简单相加，而是以最佳子片段分数为基础，再结合命中支持数和多通道信号做轻量增益。
     * 这个方法的设计思路是：不能简单地把子片段分数加起来，而是要用一种更合理的方式来衡量父块的整体质量。
     */
    private double aggregateParentScore(List<Document> childDocuments) {
        // 先取最佳子片段分数，作为父块分数的基准。
        double bestChildScore = childDocuments.stream()
            .map(this::resolveScore)
            .filter(Objects::nonNull)
            .max(Double::compareTo)
            .orElse(0D);
        // 支持数越多，说明这个父块越稳；这里用 child 数量做一个轻量权重。
        int supportCount = Math.max(0, childDocuments.size() - 1);
        // 多通道命中再给一点额外权重，避免单通道噪声块过度抬高。
        LinkedHashSet<String> channels = childDocuments.stream()
            .map(document -> asText(document.getMetadata().get(DocumentKnowledgeMetadataKeys.CHANNEL)))
            .filter(StrUtil::isNotBlank)
            .collect(Collectors.toCollection(LinkedHashSet::new));

        double supportWeight = Math.min(0.36D, supportCount * 0.12D);
        double multiChannelWeight = channels.size() > 1 ? 0.10D : 0D;
        return bestChildScore * (1D + supportWeight + multiChannelWeight);
    }

    private void putIfNotNull(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }



    /*
    证据排序规则
    排序规则是三级优先级：
    1.分数优先：分数高的排前面，保证最相关的证据在最前面
    2.父块序号次之：分数相同时，按父块在文档中的出现顺序排列
    3.子块序号兜底：父块序号也相同时（比如 fallback 的子证据），按 chunk 序号排列
    这样排序的好处是：既保证了相关性优先，又在相关性相同时保持了文档的自然阅读顺序。
     */
    private int compareEvidenceDocument(Document left, Document right) {
        // 分数高的排前面。
        int scoreCompare = Double.compare(resolveScoreOrZero(right), resolveScoreOrZero(left));
        if (scoreCompare != 0) {
            return scoreCompare;
        }
        // 分数相同时，按父块序号稳定排序。
        Integer leftParentNo = asInteger(left == null ? null : left.getMetadata().get(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_NO));
        Integer rightParentNo = asInteger(right == null ? null : right.getMetadata().get(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_NO));
        int parentNoCompare = compareNullableInteger(leftParentNo, rightParentNo);
        if (parentNoCompare != 0) {
            return parentNoCompare;
        }
        // 父块序号也相同时，再按 chunkNo 排序。
        Integer leftChunkNo = asInteger(left == null ? null : left.getMetadata().get(DocumentKnowledgeMetadataKeys.CHUNK_NO));
        Integer rightChunkNo = asInteger(right == null ? null : right.getMetadata().get(DocumentKnowledgeMetadataKeys.CHUNK_NO));
        return compareNullableInteger(leftChunkNo, rightChunkNo);
    }

    private double resolveScoreOrZero(Document document) {
        Double score = resolveScore(document);
        return score == null ? 0D : score;
    }

    private int compareNullableInteger(Integer left, Integer right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return 1;
        }
        if (right == null) {
            return -1;
        }
        return Integer.compare(left, right);
    }



    /*
    渲染父块证据文本
    这个方法负责把父块的正文和命中子片段拼成最终给模型看的文本。我们来逐步看它的执行逻辑：
    1.先取父块正文 parentText，如果为空就直接返回第一条子证据的正文作为兜底
    2.遍历所有命中的子片段，生成摘要列表。每条摘要格式是 - child#序号：子片段正文前140字符
    3.用 joinSections 把"父块内容"和"命中子片段"两部分用空行拼接
    4.最后用 trimText 截断到 maxChars，超出部分会被截断并加上 … 省略号

     */
    private String renderParentEvidenceText(SuperAgentDocumentParentBlock parentBlock,
                                            List<Document> childDocuments,
                                            int maxChars) {
        // 父块正文为空时，退回到第一条子证据的正文，至少保留一点可读内容。
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

        // 组合为"父块内容 + 命中子片段摘要"的结构，便于回答时直接引用。
        String composed = joinSections(
            "[父块内容]\n" + parentText,
            hitSummaryBuilder.isEmpty() ? "" : "[命中子片段]\n" + hitSummaryBuilder
        );
        // 最终再统一截断到 maxChars，保证不超过预算。
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

    private Long getNullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private Integer getNullableInteger(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private String trimText(String text, int maxChars) {
        if (StrUtil.isBlank(text) || text.length() <= maxChars) {
            return StrUtil.blankToDefault(text, "");
        }
        return text.substring(0, Math.max(0, maxChars - 1)) + "…";
    }




    private List<String> extractKeywordTerms(String question) {
        String normalized = normalizeQuestion(question);
        if (StrUtil.isBlank(normalized)) {
            return List.of();
        }

        LinkedHashSet<String> terms = new LinkedHashSet<>();


        Matcher alnumMatcher = ALNUM_TOKEN_PATTERN.matcher(normalized);
        while (alnumMatcher.find()) {
            terms.add(alnumMatcher.group());
        }

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

            .limit(MAX_KEYWORD_TERMS)
            .toList();
    }

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

    private void addChineseSegmentTerms(String segment, LinkedHashSet<String> terms) {
        if (StrUtil.isBlank(segment) || segment.length() < 2) {
            return;
        }

        if (segment.length() <= 12) {
            terms.add(segment);
        }
        addTailNgrams(segment, terms);
        addHeadNgrams(segment, terms);
        addSlidingNgrams(segment, terms);
    }


    /*
    动态打分表达式
     */
    private String buildKeywordScoreExpression(int termCount) {
        return IntStream.range(0, termCount)

            .mapToObj(index -> "("
                + "CASE WHEN LOWER(chunk_text) LIKE ? THEN ? ELSE 0 END + "
                + "CASE WHEN LOWER(COALESCE(section_path, '')) LIKE ? THEN ? ELSE 0 END"
                + ")")
            .collect(Collectors.joining(" + "));
    }


    /*
    WHERE条件构建：

     */
    private String buildKeywordWhereExpression(int termCount) {
        return IntStream.range(0, termCount)
            .mapToObj(index -> "(LOWER(chunk_text) LIKE ? OR LOWER(COALESCE(section_path, '')) LIKE ?)")
            .collect(Collectors.joining(" OR "));
    }


    /*
    正文命中权重：第一个关键词6分，第二个5分，以此类推，最低1分
     */
    private int keywordWeight(int index) {

        return Math.max(1, 6 - index);
    }

    // 章节路径命中权重：比正文高2分，因为章节标题更能代表内容主题。？
    private int sectionKeywordWeight(int index) {
        return keywordWeight(index) + 2;
    }

    private String likePattern(String term) {
        return "%" + term.toLowerCase(Locale.ROOT) + "%";
    }

    private String normalizeQuestion(String question) {
        if (StrUtil.isBlank(question)) {
            return "";
        }

        return question.trim()
            .toLowerCase(Locale.ROOT)
            .replaceAll("[\\r\\n\\t]+", " ")
            .replaceAll("\\s+", " ");
    }

    private String removeChineseNoisePhrases(String text) {
        if (StrUtil.isBlank(text)) {
            return "";
        }

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
     * 解析并限制 topK。
     * 这个方法对 topK 做了上下限保护：
     * 下限：如果 topK 小于等于 0，使用默认值 10
     * 上限：最多不超过 50，避免一次召回过多文档影响性能
     */
    private int resolveTopK(int topK) {
        // topK 非法时使用 10，合法时最多不超过 50，防止一次召回过大。
        return topK <= 0 ? 10 : Math.min(topK, 50);
    }

    private EmbeddingModel requireEmbeddingModel() {
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {

            throw new IllegalStateException("当前未找到可用的 EmbeddingModel，无法执行向量检索。");
        }
        return embeddingModel;
    }


    /**
     * 将 embedding 数组转换成 pgvector 字面量。  这个方法的作用是把 float[] 数组转换成 pgvector 能识别的字符串格式：
     * 为什么需要这个转换？
     * pgvector 是 PostgreSQL 的向量扩展，它定义了一个 vector 数据类型。在 SQL 中使用向量时，需要用 CAST('...' AS vector) 把字符串转成向量类型。
     * 字符串格式必须是：
     * 用方括号 [] 包裹
     * 数字之间用逗号 , 分隔
     * 不能有空格（虽然 pgvector 能容忍空格，但为了性能最好不加）
     */
    private String toVectorLiteral(float[] embedding) {
        // 空向量说明 embedding 生成失败，不能继续查询数据库。
        if (embedding == null || embedding.length == 0) {
            throw new IllegalStateException("问题向量生成失败，无法执行检索。");
        }
        // pgvector CAST(? AS vector) 需要形如 [0.1,0.2,...] 的字符串。
        StringBuilder vectorBuilder = new StringBuilder("[");
        for (int index = 0; index < embedding.length; index++) {

            if (index > 0) {
                vectorBuilder.append(',');
            }
            vectorBuilder.append(embedding[index]);
        }
        vectorBuilder.append(']');
        return vectorBuilder.toString();
    }

    /**
     * 构造 SQL IN 子句占位符。
     * 这个方法根据 ID 数量生成对应数量的 ? 占位符，用逗号连接。
     * 举个例子：
     * buildPlaceholders(1) → "?"
     * buildPlaceholders(3) → "?,?,?"
     * buildPlaceholders(5) → "?,?,?,?,?"
     */
    private String buildPlaceholders(int size) {
        // 生成形如 "?,?,?" 的占位符串，参数值仍然走 PreparedStatement 绑定。
        return IntStream.range(0, size)
            .mapToObj(index -> "?")
            .collect(Collectors.joining(","));
    }

    private int defaultInteger(Integer value) {
        return Objects.requireNonNullElse(value, 0);
    }



    /*
    record类
    为什么需要这个封装？
1.统一接口：向量检索和关键词检索都需要解析元数据范围，封装成一个方法可以避免重复代码
2.方便扩展：如果以后需要增加更多的过滤条件（比如用户权限、数据权限），只需要在这个方法里统一处理
3.类型安全：用 record 类封装，比直接传三个参数更清晰，IDE 也能提供更好的类型提示
     */
    private record ResolvedMetadataScope(
        List<Long> documentIds,
        List<Long> taskIds,
        DocumentRetrieveFilters filters
    ) {
    }
}
