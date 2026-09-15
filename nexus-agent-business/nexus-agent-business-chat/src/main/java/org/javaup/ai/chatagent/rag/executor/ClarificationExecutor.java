package org.javaup.ai.chatagent.rag.executor;

import cn.hutool.core.util.StrUtil;
import org.javaup.ai.chatagent.model.trace.ConversationTraceStageCode;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.model.ExecutionMode;
import org.javaup.ai.chatagent.rag.support.ExecutorEventSupport;
import org.javaup.ai.chatagent.service.TaskInfo;
import org.javaup.ai.chatagent.support.StreamEventWriter;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 路由歧义澄清执行器
 * @author: 阿星不是程序员
 **/
/*
澄清反问
模式：ExecutionMode.CLARIFICATION
当系统无法确定用户想问哪个文档时，会反问用户让他选择。
执行流程：
1.从 ConversationExecutionPlan 取出 clarificationReply 和 clarificationReason
2.发送思考/状态事件
3.直接返回 Flux.just(clarificationReply) — 不调用模型，不走检索
 */
@Component
public class ClarificationExecutor implements ConversationExecutor {

    private final StreamEventWriter streamEventWriter;

    public ClarificationExecutor(StreamEventWriter streamEventWriter) {
        this.streamEventWriter = streamEventWriter;
    }

    @Override
    public ExecutionMode mode() {
        return ExecutionMode.CLARIFICATION;
    }

    @Override
    public Flux<String> execute(TaskInfo taskInfo) {//从执行计划取出澄清回复，直接返回
        ConversationExecutionPlan plan = taskInfo.executionPlan();
        String clarificationReply = plan == null
            ? "当前我无法稳定判断你想问哪份知识文档，请补充更具体的文档名、主题或关键词。"
            : StrUtil.blankToDefault(plan.getClarificationReply(),
                "当前我无法稳定判断你想问哪份知识文档，请补充更具体的文档名、主题或关键词。");
        String clarificationReason = plan == null ? "" : StrUtil.blankToDefault(plan.getClarificationReason(), "");
        if (taskInfo.debugTrace() != null && StrUtil.isNotBlank(clarificationReason)) {
            taskInfo.debugTrace().getRetrievalNotes().add(clarificationReason);
        }

        ExecutorEventSupport.publishThinking(taskInfo, streamEventWriter, "当前问题涉及多份候选文档，先向你确认知识范围。");
        if (StrUtil.isNotBlank(clarificationReason)) {
            ExecutorEventSupport.publishStatus(taskInfo, streamEventWriter, clarificationReason);
        }
        if (taskInfo.traceRecorder() != null) {
            taskInfo.traceRecorder().completeStage(
                taskInfo.traceRecorder().startStage(ConversationTraceStageCode.ROUTE, mode().name(), "当前候选存在歧义，先返回澄清问题。", null),
                "已返回澄清问题。",
                Map.of(
                    "clarificationReply", clarificationReply,
                    "clarificationReason", clarificationReason,
                    "clarificationOptions", plan == null || plan.getClarificationOptions() == null ? List.of() : plan.getClarificationOptions()
                )
            );
        }
        return Flux.just(clarificationReply);
    }
}
