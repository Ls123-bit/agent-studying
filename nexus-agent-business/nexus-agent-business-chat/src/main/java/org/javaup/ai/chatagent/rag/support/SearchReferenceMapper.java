package org.javaup.ai.chatagent.rag.support;

import org.javaup.ai.chatagent.model.SearchReference;
import org.javaup.ai.manage.support.DocumentKnowledgeMetadataKeys;
import org.springframework.ai.document.Document;

import java.util.Map;


public final class SearchReferenceMapper {

    private SearchReferenceMapper() {
    }

    public static SearchReference fromDocument(Document document,
                                               int subQuestionIndex,
                                               String subQuestion,
                                               int referenceNumber) {

        /*
        这段Mapper的逻辑分三块来看
        公共字段填充：方法一进来，先从 Document 的 metadata 里取出所有需要的值，然后填充所有引用对象都需要的公共字段：
        WEB 类型的处理：如果 sourceType 是 WEB，说明这条证据是从网页搜索来的，那只需要填网页相关的几个字段就够了：填完直接 return，不需要再往下走文档类型的逻辑了。
        文档类型的处理 如果不是 WEB，那就是知识库文档类型的证据。这类证据需要的字段比较多，因为文档有层级结构：

         */

        // 统一从 Document 元数据里取值，避免把字段散落在各个分支里。
        Map<String, Object> metadata = document.getMetadata();
        // SOURCE_TYPE 决定当前引用是网页证据还是文档证据，缺失时默认按文档处理。
        String sourceType = asText(metadata.get(DocumentKnowledgeMetadataKeys.SOURCE_TYPE), "DOCUMENT");
        // 先创建统一的引用对象，再按不同来源类型补齐具体字段。
        SearchReference reference = new SearchReference();
        // referenceId 先写入临时编号，后续外层可能会重新分配展示序号。
        reference.setReferenceId(String.valueOf(referenceNumber));
        // 来源类型用于下游区分网页、文档和其他检索通道。
        reference.setSourceType(sourceType);
        // 直接保留命中的文本片段，作为最终引用展示的 snippet。
        reference.setSnippet(document.getText());
        // 记录当前证据来自哪个子问题，便于最终答案回溯。
        reference.setSubQuestionIndex(subQuestionIndex);
        // 保留子问题原文，方便追踪这条引用是回答哪个问题时命中的。
        reference.setSubQuestion(subQuestion);
        // channel 表示这条证据来自向量检索、网页检索等哪个通道。
        reference.setChannel(asText(metadata.get(DocumentKnowledgeMetadataKeys.CHANNEL), "vector"));
        // 分数可能来自 metadata，也可能为空，统一转成 Double 便于前端排序展示。
        reference.setScore(asDouble(metadata.get(DocumentKnowledgeMetadataKeys.SCORE)));
        // WEB 类型只需要网页层级的字段，不需要文档层级的结构信息。
        if ("WEB".equalsIgnoreCase(sourceType)) {
            reference.setTitle(asText(metadata.get(DocumentKnowledgeMetadataKeys.TITLE), "网页来源"));// 网页引用标题优先使用抓取到的页面标题，缺失时给一个默认说明。
            reference.setUrl(asText(metadata.get(DocumentKnowledgeMetadataKeys.URL), ""));// URL 是网页引用最关键的定位信息。
            reference.setToolName(asText(metadata.get(DocumentKnowledgeMetadataKeys.TOOL_NAME), "tavily_search")); // 记录具体抓取工具，便于排查不同网页引用的来源。
            return reference;
        }

        reference.setTitle(asText(metadata.get(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME), "文档片段"));// 文档引用默认标题取文档名，缺失时给一个通用名称。
        reference.setDocumentId(asLong(metadata.get(DocumentKnowledgeMetadataKeys.DOCUMENT_ID)));// 文档主键用于跨层级定位到原始知识库记录。
        reference.setDocumentName(asText(metadata.get(DocumentKnowledgeMetadataKeys.DOCUMENT_NAME), ""));// 记录文档名称，便于引用卡片直接展示。
        reference.setParentBlockId(asLong(metadata.get(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_ID))); // 父块信息用于把 chunk 重新归并到更高层级的章节或段落。
        reference.setParentBlockNo(asInteger(metadata.get(DocumentKnowledgeMetadataKeys.PARENT_BLOCK_NO))); // 父块序号用于同一文档内部的可读排序。
        reference.setChunkId(asLong(metadata.get(DocumentKnowledgeMetadataKeys.CHUNK_ID))); // 当前命中的 chunk id，用于精确回溯片段来源。
        reference.setChunkNo(asInteger(metadata.get(DocumentKnowledgeMetadataKeys.CHUNK_NO)));// chunk 序号用于展示同一父块下的片段顺序。
        reference.setSectionPath(asText(metadata.get(DocumentKnowledgeMetadataKeys.SECTION_PATH), ""));// sectionPath 用于表达当前片段在文档结构中的路径。
        reference.setStructureNodeId(asLong(metadata.get(DocumentKnowledgeMetadataKeys.STRUCTURE_NODE_ID)));// 结构节点 id 适合做更细粒度的树状定位。
        reference.setStructureNodeType(asInteger(metadata.get(DocumentKnowledgeMetadataKeys.STRUCTURE_NODE_TYPE)));// 结构节点类型帮助前端区分章节、段落、列表等节点形态。
        reference.setCanonicalPath(asText(metadata.get(DocumentKnowledgeMetadataKeys.CANONICAL_PATH), "")); // canonicalPath 记录规范化路径，方便去重和回溯。
        reference.setItemIndex(asInteger(metadata.get(DocumentKnowledgeMetadataKeys.ITEM_INDEX)));// itemIndex 用于某些列表型内容的顺序展示。
        reference.setKnowledgeScopeCode(asText(metadata.get(DocumentKnowledgeMetadataKeys.KNOWLEDGE_SCOPE_CODE), "")); // 知识范围编码帮助区分知识库中的业务域或知识集。
        reference.setKnowledgeScopeName(asText(metadata.get(DocumentKnowledgeMetadataKeys.KNOWLEDGE_SCOPE_NAME), ""));// 知识范围名称用于前端可读展示。
        return reference;
    }


    /*
    工具方法
     */
    private static String asText(Object value, String defaultValue) {
        // mapper 里还有几个私有的类型转换方法，逻辑很简单：
        return value == null ? defaultValue : String.valueOf(value);
    }

    private static Long asLong(Object value) {
        // 只接受数字类型，防止把字符串、布尔值等脏数据误转成 id。
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Integer asInteger(Object value) {
        // 同上，只对真正的数字类型做整型转换。
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Double asDouble(Object value) {
        // 分数字段保持为 Double，便于后续排序和展示统一处理。
        return value instanceof Number number ? number.doubleValue() : null;
    }
}


/*
这几个方法做的事情就是安全地从 metadata 里取值。metadata 的 value 类型是 Object，直接强转容易出问题，所以用这些方法包一层：空值给默认值，非数字类型不硬转，避免脏数据搞出 ClassCastException。
总的来说，SearchReference 不只是一个编号，它带着"这条证据来自哪里"的完整信息——网页类型知道 URL 和标题，文档类型知道具体在哪篇文档的哪个章节哪个切块。
 */