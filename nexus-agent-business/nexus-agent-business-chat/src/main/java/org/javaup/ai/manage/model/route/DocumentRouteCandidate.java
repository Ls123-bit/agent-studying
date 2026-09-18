package org.javaup.ai.manage.model.route;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 模型对象
 * @author: 阿星不是程序员
 **/

/*
文档候选对象包含了文档的详细信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentRouteCandidate {

    private String documentId;//文档ID

    private String documentName;//文档名称

    private String lastIndexTaskId;//最近一次索引任务ID（用于检索）

    private String knowledgeScopeCode;//所属知识阈编码

    private String knowledgeScopeName;//所属知识域名称

    private String businessCategory;//业务分类

    private String documentTags;//文档标签

    private BigDecimal score;//综合分数 路由得分

    private String reason;//评分原因 选择原因（比如"命中关键词：Spring Boot"）

}
