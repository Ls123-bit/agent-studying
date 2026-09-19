package org.javaup.ai.manage.service.impl;

import lombok.AllArgsConstructor;
import cn.hutool.core.util.StrUtil;
import org.javaup.ai.manage.config.DocumentManageProperties;
import org.javaup.ai.manage.model.graph.GraphItem;
import org.javaup.ai.manage.model.graph.GraphSection;
import org.javaup.ai.manage.service.DocumentStructureGraphService;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Values;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Objects;


@AllArgsConstructor
@Service
@ConditionalOnBean(name = "documentManageNeo4jDriver")
public class Neo4jDocumentStructureGraphService implements DocumentStructureGraphService {

    private final Driver driver;
    private final DocumentManageProperties properties;

    @Override
    public boolean isGraphAvailable(Long documentId) {
        if (documentId == null) {
            return false;
        }
        try (Session session = openSession()) {
            return session.run("MATCH (d:Document {documentId: $documentId}) RETURN count(d) > 0 AS available",
                    Values.parameters("documentId", documentId))
                .single()
                .get("available")
                .asBoolean(false);
        }
        catch (Exception exception) {
            return false;
        }
    }



    /*精确定位章节  这是所有查询的基础方法，后面的 parentSection、previousSibling、nextSibling 都依赖它。
    部署了 Neo4j 时，用 Cypher 按 nodeId 精确查节点：
    用 documentId + nodeId 两个属性精确定位，走图数据库索引，是一次点查，性能很高。
    Neo4j 的查询很直观：用 Cypher 的 MATCH 语句，通过 documentId 和 nodeId 两个属性精确匹配一个 Section 标签的节点。
     */
    @Override
    public GraphSection findSectionById(Long documentId, Long sectionNodeId) {
        // 缺少文档 ID 或章节节点 ID 时无法唯一定位章节，直接返回空。
        if (documentId == null || sectionNodeId == null) {
            return null;
        }
        // 打开 Neo4j session 后，通过节点属性 documentId + nodeId 精确匹配章节。
        try (Session session = openSession()) {
            // 查询只返回目标 Section 节点，LIMIT 1 防止异常重复数据影响调用方。
            return session.run("""
                    MATCH (s:Section {documentId: $documentId, nodeId: $nodeId})
                    RETURN s
                    LIMIT 1
                    """,
                            // documentId 限定文档范围，nodeId 精确定位节点，两者缺一不可   // 使用参数绑定传入文档 ID 和章节节点 ID。
                    Values.parameters("documentId", documentId, "nodeId", sectionNodeId))
                    // 将 Neo4j Node 转换为统一的 GraphSection 领域模型。
                .list(record -> toGraphSection(record.get("s").asNode()))
                    // 转成 Stream 后取第一条，兼容没有查询结果的情况。
                .stream()
                .findFirst()
                    // 没有命中章节时返回 null，让上层执行无证据兜底。
                .orElse(null);
        }
    }


    /*部署了 Neo4j 时，走 Neo4jDocumentStructureGraphService.findSectionByCode，直接用 Cypher 查图数据库：

     */
    @Override
    public GraphSection findSectionByCode(Long documentId, String nodeCode) {
        if (documentId == null || StrUtil.isBlank(nodeCode)) {
            // 缺少必要查询条件时直接返回 null，避免发送无意义 Cypher。
            return null;
        }
        try (Session session = openSession()) {
            return session.run("""
                    MATCH (s:Section {documentId: $documentId, nodeCode: $nodeCode})
                    RETURN s
                    ORDER BY s.nodeNo ASC
                    LIMIT 1
                    """,
                            // nodeCode 在入参处先 trim，避免用户或上游拼接时带入首尾空白导致精确匹配失败。
                    Values.parameters("documentId", documentId, "nodeCode", nodeCode.trim()))
                .list(record -> toGraphSection(record.get("s").asNode()))
                .stream()
                    // 虽然理论上 nodeCode 在单文档内应唯一，但这里仍按"取第一条"处理，增强容错性。
                .findFirst()
                .orElse(null);
        }
    }

    @Override
    public GraphSection findSectionByTitle(Long documentId, String title) {
        if (documentId == null || StrUtil.isBlank(title)) {
            return null;
        }
        String normalized = normalize(title);
        try (Session session = openSession()) {
            return session.run("""
                    MATCH (s:Section {documentId: $documentId})
                    WHERE s.normalizedTitle = $normalized OR s.normalizedPath = $normalized
                    RETURN s
                    ORDER BY s.nodeNo ASC
                    LIMIT 1
                    """,
                    Values.parameters("documentId", documentId, "normalized", normalized))
                .list(record -> toGraphSection(record.get("s").asNode()))
                .stream()
                .findFirst()
                .orElse(null);
        }
    }

    @Override
    public GraphSection findSectionByCanonicalPath(Long documentId, String canonicalPath) {
        if (documentId == null || StrUtil.isBlank(canonicalPath)) {
            return null;
        }
        try (Session session = openSession()) {
            return session.run("""
                    MATCH (s:Section {documentId: $documentId, canonicalPath: $canonicalPath})
                    RETURN s
                    LIMIT 1
                    """,
                    Values.parameters("documentId", documentId, "canonicalPath", canonicalPath.trim()))
                .list(record -> toGraphSection(record.get("s").asNode()))
                .stream()
                .findFirst()
                .orElse(null);
        }
    }

    @Override
    public GraphSection findBestSection(Long documentId, String topic, String facet) {
        String normalizedTopic = normalize(topic);
        String normalizedFacet = normalize(facet);
        if (documentId == null || (normalizedTopic.isBlank() && normalizedFacet.isBlank())) {
            return null;
        }
        GraphSection bestSection = null;
        int bestScore = 0;
        for (GraphSection section : listSections(documentId)) {
            int score = 0;
            String sectionPath = normalize(section.getSectionPath());
            String title = normalize(section.getTitle());
            String anchorText = normalize(section.getAnchorText());
            String content = normalize(section.getContentText());
            if (StrUtil.isNotBlank(normalizedTopic)) {
                if (title.contains(normalizedTopic) || sectionPath.contains(normalizedTopic)) {
                    score += 8;
                }
                else if (anchorText.contains(normalizedTopic)) {
                    score += 6;
                }
                else if (content.contains(normalizedTopic)) {
                    score += 2;
                }
            }
            if (StrUtil.isNotBlank(normalizedFacet)) {
                if (title.contains(normalizedFacet) || sectionPath.contains(normalizedFacet)) {
                    score += 5;
                }
                else if (content.contains(normalizedFacet)) {
                    score += 1;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestSection = section;
            }
        }
        return bestScore > 0 ? bestSection : null;
    }

    @Override
    public List<GraphSection> listSections(Long documentId) {
        if (documentId == null) {
            return List.of();
        }
        try (Session session = openSession()) {
            return session.run("""
                    MATCH (s:Section {documentId: $documentId})
                    RETURN s
                    ORDER BY s.nodeNo ASC
                    """,
                    Values.parameters("documentId", documentId))
                .list(record -> toGraphSection(record.get("s").asNode()));
        }
    }


    /*listChildren：列出直接子章节
    当用户问"这章包含哪些小节"时，系统需要找到当前章节下面的所有一级子节点。

     */
    @Override
    public List<GraphSection> listChildren(Long documentId, Long sectionNodeId) {
        // 参数不完整时不查 Neo4j，直接返回空子章节列表。
        if (documentId == null || sectionNodeId == null) {
            return List.of();
        }
        // 通过 HAS_CHILD 关系从当前章节走到直接子章节，只查一层，不递归展开。
        try (Session session = openSession()) {
            // 起点和终点都限定 documentId，避免跨文档关系污染查询结果。
            return session.run("""
                    MATCH (:Section {documentId: $documentId, nodeId: $nodeId})-[:HAS_CHILD]->(c:Section {documentId: $documentId})
                    RETURN c
                    ORDER BY c.nodeNo ASC
                    """,
                            // 绑定父章节节点 ID，nodeId 对应当前要展开的章节。
                    Values.parameters("documentId", documentId, "nodeId", sectionNodeId))
                    // 将每个子章节节点转换为 GraphSection，并保持 Cypher 中的 nodeNo 排序。
                .list(record -> toGraphSection(record.get("c").asNode()));
        }
    }


    /*
    查找父章节
    当用户问"这章属于哪个章节"时，系统需要沿着树结构向上找到父节点。
    Neo4j 中父子关系是通过 HAS_CHILD 这条有向边来表达的。查父章节就是反向遍历这条边：找到那个通过 HAS_CHILD 指向当前章节的节点，它就是父章节。
    用图来表示就是：父章节 (p) --[HAS_CHILD]--> 当前章节 (nodeId)
     */
    @Override
    public GraphSection parentSection(Long documentId, Long sectionNodeId) {
        // 父章节查询需要当前文档和当前章节节点 ID，两者缺一不可。
        if (documentId == null || sectionNodeId == null) {
            return null;
        }
        // 查询方向是父章节 -HAS_CHILD-> 当前章节，因此返回关系起点 p。
        try (Session session = openSession()) {
            // 只返回一个父章节；正常文档结构里一个章节最多只有一个直接父节点。
            return session.run("""
                    MATCH (p:Section {documentId: $documentId})-[:HAS_CHILD]->(:Section {documentId: $documentId, nodeId: $nodeId})
                    RETURN p
                    LIMIT 1
                    """,
                            // 绑定当前章节节点 ID，用它定位 HAS_CHILD 关系的终点。
                    Values.parameters("documentId", documentId, "nodeId", sectionNodeId))
                    // 把 Neo4j 返回的父章节节点转换成统一模型。
                .list(record -> toGraphSection(record.get("p").asNode()))
                    // 转成 Stream 取第一条，兼容根章节没有父节点的场景。
                .stream()
                .findFirst()
                    // 没有父章节时返回 null，供上层渲染"未找到"或跳过父章节说明。
                .orElse(null);
        }
    }


    /*
    previousSibling：查找前一个兄弟章节
    当用户问"上一节是什么"时，系统需要找到同级别的前一个章节。
    这里有个巧妙的设计：Neo4j 中只存了一种关系 NEXT_SIBLING（指向下一个兄弟），没有单独存 PREV_SIBLING。那怎么查"上一个"呢？答案是反向匹配：前一个章节 (p) --[NEXT_SIBLING]--> 当前章节 (nodeId)
    谁的 NEXT_SIBLING 指向我，谁就是我的前一个兄弟。这样只需要维护一种关系，就能双向查询，减少了数据冗余。
     */
    @Override
    public GraphSection previousSibling(Long documentId, Long sectionNodeId) {
        // 上一个兄弟章节查询需要明确文档和当前章节节点。
        if (documentId == null || sectionNodeId == null) {
            return null;
        }
        // 查询方向是 previous -NEXT_SIBLING-> current，因此返回关系起点 p。
        try (Session session = openSession()) {
            // 这里复用 NEXT_SIBLING 的反向匹配语义，找出指向当前章节的前一个章节。
            return session.run("""
                    MATCH (p:Section {documentId: $documentId})-[:NEXT_SIBLING]->(:Section {documentId: $documentId, nodeId: $nodeId})
                    RETURN p
                    LIMIT 1
                    """,
                            // 绑定当前章节节点 ID，作为 NEXT_SIBLING 关系的终点。
                    Values.parameters("documentId", documentId, "nodeId", sectionNodeId))
                    // 将命中的前一个兄弟章节转换成 GraphSection。
                .list(record -> toGraphSection(record.get("p").asNode()))
                    // 没有前一个兄弟章节时，列表为空，最终返回 null。
                .stream()
                .findFirst()
                .orElse(null);
        }
    }


    /*nextSibling：查找后一个兄弟章节
    当用户问"下一节是什么"时，系统需要找到同级别的后一个章节。
    和 previousSibling 相比，nextSibling 是正向遍历 NEXT_SIBLING 关系：当前章节 (nodeId) --[NEXT_SIBLING]--> 下一个章节 (n) 当前章节的 NEXT_SIBLING 指向谁，谁就是下一个兄弟。这是最自然的查询方向。
     */
    @Override
    public GraphSection nextSibling(Long documentId, Long sectionNodeId) {
        // 下一个兄弟章节查询同样需要文档 ID 和当前章节节点 ID。
        if (documentId == null || sectionNodeId == null) {
            return null;
        }
        // 查询方向是 current -NEXT_SIBLING-> next，因此返回关系终点 n。
        try (Session session = openSession()) {
            // 查询方向是 current -NEXT_SIBLING-> next，因此返回关系终点 n。
            return session.run("""
                    MATCH (:Section {documentId: $documentId, nodeId: $nodeId})-[:NEXT_SIBLING]->(n:Section {documentId: $documentId})
                    RETURN n
                    LIMIT 1
                    """,
                            // 绑定当前章节节点 ID，作为 NEXT_SIBLING 关系的起点。
                    Values.parameters("documentId", documentId, "nodeId", sectionNodeId))
                    // 将命中的后一个兄弟章节转换成 GraphSection。
                .list(record -> toGraphSection(record.get("n").asNode()))
                    // 没有后一个兄弟章节时，列表为空，最终返回 null。
                .stream()
                .findFirst()
                .orElse(null);
        }
    }

    @Override
    public GraphItem findItemByIndex(Long documentId, Long sectionNodeId, Integer itemIndex) {
        if (documentId == null || sectionNodeId == null || itemIndex == null) {
            return null;
        }
        try (Session session = openSession()) {
            return session.run("""
                    MATCH (:Section {documentId: $documentId, nodeId: $sectionNodeId})-[:HAS_ITEM]->(i:Item {documentId: $documentId, itemIndex: $itemIndex})
                    RETURN i
                    ORDER BY i.nodeNo ASC
                    LIMIT 1
                    """,
                    Values.parameters("documentId", documentId, "sectionNodeId", sectionNodeId, "itemIndex", itemIndex))
                .list(record -> toGraphItem(record.get("i").asNode()))
                .stream()
                .findFirst()
                .orElse(null);
        }
    }

    @Override
    public List<GraphItem> listItems(Long documentId, Long sectionNodeId) {
        if (documentId == null || sectionNodeId == null) {
            return List.of();
        }
        try (Session session = openSession()) {
            return session.run("""
                    MATCH (:Section {documentId: $documentId, nodeId: $sectionNodeId})-[:HAS_ITEM]->(i:Item {documentId: $documentId})
                    RETURN i
                    ORDER BY i.nodeNo ASC
                    """,
                    Values.parameters("documentId", documentId, "sectionNodeId", sectionNodeId))
                .list(record -> toGraphItem(record.get("i").asNode()));
        }
    }

    @Override
    public List<GraphItem> searchItemsInSection(Long documentId, Long sectionNodeId, String keyword) {
        if (documentId == null || sectionNodeId == null) {
            return List.of();
        }
        String normalizedKeyword = normalize(keyword);
        if (normalizedKeyword.isBlank()) {
            return listItems(documentId, sectionNodeId);
        }
        try (Session session = openSession()) {
            return session.run("""
                    MATCH (:Section {documentId: $documentId, nodeId: $sectionNodeId})-[:HAS_ITEM]->(i:Item {documentId: $documentId})
                    WHERE i.normalizedTitle CONTAINS $keyword OR toLower(coalesce(i.contentText, '')) CONTAINS $keyword
                    RETURN i
                    ORDER BY i.nodeNo ASC
                    """,
                    Values.parameters("documentId", documentId, "sectionNodeId", sectionNodeId, "keyword", normalizedKeyword))
                .list(record -> toGraphItem(record.get("i").asNode()));
        }
    }

    private Session openSession() {
        return driver.session(SessionConfig.forDatabase(properties.getNeo4j().getDatabase()));
    }

    private GraphSection toGraphSection(org.neo4j.driver.types.Node node) {
        if (node == null) {
            return null;
        }
        return GraphSection.builder()
            .nodeId(asLong(node, "nodeId"))
            .documentId(asLong(node, "documentId"))
            .parseTaskId(asLong(node, "parseTaskId"))
            .nodeNo(asInteger(node, "nodeNo"))
            .depth(asInteger(node, "depth"))
            .parentNodeId(asLong(node, "parentNodeId"))
            .prevSiblingNodeId(asLong(node, "prevSiblingNodeId"))
            .nextSiblingNodeId(asLong(node, "nextSiblingNodeId"))
            .nodeCode(asText(node, "nodeCode"))
            .title(asText(node, "title"))
            .anchorText(asText(node, "anchorText"))
            .sectionPath(asText(node, "sectionPath"))
            .canonicalPath(asText(node, "canonicalPath"))
            .contentText(asText(node, "contentText"))
            .build();
    }

    private GraphItem toGraphItem(org.neo4j.driver.types.Node node) {
        if (node == null) {
            return null;
        }
        return GraphItem.builder()
            .nodeId(asLong(node, "nodeId"))
            .documentId(asLong(node, "documentId"))
            .parseTaskId(asLong(node, "parseTaskId"))
            .nodeNo(asInteger(node, "nodeNo"))
            .nodeType(asText(node, "nodeType"))
            .sectionNodeId(asLong(node, "sectionNodeId"))
            .prevSiblingNodeId(asLong(node, "prevSiblingNodeId"))
            .nextSiblingNodeId(asLong(node, "nextSiblingNodeId"))
            .title(asText(node, "title"))
            .anchorText(asText(node, "anchorText"))
            .sectionPath(asText(node, "sectionPath"))
            .canonicalPath(asText(node, "canonicalPath"))
            .contentText(asText(node, "contentText"))
            .itemIndex(asInteger(node, "itemIndex"))
            .build();
    }

    private Long asLong(org.neo4j.driver.types.Node node, String key) {
        return node.containsKey(key) && !node.get(key).isNull() ? node.get(key).asLong() : null;
    }

    private Integer asInteger(org.neo4j.driver.types.Node node, String key) {
        return node.containsKey(key) && !node.get(key).isNull() ? node.get(key).asInt() : null;
    }

    private String asText(org.neo4j.driver.types.Node node, String key) {
        return node.containsKey(key) && !node.get(key).isNull() ? node.get(key).asString("") : "";
    }

    private String normalize(String text) {
        return StrUtil.blankToDefault(text, "")
            .replaceAll("[\\s>`*#_\\-]+", "")
            .toLowerCase(Locale.ROOT);
    }
}
