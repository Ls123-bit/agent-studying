package org.javaup.ai.chatagent.tool;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tavily 搜索工具入参。
 *
 * <p>这里保持成普通 Java Bean，方便和项目里其他 DTO 的阅读习惯保持一致，
 * 同时也便于 Spring AI 在工具调用时按字段名完成 JSON 反序列化。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TavilySearchRequest {

    private String query;
    private String topic;
    private Integer maxResults;
}
