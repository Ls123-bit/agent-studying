package org.javaup.ai.chatagent.rag.executor;

import org.javaup.ai.chatagent.rag.model.ExecutionMode;
import org.javaup.ai.chatagent.service.TaskInfo;
import reactor.core.publisher.Flux;

/**
 * 统一对话执行器抽象。
 */
public interface ConversationExecutor {

    /**
     * 当前执行器支持的模式。
     */
    ExecutionMode mode();

    /**
     * 执行当前任务，并只返回“正文分片”流。
     *
     * <p>thinking / status / references 等增强事件，
     * 由执行器自己通过 TaskInfo.sink() 直接发出。</p>
     */
    Flux<String> execute(TaskInfo taskInfo);
}
