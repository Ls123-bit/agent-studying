package org.javaup.ai.chatagent.rag.support;

import org.javaup.ai.chatagent.model.SearchReference;
import org.javaup.ai.manage.support.DocumentKnowledgeMetadataKeys;
import org.springframework.ai.document.Document;

import java.util.Map;

/**
 * 检索文档到统一引用对象的映射器。
 */
public final class SearchReferenceMapper {

    private SearchReferenceMapper() {
    }

    /**
     * 把 Spring AI Document 转成统一引用对象。
     *
     * <p>引用层不应该重新理解 metadata 里的各个键名，
     * 所以这里集中做一次映射，保证下游永远拿到稳定结构。</p>
     */
    public static SearchReference fromDocument(Document document,
                                               int subQuestionIndex,
                                               String subQuestion,
                                               int referenceNumber) {
        Map<String, Object> metadata = document.getMetadata();
        String sourceType = asText(metadata.get(DocumentKnowledgeMetadataKeys.SOURCE_TYPE), "DOCUMENT");
        SearchReference reference = new SearchReference();
        reference.setReferenceId(String.valueOf(referenceNumber));
        reference.setSourceType(sourceType);
        reference.setSnippet(document.getText());
        reference.setSubQuestionIndex(subQuestionIndex);
        reference.setSubQuestion(subQuestion);
        reference.setChannel(asText(metadata.get(DocumentKnowledgeMetadataKeys.CHANNEL), "vector"));
        reference.setScore(asDouble(metadata.get(DocumentKnowledgeMetadataKeys.SCORE)));

        /*
         * 文档证据和网页证据的 metadata 结构不完全一样。
         * 这里统一在映射层做分支，避免下游展示层和 Prompt 层自己去猜当前是哪种来源。
         */
        if ("WEB".equalsIgnoreCase(sourceType)) {
            reference.setTitle(asText(metadata.get(DocumentKnowledgeMetadataKeys.TITLE), "网页来源"));
            reference.setUrl(asText(metadata.get(DocumentKnowledgeMetadataKeys.URL), ""));
            reference.setToolName(asText(metadata.get(DocumentKnowledgeMetadataKeys.TOOL_NAME), "tavily_search"));
            return reference;
        }

        reference.setTitle(asText(metadata.get(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME), "文档片段"));
        reference.setDocumentId(asLong(metadata.get(DocumentKnowledgeMetadataKeys.DOCUMENT_ID)));
        reference.setDocumentName(asText(metadata.get(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME), ""));
        reference.setChunkId(asLong(metadata.get(DocumentKnowledgeMetadataKeys.CHUNK_ID)));
        reference.setChunkNo(asInteger(metadata.get(DocumentKnowledgeMetadataKeys.CHUNK_NO)));
        reference.setSectionPath(asText(metadata.get(DocumentKnowledgeMetadataKeys.SECTION_PATH), ""));
        reference.setPageNo(asText(metadata.get(DocumentKnowledgeMetadataKeys.PAGE_NO), ""));
        reference.setKnowledgeScopeCode(asText(metadata.get(DocumentKnowledgeMetadataKeys.KNOWLEDGE_SCOPE_CODE), ""));
        reference.setKnowledgeScopeName(asText(metadata.get(DocumentKnowledgeMetadataKeys.KNOWLEDGE_SCOPE_NAME), ""));
        return reference;
    }

    private static String asText(Object value, String defaultValue) {
        return value == null ? defaultValue : String.valueOf(value);
    }

    private static Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Integer asInteger(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }
}
