package org.javaup.ai.manage.service;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 服务层
 * @author: 阿星不是程序员
 **/
/**
 * 文档异步处理服务。
 */
public interface DocumentAsyncProcessService {

    /**
     * 处理“解析并推荐策略”任务。
     */
    void handleParseRoute(Long documentId, Long taskId);

    /**
     * 处理“构建索引”任务。
     */
    void handleIndexBuild(Long documentId, Long taskId, Long planId);
}
