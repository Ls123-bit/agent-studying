package org.javaup.ai.manage.service;

/*
异步处理管线
 */
public interface DocumentAsyncProcessService {

    void handleParseRoute(Long documentId, Long taskId);//处理解析任务

    void handleIndexBuild(Long documentId, Long taskId, Long planId);//处理索引构建任务
}
