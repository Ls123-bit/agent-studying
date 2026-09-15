package org.javaup.ai.chatagent.rag.executor;

import org.javaup.ai.chatagent.rag.model.ExecutionMode;
import org.javaup.ai.chatagent.service.TaskInfo;
import reactor.core.publisher.Flux;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 统一对话执行器抽象
 * @author: 阿星不是程序员
 **/

public interface ConversationExecutor {

    ExecutionMode mode();//声明自己负责哪种模式

    Flux<String> execute(TaskInfo taskInfo);//执行并返回流式输出
}
