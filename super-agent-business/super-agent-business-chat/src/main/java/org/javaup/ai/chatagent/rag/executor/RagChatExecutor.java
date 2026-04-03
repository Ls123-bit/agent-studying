package org.javaup.ai.chatagent.rag.executor;

import cn.hutool.core.util.StrUtil;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.model.ExecutionMode;
import org.javaup.ai.chatagent.rag.model.RagRetrievalContext;
import org.javaup.ai.chatagent.rag.service.RagPromptAssemblyService;
import org.javaup.ai.chatagent.rag.service.RagRetrievalEngine;
import org.javaup.ai.chatagent.rag.support.ExecutorEventSupport;
import org.javaup.ai.chatagent.service.TaskInfo;
import org.javaup.ai.chatagent.support.StreamEventWriter;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 知识问答执行器。
 *
 * <p>这条路径的核心原则是：
 * 先拿证据，再回答；没有证据就直接结束，不让模型自由补全。</p>
 */
@Component
public class RagChatExecutor implements ConversationExecutor {

    private final ChatClient chatClient;
    private final RagRetrievalEngine ragRetrievalEngine;
    private final RagPromptAssemblyService ragPromptAssemblyService;
    private final StreamEventWriter streamEventWriter;

    public RagChatExecutor(ChatModel chatModel,
                           RagRetrievalEngine ragRetrievalEngine,
                           RagPromptAssemblyService ragPromptAssemblyService,
                           StreamEventWriter streamEventWriter) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.ragRetrievalEngine = ragRetrievalEngine;
        this.ragPromptAssemblyService = ragPromptAssemblyService;
        this.streamEventWriter = streamEventWriter;
    }

    @Override
    public ExecutionMode mode() {
        return ExecutionMode.RAG_CHAT;
    }

    @Override
    public Flux<String> execute(TaskInfo taskInfo) {
        ConversationExecutionPlan plan = taskInfo.executionPlan();
        /*
         * 进入知识问答执行器后，先给前端一个明确的过程提示：
         * 当前已经不再是普通 Agent 路径，而是在准备知识检索证据。
         */
        ExecutorEventSupport.publishThinking(taskInfo, streamEventWriter, "正在根据问题规划知识检索范围。");

        return Mono.fromCallable(() -> ragRetrievalEngine.retrieve(plan))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMapMany(context -> streamFromRetrievalContext(taskInfo, plan, context));
    }

    /**
     * 基于检索结果决定是直接兜底返回，还是继续交给 ChatClient 生成答案。
     */
    private Flux<String> streamFromRetrievalContext(TaskInfo taskInfo,
                                                    ConversationExecutionPlan plan,
                                                    RagRetrievalContext context) {
        /*
         * 先把检索阶段已经产生的说明性文本逐条补发给前端。
         * 这样用户能看到“每个子问题查到了什么”，而不是只看到最终答案。
         */
        context.getRetrievalNotes().forEach(note -> ExecutorEventSupport.publishThinking(taskInfo, streamEventWriter, note));
        /*
         * usedChannels 最终会和原有 usedTools 一起归档。
         * 这里虽然它们语义上更像“检索通道”，但在当前会话归档模型里仍然统一落到 usedTools 容器。
         */
        taskInfo.usedTools().addAll(context.getUsedChannels());
        taskInfo.debugTrace().setRetrievalNotes(new java.util.ArrayList<>(context.getRetrievalNotes()));
        taskInfo.debugTrace().setUsedChannels(new java.util.ArrayList<>(context.getUsedChannels()));

        if (context.isEmpty()) {
            /*
             * 没有证据时，不再继续组 Prompt。
             * 直接在这里短路返回，是为了彻底避免“模型靠自身记忆硬补答案”。
             */
            ExecutorEventSupport.publishThinking(taskInfo, streamEventWriter, "当前没有足够证据，直接返回无证据兜底回复。");
            return Flux.just(StrUtil.blankToDefault(plan.getNoEvidenceReply(), "当前没有足够证据支持明确回答。"));
        }

        /*
         * 只有真正确定有证据时，才把引用快照挂进 TaskInfo。
         * 这样 finishSuccessfully(...) 里去重和最终补发 reference 事件时，拿到的是定稿证据集。
         */
        taskInfo.references().addAll(context.flattenReferences());
        ExecutorEventSupport.publishThinking(taskInfo, streamEventWriter, "证据整理完成，正在基于证据生成回答。");
        /*
         * Prompt 组装拆成 systemPrompt / userPrompt 两段，是为了：
         * 1. 把“回答约束”放在 system 里
         * 2. 把“问题 + 证据材料”放在 user 里
         *
         * 后台观测页也会直接展示这两段内容。
         */
        String systemPrompt = ragPromptAssemblyService.buildSystemPrompt();
        String userPrompt = ragPromptAssemblyService.buildUserPrompt(plan, context);
        taskInfo.debugTrace().setRagSystemPrompt(systemPrompt);
        taskInfo.debugTrace().setRagUserPrompt(userPrompt);

        /*
         * 到这里才真正进入模型生成阶段。
         * 当前执行器只返回正文分片，真正的 SSE 发包仍然由 BusinessChatService 统一处理。
         */
        return chatClient.prompt()
            .system(systemPrompt)
            .user(userPrompt)
            .stream()
            .content();
    }
}
