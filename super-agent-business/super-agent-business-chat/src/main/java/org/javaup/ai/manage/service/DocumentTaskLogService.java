package org.javaup.ai.manage.service;

/**
 * 文档任务日志服务。
 */
public interface DocumentTaskLogService {

    /**
     * 新增日志记录。
     */
    void saveLog(Long taskId,
                 Long documentId,
                 Integer stageType,
                 Integer eventType,
                 Integer logLevel,
                 Integer operatorType,
                 Long operatorId,
                 String content,
                 Object detail);
}
