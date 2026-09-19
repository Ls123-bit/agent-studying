package org.javaup.ai.manage.model.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
/*
图数据结构
查询结果（目标章节、子节点列表、兄弟节点、条目列表）
 */
public class GraphQueryResult {

    private GraphSection targetSection;

    private GraphSection parentSection;

    private GraphSection previousSibling;

    private GraphSection nextSibling;

    private GraphItem targetItem;

    @Builder.Default
    private List<GraphSection> children = new ArrayList<>();

    @Builder.Default
    private List<GraphItem> matchedItems = new ArrayList<>();

    @Builder.Default
    private List<GraphItem> allItems = new ArrayList<>();
}
