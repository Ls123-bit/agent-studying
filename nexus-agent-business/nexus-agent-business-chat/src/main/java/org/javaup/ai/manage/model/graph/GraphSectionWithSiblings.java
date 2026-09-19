package org.javaup.ai.manage.model.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
/*
图数据结构
章节 + 兄弟节点
*/
public class GraphSectionWithSiblings {

    private GraphSection section;

    private GraphSection parent;

    private GraphSection previousSibling;

    private GraphSection nextSibling;
}
