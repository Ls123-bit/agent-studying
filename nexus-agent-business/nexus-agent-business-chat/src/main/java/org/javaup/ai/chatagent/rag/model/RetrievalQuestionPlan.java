package org.javaup.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

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
