package org.javaup.ai.chatagent.rag.executor;

import cn.hutool.core.util.StrUtil;
import org.javaup.ai.chatagent.rag.model.ExecutionMode;
import org.javaup.ai.chatagent.rag.support.ExecutorEventSupport;
import org.javaup.ai.chatagent.service.TaskInfo;
import org.javaup.ai.chatagent.support.StreamEventWriter;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 澄清执行器。
 *
 * <p>这条路径不做知识检索，也不调用 Agent，
 * 直接把预先生成好的追问话术流式返回给用户。</p>
 */
@Component
public class ClarifyExecutor implements ConversationExecutor {

    private final StreamEventWriter streamEventWriter;

    public ClarifyExecutor(StreamEventWriter streamEventWriter) {
        this.streamEventWriter = streamEventWriter;
    }

    @Override
    public ExecutionMode mode() {
        return ExecutionMode.CLARIFY;
    }

    @Override
    public Flux<String> execute(TaskInfo taskInfo) {
        String note = "当前问题存在歧义，优先向用户澄清具体知识域。";
        taskInfo.debugTrace().getRetrievalNotes().add(note);
        ExecutorEventSupport.publishThinking(taskInfo, streamEventWriter, note);
        String prompt = taskInfo.executionPlan().getClarifyPrompt();
        return Flux.just(StrUtil.blankToDefault(prompt, "可以补充更具体的系统名称、模块名称或业务关键词，我再继续帮你查。"));
    }
}
