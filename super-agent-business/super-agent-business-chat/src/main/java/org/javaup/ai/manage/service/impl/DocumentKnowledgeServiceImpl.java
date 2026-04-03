package org.javaup.ai.manage.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.manage.data.SuperAgentDocument;
import org.javaup.ai.manage.mapper.SuperAgentDocumentMapper;
import org.javaup.ai.manage.model.DocumentRetrieveRequest;
import org.javaup.ai.manage.model.KnowledgeDocumentDescriptor;
import org.javaup.ai.manage.service.DocumentKnowledgeService;
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
        ORDER BY embedding <=> CAST(? AS vector)
        LIMIT ?
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
        ORDER BY keyword_score DESC, chunk_no ASC, id ASC
        LIMIT ?
        """;

    /**
     * 关键词提取时识别英数词的正则。
     */
    private static final Pattern ALNUM_TOKEN_PATTERN = Pattern.compile("[a-z0-9._-]{2,}");

    /**
     * 关键词提取时识别中文连续片段的正则。
     */
    private static final Pattern CHINESE_TOKEN_PATTERN = Pattern.compile("[\\p{IsHan}]{2,}");

    private static final String CHINESE_STOP_CHARS = "的了呢吗啊呀哦是有和及与或请帮我一下一下子如何怎么什么哪个这个那个是否关于可以需要想问看看";

    private final SuperAgentDocumentMapper documentMapper;
    private final JdbcTemplate pgVectorJdbcTemplate;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;

    public DocumentKnowledgeServiceImpl(SuperAgentDocumentMapper documentMapper,
                                        @Qualifier("documentManagePgVectorJdbcTemplate") JdbcTemplate pgVectorJdbcTemplate,
                                        ObjectProvider<EmbeddingModel> embeddingModelProvider) {
        this.documentMapper = documentMapper;
        this.pgVectorJdbcTemplate = pgVectorJdbcTemplate;
        this.embeddingModelProvider = embeddingModelProvider;
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

        String sql = VECTOR_RETRIEVE_SQL_TEMPLATE.formatted(
            DocumentPgVectorConstants.EMBEDDING_TABLE_NAME,
            buildPlaceholders(documentIds.size()),
            buildPlaceholders(taskIds.size())
        );

        List<Object> params = new ArrayList<>();
        /*
         * 同一个 queryVector 会在 SQL 里用两次：
         * 1. 计算 similarity_score
         * 2. ORDER BY 向量距离
         */
        params.add(questionVector);
        params.addAll(documentIds);
        params.addAll(taskIds);
        params.add(questionVector);
        params.add(resolveTopK(request.getTopK()));

        return pgVectorJdbcTemplate.query(sql, params.toArray(), (resultSet, rowNum) -> {
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
         * 关键词检索先把问题拆成“适合 LIKE 命中”的词项。
         * 如果一个词项都提不出来，就说明当前问题不适合走这条通道，直接返回空结果。
         */
        List<String> terms = extractKeywordTerms(request.getQuestion());
        if (terms.isEmpty()) {
            return List.of();
        }

        List<Long> documentIds = distinctIds(request.getDocumentIdList());
        List<Long> taskIds = distinctIds(request.getTaskIdList());
        Map<Long, KnowledgeDocumentDescriptor> descriptorMap = listDescriptorMap(documentIds);

        String scoreExpression = buildKeywordScoreExpression(terms.size());
        String whereExpression = buildKeywordWhereExpression(terms.size());
        String sql = KEYWORD_RETRIEVE_SQL_TEMPLATE.formatted(
            scoreExpression,
            DocumentPgVectorConstants.EMBEDDING_TABLE_NAME,
            buildPlaceholders(documentIds.size()),
            buildPlaceholders(taskIds.size()),
            whereExpression
        );

        List<Object> params = new ArrayList<>();

        /*
         * scoreExpression 需要“命中模式 + 权重”两类参数，
         * 顺序必须和 CASE WHEN 片段保持完全一致。
         */
        for (int index = 0; index < terms.size(); index++) {
            String pattern = likePattern(terms.get(index));
            /*
             * 这里不是简单传一个 LIKE 模式，而是同时传：
             * 1. 模式本身
             * 2. 命中后的权重
             *
             * 这样 SQL 层就能先做一轮粗粒度 lexical score 排序。
             */
            params.add(pattern);
            params.add(keywordWeight(index));
        }

        params.addAll(documentIds);
        params.addAll(taskIds);

        /*
         * WHERE 子句里的 OR 条件和前面的 scoreExpression 是分开的，
         * 因此模式参数需要再追加一遍。
         */
        for (String term : terms) {
            params.add(likePattern(term));
        }
        params.add(resolveTopK(request.getTopK()));

        return pgVectorJdbcTemplate.query(sql, params.toArray(), (resultSet, rowNum) -> {
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
        if (CollUtil.isEmpty(request.getDocumentIdList()) || CollUtil.isEmpty(request.getTaskIdList())) {
            return false;
        }
        return true;
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
         * 第二轮再抓中文连续片段，并按 2~4 字窗口切出候选词。
         * 这是为了让中文问题在没有 ES 分词器的情况下，仍然能有一版轻量可运行的关键词检索。
         */
        Matcher chineseMatcher = CHINESE_TOKEN_PATTERN.matcher(normalized);
        while (chineseMatcher.find()) {
            String token = stripChineseStopChars(chineseMatcher.group());
            if (token.length() < 2) {
                continue;
            }
            if (token.length() <= 8) {
                terms.add(token);
            }

            int maxGram = Math.min(4, token.length());
            for (int size = 2; size <= maxGram; size++) {
                for (int index = 0; index <= token.length() - size && terms.size() < 12; index++) {
                    terms.add(token.substring(index, index + size));
                }
            }
        }

        return terms.stream()
            .filter(term -> term.length() >= 2)
            /*
             * 最终只保留少量最有价值的词项，避免 SQL WHERE 条件膨胀得过长。
             */
            .limit(6)
            .toList();
    }

    /**
     * 生成关键词得分表达式。
     *
     * <p>命中越靠前的词，权重越大。
     * 这样像版本号、缩写、系统名这类强特征词会优先把结果顶上来。</p>
     */
    private String buildKeywordScoreExpression(int termCount) {
        return java.util.stream.IntStream.range(0, termCount)
            .mapToObj(index -> "CASE WHEN LOWER(chunk_text) LIKE ? THEN ? ELSE 0 END")
            .collect(Collectors.joining(" + "));
    }

    /**
     * 生成关键词命中过滤条件。
     */
    private String buildKeywordWhereExpression(int termCount) {
        return java.util.stream.IntStream.range(0, termCount)
            .mapToObj(index -> "LOWER(chunk_text) LIKE ?")
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
     * 对中文连续片段做轻量停用字消减。
     */
    private String stripChineseStopChars(String text) {
        if (StrUtil.isBlank(text)) {
            return "";
        }
        /*
         * 当前没有接标准中文停用词表，这里先用一个轻量字符级过滤做近似处理。
         * 这样至少能把“的/了/吗/如何/什么”这类噪音字符压掉一层。
         */
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (CHINESE_STOP_CHARS.indexOf(current) >= 0) {
                continue;
            }
            builder.append(current);
        }
        return builder.toString();
    }

    /**
     * 把可空字符串字段规整成 Spring AI Document.metadata 可接受的非 null 值。
     */
    private String safeText(String text) {
        return text == null ? "" : text;
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
}
