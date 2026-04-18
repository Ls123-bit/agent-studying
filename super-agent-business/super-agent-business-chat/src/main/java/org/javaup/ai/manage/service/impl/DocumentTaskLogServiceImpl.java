package org.javaup.ai.manage.service.impl;

import com.baidu.fsg.uid.UidGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.javaup.ai.manage.data.SuperAgentDocumentTaskLog;
import org.javaup.ai.manage.mapper.SuperAgentDocumentTaskLogMapper;
import org.javaup.ai.manage.service.DocumentTaskLogService;
import org.javaup.enums.BusinessStatus;
import org.springframework.stereotype.Service;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 服务实现层
 * @author: 阿星不是程序员
 **/
/**
 * 文档任务日志服务实现。
 */
@Service
public class DocumentTaskLogServiceImpl implements DocumentTaskLogService {

    private final SuperAgentDocumentTaskLogMapper taskLogMapper;

    private final ObjectMapper objectMapper;

    @Resource
    private UidGenerator uidGenerator;

    public DocumentTaskLogServiceImpl(SuperAgentDocumentTaskLogMapper taskLogMapper,
                                      ObjectMapper objectMapper) {
        this.taskLogMapper = taskLogMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public void saveLog(Long taskId,
                        Long documentId,
                        Integer stageType,
                        Integer eventType,
                        Integer logLevel,
                        Integer operatorType,
                        Long operatorId,
                        String content,
                        Object detail) {
        SuperAgentDocumentTaskLog log = new SuperAgentDocumentTaskLog();
        log.setId(uidGenerator.getUid());
        log.setTaskId(taskId);
        log.setDocumentId(documentId);
        log.setStageType(stageType);
        log.setEventType(eventType);
        log.setLogLevel(logLevel);
        log.setOperatorType(operatorType);
        log.setOperatorId(operatorId);
        log.setContent(content);
        log.setDetailJson(toJson(detail));
        log.setStatus(BusinessStatus.YES.getCode());
        taskLogMapper.insert(log);
    }

    /**
     * 统一把日志扩展信息转成 JSON，序列化失败时退化成普通字符串。
     */
    private String toJson(Object detail) {
        if (detail == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(detail);
        }
        catch (JsonProcessingException exception) {
            return String.valueOf(detail);
        }
    }
}
