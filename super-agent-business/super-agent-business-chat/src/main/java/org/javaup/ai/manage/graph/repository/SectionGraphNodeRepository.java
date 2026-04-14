package org.javaup.ai.manage.graph.repository;

import org.javaup.ai.manage.graph.node.SectionGraphNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Neo4j 章节节点 Repository。
 */
public interface SectionGraphNodeRepository extends Neo4jRepository<SectionGraphNode, Long> {

    @Query("MATCH (s:Section {documentId: $documentId, nodeCode: $nodeCode}) RETURN s")
    Optional<SectionGraphNode> findByDocumentIdAndNodeCode(@Param("documentId") Long documentId,
                                                            @Param("nodeCode") String nodeCode);

    @Query("MATCH (s:Section {documentId: $documentId}) WHERE toLower(s.title) CONTAINS toLower($title) RETURN s ORDER BY s.nodeNo LIMIT 1")
    Optional<SectionGraphNode> findByDocumentIdAndTitleContaining(@Param("documentId") Long documentId,
                                                                   @Param("title") String title);

    @Query("MATCH (parent:Section {nodeId: $parentNodeId})-[:HAS_CHILD]->(child:Section) RETURN child ORDER BY child.nodeNo")
    List<SectionGraphNode> findChildrenByParentNodeId(@Param("parentNodeId") Long parentNodeId);

    @Query("MATCH (s:Section {nodeId: $nodeId})-[:NEXT_SIBLING]->(next:Section) RETURN next")
    Optional<SectionGraphNode> findNextSibling(@Param("nodeId") Long nodeId);

    @Query("MATCH (prev:Section)-[:NEXT_SIBLING]->(s:Section {nodeId: $nodeId}) RETURN prev")
    Optional<SectionGraphNode> findPrevSibling(@Param("nodeId") Long nodeId);

    @Query("MATCH (parent:Section)-[:HAS_CHILD]->(s:Section {nodeId: $nodeId}) RETURN parent")
    Optional<SectionGraphNode> findParent(@Param("nodeId") Long nodeId);

    @Query("MATCH (d:Document {documentId: $documentId})-[:HAS_SECTION]->(s:Section) RETURN s ORDER BY s.nodeNo")
    List<SectionGraphNode> findTopSectionsByDocumentId(@Param("documentId") Long documentId);

    @Query("MATCH (s:Section {documentId: $documentId}) RETURN s ORDER BY s.nodeNo")
    List<SectionGraphNode> findAllByDocumentId(@Param("documentId") Long documentId);

    @Query("MATCH (s:Section {documentId: $documentId}) DETACH DELETE s")
    void deleteAllByDocumentId(@Param("documentId") Long documentId);
}
