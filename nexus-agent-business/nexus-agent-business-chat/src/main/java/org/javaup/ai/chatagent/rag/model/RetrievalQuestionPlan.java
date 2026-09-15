package org.javaup.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 检索阶段真正执行的问题计划
 * @author: 阿星不是程序员
 **/
/*
数据模型

 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalQuestionPlan {

    private String retrievalQuestion;//用于检索的主问题

    private List<String> subQuestions;//拆分出的子问题列表
}
