package org.javaup.ai.manage.service.impl;

import lombok.AllArgsConstructor;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.manage.config.DocumentManageProperties;
import org.javaup.ai.manage.data.SuperAgentDocumentStructureNode;
import org.javaup.ai.manage.model.es.DocumentNavigationIndexRecord;
import org.javaup.ai.manage.service.DocumentNavigationIndexService;
import org.javaup.enums.DocumentStructureNodeTypeEnum;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;


@Slf4j
@AllArgsConstructor
@Service
@ConditionalOnProperty(prefix = "app.manage.elasticsearch", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ElasticsearchDocumentNavigationIndexService implements DocumentNavigationIndexService {

    private static final int DEFAULT_SEARCH_SIZE = 8;

    @Qualifier("documentManageElasticsearchClient")
    private final ElasticsearchClient elasticsearchClient;
    private final DocumentManageProperties properties;

    @Override
    public void reindexDocumentNodes(Long documentId, Long parseTaskId, List<SuperAgentDocumentStructureNode> nodes) {
        if (documentId == null) {
            return;
        }
        log.info("开始重建导航索引: documentId={}, parseTaskId={}, nodeCount={}, index={}",
            documentId,
            parseTaskId,
            nodes == null ? 0 : nodes.size(),
            properties.getElasticsearch().getNavigationIndexName());
        deleteByDocumentId(documentId);
        if (CollUtil.isEmpty(nodes)) {
            log.info("导航索引重建跳过写入，因为结构节点为空: documentId={}, parseTaskId={}", documentId, parseTaskId);
            return;
        }
        BulkRequest.Builder bulkBuilder = new BulkRequest.Builder()
            .index(properties.getElasticsearch().getNavigationIndexName())
            .refresh(Refresh.WaitFor);
        for (SuperAgentDocumentStructureNode node : nodes) {
            if (node == null || node.getId() == null) {
                continue;
            }
            DocumentNavigationIndexRecord record = toIndexRecord(node, parseTaskId);
            bulkBuilder.operations(operation -> operation
                .index(index -> index
                    .id(String.valueOf(record.getNodeId()))
                    .document(record)
                )
            );
        }
        try {
            BulkResponse response = elasticsearchClient.bulk(bulkBuilder.build());
            if (response.errors()) {
                String errorMessage = response.items().stream()
                    .filter(item -> item.error() != null)
                    .map(item -> item.id() + ":" + item.error().reason())
                    .collect(Collectors.joining("; "));
                throw new IllegalStateException("批量写入导航索引失败: " + errorMessage);
            }
            log.info("文档结构节点已同步写入导航索引: documentId={}, parseTaskId={}, nodeCount={}, index={}",
                documentId, parseTaskId, nodes.size(), properties.getElasticsearch().getNavigationIndexName());
        }
        catch (IOException exception) {
            throw new IllegalStateException("写入导航索引失败", exception);
        }
    }

    @Override
    public void deleteByDocumentId(Long documentId) {
        if (documentId == null) {
            return;
        }
        try {
            log.info("删除导航索引文档数据: documentId={}, index={}", documentId, properties.getElasticsearch().getNavigationIndexName());
            elasticsearchClient.deleteByQuery(delete -> delete
                .index(properties.getElasticsearch().getNavigationIndexName())
                .refresh(true)
                .query(query -> query.term(term -> term
                    .field("documentId")
                    .value(documentId)
                ))
            );
        }
        catch (IOException exception) {
            throw new IllegalStateException("删除导航索引失败", exception);
        }
    }


    /*
    searchSections：ES 检索的完整流程
     */
    @Override
    public List<NavigationSectionHit> searchSections(Long documentId,
                                                     String topic,
                                                     String facet,
                                                     String informationNeed,
                                                     String question,
                                                     int size) {
        if (documentId == null) {
            return List.of();
        }
        // 将四个文本维度去重合并为查询词列表，任一为空则跳过  第一步是 buildQueries，把四个文本参数去重合并成查询词列表：
        List<String> queries = buildQueries(topic, facet, informationNeed, question);
        if (queries.isEmpty()) {
            return List.of();
        }
        // size <= 0 时使用默认值，同时限制上限为 20，防止一次拉取过多数据
        int searchSize = size <= 0 ? DEFAULT_SEARCH_SIZE : Math.min(size, 20);
        log.info("导航索引搜索请求: documentId={}, topic='{}', facet='{}', informationNeed='{}', question='{}', size={}, queries={}",
            documentId,
            safeText(topic),
            safeText(facet),
            safeText(informationNeed),
            safeText(question),
            searchSize,
            queries);
        try {
            //ES bool 查询结构
            /*
            整个查询是一个 bool 查询，分两部分：
            filter 部分（不参与评分，只过滤范围）：
            documentId 精确匹配 → 只在当前文档的章节里搜
            nodeType = SECTION → 只搜章节节点，排除步骤、列表项等
            should 部分（参与评分，决定排序）：
            每个查询词都调用 addSectionShouldQueries 构造三条 should 子句
            minimumShouldMatch = 1：至少命中一条 should 才返回结果
             */
            SearchResponse<DocumentNavigationIndexRecord> response = elasticsearchClient.search(search -> search
                    .index(properties.getElasticsearch().getNavigationIndexName())
                    .size(searchSize)
                    .query(query -> query.bool(bool -> {
                        // filter 子句：精确限定文档范围，不参与相关度评分
                        bool.filter(filter -> filter.term(term -> term
                            .field("documentId")
                            .value(documentId)
                        ));
                        // filter 子句：只搜索章节节点，排除步骤、列表项等其他类型
                        bool.filter(filter -> filter.term(term -> term
                            .field("nodeType")
                            .value(DocumentStructureNodeTypeEnum.SECTION.name())
                        ));
                        // 每个查询词独立构造 should 子句，多个词之间是”或”关系
                        for (String queryText : queries) {
                            addSectionShouldQueries(bool, queryText);
                        }
                        // 至少命中一个 should 子句，避免 filter-only 查询返回全量数据
                        bool.minimumShouldMatch("1");
                        return bool;
                    })),
                DocumentNavigationIndexRecord.class);

           /*
            结果处理
            ES 返回的结果按相关度降序排列，这里直接遍历转成 NavigationSectionHit 列表。如果 ES 不可用或查询超时，catch 住异常返回空列表，由上层的第三层本地匹配兜底。
            */
            List<NavigationSectionHit> hits = new ArrayList<>();
            for (Hit<DocumentNavigationIndexRecord> hit : response.hits().hits()) {
                DocumentNavigationIndexRecord source = hit.source();
                if (source == null || source.getNodeId() == null) {
                    continue;
                }
                hits.add(new NavigationSectionHit(
                    source.getNodeId(),
                    safeText(source.getNodeCode()),
                    safeText(source.getTitle()),
                    safeText(source.getSectionPath()),
                    safeText(source.getCanonicalPath()),
                    hit.score() == null ? 0D : hit.score()// ES 偶尔返回 null score，兜底为 0
                ));
            }
            log.info("导航索引搜索完成: documentId={}, hitCount={}, topHits={}",
                documentId,
                hits.size(),
                hits.stream().limit(3).map(hit -> hit.nodeId() + ":" + hit.sectionPath() + ":" + hit.score()).toList());
            return hits;
        }
        catch (IOException exception) {
            log.warn("导航索引章节搜索失败，自动回退到结构图兜底匹配: documentId={}, question='{}', error={}",
                documentId, safeText(question), exception.getMessage());
            return List.of();
        }
    }



    /*
    多字段加权匹配
     */
    private void addSectionShouldQueries(co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery.Builder bool,
                                         String queryText) {
        if (StrUtil.isBlank(queryText)) {
            return;
        }
        // 短语匹配要求词序完全一致，精确度最高，给予最大 boost
        bool.should(should -> should.matchPhrase(matchPhrase -> matchPhrase
            .field("title")
            .query(queryText)
            .boost(20.0f)
        ));
        bool.should(should -> should.matchPhrase(matchPhrase -> matchPhrase
            .field("sectionPath")
            .query(queryText)
            .boost(15.0f)
        ));
        // best_fields 取各字段中得分最高的一个，适合关键词散落在不同字段的情况
        bool.should(should -> should.multiMatch(multiMatch -> multiMatch
            .query(queryText)
            .fields("title^10", "sectionPath^8", "anchorText^5", "contentText")
            .type(TextQueryType.BestFields)
        ));
    }


    /*
    因为这里 topic 和 question 传的是同一个值，去重后只剩两条：改写问题 + facet（如果 facet 不为空的话）。
     */
    private List<String> buildQueries(String topic, String facet, String informationNeed, String question) {
        List<String> queries = new ArrayList<>();
        addNonBlank(queries, topic);
        addNonBlank(queries, facet);
        addNonBlank(queries, informationNeed);
        addNonBlank(queries, question);
        // distinct 去重：topic 与 question 内容相同时只保留一条，避免重复加权
        return queries.stream().distinct().toList();
    }

    private void addNonBlank(List<String> queries, String value) {
        if (StrUtil.isNotBlank(value)) {
            queries.add(value.trim());
        }
    }

    private DocumentNavigationIndexRecord toIndexRecord(SuperAgentDocumentStructureNode node, Long parseTaskId) {
        DocumentStructureNodeTypeEnum nodeType = DocumentStructureNodeTypeEnum.getRc(node.getNodeType());
        return DocumentNavigationIndexRecord.builder()
            .nodeId(node.getId())
            .documentId(node.getDocumentId())
            .parseTaskId(node.getParseTaskId() == null ? parseTaskId : node.getParseTaskId())
            .nodeType(nodeType == null ? "" : nodeType.name())
            .nodeCode(safeText(node.getNodeCode()))
            .nodeNo(node.getNodeNo())
            .depth(node.getDepth())
            .parentNodeId(node.getParentNodeId())
            .title(safeText(node.getTitle()))
            .anchorText(safeText(node.getAnchorText()))
            .sectionPath(safeText(node.getSectionPath()))
            .canonicalPath(safeText(node.getCanonicalPath()))
            .contentText(safeText(node.getContentText()))
            .itemIndex(node.getItemIndex())
            .build();
    }

    private String safeText(String text) {
        return text == null ? "" : text.trim();
    }
}
