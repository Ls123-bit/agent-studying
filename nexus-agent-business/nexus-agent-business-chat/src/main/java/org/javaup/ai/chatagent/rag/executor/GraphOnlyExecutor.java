package org.javaup.ai.chatagent.rag.executor;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.model.trace.ConversationTraceStageCode;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.model.DocumentNavigationDecision;
import org.javaup.ai.chatagent.rag.model.ExecutionMode;
import org.javaup.ai.chatagent.rag.service.GraphAnswerRenderer;
import org.javaup.ai.chatagent.rag.service.StructureGraphQueryEngine;
import org.javaup.ai.chatagent.rag.support.ExecutorEventSupport;
import org.javaup.ai.chatagent.service.ConversationTraceRecorder;
import org.javaup.ai.chatagent.service.TaskInfo;
import org.javaup.ai.chatagent.support.StreamEventWriter;
import org.javaup.ai.manage.model.graph.GraphQueryResult;
import org.javaup.ai.manage.model.graph.GraphSectionWithChildren;
import org.javaup.ai.manage.model.graph.GraphSectionWithSiblings;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/*
纯图结构查询
模式：ExecutionMode.GRAPH_ONLY
只回答文档结构相关的问题，不走检索，直接查 Neo4j 图。
执行流程：
1.从 ConversationExecutionPlan 取出 DocumentNavigationDecision
2.根据导航动作调用：structureGraphQueryEngine.findSectionWithSiblings() — 查兄弟节点
                 structureGraphQueryEngine.findSectionWithChildren() — 查子节点

3.用 GraphAnswerRenderer.renderGraphAnswer() 把图查询结果转成自然语言

 */
/*
Spring 容器启动时，ConversationExecutorRegistry 会收集所有 ConversationExecutor 实现，按 mode() 建立映射。当路由决策返回 GRAPH_ONLY 时，注册表就能直接找到这个执行器。
 */
@Component
@Slf4j
public class GraphOnlyExecutor implements ConversationExecutor {

    private final StructureGraphQueryEngine structureGraphQueryEngine;
    private final GraphAnswerRenderer graphAnswerRenderer;
    private final StreamEventWriter streamEventWriter;

    public GraphOnlyExecutor(StructureGraphQueryEngine structureGraphQueryEngine,
                             GraphAnswerRenderer graphAnswerRenderer,
                             StreamEventWriter streamEventWriter) {
        this.structureGraphQueryEngine = structureGraphQueryEngine;
        this.graphAnswerRenderer = graphAnswerRenderer;
        this.streamEventWriter = streamEventWriter;
    }

    @Override
    public ExecutionMode mode() {
        return ExecutionMode.GRAPH_ONLY;
    }












    @Override
    public Flux<String> execute(TaskInfo taskInfo) {//从执行计划读取导航决策，查图，渲染回答
        /*
        第一步：提取执行上下文
        TaskInfo 是贯穿整个对话执行流的上下文对象，里面携带了执行计划、事件流 sink、trace 记录器等所有运行时信息。执行计划（ConversationExecutionPlan）则是前面准备阶段产出的成果，包含了路由决策、问题改写结果、文档 ID 等关键数据。
         */
        // 先取出本次任务的执行计划，结构图直答流程完全依赖这份上下文。
        ConversationExecutionPlan plan = taskInfo.executionPlan();
        // 再从执行计划里拆出导航决策，后续要靠它判断应该查相邻章节还是子章节。
        DocumentNavigationDecision decision = plan == null ? null : plan.getNavigationDecision();

        /*
        第二步：前置校验与兜底
        这里做了一个很重要的防御：虽然路由阶段已经判定走 GRAPH_ONLY，但如果章节解析失败（比如 resolveSection 没找到匹配的章节），structureNodeId 就会是 null。这时候与其报错，不如优雅地返回一个兜底回复。
         */
        // 只要计划、决策、结构锚点或结构节点 ID 任意一项缺失，就说明当前请求无法进入结构图查询路径。
        if (plan == null || decision == null || decision.getStructureAnchor() == null || decision.getStructureAnchor().getStructureNodeId() == null) {
            // 打出结构化日志，方便后续快速定位到底是缺计划、缺决策，还是缺结构节点。
            log.info("GRAPH_ONLY 执行器直接返回无证据: planPresent={}, decisionPresent={}, structureNodeId={}",
                plan != null,
                decision != null,
                decision == null || decision.getStructureAnchor() == null ? null : decision.getStructureAnchor().getStructureNodeId());
            // 返回兜底文案，优先使用计划里配置的无证据回复，否则使用系统默认提示。
            return Flux.just(StrUtil.blankToDefault(plan == null ? "" : plan.getNoEvidenceReply(), "当前没有足够证据支持明确回答。"));
        }
        /*
        第三步：发布思考事件与开启 Trace
        这里有两个动作：
        1.发布 thinking 事件 —— 通过 ExecutorEventSupport.publishThinking 往 SSE 流里推一条状态消息，前端收到后会展示"正在思考"的动画效果
        2.开启 Trace 阶段 —— 如果当前任务配置了 trace 记录器，就标记一个 GRAPH_QUERY 阶段的开始，后续查询完成后会把结果写回这个阶段
         */
        // 向事件流发送"正在思考"提示，让前端知道系统已经进入结构图查询阶段。
        ExecutorEventSupport.publishThinking(taskInfo, streamEventWriter, "正在通过结构图直接查询章节关系。");
        // 如果 traceRecorder 存在，就为本次结构图查询开启一个可追踪的阶段。
        ConversationTraceRecorder.StageHandle graphStage = taskInfo.traceRecorder() == null
            ? null
            : taskInfo.traceRecorder().startStage(ConversationTraceStageCode.GRAPH_QUERY, mode().name(), "正在执行结构图查询。", null);



        /*
        第四步：提取查询坐标
        documentId 限定了查询范围在哪个文档内，sectionNodeId 则是具体要查的章节节点。这两个值就是后续所有图查询的入口参数。
         */
        Long documentId = plan.getSelectedDocumentId();// 取出当前路由命中的文档 ID，后面的所有图查询都限定在这个文档范围内。
        Long sectionNodeId = decision.getStructureAnchor().getStructureNodeId();// 取出目标章节节点 ID，它就是本次结构图查询的入口坐标。
        log.info("GRAPH_ONLY 执行开始: documentId={}, sectionNodeId={}, action={}, navigationSummary='{}'",// 记录执行开始日志，把文档 ID、章节节点 ID 和路由摘要一起写出来，便于排查。
            documentId,
            sectionNodeId,
            decision.getNavigationAction(),
            decision.getSummaryText());
        /*
        第五步：根据导航动作分支查询
        这是整个 execute 方法的核心分支逻辑：
        两种查询模式
        SECTION_ADJACENCY_LOOKUP：用户问的是"上一节是什么"、"这章属于哪个章节"这类相邻关系问题，查询会返回父章节、前一个兄弟、后一个兄弟
        其他动作（默认走子章节）：用户问的是"这章包含哪些小节"这类展开问题，查询会返回当前章节的直接子节点列表
         */
        GraphQueryResult graphResult;// 准备承接结构图查询结果的统一对象，后面无论查相邻章节还是子章节都会封装到这里。
        // 根据导航动作判断本次要查询"相邻章节"还是"直接子章节"。
        if (decision.getNavigationAction() == org.javaup.ai.chatagent.rag.model.DocumentNavigationAction.SECTION_ADJACENCY_LOOKUP) {
            // 相邻章节模式会返回当前章节的父章节、前一节和后一节。
            GraphSectionWithSiblings result = structureGraphQueryEngine.findSectionWithSiblings(documentId, sectionNodeId);
            // 把分散的查询结果收拢成统一的 GraphQueryResult，方便后续渲染器直接消费。
            graphResult = GraphQueryResult.builder()
                .targetSection(result.getSection())
                .parentSection(result.getParent())
                .previousSibling(result.getPreviousSibling())
                .nextSibling(result.getNextSibling())
                .build();
        }
        else {
            // 子章节模式会返回当前章节的直接子节点集合。
            GraphSectionWithChildren result = structureGraphQueryEngine.findSectionWithChildren(documentId, sectionNodeId);
            // 同样封装成统一结果，保证后续答案渲染逻辑只关心 GraphQueryResult。
            graphResult = GraphQueryResult.builder()
                .targetSection(result.getSection())
                .children(result.getChildren())
                .build();
        }
        /*
        第六步：渲染答案
        查询结果拿到之后，不能直接把原始数据丢给用户，需要通过 GraphAnswerRenderer 把结构化的图查询结果转换成人能读懂的自然语言。具体的渲染逻辑我们后面单独讲。
         */
        // 将结构图查询结果交给渲染器，生成最终要返回给用户的自然语言答案。
        String answer = graphAnswerRenderer.renderGraphAnswer(mode(), decision, graphResult);
        // 记录执行完成日志，把命中的章节标题和答案长度一起带上，方便观察查询效果。
        log.info("GRAPH_ONLY 执行完成: documentId={}, sectionNodeId={}, targetSection='{}', answerLength={}",
            documentId,
            sectionNodeId,
            graphResult.getTargetSection() == null ? "" : graphResult.getTargetSection().displayTitle(),
            answer == null ? 0 : answer.length());
        /*
        第七步：记录 Trace 并返回结果
        最后这段做了两件事：
        1.写入 Trace —— 把本次查询的关键信息（目标章节、父章节、子章节数、前后兄弟、最终答案）全部记录下来，方便后续在管理后台回溯每次对话的执行细节
        2.返回结果 —— 如果答案不为空就直接返回；如果为空（比如图里没查到对应章节），就返回兜底文案
         */
        // 如果 traceRecorder 存在，就把结构图命中结果和答案摘要写回当前阶段。
        if (taskInfo.traceRecorder() != null) {
            // 每个字段都尽量保留可读文本，便于在 trace 里快速回看这次查询究竟命中了什么。
            taskInfo.traceRecorder().completeStage(graphStage, "结构图查询完成。", Map.of(
                    // 记录最终命中的目标章节标题。
                "targetSection", graphResult.getTargetSection() == null ? "" : StrUtil.blankToDefault(graphResult.getTargetSection().displayTitle(), ""),
                    // 记录父章节标题，帮助确认当前章节的层级位置。
                "parentSection", graphResult.getParentSection() == null ? "" : StrUtil.blankToDefault(graphResult.getParentSection().displayTitle(), ""),
                    // 记录直接子章节数量，用来判断子章节展开是否成功。
                "childCount", graphResult.getChildren() == null ? 0 : graphResult.getChildren().size(),
                    // 记录前一个兄弟章节标题，方便核对左右邻接关系。
                "previousSibling", graphResult.getPreviousSibling() == null ? "" : StrUtil.blankToDefault(graphResult.getPreviousSibling().displayTitle(), ""),
                    // 记录后一个兄弟章节标题，方便核对左右邻接关系。
                "nextSibling", graphResult.getNextSibling() == null ? "" : StrUtil.blankToDefault(graphResult.getNextSibling().displayTitle(), ""),
                    // 记录最终答案正文，便于 trace 里直接回看渲染结果。
                "answer", StrUtil.blankToDefault(answer, "")
            ));
        }
        // 如果渲染结果为空，就回退到无证据提示；否则逐条返回渲染后的答案。
        return Flux.fromIterable(answer.isBlank() ? List.of(StrUtil.blankToDefault(plan.getNoEvidenceReply(), "当前没有足够证据支持明确回答。")) : List.of(answer));
    }
}
