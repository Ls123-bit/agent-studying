package org.javaup.ai.manage.support;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 支撑组件
 * @author: 阿星不是程序员
 **/
/**
 * 文档结构信号。
 *
 * <p>它是结构解析的第一阶段结果：
 * 先回答“这一行像什么”，再进入层级归属和树修复。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentStructureSignal {

    private int lineNo;

    private String rawText;

    private String normalizedText;

    private DocumentStructureSignalKind kind;

    /**
     * 原始结构编码，例如 1.2 / 第一章 / A.3。
     */
    private String nodeCode;

    /**
     * 候选标题文本。
     */
    private String title;

    /**
     * 粗粒度层级提示。
     */
    private Integer levelHint;

    /**
     * 原始行缩进层级，用于嵌套列表判断。
     */
    private Integer indentLevel;

    /**
     * 列表项/步骤项序号。
     */
    private Integer itemIndex;

    /**
     * 数字编号路径，例如 [2,1,3]。
     */
    @Builder.Default
    private List<Integer> numericPath = new ArrayList<>();

    /**
     * 该信号由哪些规则命中得到。
     */
    @Builder.Default
    private List<String> reasons = new ArrayList<>();

    /**
     * 当前置信度。
     */
    private double confidence;

    public boolean isHeadingLike() {
        return kind == DocumentStructureSignalKind.HEADING
            || kind == DocumentStructureSignalKind.HEADING_CANDIDATE;
    }

    public boolean isListLike() {
        return kind == DocumentStructureSignalKind.STEP_ITEM
            || kind == DocumentStructureSignalKind.LIST_ITEM;
    }

    public boolean isAmbiguous() {
        return kind == DocumentStructureSignalKind.HEADING_CANDIDATE;
    }
}
