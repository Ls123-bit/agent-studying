package org.javaup.ai.chatagent.rag.executor;

import org.javaup.ai.chatagent.rag.model.ExecutionMode;
import org.javaup.ai.chatagent.service.TaskInfo;
import reactor.core.publisher.Flux;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 统一对话执行器抽象
 * @author: 阿星不是程序员
 **/


/*
所有的执行器都实现同一个接口
 */
// ConversationExecutor.java —— 统一执行器接口
/**
 * 统一对话执行器抽象。
 * 这里定义的是“执行策略”层面的统一协议：
 * 上游只需要拿到 {@link org.javaup.ai.chatagent.rag.model.ExecutionMode}，
 * 就可以从注册表中找到对应执行器，并调用 {@link #execute(TaskInfo)} 完成具体执行。
 *
 * 不同执行器会分别负责不同问答路径，例如：
 * 1. 文档检索问答
 * 2. 结构图直接回答
 * 3. 结构图定位后取证
 * 4. ReactAgent 自主执行
 * 5. 路由歧义澄清
 */
public interface ConversationExecutor {
    /**
     * 返回当前执行器负责的执行模式。
     * 注册表会以这个模式值作为 key，把模式与具体执行器建立一一映射关系。
     *
     * @return 当前执行器支持的执行模式
     */
    ExecutionMode mode();//声明自己负责哪种模式
    /**
     * 执行当前模式下的具体对话逻辑。
     * 这里返回的是一个流式文本输出，供上层统一接入 SSE 推送链路。
     *
     * @param taskInfo 当前会话运行态，里面包含执行计划、上下文、调试信息、引用集合等共享数据
     * @return 当前执行器产出的文本流
     */
    Flux<String> execute(TaskInfo taskInfo);//执行并返回流式输出（文本流）
}
