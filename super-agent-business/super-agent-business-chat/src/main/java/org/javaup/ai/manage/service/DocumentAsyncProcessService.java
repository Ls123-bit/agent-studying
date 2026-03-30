package org.javaup.ai.manage.service;

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
