package org.javaup.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 知识域候选项。
 *
 * <p>当一轮问题没有明确指向哪个业务系统时，
 * 编排器会把这些候选项交给澄清执行器拼成追问话术。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeScopeOption {

    /**
     * 知识域编码。
     */
    private String scopeCode;

    /**
     * 知识域名称。
     */
    private String scopeName;

    /**
     * 当前知识域下可参与检索的文档主键列表。
     */
    private List<Long> documentIds;

    /**
     * 当前知识域下对应的有效索引任务列表。
     */
    private List<Long> taskIds;

    /**
     * 匹配分数。
     */
    private double score;

    /**
     * 文档展示名摘要，方便调试和后续扩展前端提示。
     */
    private List<String> documentNames;
}
