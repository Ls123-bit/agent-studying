package org.javaup.ai.manage.service;

import org.javaup.ai.manage.dto.DocumentQuestionAskDto;
import org.javaup.ai.manage.vo.DocumentQuestionAskVo;

/**
 * 文档问答服务。
 *
 * <p>负责把“用户问题 -> 向量检索 -> 大模型组织答案”串成一条完整链路，
 * 让文档接入、索引构建和问答使用真正闭环。</p>
 */
public interface DocumentQuestionAnswerService {

    /**
     * 基于指定文档执行检索问答。
     */
    DocumentQuestionAskVo ask(DocumentQuestionAskDto dto);
}
