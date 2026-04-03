package org.javaup.ai.manage.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.manage.data.SuperAgentDocument;
import org.javaup.ai.manage.dto.DocumentQuestionAskDto;
import org.javaup.ai.manage.mapper.SuperAgentDocumentMapper;
import org.javaup.ai.manage.model.DocumentRetrieveRequest;
import org.javaup.ai.manage.service.DocumentQuestionAnswerService;
import org.javaup.ai.manage.service.DocumentKnowledgeService;
import org.javaup.ai.manage.support.DocumentKnowledgeMetadataKeys;
import org.javaup.ai.manage.vo.DocumentQuestionAskVo;
import org.javaup.ai.manage.vo.DocumentQuestionReferenceVo;
import org.javaup.enums.BusinessStatus;
import org.javaup.enums.DocumentIndexStatusEnum;
import org.javaup.enums.DocumentManageCode;
import org.javaup.exception.SuperAgentFrameException;
import org.springframework.ai.document.Document;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

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

    private final SuperAgentDocumentMapper documentMapper;

    private final ObjectProvider<ChatModel> chatModelProvider;

    private final DocumentKnowledgeService documentKnowledgeService;

    public DocumentQuestionAnswerServiceImpl(SuperAgentDocumentMapper documentMapper,
                                             ObjectProvider<ChatModel> chatModelProvider,
                                             DocumentKnowledgeService documentKnowledgeService) {
        this.documentMapper = documentMapper;
        this.chatModelProvider = chatModelProvider;
        this.documentKnowledgeService = documentKnowledgeService;
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

        List<DocumentQuestionReferenceVo> referenceList = queryTopKReferences(
            dto.getQuestion().trim(), availableDocumentIdList, taskIdList, topK, documentNameMap);
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
    private List<DocumentQuestionReferenceVo> queryTopKReferences(String question,
                                                                  List<Long> documentIdList,
                                                                  List<Long> taskIdList,
                                                                  int topK,
                                                                  Map<Long, String> documentNameMap) {
        /*
         * 管理台问答目前仍然使用“纯向量召回”这条相对稳定的链路，
         * 但真正的底层检索实现已经统一收口到 DocumentKnowledgeService，
         * 避免这里继续维护第二份 PGVector SQL。
         */
        List<Document> documents = documentKnowledgeService.vectorSearch(new DocumentRetrieveRequest(
            question,
            documentIdList,
            taskIdList,
            topK
        ));
        return documents.stream()
            .map(document -> toReferenceVo(document, documentNameMap))
            .toList();
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
     * 把统一检索结果转换成管理台问答引用对象。
     */
    private DocumentQuestionReferenceVo toReferenceVo(Document document, Map<Long, String> documentNameMap) {
        Map<String, Object> metadata = document.getMetadata();
        Long documentId = asLong(metadata.get(DocumentKnowledgeMetadataKeys.DOCUMENT_ID));
        Long chunkId = asLong(metadata.get(DocumentKnowledgeMetadataKeys.CHUNK_ID));
        Long taskId = asLong(metadata.get(DocumentKnowledgeMetadataKeys.TASK_ID));
        Integer chunkNo = asInteger(metadata.get(DocumentKnowledgeMetadataKeys.CHUNK_NO));
        Double score = asDouble(metadata.get(DocumentKnowledgeMetadataKeys.SCORE));

        return new DocumentQuestionReferenceVo(
            chunkId,
            documentId,
            documentNameMap.getOrDefault(documentId, asText(metadata.get(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME))),
            taskId,
            chunkNo,
            asText(metadata.get(DocumentKnowledgeMetadataKeys.SECTION_PATH)),
            asText(metadata.get(DocumentKnowledgeMetadataKeys.PAGE_NO)),
            document.getText(),
            score == null ? 0D : score
        );
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
    }

    private Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    private Double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return null;
    }

    private String asText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 统一处理 topK 默认值。
     */
    private int resolveTopK(Integer topK) {
        // topK 既要有默认值，也要限制上限，防止一次性召回过多片段拖慢问答链路。
        return topK == null || topK <= 0 ? DEFAULT_TOP_K : Math.min(topK, 20);
    }
}
