package org.javaup.ai.chatagent.tool;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/*
工具入参定义
模型生成的工具调用参数会被反序列化成这个对象：
query：搜索关键词（必填）
topic：搜索主题，只允许 general、news、finance 三个值（可选）
maxResults：最大返回结果数（可选）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TavilySearchRequest {

    private String query;
    private String topic;
    private Integer maxResults;
}
