package org.javaup.ai.manage.graph.repository;

import org.javaup.ai.manage.graph.node.ItemGraphNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Neo4j 编号项节点 Repository。
 */
public interface ItemGraphNodeRepository extends Neo4jRepository<ItemGraphNode, Long> {

    @Query("MATCH (s:Section {nodeId: $sectionNodeId})-[:HAS_ITEM]->(i:Item) RETURN i ORDER BY i.itemIndex")
    List<ItemGraphNode> findBySectionNodeId(@Param("sectionNodeId") Long sectionNodeId);

    @Query("MATCH (s:Section {nodeId: $sectionNodeId})-[:HAS_ITEM]->(i:Item {itemIndex: $itemIndex}) RETURN i")
    Optional<ItemGraphNode> findBySectionNodeIdAndItemIndex(@Param("sectionNodeId") Long sectionNodeId,
                                                             @Param("itemIndex") Integer itemIndex);

    @Query("MATCH (s:Section {nodeId: $sectionNodeId})-[:HAS_ITEM]->(i:Item) WHERE toLower(i.contentText) CONTAINS toLower($keyword) RETURN i ORDER BY i.itemIndex")
    List<ItemGraphNode> searchBySectionNodeIdAndKeyword(@Param("sectionNodeId") Long sectionNodeId,
                                                         @Param("keyword") String keyword);

    @Query("MATCH (i:Item {documentId: $documentId}) DETACH DELETE i")
    void deleteAllByDocumentId(@Param("documentId") Long documentId);
}
