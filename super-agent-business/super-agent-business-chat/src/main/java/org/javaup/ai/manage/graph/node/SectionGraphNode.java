package org.javaup.ai.manage.graph.node;

import lombok.Data;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.ArrayList;
import java.util.List;

/**
 * Neo4j 章节节点。
 */
@Data
@Node("Section")
public class SectionGraphNode {

    @Id
    private Long nodeId;

    @Property
    private Long documentId;

    @Property
    private Integer nodeNo;

    @Property
    private String nodeCode;

    @Property
    private String title;

    @Property
    private String anchorText;

    @Property
    private String sectionPath;

    @Property
    private String canonicalPath;

    @Property
    private Integer depth;

    @Property
    private String contentText;

    @Relationship(type = "HAS_CHILD", direction = Relationship.Direction.OUTGOING)
    private List<SectionGraphNode> children = new ArrayList<>();

    @Relationship(type = "NEXT_SIBLING", direction = Relationship.Direction.OUTGOING)
    private SectionGraphNode nextSibling;

    @Relationship(type = "HAS_ITEM", direction = Relationship.Direction.OUTGOING)
    private List<ItemGraphNode> items = new ArrayList<>();
}
