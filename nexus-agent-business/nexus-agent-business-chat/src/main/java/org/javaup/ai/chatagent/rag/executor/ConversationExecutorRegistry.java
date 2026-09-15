package org.javaup.ai.chatagent.rag.executor;

import org.javaup.ai.chatagent.rag.model.ExecutionMode;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 对话执行器注册表
 * @author: 阿星不是程序员
 **/
/*
扩展性：如果你想加一种新执行模式，只需要：1）在 ExecutionMode 枚举加一个值；2）写一个新的 ConversationExecutor 实现类并标注 @Component。注册表会自动发现它。
 */
@Component
public class ConversationExecutorRegistry {

    private final Map<ExecutionMode, ConversationExecutor> executorMap = new EnumMap<>(ExecutionMode.class);

    public ConversationExecutorRegistry(List<ConversationExecutor> executors) {//构造方法，构造时自动发现所有执行器 Bean，按 mode 注册到 EnumMap
        for (ConversationExecutor executor : executors) {
            executorMap.put(executor.mode(), executor);
        }
    }

    public ConversationExecutor get(ExecutionMode mode) {//根据模式取执行器，找不到抛 IllegalStateException
        ConversationExecutor executor = executorMap.get(mode);
        if (executor == null) {
            throw new IllegalStateException("未找到执行模式对应的执行器: " + mode);
        }
        return executor;
    }
}
