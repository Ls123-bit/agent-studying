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
章节 + 子节点
 */
public class GraphSectionWithChildren {

    private GraphSection section;

    @Builder.Default
    private List<GraphSection> children = new ArrayList<>();
}
