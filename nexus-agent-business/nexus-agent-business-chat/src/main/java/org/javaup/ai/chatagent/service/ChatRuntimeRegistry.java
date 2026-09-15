package org.javaup.ai.chatagent.service;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 服务层
 * @author: 阿星不是程序员
 **/


/*
并发安全类
JVM 级别的任务注册表，用 ConcurrentMap 实现，防止同一个会话在单机上被重复处理。

在并发控制中的角色：
Redis 租约解决的是"跨节点"的并发问题
JVM 注册表解决的是"同一节点内"的并发问题（比如同一个请求被重复提交）
两层加在一起，形成完整的并发保护
 */
@Component
public class ChatRuntimeRegistry {

    private final ConcurrentMap<String, TaskInfo> taskMap = new ConcurrentHashMap<>();

    public boolean register(TaskInfo taskInfo) {//原子注册，已存在则返回 false
        return taskMap.putIfAbsent(taskInfo.conversationId(), taskInfo) == null;
    }

    public Optional<TaskInfo> get(String conversationId) {//查询当前运行中的任务
        return Optional.ofNullable(taskMap.get(conversationId));
    }

    public void remove(String conversationId) {//无条件移除

        taskMap.remove(conversationId);
    }

    public void remove(String conversationId, TaskInfo expectedTaskInfo) {//CAS 移除，只有匹配时才删
        if (conversationId == null || expectedTaskInfo == null) {
            return;
        }
        taskMap.remove(conversationId, expectedTaskInfo);
    }
}
