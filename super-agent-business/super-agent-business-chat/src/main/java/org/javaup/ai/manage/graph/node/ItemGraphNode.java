package org.javaup.ai.manage.graph.node;

import lombok.Data;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;

/**
 * Neo4j 编号项节点（步骤/列表项）。
 */
@Data
@Node("Item")
public class ItemGraphNode {

    @Id
    private Long nodeId;

    @Property
    private Long documentId;

    @Property
    private Integer nodeNo;

    @Property
    private String nodeType;

    @Property
    private Integer itemIndex;

    @Property
    private String title;

    @Property
    private String anchorText;

    @Property
    private String sectionPath;

    @Property
    private String canonicalPath;

    @Property
    private String contentText;
}
