package org.javaup.ai.manage.support;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 支撑组件
 * @author: 阿星不是程序员
 **/
/**
 * 文档结构节点候选对象。
 *
 * <p>它是解析阶段提取出的“结构树中间态”，
 * 后续会在异步解析任务里被持久化成结构节点表实体。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentStructureNodeCandidate {

    /**
     * 当前文档内的稳定顺序号。
     */
    private Integer nodeNo;

    /**
     * 节点类型。
     */
    private Integer nodeType;

    /**
     * 父节点序号。
     */
    private Integer parentNodeNo;

    /**
     * 上一个同级节点序号。
     */
    private Integer prevSiblingNodeNo;

    /**
     * 下一个同级节点序号。
     */
    private Integer nextSiblingNodeNo;

    /**
     * 深度。
     */
    private Integer depth;

    /**
     * 结构编码，例如 1.2 / 第一章 / 4。
     */
    private String nodeCode;

    /**
     * 节点标题。
     */
    private String title;

    /**
     * 用于锚点和检索的短锚文本。
     */
    private String anchorText;

    /**
     * 节点稳定路径。
     */
    private String canonicalPath;

    /**
     * 面向现有系统兼容的章节路径文本。
     */
    private String sectionPath;

    /**
     * 节点正文。
     */
    private String contentText;

    /**
     * 列表项/步骤项序号；非列表节点为空。
     */
    private Integer itemIndex;
}
