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
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/*
图定位+证据提取
模式：ExecutionMode.GRAPH_THEN_EVIDENCE
先通过图结构定位到具体章节，再读取该章节下的条目/步骤内容。
执行流程:
1.从导航决策中获取目标章节和条目索引
2.如果有明确的条目索引，直接查；否则从问题中提取关键词搜索
3.调用 structureGraphQueryEngine.buildGraphResult(documentId, sectionNodeId, itemIndex, itemKeyword)
4.验证是否有证据，有则渲染，无则降级

执行器注册：和 GraphOnlyExecutor 一样，GraphThenEvidenceExecutor 也实现了 ConversationExecutor 接口：依赖和 GraphOnlyExecutor 完全一样：结构图查询引擎、答案渲染器、事件流写入器。区别在于 mode() 返回的是 GRAPH_THEN_EVIDENCE。
 */
@Component
@Slf4j
public class GraphThenEvidenceExecutor implements ConversationExecutor {

    private final StructureGraphQueryEngine structureGraphQueryEngine;
    private final GraphAnswerRenderer graphAnswerRenderer;
    private final StreamEventWriter streamEventWriter;

    public GraphThenEvidenceExecutor(StructureGraphQueryEngine structureGraphQueryEngine,
                                     GraphAnswerRenderer graphAnswerRenderer,
                                     StreamEventWriter streamEventWriter) {
        this.structureGraphQueryEngine = structureGraphQueryEngine;
        this.graphAnswerRenderer = graphAnswerRenderer;
        this.streamEventWriter = streamEventWriter;
    }

    @Override
    public ExecutionMode mode() {
        return ExecutionMode.GRAPH_THEN_EVIDENCE;
    }





    @Override
    public Flux<String> execute(TaskInfo taskInfo) {//定位章节 → 提取条目关键词 → 查图 → 渲染
       /*
       第一步：提取执行上下文与前置校验
       这段和 GraphOnlyExecutor 的前置校验逻辑完全一样：检查执行计划、导航决策、结构锚点是否齐全。缺任何一项就直接返回兜底文案
        */
        // 先取出本次对话的执行计划，GRAPH_THEN_EVIDENCE 需要依赖计划里的文档、章节和问题信息。
        ConversationExecutionPlan plan = taskInfo.executionPlan();
        // 从计划里取出文档导航决策，里面包含结构锚点、条目锚点和导航摘要。
        DocumentNavigationDecision decision = plan == null ? null : plan.getNavigationDecision();
        // 只要计划、导航决策、结构锚点或章节节点 ID 缺失，就无法进入"先定位再取证"的流程。
        if (plan == null || decision == null || decision.getStructureAnchor() == null || decision.getStructureAnchor().getStructureNodeId() == null) {
            // 记录缺失项，方便排查路由阶段是否没有生成有效的结构图锚点。
            log.info("GRAPH_THEN_EVIDENCE 执行器直接返回无证据: planPresent={}, decisionPresent={}, structureNodeId={}",
                plan != null,
                decision != null,
                decision == null || decision.getStructureAnchor() == null ? null : decision.getStructureAnchor().getStructureNodeId());
            // 直接返回无证据兜底文案，优先使用计划里的 noEvidenceReply。
            return Flux.just(StrUtil.blankToDefault(plan == null ? "" : plan.getNoEvidenceReply(), "当前没有足够证据支持明确回答。"));
        }

        /*
        第二步：发布事件与开启 Trace
        注意这里的 thinking 提示文案和 GraphOnlyExecutor 不同——它说的是"定位目标章节和编号项"，因为这个执行器不只是查章节关系，还要定位到具体的条目。
         */
        // 向前端事件流发送思考提示，说明系统正在进入结构图定位和取证阶段。
        ExecutorEventSupport.publishThinking(taskInfo, streamEventWriter, "正在通过结构图定位目标章节和编号项。");
        // 如果当前任务启用了 traceRecorder，就开启一个 GRAPH_QUERY 阶段用于记录结构定位过程。
        ConversationTraceRecorder.StageHandle graphStage = taskInfo.traceRecorder() == null
            ? null
            : taskInfo.traceRecorder().startStage(ConversationTraceStageCode.GRAPH_QUERY, mode().name(), "正在执行结构图定位与取证。", null);
        // 记录执行入口信息：文档、章节、可能的编号项，以及路由阶段给出的摘要。
        log.info("GRAPH_THEN_EVIDENCE 执行开始: documentId={}, sectionNodeId={}, itemIndex={}, navigationSummary='{}'",
            plan.getSelectedDocumentId(),
            decision.getStructureAnchor().getStructureNodeId(),
            decision.getItemAnchor() == null ? null : decision.getItemAnchor().getItemIndex(),
            decision.getSummaryText());

        /*
        第三步：构造结构图查询结果（buildGraphResult）
        这是 GraphThenEvidenceExecutor 和 GraphOnlyExecutor 最大的区别所在：
         */
        // 根据执行计划和导航决策构造结构图查询结果，里面会包含目标章节、条目、子章节和匹配项。
        GraphQueryResult graphResult = buildGraphResult(plan, decision);

        /*
        第四步：证据校验
        查询结果拿到之后，不能直接渲染。需要先校验一下：结构图是否真的找到了足够的证据来回答用户的问题？
         */
        // 结构图结果需要再做一次证据约束校验，防止只定位到章节标题但没有可回答的内容。
        if (!hasGraphEvidence(graphResult, decision)) {
            log.info("GRAPH_THEN_EVIDENCE 证据校验失败: documentId={}, sectionNodeId={}, notes={}",
                plan.getSelectedDocumentId(),
                decision.getStructureAnchor().getStructureNodeId(),
                List.of("结构图未定位到满足条件的章节或编号项。"));
            if (taskInfo.traceRecorder() != null) {
                taskInfo.traceRecorder().completeStage(graphStage, "结构图定位完成，但证据不满足约束。", Map.of(
                    "targetSection", graphResult == null || graphResult.getTargetSection() == null ? "" : StrUtil.blankToDefault(graphResult.getTargetSection().displayTitle(), ""),
                    "targetItemIndex", graphResult == null || graphResult.getTargetItem() == null || graphResult.getTargetItem().getItemIndex() == null ? "" : String.valueOf(graphResult.getTargetItem().getItemIndex()),
                    "notes", List.of("结构图未定位到满足条件的章节或编号项。")
                ));
            }
            // 证据不满足时不继续渲染，直接返回无证据兜底文案。
            return Flux.just(StrUtil.blankToDefault(plan.getNoEvidenceReply(), "当前没有足够证据支持明确回答。"));
        }
        /*
        第五步：渲染答案  证据校验通过后，就可以把结构图查询结果交给渲染器了：
         */
        // 证据校验通过后，把结构图结果交给渲染器生成自然语言答案。
        String answer = graphAnswerRenderer.renderGraphAnswer(mode(), decision, graphResult);
        // 记录最终命中的章节、编号项和答案长度，便于观察取证与渲染是否符合预期。
        log.info("GRAPH_THEN_EVIDENCE 执行完成: documentId={}, sectionNodeId={}, targetSection='{}', targetItemIndex={}, answerLength={}",
            plan.getSelectedDocumentId(),
            decision.getStructureAnchor().getStructureNodeId(),
            graphResult.getTargetSection() == null ? "" : graphResult.getTargetSection().displayTitle(),
            graphResult.getTargetItem() == null ? null : graphResult.getTargetItem().getItemIndex(),
            answer == null ? 0 : answer.length());


        /*
        第六步：记录Trace并返回结果
         */
        // 如果 traceRecorder 存在，就把成功取证结果写回 GRAPH_QUERY 阶段。
        if (taskInfo.traceRecorder() != null) {
            // trace 中保留章节、编号项、命中条目数量和答案，方便回放本次结构图取证过程。
            taskInfo.traceRecorder().completeStage(graphStage, "结构图取证完成。", Map.of(
                "targetSection", graphResult.getTargetSection() == null ? "" : StrUtil.blankToDefault(graphResult.getTargetSection().displayTitle(), ""),
                "targetItemIndex", graphResult.getTargetItem() == null || graphResult.getTargetItem().getItemIndex() == null ? "" : String.valueOf(graphResult.getTargetItem().getItemIndex()),
                "matchedItemCount", graphResult.getMatchedItems() == null ? 0 : graphResult.getMatchedItems().size(),
                "answer", StrUtil.blankToDefault(answer, "")
            ));
        }
        // 如果答案为空，则回退到无证据提示；否则将答案作为 Flux 返回给上层流式接口。
        return Flux.fromIterable(answer.isBlank() ? List.of(StrUtil.blankToDefault(plan.getNoEvidenceReply(), "当前没有足够证据支持明确回答。")) : List.of(answer));
    }




    /*
    私有方法
    这里做了三件事：
    1.提取编号项索引（itemIndex）—— 如果用户问的是"第3步是什么"，路由阶段已经解析出 itemIndex = 3
    2.提取条目关键词（itemKeyword）—— 如果用户问的是"哪一步需要人工审核"，需要从问题中抽取"人工审核"作为搜索关键词
    3.调用 buildGraphResult —— 把这些参数交给查询引擎，一次性查出所有需要的数据

     */
    private GraphQueryResult buildGraphResult(ConversationExecutionPlan plan, DocumentNavigationDecision decision) {//构建图查询结果
        Long documentId = plan.getSelectedDocumentId();// 取出路由选中的文档 ID，结构图查询必须限定在这个文档范围内。
        Long sectionNodeId = decision.getStructureAnchor().getStructureNodeId();// 取出结构锚点中的章节节点 ID，它是结构图取证的起点。
        Integer itemIndex = decision.getItemAnchor() == null ? null : decision.getItemAnchor().getItemIndex();// 如果路由识别到了"第几步/第几项"，就取出该编号用于精确查找条目。
        String itemKeyword = extractItemKeyword(plan.getOriginalQuestion(), decision); // 从原始问题里抽取条目关键词，用于"哪一步要求 xxx"这类非显式编号问题。
        return structureGraphQueryEngine.buildGraphResult(documentId, sectionNodeId, itemIndex, itemKeyword);// 交给结构图查询引擎，统一查询章节、子章节、所有条目、目标条目和关键词命中条目。
    }



    /*
    从问题中提取条目关键词
     */
    private String extractItemKeyword(String question, DocumentNavigationDecision decision) {//从问题中提取条目关键词（处理"哪一步""哪一项"等中文模式）
        // 原始问题为空时按空串处理，避免后续 contains 和替换操作出现空指针。
        String normalized = StrUtil.blankToDefault(question, "");
        // 这里只处理"哪一步/哪一项"这类中文问法，用于从问句尾部抽取条目关键词。
        if (normalized.contains("哪一步") || normalized.contains("哪一项")) {
            // 根据命中的问法切分出"哪一步/哪一项"之后的文本。
            String keyword = normalized.contains("哪一步")
                ? StrUtil.subAfter(normalized, "哪一步", false)
                : StrUtil.subAfter(normalized, "哪一项", false);
            // 去掉常见动词、助词和标点，留下更适合匹配条目标题或正文的核心关键词。
            keyword = keyword
                .replace("要求", "")
                .replace("需要", "")
                .replace("执行", "")
                .replace("进行", "")
                .replace("包含", "")
                .replace("的是", "")
                .replace("是什么", "")
                .replace("什么", "")
                .replace("？", "")
                .replace("?", "")
                .replace("。", "")
                .replace("，", "")
                .trim();
            // 清洗后仍有内容，才把它作为条目关键词交给结构图搜索。
            if (StrUtil.isNotBlank(keyword)) {
                return keyword;
            }
        }
        // 没有识别到有效条目关键词时返回空串，后续 buildGraphResult 会跳过关键词搜索。
        return "";
    }


    /*
    证据校验
     */
    private boolean hasGraphEvidence(GraphQueryResult graphResult, DocumentNavigationDecision decision) {//验证图查询是否有有效证据
        // 没有查询结果或没有目标章节时，说明结构图没有提供任何可回答依据。
        if (graphResult == null || graphResult.getTargetSection() == null) {
            return false;
        }
        // 如果用户问题明确要求某个编号项，就必须命中目标条目或至少命中相关条目。
        if (decision != null && decision.getItemAnchor() != null && decision.getItemAnchor().getItemIndex() != null) {
            // targetItem 表示精确编号命中；matchedItems 表示关键词兜底命中。
            return graphResult.getTargetItem() != null
                || (graphResult.getMatchedItems() != null && !graphResult.getMatchedItems().isEmpty());
        }
        // 没有编号约束时，只要目标章节有正文，或者关键词命中了条目，就认为证据足够。
        return StrUtil.isNotBlank(graphResult.getTargetSection().getContentText())
            || (graphResult.getMatchedItems() != null && !graphResult.getMatchedItems().isEmpty());
    }
}
