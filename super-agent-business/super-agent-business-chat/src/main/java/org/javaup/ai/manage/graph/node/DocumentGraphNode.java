package org.javaup.ai.manage.graph.node;

import lombok.Data;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.ArrayList;
import java.util.List;

/**
 * Neo4j 文档根节点。
 */
@Data
@Node("Document")
public class DocumentGraphNode {

    @Id
    private Long documentId;

    @Property
    private String documentName;

    @Property
    private Long parseTaskId;

    @Relationship(type = "HAS_SECTION", direction = Relationship.Direction.OUTGOING)
    private List<SectionGraphNode> topSections = new ArrayList<>();
}
