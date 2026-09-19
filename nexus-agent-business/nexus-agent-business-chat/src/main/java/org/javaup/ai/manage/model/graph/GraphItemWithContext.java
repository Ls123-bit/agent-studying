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
条目 + 上下文信息
  */
public class GraphItemWithContext {

    private GraphSection section;

    private GraphItem item;

    @Builder.Default
    private List<GraphItem> siblingItems = new ArrayList<>();
}
