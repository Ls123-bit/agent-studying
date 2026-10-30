package org.javaup.ai.chatagent.rag.executor;

import cn.hutool.core.util.StrUtil;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import org.javaup.ai.chatagent.config.ChatAgentProvider;
import org.javaup.ai.chatagent.model.trace.ConversationTraceStageCode;
import org.javaup.ai.chatagent.rag.model.ExecutionMode;
import org.javaup.ai.chatagent.rag.support.ExecutorEventSupport;
import org.javaup.ai.chatagent.service.ConversationTraceRecorder;
import org.javaup.ai.chatagent.service.TaskInfo;
import org.javaup.ai.chatagent.support.StreamEventWriter;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/*
开放式ReAct Agent
模式：ExecutionMode.REACT_AGENT
处理开放式问题，Agent 可以自主决定是否调用工具（比如网络搜索），支持多步推理。
执行流程：
1.调用 reactAgent.stream(agentQuestion, runnableConfig)
2.监听 NodeOutput 事件流
3.处理两种输出类型：AGENT_MODEL_STREAMING（流式文本）和 AGENT_MODEL_FINISHED（完成）
4.捕获 GraphRunnerException（Agent 执行异常）
安全限制：


整个流程可以概括为五步：
1.推送状态事件——告诉前端"我开始干活了"
2.记录调试信息——方便后续排查问题走了哪条路
3.开启链路追踪——如果启用了 trace，就记录这个阶段的开始
4.调用 ReactAgent 执行推理——这是核心，Agent 会自主决定是否调用工具
5.处理结果和异常——正常结束记录成功，出错记录失败

 */
@Component
public class ReactAgentExecutor implements ConversationExecutor {
    /*
    这里有两个核心依赖：
ReactAgent reactAgent：这是 Spring AI Alibaba 提供的 ReAct Agent 图执行引擎，负责真正的推理和工具调用循环
StreamEventWriter streamEventWriter：SSE 事件序列化工具，负责把各种事件（thinking、text、error 等）序列化成 JSON 推送给前端
mode() 方法直接返回 ExecutionMode.REACT_AGENT，告诉注册表"我负责处理开放式问答"。
     */
    private final ChatAgentProvider chatAgentProvider;
    private final StreamEventWriter streamEventWriter;

    public ReactAgentExecutor(ChatAgentProvider chatAgentProvider,
                              StreamEventWriter streamEventWriter) {
        this.chatAgentProvider = chatAgentProvider;
        this.streamEventWriter = streamEventWriter;
    }

    @Override
    public ExecutionMode mode() {
        return ExecutionMode.REACT_AGENT;
    }









/*
当注册表把请求分发到 ReactAgentExecutor 后，就会调用它的 execute(TaskInfo taskInfo) 方法。这个方法是整个开放式问答的入口，我们来逐段看它的源码。
 */
    @Override
    public Flux<String> execute(TaskInfo taskInfo) {//启动 ReactAgent 流式执行
        /*第一步：初始化状态标记
        一进来就创建了一个 AtomicBoolean，初始值是 false。这个标记的作用后面会详细讲，简单说就是：ReactAgent 在流式输出时，既会逐片段推送文本，也会在最后发一个"完成"事件带上完整文本。如果我们已经逐片段输出过了，就不需要再重复输出完成事件里的内容。
         */
        // 用原子布尔值记录是否已经收到过模型流式文本，避免最终完成事件再重复输出一遍完整答案。
        AtomicBoolean streamedText = new AtomicBoolean(false);
        /*
        第二步：推送 thinking 事件
         */
        // 向前端推送 thinking 事件，说明当前问题已经进入 ReAct Agent 自主推理和工具调用路径。
        ExecutorEventSupport.publishThinking(taskInfo, streamEventWriter, "当前问题进入开放式 Agent 自主执行阶段。");

        /*
        第三步：追加调试轨迹
        这行很简单，往调试轨迹的 retrievalNotes 列表里加一条说明。这个信息不会推送给前端用户，而是给开发者排查问题用的——比如你想知道某个问题为什么没走 RAG 检索而是走了 Agent，看这个字段就清楚了。
         */
        // 在调试轨迹里追加执行路径说明，方便后续排查为什么没有进入结构图或普通 RAG。
        taskInfo.debugTrace().getRetrievalNotes().add("当前问题走 ReactAgent 执行路径，由 Agent 自主决定是否调用联网搜索或其他工具。");
        /*
        第四步：开启链路追踪阶段
        这段代码的逻辑是：如果当前请求启用了链路追踪（traceRecorder 不为空），就开启一个新的追踪阶段。
         */
        // 如果本次任务带有 traceRecorder，就开启 REACT_AGENT 阶段来记录 Agent 的执行过程。
        ConversationTraceRecorder.StageHandle agentStage = taskInfo.traceRecorder() == null
            ? null
            : taskInfo.traceRecorder().startStage(
                // trace 阶段类型固定为 REACT_AGENT，便于前端按阶段聚合展示。
                ConversationTraceStageCode.REACT_AGENT,
                // 记录当前 executor 的模式名，和路由结果保持一致。
                mode().name(),
                // 阶段文案描述当前动作：ReAct 推理以及可能发生的工具调用。
                "正在执行 ReAct Agent 推理与工具调用。",
                // 当前阶段没有额外输入元数据，所以传 null。
                null
            );
        /*
        第五步：调用ReactAgent执行推理
        这是整个方法最核心的一行。reactAgent.stream() 接收两个参数：
        taskInfo.executionPlan().getAgentQuestion()：经过前置规划阶段改写后的问题文本。注意这不是用户的原始问题，而是经过意图识别和问题改写后的版本，更适合 Agent 理解和处理。
        taskInfo.runnableConfig()：运行配置，包含了会话 ID、线程 ID、上下文变量（如 SSE sink、调试轨迹、引用列表等）。Agent 执行过程中的工具可以通过这个 config 访问到这些共享上下文。
        stream() 方法返回的是 Flux<NodeOutput>——一个响应式流，Agent 每产生一个输出节点（可能是模型文本、工具调用结果、状态变更等），都会往这个流里推一个 NodeOutput。
         */
        try {
            // 每次执行只取一次 Agent 实例并持有到本次流结束：
            // 这样即使执行过程中发生 MCP 配置 reload（Agent 被重建），本次 run 也不会中途换引擎。
            ReactAgent reactAgent = chatAgentProvider.get();
            // 将规划阶段生成的 agentQuestion 交给 ReactAgent，并附带 runnableConfig 保存会话和工具运行配置。
            return reactAgent.stream(taskInfo.executionPlan().getAgentQuestion(), taskInfo.runnableConfig())

               /*
               第六步：切换线程池
               publishOn(Schedulers.boundedElastic()) 把后续的操作符切换到弹性线程池执行。这样做是因为后面的文本提取和 trace 写入可能涉及 I/O 操作，如果在调用方线程（通常是 Netty 的事件循环线程）上执行，会阻塞其他请求的处理。
                */
                .publishOn(Schedulers.boundedElastic())   // 将后续文本抽取和 trace 回调切到 boundedElastic，避免阻塞调用方线程。

               /*
               第七步：提取文本逻辑
               concatMap 会按顺序处理每个 NodeOutput，调用 extractTextChunk 方法从中提取用户可见的文本。这个方法的详细逻辑我们下一篇再讲，这里先知道它的作用：从各种类型的输出中筛选出真正要给用户看的文本片段。
                */
                    // ReactAgent 输出的是 NodeOutput，这里逐个抽取真正要返回给用户的文本片段。
                .concatMap(output -> extractTextChunk(output, streamedText))
                    /*
                    第八步：流正常结束的处理
                    当 Agent 的输出流正常结束（模型给出了最终答案），doOnComplete 回调会被触发。它做的事情是：
                        1.检查是否启用了链路追踪
                        2.如果启用了，调用 completeStage 关闭追踪阶段，标记为成功
                        3.同时把工具调用的详细轨迹（toolTraces）和使用过的工具名称（usedTools）作为快照写入
                     */// 当 Agent 输出流正常结束时，补齐 trace 阶段的成功记录。
                .doOnComplete(() -> {
                    // traceRecorder 为空时说明本次请求未启用链路追踪，直接跳过 trace 写入。
                    if (taskInfo.traceRecorder() != null) {
                        // 记录工具调用明细和已使用工具集合，方便回看 Agent 自主执行了哪些动作。
                        taskInfo.traceRecorder().completeStage(agentStage, "ReAct Agent 执行完成。", Map.of(
                                // toolTraces 通常包含每次工具调用的调试轨迹；为空时写空列表。
                            "toolNames", taskInfo.debugTrace().getToolTraces() == null ? List.of() : taskInfo.debugTrace().getToolTraces(),
                                // usedTools 是本轮实际使用过的工具名集合；为空时写空列表。
                            "usedTools", taskInfo.usedTools() == null ? List.of() : taskInfo.usedTools()
                        ));
                    }
                })
                    /*
                    第九步：流异步异常的处理
                    如果 Agent 在执行过程中出了异步异常（比如模型调用超时、工具执行失败且重试耗尽等），doOnError 会被触发，把错误信息写入追踪阶段的失败记录。
                     */// 当 Agent 输出流异步报错时，把错误写入 trace。
                .doOnError(error -> {
                    // 只有启用 traceRecorder 时才记录失败阶段。
                    if (taskInfo.traceRecorder() != null) {
                        // 将异常消息写入失败记录，便于排查模型流、工具调用或图执行异常。
                        taskInfo.traceRecorder().failStage(agentStage, "ReAct Agent 执行失败。", error.getMessage(), null);
                    }
                });
        }
        /*
        第十步：同步异常的兜底处理
        这个 catch 块处理的是 reactAgent.stream() 在创建流的时候就同步抛出的异常。比如图的配置有问题、checkpoint 加载失败等情况，异常会在 stream() 调用时直接抛出，而不是通过流的 onError 信号传递。
        处理方式是：记录失败追踪，然后用 Flux.error(exception) 把异常包装成一个错误流返回给上游，让 BusinessChatService 的统一错误处理逻辑来接管后续的用户提示和资源清理。
         */
        catch (GraphRunnerException exception) {
            // GraphRunnerException 可能在创建/启动 graph stream 时同步抛出，需要在 catch 中单独记录失败。
            if (taskInfo.traceRecorder() != null) {
                // 将同步抛出的图运行异常写入 REACT_AGENT 阶段。
                taskInfo.traceRecorder().failStage(agentStage, "ReAct Agent 执行失败。", exception.getMessage(), null);
            }
            // 保留原始异常向上游传播，让统一错误处理逻辑继续接管响应。
            return Flux.error(exception);
        }
    }



    /*
    输出类型分拣
     */
    private Mono<String> extractTextChunk(NodeOutput output, AtomicBoolean streamedText) {//从 Agent 输出中提取文本片段
        /*
        三层过滤逻辑
         */
        /*
        第一层：类型过滤
        NodeOutput 是一个父类型，它的子类型有很多。只有 StreamingOutput 才可能携带模型生成的文本，其他类型（比如图节点的状态快照）直接丢弃。
         */
        // ReactAgent 图里可能会输出非流式节点结果；只有 StreamingOutput 才可能携带给用户的文本。
        if (!(output instanceof StreamingOutput<?> streamingOutput)) {
            // 非 StreamingOutput 不参与前端答案流，直接返回空 Mono。
            return Mono.empty();
        }

        /*
        第二层：内容过滤
        即使是 StreamingOutput，也不一定有文本内容。比如工具调用的 streaming 事件，它的文本字段可能是空的。所以要再检查一次内容是否为空。
         */
        // 从 StreamingOutput 中兼容提取 message 文本、originData Message 文本或 originData 字符串。
        String content = extractStreamingText(streamingOutput);
        // 没有文本内容时，不向用户流输出任何片段。
        if (StrUtil.isBlank(content)) {
            return Mono.empty();
        }


        // AGENT_MODEL_STREAMING 表示模型正在流式输出增量文本，应立即返回给前端。
        if (streamingOutput.getOutputType() == OutputType.AGENT_MODEL_STREAMING) {
            // 标记已经收到过流式片段，后面遇到 FINISHED 时就不再重复发送完整文本。
            streamedText.set(true);
            // 返回当前流式文本片段。
            return Mono.just(content);
        }
        // AGENT_MODEL_FINISHED 表示模型最终完成事件，可能携带完整文本或最终文本。
        if (streamingOutput.getOutputType() == OutputType.AGENT_MODEL_FINISHED) {
            // 如果前面已经流式输出过文本，完成事件通常是重复内容，跳过以避免答案重复。
            if (streamedText.get()) {
                return Mono.empty();
            }
            // 如果没有收到过流式片段，则用完成事件中的文本作为兜底输出。
            return Mono.just(content);
        }
        // 其他输出类型可能是工具调用、节点状态或中间数据，不直接作为用户答案文本。
        return Mono.empty();
    }


    /*
    多载体兼容提取
    这个方法处理的是一个兼容性问题：不同版本的框架、不同的模型提供商，文本可能放在不同的位置。所以这里做了三级降级提取：
     */
    private String extractStreamingText(StreamingOutput<?> streamingOutput) {//处理流式输出对象
       /*
       优先级一streamingOutput.message()
       这是最标准的路径。Spring AI 的 Message 接口是模型输出的标准载体，getText() 返回的就是模型生成的文本内容。大多数情况下，文本都能从这里拿到。
        */
        // 优先读取 StreamingOutput 暴露的 message，这是最标准的模型输出载体。
        Message message = streamingOutput.message();
        // message 存在且文本不为空时，直接返回该文本。
        if (message != null && StrUtil.isNotBlank(message.getText())) {
            return message.getText();
        }



        /*
        优先级二 originData 是 Message
        有些框架事件不会直接设置 message() 字段，而是把原始的 Message 对象放在 originData 里。这种情况下，我们从 originData 里把它取出来。
         */
        // 部分框架事件会把原始数据放在 originData 中，需要继续做兼容提取。
        Object originData = streamingOutput.getOriginData();
        // originData 如果本身是 Message，也优先读取它的文本。
        if (originData instanceof Message originMessage && StrUtil.isNotBlank(originMessage.getText())) {
            return originMessage.getText();
        }

        /*
        优先级三：originData 是 String
        最后的兜底：如果 originData 直接就是一个字符串，那就把它当作文本内容返回。这种情况比较少见，但为了兼容性还是要处理。
         */
        // originData 如果直接是字符串，就把它作为文本内容返回。
        if (originData instanceof String text && StrUtil.isNotBlank(text)) {
            return text;
        }
        /*
        全部失败：返回空串
        三种载体都没有有效文本，返回空串。上层的 extractTextChunk 会检查到空串后返回 Mono.empty()，这个输出就被安静地过滤掉了。
         */
        // 三种载体都没有有效文本时，返回空串交给上层过滤。
        return "";
    }
}
