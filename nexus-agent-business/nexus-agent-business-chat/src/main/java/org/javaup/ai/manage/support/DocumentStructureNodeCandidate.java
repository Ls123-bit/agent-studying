package org.javaup.ai.manage.support;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 支撑组件
 * @author: 阿星不是程序员
 **/

/*
字段说明：
最终输出的每个节点包含：

 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentStructureNodeCandidate {

    private Integer nodeNo;//节点编号

    private Integer nodeType;//节点类型（DOCUMENT / SECTION / LIST_ITEM / STEP）

    private Integer parentNodeNo;//父节点

    private Integer prevSiblingNodeNo;//前兄弟

    private Integer nextSiblingNodeNo;//后兄弟

    private Integer depth;//在树中的深度（根节点为 0）

    private String nodeCode;//编号部分（如 "1.2.3"、"第一章"）

    private String title;//标题文本

    private String anchorText;//锚点文本

    private String canonicalPath;//机器路径

    private String sectionPath;//人类可读路径

    private String contentText;//该节点下聚合的正文内容

    private Integer itemIndex;//列表项序号
}
