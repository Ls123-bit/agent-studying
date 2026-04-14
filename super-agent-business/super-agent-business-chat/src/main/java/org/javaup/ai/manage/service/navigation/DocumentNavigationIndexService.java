package org.javaup.ai.manage.service.navigation;

import cn.hutool.core.util.StrUtil;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.manage.config.DocumentManageProperties;
import org.javaup.ai.manage.data.SuperAgentDocumentStructureNode;
import org.javaup.ai.manage.service.DocumentStructureNodeService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 文档结构导航索引服务。
 *
 * <p>负责把文档结构节点同步到 ES 导航索引，
 * 以及提供基于 IK 分词的章节语义匹配查询。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.manage.elasticsearch", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DocumentNavigationIndexService {

    private final ElasticsearchClient elasticsearchClient;
    private final DocumentManageProperties properties;
    private final DocumentStructureNodeService documentStructureNodeService;

    public DocumentNavigationIndexService(
        @Qualifier("documentManageElasticsearchClient") ElasticsearchClient elasticsearchClient,
        DocumentManageProperties properties,
        DocumentStructureNodeService documentStructureNodeService) {
        this.elasticsearchClient = elasticsearchClient;
        this.properties = properties;
        this.documentStructureNodeService = documentStructureNodeService;
    }

    /**
     * 同步指定文档的结构节点到导航索引。
     */
    public void syncNavigationIndex(Long documentId, Long parseTaskId) {
        String indexName = properties.getElasticsearch().getNavigationIndexName();
        try {
            deleteByDocumentId(indexName, documentId);
            Map<Long, SuperAgentDocumentStructureNode> nodeMap = documentStructureNodeService.nodeMap(documentId, parseTaskId);
            if (nodeMap.isEmpty()) {
                log.info("导航索引同步: documentId={}, 无结构节点，跳过。", documentId);
                return;
            }
            bulkIndex(indexName, nodeMap);
            log.info("导航索引同步完成: documentId={}, nodeCount={}", documentId, nodeMap.size());
        }
        catch (IOException exception) {
            log.error("导航索引同步失败: documentId={}, error={}", documentId, exception.getMessage(), exception);
        }
    }

    /**
     * 基于 IK 分词的章节语义匹配查询。
     *
     * <p>title/sectionPath 命中权重远高于 contentText，
     * 避免"正文提到某个词"被误判成"这个章节就是关于这个主题的"。</p>
     */
    public List<NavigationMatchResult> searchSections(Long documentId, String topic, String facet, int topK) {
        String indexName = properties.getElasticsearch().getNavigationIndexName();
        try {
            BoolQuery.Builder bool = new BoolQuery.Builder();
            bool.filter(f -> f.term(t -> t.field("documentId").value(documentId)));
            bool.filter(f -> f.term(t -> t.field("nodeType").value("SECTION")));

            if (StrUtil.isNotBlank(topic)) {
                bool.should(s -> s.matchPhrase(m -> m.field("title").query(topic).boost(20.0f)));
                bool.should(s -> s.matchPhrase(m -> m.field("sectionPath").query(topic).boost(15.0f)));
                bool.should(s -> s.match(m -> m.field("title").query(topic).boost(10.0f)));
                bool.should(s -> s.match(m -> m.field("sectionPath").query(topic).boost(8.0f)));
                bool.should(s -> s.match(m -> m.field("anchorText").query(topic).boost(5.0f)));
                bool.should(s -> s.match(m -> m.field("contentText").query(topic).boost(1.0f)));
            }
            if (StrUtil.isNotBlank(facet)) {
                bool.should(s -> s.matchPhrase(m -> m.field("title").query(facet).boost(8.0f)));
                bool.should(s -> s.match(m -> m.field("title").query(facet).boost(4.0f)));
                bool.should(s -> s.match(m -> m.field("sectionPath").query(facet).boost(3.0f)));
            }
            bool.minimumShouldMatch("1");

            SearchResponse<Map> response = elasticsearchClient.search(SearchRequest.of(search -> search
                .index(indexName)
                .query(q -> q.bool(bool.build()))
                .size(topK)
            ), Map.class);

            List<NavigationMatchResult> results = new ArrayList<>();
            for (Hit<Map> hit : response.hits().hits()) {
                if (hit.source() == null) {
                    continue;
                }
                results.add(NavigationMatchResult.fromEsHit(hit.source(), hit.score() == null ? 0.0 : hit.score()));
            }
            return results;
        }
        catch (IOException exception) {
            log.warn("导航索引查询失败: documentId={}, topic='{}', facet='{}', error={}", documentId, topic, facet, exception.getMessage());
            return List.of();
        }
    }

    private void deleteByDocumentId(String indexName, Long documentId) throws IOException {
        elasticsearchClient.deleteByQuery(delete -> delete
            .index(indexName)
            .query(q -> q.term(t -> t.field("documentId").value(documentId)))
            .refresh(true)
        );
    }

    private void bulkIndex(String indexName, Map<Long, SuperAgentDocumentStructureNode> nodeMap) throws IOException {
        BulkRequest.Builder bulk = new BulkRequest.Builder();
        for (SuperAgentDocumentStructureNode node : nodeMap.values()) {
            if (node == null) {
                continue;
            }
            String nodeType = resolveNodeTypeName(node.getNodeType());
            Map<String, Object> doc = new java.util.LinkedHashMap<>();
            doc.put("nodeId", node.getId());
            doc.put("documentId", node.getDocumentId());
            doc.put("nodeType", nodeType);
            doc.put("nodeCode", safeText(node.getNodeCode()));
            doc.put("depth", node.getDepth() == null ? 0 : node.getDepth());
            doc.put("parentNodeId", node.getParentNodeId() == null ? 0L : node.getParentNodeId());
            doc.put("itemIndex", node.getItemIndex() == null ? 0 : node.getItemIndex());
            doc.put("title", safeText(node.getTitle()));
            doc.put("anchorText", safeText(node.getAnchorText()));
            doc.put("sectionPath", safeText(node.getSectionPath()));
            doc.put("canonicalPath", safeText(node.getCanonicalPath()));
            doc.put("contentText", safeText(node.getContentText()));
            bulk.operations(op -> op.index(idx -> idx
                .index(indexName)
                .id(String.valueOf(node.getId()))
                .document(doc)
            ));
        }
        elasticsearchClient.bulk(bulk.refresh(Refresh.WaitFor).build());
    }

    private String resolveNodeTypeName(Integer nodeType) {
        if (nodeType == null) {
            return "UNKNOWN";
        }
        return switch (nodeType) {
            case 1 -> "DOCUMENT";
            case 2 -> "SECTION";
            case 3 -> "STEP";
            case 4 -> "LIST_ITEM";
            default -> "UNKNOWN";
        };
    }

    private String safeText(String text) {
        return text == null ? "" : text.trim();
    }

    /**
     * 导航索引匹配结果。
     */
    public record NavigationMatchResult(
        Long nodeId,
        Long documentId,
        String nodeType,
        String nodeCode,
        Integer depth,
        Long parentNodeId,
        Integer itemIndex,
        String title,
        String anchorText,
        String sectionPath,
        String canonicalPath,
        double score
    ) {
        @SuppressWarnings("unchecked")
        static NavigationMatchResult fromEsHit(Map<String, Object> source, double score) {
            return new NavigationMatchResult(
                toLong(source.get("nodeId")),
                toLong(source.get("documentId")),
                String.valueOf(source.getOrDefault("nodeType", "")),
                String.valueOf(source.getOrDefault("nodeCode", "")),
                toInt(source.get("depth")),
                toLong(source.get("parentNodeId")),
                toInt(source.get("itemIndex")),
                String.valueOf(source.getOrDefault("title", "")),
                String.valueOf(source.getOrDefault("anchorText", "")),
                String.valueOf(source.getOrDefault("sectionPath", "")),
                String.valueOf(source.getOrDefault("canonicalPath", "")),
                score
            );
        }

        private static Long toLong(Object value) {
            if (value == null) {
                return null;
            }
            if (value instanceof Number number) {
                return number.longValue();
            }
            try {
                return Long.parseLong(String.valueOf(value));
            }
            catch (NumberFormatException exception) {
                return null;
            }
        }

        private static Integer toInt(Object value) {
            if (value == null) {
                return null;
            }
            if (value instanceof Number number) {
                return number.intValue();
            }
            try {
                return Integer.parseInt(String.valueOf(value));
            }
            catch (NumberFormatException exception) {
                return null;
            }
        }
    }
}
