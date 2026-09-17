package org.javaup.ai.manage.support;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 支撑组件
 * @author: 阿星不是程序员
 **/
/*
文档的解析结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentAnalysisResult {

    private String parsedText;//解析出的纯文本

    private Integer charCount;//字符数

    private Integer tokenCount;//估算的token数

    private Integer structureLevel;//结构化程度评分 （HIGH / MEDIUM / LOW / UNKNOWN）

    private Integer contentQualityLevel;//内容质量评分（HIGH / MEDIUM / LOW）

    private Integer headingCount;//标题数量

    private Integer paragraphCount;//段落数量

    private Integer maxParagraphLength;//最长段落长度

    private List<DocumentStructureNodeCandidate> structureNodes = new ArrayList<>();//提取出的结构节点候选+结构节点候选列表
}
