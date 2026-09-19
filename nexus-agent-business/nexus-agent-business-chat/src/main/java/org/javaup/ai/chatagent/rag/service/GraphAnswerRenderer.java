package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import org.javaup.ai.chatagent.rag.model.DocumentNavigationAction;
import org.javaup.ai.chatagent.rag.model.DocumentNavigationDecision;
import org.javaup.ai.chatagent.rag.model.ExecutionMode;
import org.javaup.ai.manage.model.graph.GraphItem;
import org.javaup.ai.manage.model.graph.GraphQueryResult;
import org.javaup.ai.manage.model.graph.GraphSection;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.StringJoiner;

/*
把图查询结果转成用户可读的自然语言回答
渲染逻辑：
GRAPH_ONLY 模式：
    问邻接（上一章/下一章）→ renderAdjacency()
    问子节点 → renderChildren()
GRAPH_THEN_EVIDENCE 模式：
    渲染具体条目内容、匹配的条目列表、或章节正文
 */
/*
入口方法做了三层判断：
1.结果为空 → 返回空串，上层执行器会用兜底文案替代
2.GRAPH_THEN_EVIDENCE 模式 → 走证据增强渲染（这不是本篇重点，简单带过）
3.GRAPH_ONLY 模式 → 走纯结构图渲染，这是我们要重点看的
 */
@Service
public class GraphAnswerRenderer {
//渲染图查询回答
    public String renderGraphAnswer(ExecutionMode mode,
                                    DocumentNavigationDecision decision,
                                    GraphQueryResult graphResult) {
        // 如果结构图没有返回结果或没有命中目标章节，就没有可渲染的结构答案。
        if (graphResult == null || graphResult.getTargetSection() == null) {
            // 返回空串交给上层决定是否走无证据兜底。
            return "";
        }
        // GRAPH_THEN_EVIDENCE 模式会在结构关系之外继续补充条目或正文证据。
        if (mode == ExecutionMode.GRAPH_THEN_EVIDENCE) {
            // 交给证据增强渲染分支，优先回答具体步骤、命中条目或章节正文。
            return renderGraphThenEvidence(decision, graphResult);
        }
        // 默认按 GRAPH_ONLY 处理，只围绕结构图关系生成答案。
        return renderGraphOnly(decision, graphResult);
    }


    /*
    纯结构图渲染
     */
    private String renderGraphOnly(DocumentNavigationDecision decision, GraphQueryResult graphResult) {
        // 从导航决策里取出动作，用来判断用户要的是相邻关系还是子章节展开。
        DocumentNavigationAction action = decision == null ? null : decision.getNavigationAction();
        // 取出重写后的检索问题，后面会用关键词辅助判断用户问题的真实意图。
        String question = decision == null || decision.getRetrievalPlan() == null
            ? ""
            : StrUtil.blankToDefault(decision.getRetrievalPlan().getRetrievalQuestion(), "");
        // 如果路由动作已经明确为相邻章节，或者问题文本里出现相邻关系关键词，就渲染前后章节。
        if (action == DocumentNavigationAction.SECTION_ADJACENCY_LOOKUP || asksAdjacency(question)) {
            // 相邻渲染会输出目标章节、父章节、上一节和下一节。
            return renderAdjacency(graphResult);
        }
        // 如果用户在问子章节，或者图查询本身已经带回了子章节列表，就渲染子章节清单。
        if (asksChildren(question) || !graphResult.getChildren().isEmpty()) {
            // 子章节渲染会输出目标章节下的直接子节点。
            return renderChildren(graphResult.getTargetSection(), graphResult.getChildren());
        }
        // 没有相邻关系和子章节展开需求时，只返回目标章节标题作为最简结构答案。
        return graphResult.getTargetSection().displayTitle();
    }

    private String renderGraphThenEvidence(DocumentNavigationDecision decision, GraphQueryResult graphResult) {
        if (graphResult.getTargetItem() != null) {
            GraphItem item = graphResult.getTargetItem();
            return "“" + graphResult.getTargetSection().displayTitle() + "”中的第" + item.getItemIndex() + "步是：\n"
                + formatItem(item);
        }
        if (graphResult.getMatchedItems() != null && !graphResult.getMatchedItems().isEmpty()) {
            StringJoiner joiner = new StringJoiner("\n");
            joiner.add("在“" + graphResult.getTargetSection().displayTitle() + "”中命中了以下步骤：");
            for (GraphItem item : graphResult.getMatchedItems()) {
                joiner.add(formatItem(item));
            }
            return joiner.toString();
        }
        GraphSection targetSection = graphResult.getTargetSection();
        if (StrUtil.isNotBlank(targetSection.getContentText())) {
            return "“" + targetSection.displayTitle() + "”中的相关内容如下：\n" + targetSection.getContentText().trim();
        }
        return targetSection.displayTitle();
    }

    /*
    renderAdjacency：渲染相邻章节关系
    当判定用户在问相邻关系时，调用这个方法：
    最终生成的答案大概长这样：
    目标章节是："3.2 数据清洗"。
它属于："第三章 数据处理"。
上一节："3.1 数据采集"
下一节："3.3 数据转换"
     */
    private String renderAdjacency(GraphQueryResult graphResult) {
        // 使用换行符拼接相邻章节说明，保证多行信息在前端更易读。
        StringJoiner joiner = new StringJoiner("\n");
        // 目标章节是回答的中心，上一节、下一节和父章节都围绕它展开。
        GraphSection targetSection = graphResult.getTargetSection();
        // 父章节用于补充目标章节的层级归属。
        GraphSection parentSection = graphResult.getParentSection();
        // 先写出目标章节标题，帮助用户确认当前定位的是哪一节。
        joiner.add("目标章节是：“" + targetSection.displayTitle() + "”。");
        // 如果父章节存在，就把层级归属也补充进答案。
        if (parentSection != null) {
            // 这句用于回答"属于哪个章节"或辅助理解目标章节上下文。
            joiner.add("它属于：“" + parentSection.displayTitle() + "”。");
        }
        // 输出上一节；没有上一节时，formatSectionOrFallback 会给出统一兜底文案。
        joiner.add("上一节：" + formatSectionOrFallback(graphResult.getPreviousSibling()));
        // 输出下一节；没有下一节时，同样使用统一兜底文案。
        joiner.add("下一节：" + formatSectionOrFallback(graphResult.getNextSibling()));
        // 返回最终拼好的相邻章节说明。
        return joiner.toString();
    }


    /*
    渲染子章节清单
    当判定用户在问子章节展开时，调用这个方法：
    生成的答案大概长这样：
    "第三章 数据处理"包含以下章节：
- 3.1 数据采集
- 3.2 数据清洗
- 3.3 数据转换
- 3.4 数据存储
     */
    private String renderChildren(GraphSection targetSection, List<GraphSection> children) {
        // 使用换行符拼接子章节列表，避免多个章节挤在同一行里。
        StringJoiner joiner = new StringJoiner("\n");
        // 先声明目标章节，告诉用户下面的列表属于哪个章节。
        joiner.add("“" + targetSection.displayTitle() + "”包含以下章节：");
        // 如果没有直接子章节，就明确告诉用户当前节点下没有继续展开的结构。
        if (children == null || children.isEmpty()) {
            // 返回前先补一条空列表说明，避免用户误以为系统漏答。
            joiner.add("未找到直接子章节。");
            // 空列表场景到这里已经完整回答，可以直接返回。
            return joiner.toString();
        }
        // 遍历每个直接子章节，按列表项输出。
        for (GraphSection child : children) {
            // 每个子章节只展示标题，保持结构图直答简洁。
            joiner.add("- " + child.displayTitle());
        }
        // 返回最终拼好的子章节清单。
        return joiner.toString();
    }

    private String formatItem(GraphItem item) {
        if (item == null) {
            return "";
        }
        if (item.getItemIndex() != null) {
            return "第" + item.getItemIndex() + "步：" + StrUtil.blankToDefault(item.displayText(), "");
        }
        return StrUtil.blankToDefault(item.displayText(), "");
    }

    /*
    简单的兜底方法
    如果某个方向没有兄弟章节（比如已经是第一节了，没有"上一节"），就会显示"未找到相邻章节"，而不是报错或者留空。
     */
    private String formatSectionOrFallback(GraphSection section) {
        // 章节为空时返回统一兜底文案；章节存在时用书名号样式包住标题。
        return section == null ? "未找到相邻章节" : "“" + section.displayTitle() + "”";
    }

    /*
    关键词判断方法 没有用正则也没有用NLP 够用就行
     */
    private boolean asksAdjacency(String question) {
        // 通过常见的相邻关系关键词判断用户是不是在问上一节、下一节或层级归属。
        return question.contains("上一节")
            || question.contains("下一节")
            || question.contains("前一节")
            || question.contains("后一节")
            || question.contains("属于哪个章节");
    }

    private boolean asksChildren(String question) {
        // 通过常见的子章节关键词判断用户是不是在问目标章节下面包含哪些内容。
        return question.contains("包含哪些章节")
            || question.contains("都包含哪些章节")
            || question.contains("有哪些小节")
            || question.contains("有哪些章节");
    }
}
