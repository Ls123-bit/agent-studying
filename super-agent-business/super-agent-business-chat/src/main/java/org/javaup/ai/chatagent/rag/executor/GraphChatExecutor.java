package org.javaup.ai.chatagent.rag.executor;

import cn.hutool.core.util.StrUtil;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.model.DocumentNavigationDecision;
import org.javaup.ai.chatagent.rag.model.ExecutionMode;
import org.javaup.ai.chatagent.rag.service.GraphAnswerRenderer;
import org.javaup.ai.chatagent.rag.support.ExecutorEventSupport;
import org.javaup.ai.chatagent.service.TaskInfo;
import org.javaup.ai.chatagent.support.StreamEventWriter;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 结构图执行器。
 *
 * <p>处理 GRAPH_ONLY 和 GRAPH_THEN_EVIDENCE 两种模式。
 * 不走向量检索和关键词检索，直接从结构图获取答案。</p>
 */
@Component
public class GraphChatExecutor implements ConversationExecutor {

    private final GraphAnswerRenderer graphAnswerRenderer;
    private final StreamEventWriter streamEventWriter;

    public GraphChatExecutor(GraphAnswerRenderer graphAnswerRenderer,
                             StreamEventWriter streamEventWriter) {
        this.graphAnswerRenderer = graphAnswerRenderer;
        this.streamEventWriter = streamEventWriter;
    }

    @Override
    public ExecutionMode mode() {
        return ExecutionMode.GRAPH_ONLY;
    }

    /**
     * 同时支持 GRAPH_ONLY 和 GRAPH_THEN_EVIDENCE。
     */
    public boolean supports(ExecutionMode executionMode) {
        return executionMode == ExecutionMode.GRAPH_ONLY
            || executionMode == ExecutionMode.GRAPH_THEN_EVIDENCE;
    }

    @Override
    public Flux<String> execute(TaskInfo taskInfo) {
        ConversationExecutionPlan plan = taskInfo.executionPlan();
        DocumentNavigationDecision decision = plan.getNavigationDecision();
        if (decision == null) {
            return fallbackNoEvidence(taskInfo, plan);
        }
        ExecutorEventSupport.publishThinking(taskInfo, streamEventWriter, "正在从文档结构图中查询答案。");
        Long documentId = plan.getSelectedDocumentId();
        ExecutionMode executionMode = decision.getExecutionMode();
        String answer = null;
        if (executionMode == ExecutionMode.GRAPH_ONLY) {
            answer = graphAnswerRenderer.renderGraphOnlyAnswer(decision, documentId);
        }
        else if (executionMode == ExecutionMode.GRAPH_THEN_EVIDENCE) {
            answer = graphAnswerRenderer.renderGraphThenEvidenceAnswer(decision, documentId);
        }
        if (StrUtil.isBlank(answer)) {
            if (decision.isMissingRequestedStructure()) {
                return fallbackMissingStructure(taskInfo, plan);
            }
            return fallbackNoEvidence(taskInfo, plan);
        }
        return Flux.just(answer);
    }

    private Flux<String> fallbackNoEvidence(TaskInfo taskInfo, ConversationExecutionPlan plan) {
        String noEvidenceReply = StrUtil.blankToDefault(
            plan.getNoEvidenceReply(),
            "当前没有从文档结构中找到足够信息来回答这个问题。你可以补充更具体的章节名或关键词后再试。"
        );
        ExecutorEventSupport.publishThinking(taskInfo, streamEventWriter, "文档结构中未找到匹配的章节或步骤。");
        return Flux.just(noEvidenceReply);
    }

    private Flux<String> fallbackMissingStructure(TaskInfo taskInfo, ConversationExecutionPlan plan) {
        String reply = "当前问题指向的目标章节在文档中不存在。文档中没有与此完全对应的章节结构，无法给出可靠结论。";
        ExecutorEventSupport.publishThinking(taskInfo, streamEventWriter, "目标章节在文档结构树中不存在。");
        return Flux.just(reply);
    }
}
