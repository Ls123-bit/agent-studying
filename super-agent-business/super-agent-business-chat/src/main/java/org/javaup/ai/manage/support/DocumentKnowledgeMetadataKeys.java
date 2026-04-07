package org.javaup.ai.manage.support;

/**
 * 文档检索结果写入 Spring AI {@code Document.metadata} 时使用的统一键名。
 *
 * <p>后续无论是混合检索、重排序还是 Prompt 装配，
 * 都只面向这些稳定键名编程，避免调用方直接依赖 SQL 列名。</p>
 */
public final class DocumentKnowledgeMetadataKeys {

    public static final String SOURCE_TYPE = "sourceType";
    public static final String CHANNEL = "channel";
    public static final String SCORE = "score";
    public static final String DOCUMENT_ID = "documentId";
    public static final String DOCUMENT_NAME = "documentName";
    public static final String TASK_ID = "taskId";
    public static final String PARENT_BLOCK_ID = "parentBlockId";
    public static final String PARENT_BLOCK_NO = "parentBlockNo";
    public static final String CHUNK_ID = "chunkId";
    public static final String CHUNK_NO = "chunkNo";
    public static final String SECTION_PATH = "sectionPath";
    public static final String KNOWLEDGE_SCOPE_CODE = "knowledgeScopeCode";
    public static final String KNOWLEDGE_SCOPE_NAME = "knowledgeScopeName";
    public static final String BUSINESS_CATEGORY = "businessCategory";
    public static final String DOCUMENT_TAGS = "documentTags";
    public static final String TITLE = "title";
    public static final String URL = "url";
    public static final String TOOL_NAME = "toolName";
    public static final String ORIGINAL_SNIPPET = "originalSnippet";
    public static final String RRF_SCORE = "rrfScore";

    private DocumentKnowledgeMetadataKeys() {
    }
}
