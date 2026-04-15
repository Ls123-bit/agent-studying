package org.javaup.ai.chatagent.rag.executor;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.rag.core.graph.GraphQueryDetector;
import org.javaup.ai.chatagent.rag.core.graph.GraphQueryDetector.GraphQueryType;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.model.ExecutionMode;
import org.javaup.ai.chatagent.rag.service.GraphAnswerRenderer;
import org.javaup.ai.chatagent.rag.support.ExecutorEventSupport;
import org.javaup.ai.chatagent.service.TaskInfo;
import org.javaup.ai.chatagent.support.StreamEventWriter;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 图查询执行器。
 *
 * <p>在 ragent "软路由"架构下重新接入图查询能力。
 * 与旧版 GraphChatExecutor 的核心区别：</p>
 * <ul>
 *   <li>不依赖 DocumentNavigationDecision</li>
 *   <li>从 ConversationExecutionPlan 中获取 graphQueryType 和 graphTargetSectionNodeId</li>
 *   <li>图查询失败时降级到 RAG_CHAT（调用 RagChatExecutor）</li>
 * </ul>
 *
 * <p>触发条件由两个独立信号的交集决定：</p>
 * <ol>
 *   <li>GraphQueryDetector 判断问题模式（正则）</li>
 *   <li>意图分类提供目标章节（软路由）</li>
 * </ol>
 */
@Slf4j
@Component
public class GraphQueryExecutor implements ConversationExecutor {

    private final GraphAnswerRenderer graphAnswerRenderer;
    private final GraphQueryDetector graphQueryDetector;
    private final RagChatExecutor ragChatExecutor;
    private final StreamEventWriter streamEventWriter;

    public GraphQueryExecutor(GraphAnswerRenderer graphAnswerRenderer,
                              GraphQueryDetector graphQueryDetector,
                              RagChatExecutor ragChatExecutor,
                              StreamEventWriter streamEventWriter) {
        this.graphAnswerRenderer = graphAnswerRenderer;
        this.graphQueryDetector = graphQueryDetector;
        this.ragChatExecutor = ragChatExecutor;
        this.streamEventWriter = streamEventWriter;
    }

    @Override
    public ExecutionMode mode() {
        return ExecutionMode.GRAPH_ONLY;
    }

    /**
     * 同时支持 GRAPH_ONLY 和 GRAPH_THEN_EVIDENCE。
     */
    public boolean supports(ExecutionMode mode) {
        return mode == ExecutionMode.GRAPH_ONLY || mode == ExecutionMode.GRAPH_THEN_EVIDENCE;
    }

    @Override
    public Flux<String> execute(TaskInfo taskInfo) {
        ConversationExecutionPlan plan = taskInfo.executionPlan();
        GraphQueryType queryType = plan.getGraphQueryType();
        Long documentId = plan.getSelectedDocumentId();
        Long sectionNodeId = plan.getGraphTargetSectionNodeId();
        String question = plan.getRewriteQuestion();

        if (queryType == null || queryType == GraphQueryType.NONE) {
            log.info("[图查询] queryType=NONE，降级到RAG_CHAT");
            return ragChatExecutor.execute(taskInfo);
        }

        ExecutorEventSupport.publishThinking(taskInfo, streamEventWriter, "正在通过文档结构图查询。");

        try {
            String answer = switch (queryType) {
                case SECTION_ADJACENCY -> graphAnswerRenderer.renderAdjacencyAnswer(documentId, sectionNodeId);
                case SECTION_CHILDREN -> graphAnswerRenderer.renderChildrenAnswer(documentId, question);
                case ITEM_REFERENCE -> {
                    Integer itemIndex = graphQueryDetector.extractItemIndex(question);
                    yield itemIndex != null
                            ? graphAnswerRenderer.renderItemByIndex(documentId, sectionNodeId, itemIndex)
                            : null;
                }
                case ITEM_SEARCH -> {
                    String keyword = graphQueryDetector.extractItemSearchKeyword(question);
                    yield StrUtil.isNotBlank(keyword)
                            ? graphAnswerRenderer.renderItemSearch(documentId, sectionNodeId, keyword)
                            : null;
                }
                default -> null;
            };

            if (StrUtil.isNotBlank(answer)) {
                log.info("[图查询] 成功: queryType={}, documentId={}, sectionNodeId={}",
                        queryType, documentId, sectionNodeId);
                return Flux.just(answer);
            }

            log.info("[图查询] 结果为空，降级到RAG_CHAT: queryType={}", queryType);
        } catch (Exception e) {
            log.warn("[图查询] 执行异常，降级到RAG_CHAT: queryType={}, error={}", queryType, e.getMessage());
        }

        ExecutorEventSupport.publishThinking(taskInfo, streamEventWriter, "图查询未找到结果，切换到知识检索。");
        return ragChatExecutor.execute(taskInfo);
    }
}
