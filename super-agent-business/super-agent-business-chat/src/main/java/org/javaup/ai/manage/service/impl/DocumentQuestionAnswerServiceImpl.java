package org.javaup.ai.manage.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.manage.data.SuperAgentDocument;
import org.javaup.ai.manage.dto.DocumentQuestionAskDto;
import org.javaup.ai.manage.mapper.SuperAgentDocumentMapper;
import org.javaup.ai.manage.service.DocumentQuestionAnswerService;
import org.javaup.ai.manage.support.DocumentPgVectorConstants;
import org.javaup.ai.manage.vo.DocumentQuestionAskVo;
import org.javaup.ai.manage.vo.DocumentQuestionReferenceVo;
import org.javaup.enums.BusinessStatus;
import org.javaup.enums.DocumentIndexStatusEnum;
import org.javaup.enums.DocumentManageCode;
import org.javaup.exception.SuperAgentFrameException;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 文档问答服务实现。
 *
 * <p>这一层负责把“文档当前有效索引 -> PGVector 检索 -> ChatModel 组织答案”串起来，
 * 让文档管理模块自己就具备完整的 RAG 闭环能力，而不依赖 chatagent 包。</p>
 */
@Slf4j
@Service
public class DocumentQuestionAnswerServiceImpl implements DocumentQuestionAnswerService {

    /**
     * 默认召回条数。
     */
    private static final int DEFAULT_TOP_K = 5;

    /**
     * PGVector topK 检索 SQL 模板。
     *
     * <p>这里使用 cosine distance 进行排序，距离越小越相似；
     * 对外返回时再转换成 similarityScore，便于前端和业务侧理解。</p>
     */
    private static final String RETRIEVE_SQL_TEMPLATE = """
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

    private final SuperAgentDocumentMapper documentMapper;

    private final JdbcTemplate pgVectorJdbcTemplate;

    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;

    private final ObjectProvider<ChatModel> chatModelProvider;

    public DocumentQuestionAnswerServiceImpl(SuperAgentDocumentMapper documentMapper,
                                             @Qualifier("documentManagePgVectorJdbcTemplate") JdbcTemplate pgVectorJdbcTemplate,
                                             ObjectProvider<EmbeddingModel> embeddingModelProvider,
                                             ObjectProvider<ChatModel> chatModelProvider) {
        this.documentMapper = documentMapper;
        this.pgVectorJdbcTemplate = pgVectorJdbcTemplate;
        this.embeddingModelProvider = embeddingModelProvider;
        this.chatModelProvider = chatModelProvider;
    }

    @Override
    public DocumentQuestionAskVo ask(DocumentQuestionAskDto dto) {
        // topK 和文档列表先做统一归一化，避免后面检索和返回结构口径不一致。
        int topK = resolveTopK(dto.getTopK());
        List<Long> requestDocumentIdList = new LinkedHashSet<>(dto.getDocumentIdList()).stream().toList();

        // 问答只能基于“索引已构建成功”的文档执行，未构建完成的文档直接过滤掉。
        List<SuperAgentDocument> documentList = listAvailableDocuments(requestDocumentIdList);
        if (CollUtil.isEmpty(documentList)) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_INDEX_UNAVAILABLE.getCode(),
                "所选文档都没有可用的已构建索引，请先完成索引构建。");
        }

        // 这些映射和 ID 列表会同时用于 PGVector 检索和结果展示。
        Map<Long, String> documentNameMap = documentList.stream()
            .collect(Collectors.toMap(SuperAgentDocument::getId, SuperAgentDocument::getDocumentName,
                (left, right) -> left, LinkedHashMap::new));
        List<Long> availableDocumentIdList = documentList.stream().map(SuperAgentDocument::getId).toList();
        List<Long> taskIdList = documentList.stream()
            .map(SuperAgentDocument::getLastIndexTaskId)
            .filter(Objects::nonNull)
            .toList();

        // 先把问题转成向量，再进入向量库做 topK 召回。
        String questionVector = toVectorLiteral(requireEmbeddingModel().embed(dto.getQuestion().trim()));
        List<DocumentQuestionReferenceVo> referenceList = queryTopKReferences(
            questionVector, availableDocumentIdList, taskIdList, topK, documentNameMap);
        if (referenceList.isEmpty()) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_RETRIEVE_EMPTY.getCode(),
                "未从所选文档中检索到相关片段，请调整提问方式后再试。");
        }

        // 检索片段拿到以后，再交给 ChatModel 组织成最终答案。
        String answer = buildAnswer(dto.getQuestion().trim(), referenceList);
        return new DocumentQuestionAskVo(
            dto.getQuestion().trim(),
            topK,
            availableDocumentIdList.size(),
            referenceList.size(),
            answer,
            referenceList
        );
    }

    /**
     * 查询具备可用索引的文档。
     */
    private List<SuperAgentDocument> listAvailableDocuments(List<Long> documentIdList) {
        // 这里除了校验文档状态，还要求 lastIndexTaskId 不为空，
        // 因为问答检索要明确知道应该命中哪一次成功构建的索引数据。
        return documentMapper.selectList(new LambdaQueryWrapper<SuperAgentDocument>()
            .in(SuperAgentDocument::getId, documentIdList)
            .eq(SuperAgentDocument::getStatus, BusinessStatus.YES.getCode())
            .eq(SuperAgentDocument::getIndexStatus, DocumentIndexStatusEnum.BUILD_SUCCESS.getCode())
            .isNotNull(SuperAgentDocument::getLastIndexTaskId));
    }

    /**
     * 从 PGVector 检索 topK 命中片段。
     */
    private List<DocumentQuestionReferenceVo> queryTopKReferences(String questionVector,
                                                                  List<Long> documentIdList,
                                                                  List<Long> taskIdList,
                                                                  int topK,
                                                                  Map<Long, String> documentNameMap) {
        // 文档维度和任务维度同时收缩，是为了避免命中文档旧版本索引的数据。
        String documentPlaceholder = buildPlaceholders(documentIdList.size());
        String taskPlaceholder = buildPlaceholders(taskIdList.size());
        String sql = RETRIEVE_SQL_TEMPLATE.formatted(
            DocumentPgVectorConstants.EMBEDDING_TABLE_NAME, documentPlaceholder, taskPlaceholder);

        List<Object> parameterList = new ArrayList<>();

        // SQL 里 questionVector 被用两次：
        // 一次计算 similarity_score，一次做 ORDER BY 距离排序。
        parameterList.add(questionVector);
        parameterList.addAll(documentIdList);
        parameterList.addAll(taskIdList);
        parameterList.add(questionVector);
        parameterList.add(topK);

        // 检索结果在这里直接映射成前端可展示的引用对象，避免上层再做二次组装。
        return pgVectorJdbcTemplate.query(sql, parameterList.toArray(), (resultSet, rowNum) ->
            new DocumentQuestionReferenceVo(
                resultSet.getLong("id"),
                resultSet.getLong("document_id"),
                documentNameMap.get(resultSet.getLong("document_id")),
                resultSet.getLong("task_id"),
                resultSet.getInt("chunk_no"),
                resultSet.getString("section_path"),
                resultSet.getString("page_no"),
                resultSet.getString("chunk_text"),
                resultSet.getDouble("similarity_score")
            ));
    }

    /**
     * 基于召回片段组织最终答案。
     *
     * <p>优先使用 Spring AI 的 ChatModel 输出自然语言答案；
     * 如果当前环境没有注入 ChatModel，则退化成“直接返回命中片段摘要”的方式，确保链路可用。</p>
     */
    private String buildAnswer(String question, List<DocumentQuestionReferenceVo> referenceList) {
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            // 没有独立问答模型时，仍然返回一个可阅读的兜底答案，保证功能不中断。
            return buildFallbackAnswer(referenceList);
        }

        StringBuilder referencePrompt = new StringBuilder();
        for (int index = 0; index < referenceList.size(); index++) {
            DocumentQuestionReferenceVo reference = referenceList.get(index);

            // 把每个召回片段都编号拼进 prompt，方便模型在答案里按 [1][2] 做引用。
            referencePrompt.append("[")
                .append(index + 1)
                .append("] 文档：")
                .append(reference.getDocumentName())
                .append("；章节：")
                .append(StrUtil.isNotBlank(reference.getSectionPath()) ? reference.getSectionPath() : "未识别")
                .append("；页码：")
                .append(StrUtil.isNotBlank(reference.getPageNo()) ? reference.getPageNo() : "未知")
                .append("\n内容：")
                .append(reference.getChunkText())
                .append("\n\n");
        }

        String prompt = """
            你是企业知识问答助手。
            请严格基于给定的检索资料回答用户问题，不要编造资料中不存在的内容。
            如果资料不足以回答问题，请明确说明“当前检索到的资料不足以支持明确结论”。
            如果引用了资料，可以在句子末尾追加类似 [1][2] 的引用标记。

            用户问题：
            """ + question + "\n\n检索资料：\n" + referencePrompt;

        // 这里不直接把用户问题丢给模型，而是通过强约束提示词要求“必须基于检索资料回答”。
        String answer = ChatClient.builder(chatModel)
            .build()
            .prompt()
            .user(prompt)
            .call()
            .content();
        if (StrUtil.isBlank(answer)) {
            // 即使模型空返回，也退回到引用片段摘要，避免前端看到空白答案。
            return buildFallbackAnswer(referenceList);
        }
        return answer.trim();
    }

    /**
     * 当当前环境没有可用 ChatModel 时，回退成简单摘要答案。
     */
    private String buildFallbackAnswer(List<DocumentQuestionReferenceVo> referenceList) {
        StringBuilder fallbackAnswer = new StringBuilder("当前未启用独立问答模型，以下是最相关的检索片段：\n");
        for (int index = 0; index < referenceList.size(); index++) {
            DocumentQuestionReferenceVo reference = referenceList.get(index);

            // 兜底答案不做复杂生成，只把最关键的命中文档、章节和正文片段按序展示出来。
            fallbackAnswer.append(index + 1)
                .append(". ")
                .append(reference.getDocumentName())
                .append(" - ")
                .append(StrUtil.isNotBlank(reference.getSectionPath()) ? reference.getSectionPath() : "未识别章节")
                .append("：")
                .append(reference.getChunkText())
                .append("\n");
        }
        return fallbackAnswer.toString().trim();
    }

    /**
     * 获取当前可用的 EmbeddingModel。
     */
    private EmbeddingModel requireEmbeddingModel() {
        // 检索链路的第一步就是向量化问题，所以 EmbeddingModel 是硬依赖。
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_VECTOR_FAILED.getCode(),
                "当前未找到可用的 EmbeddingModel，无法执行检索。");
        }
        return embeddingModel;
    }

    /**
     * 把查询向量转换成 PostgreSQL vector 字面量。
     */
    private String toVectorLiteral(float[] embedding) {
        if (embedding == null || embedding.length == 0) {
            throw new SuperAgentFrameException(DocumentManageCode.DOCUMENT_VECTOR_FAILED.getCode(),
                "问题向量生成失败，无法执行检索。");
        }
        StringBuilder vectorBuilder = new StringBuilder("[");
        for (int index = 0; index < embedding.length; index++) {
            // PostgreSQL vector 的文本字面量格式形如 [0.1,0.2,0.3]。
            if (index > 0) {
                vectorBuilder.append(",");
            }
            vectorBuilder.append(embedding[index]);
        }
        vectorBuilder.append("]");
        return vectorBuilder.toString();
    }

    /**
     * 组装 SQL IN 占位符。
     */
    private String buildPlaceholders(int size) {
        // 用参数占位符而不是直接拼接值，方便复用 PreparedStatement 并规避注入风险。
        return java.util.stream.IntStream.range(0, size)
            .mapToObj(index -> "?")
            .collect(Collectors.joining(","));
    }

    /**
     * 统一处理 topK 默认值。
     */
    private int resolveTopK(Integer topK) {
        // topK 既要有默认值，也要限制上限，防止一次性召回过多片段拖慢问答链路。
        return topK == null || topK <= 0 ? DEFAULT_TOP_K : Math.min(topK, 20);
    }
}
