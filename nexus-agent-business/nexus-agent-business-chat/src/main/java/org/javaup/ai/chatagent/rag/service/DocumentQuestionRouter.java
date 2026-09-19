package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.rag.model.ConversationItemAnchor;
import org.javaup.ai.chatagent.rag.model.ConversationStructureAnchor;
import org.javaup.ai.chatagent.rag.model.DocumentNavigationAction;
import org.javaup.ai.chatagent.rag.model.DocumentNavigationDecision;
import org.javaup.ai.chatagent.rag.model.ExecutionMode;
import org.javaup.ai.chatagent.rag.model.RagRewriteResult;
import org.javaup.ai.chatagent.rag.model.RetrievalQuestionPlan;
import org.javaup.ai.chatagent.service.ObservedChatModelService;
import org.javaup.ai.manage.model.graph.GraphSection;
import org.javaup.ai.manage.service.DocumentNavigationIndexService;
import org.javaup.ai.manage.service.DocumentStructureGraphService;
import org.javaup.ai.prompt.PromptTemplateNames;
import org.javaup.ai.prompt.PromptTemplateService;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/*
 * 文档问题路由服务
 * 这个类负责判断：用户的问题该用哪种执行模式来回答。是直接查图结构就够了？还是需要走完整的 RAG 检索？
 */
@Slf4j
@Service
public class DocumentQuestionRouter {

    // 匹配 1.2 / 3.4.5 这类章节编号，用于识别用户给出的结构锚点。
    private static final Pattern SECTION_CODE_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)+)");
    // 匹配“第 3 章 / 第三节 / 第 4 小节”，补齐非小数编号的章节锚点表达。
    private static final Pattern CHINESE_SECTION_REFERENCE_PATTERN = Pattern.compile("第\\s*([0-9一二三四五六七八九十百]+)\\s*(章|节|小节)");
    // 匹配“第几步”，该类问题应交给 GRAPH_THEN_EVIDENCE，而不是 GRAPH_ONLY。
    private static final Pattern STEP_REFERENCE_PATTERN = Pattern.compile("第\\s*([0-9一二三四五六七八九十百]+)\\s*步");
    // 匹配“第几条/点/项/个”，用于保留原有编号项定位能力。
    private static final Pattern ORDINAL_REFERENCE_PATTERN = Pattern.compile("第\\s*([0-9一二三四五六七八九十百]+)\\s*(条|点|项|个)");
    // 匹配用户用引号包住的标题短语，例如“上线观察”，用于结构节点定位。
    private static final Pattern QUOTED_TEXT_PATTERN = Pattern.compile("[“\"']([^”\"']{2,40})[”\"']");
    // 兼容 LLM 偶尔输出代码块或解释文字时，从返回文本里抽取 JSON 对象。
    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("\\{.*}", Pattern.DOTALL);

    // LLM 兜底分类只有达到该阈值，才允许进入 GRAPH_ONLY，低置信统一回退 RAG。
    private static final double GRAPH_ONLY_INTENT_CONFIDENCE_THRESHOLD = 0.75D;

    private static final List<String> ADJACENCY_HINTS = List.of(
        "上一节", "下一节", "前一节", "后一节", "上一章", "下一章", "前一章", "后一章",
        "上章", "下章", "上一个章节", "下一个章节", "属于哪个章节", "章节位置"
    );

    private static final List<String> OUTLINE_HINTS = List.of(
        "包含哪些章节", "都包含哪些章节", "有哪些章节", "有哪些小节", "包含哪些小节", "章节列表", "目录"
    );

    // 强目录展开表达可以直接判定为 outline，比单独的“目录/下面/有哪些”更不容易误伤正文问题。   这些都是完整的短语，语义明确，不会有歧义
    private static final List<String> OUTLINE_EXPLICIT_HINTS = List.of(
        "包含哪些章节", "都包含哪些章节", "有哪些章节", "有哪些小节", "包含哪些小节",
        "章节列表", "小节列表", "子章节", "子小节", "下级章节", "展开目录", "列出目录"
    );

    private static final List<String> ITEM_HINTS = List.of(
        "哪一步", "哪一项", "第几步", "第几项", "具体步骤", "步骤中的"
    );

    // 强分析词基本都要求解释、推理或对比，命中后通常不适合直接图直答。
    private static final List<String> ANALYTIC_STRONG_HINTS = List.of(
        "为什么", "原因", "可能原因", "影响", "区别", "对比", "比较",
        "如何理解", "怎么理解", "说明了什么", "是否意味着", "是否说明", "分析", "解释"
    );

    // “关系/关联”这类词有歧义：可能是内容关系，也可能只是章节前后或上下级关系。
    private static final List<String> ANALYTIC_WEAK_RELATION_HINTS = List.of(
        "关系", "关联", "联系", "相关"
    );

    // 这些短语说明用户问的是目录结构关系，不应被“关系/关联”误判为分析型问题。
    private static final List<String> STRUCTURAL_RELATION_HINTS = List.of(
        "前后关系", "相邻关系", "上下级关系", "父子关系", "目录关系", "章节关系",
        "所属关系", "位置关系", "顺序关系", "属于哪个章节", "上级章节", "下级章节",
        "同级章节", "父章节", "子章节"
    );

    // 这些分析型词会要求模型解释原因、影响或对比，不适合直接用结构图回答。
    private static final List<String> GRAPH_ONLY_BLOCKING_ANALYTIC_HINTS = List.of(
        "为什么", "原因", "可能原因", "影响", "区别", "对比", "比较",
        "如何理解", "怎么理解", "说明了什么", "是否意味着", "是否说明", "分析", "解释"
    );

    // 这些正文诉求词说明用户想看内容证据，而不是只看章节前后或目录层级。    这些词说明用户想看的是正文证据，而不是目录结构。比如"3.2 下面讲了什么"虽然有章节编号，但用户要的是正文内容，不能只给他一个子章节列表。
    private static final List<String> GRAPH_ONLY_CONTENT_HINTS = List.of(
        "内容", "要求", "规定", "流程", "步骤", "处理", "执行",
        "怎么做", "讲了什么", "写了什么", "说了什么"
    );

    // 方向/层级线索用于决定是否值得调用 LLM 兜底分类，其中包含一些较模糊的自然表达。
    private static final List<String> GRAPH_ONLY_DIRECTION_HINTS = List.of(
        "前面", "后面", "上面", "下面", "之前", "之后", "此前", "随后", "后续", "接着", "紧接着",
        "往前", "往后",
        "前一个", "后一个", "上一个", "下一个", "上一", "下一", "相邻", "前后",
        "顺序", "位置", "属于", "上级", "父章节", "同级"
    );

    // 明确相邻关系词才用于本地高置信规则，避免“后面是什么”这类歧义问题被规则过早截断。
    private static final List<String> GRAPH_ONLY_EXPLICIT_ADJACENCY_HINTS = List.of(
        "前一个", "后一个", "上一个", "下一个", "前一", "后一", "上一", "下一", "相邻",
        "前后", "顺序", "位置", "属于", "上级", "父章节", "同级"
    );

    // 这些结构对象词说明问题对象可能是章节、标题、目录节点，而不是普通正文片段。
    private static final List<String> GRAPH_ONLY_STRUCTURE_OBJECT_HINTS = List.of(
        "章节", "小节", "这章", "这节", "这部分", "这一章", "该章", "本章", "标题", "目录", "部分", "模块", "节点", "条目"
    );

    // 这些动作词用于识别“展开目录 / 查看下级章节”类 GRAPH_ONLY 问题。    展开动作列表   这些词单独出现时有歧义（"下面有哪些"可能问正文也可能问目录），所以必须配合结构锚点才能命中。
    private static final List<String> GRAPH_ONLY_OUTLINE_ACTION_HINTS = List.of(
        "下面", "下级", "子章节", "子小节", "子项", "展开", "包含哪些", "包括哪些",
        "有哪些", "列出", "列一下", "组成", "目录"
    );

    // 这些代词本身不够判断结构意图，但可以和方向/层级词一起触发 LLM 兜底分类。
    private static final List<String> GRAPH_ONLY_PRONOUN_ANCHOR_HINTS = List.of(
        "这个", "该", "它", "刚才", "上述", "上面"
    );

    // 当问题问“哪一节/哪个章节”等目标时，歧义方向词可以提升为高置信相邻章节判断。
    private static final List<String> GRAPH_ONLY_ADJACENCY_ANSWER_HINTS = List.of(
        "哪一节", "哪一章", "哪个章节", "哪个小节", "哪个标题", "哪部分", "哪块"
    );

    private final DocumentStructureGraphService graphService;
    private final ObjectProvider<DocumentNavigationIndexService> navigationIndexServiceProvider;
    private final ObservedChatModelService observedChatModelService;
    private final PromptTemplateService promptTemplateService;
    private final ObjectMapper objectMapper;



    public DocumentQuestionRouter(DocumentStructureGraphService graphService,
                                  ObjectProvider<DocumentNavigationIndexService> navigationIndexServiceProvider,
                                  ObservedChatModelService observedChatModelService,
                                  PromptTemplateService promptTemplateService,
                                  ObjectMapper objectMapper) {
        this.graphService = graphService;
        this.navigationIndexServiceProvider = navigationIndexServiceProvider;
        this.observedChatModelService = observedChatModelService;
        this.promptTemplateService = promptTemplateService;
        this.objectMapper = objectMapper;
    }


    /*
    进入 DocumentQuestionRouter.route(...) 方法，第一步是把输入参数整理好
    ——————————————————————————————————————————————————————————————————————————
    这段代码做了四件事：
    1.确定主问题：优先用改写后的问题，没有改写结果就退回原问题
    2.规范化子问题：把改写结果里的子问题列表去重去空，没有子问题时用主问题兜底
    3.构建检索计划：把主问题和子问题打包成 RetrievalQuestionPlan，后续检索引擎会逐个取证
    4.拼接路由文本：原问题和改写问题拼在一起，这样既保留了用户原始措辞（比如"这个后面是啥"），也包含了改写后更清晰的表达
     */
    public DocumentNavigationDecision route(Long documentId,
                                            String originalQuestion,
                                            RagRewriteResult rewriteResult) {//路由决策主入口
        String rewrittenQuestion = firstNonBlank(
            rewriteResult == null ? "" : rewriteResult.getRewrittenQuestion(),
            originalQuestion
        );
        List<String> subQuestions = normalizeSubQuestions(rewriteResult, rewrittenQuestion);
        RetrievalQuestionPlan retrievalPlan = new RetrievalQuestionPlan(rewrittenQuestion, subQuestions);
        // routeText 把原问题和改写后的独立问题合并起来，避免只看改写结果时丢失用户最初的表达习惯。
        String routeText = (safeText(originalQuestion) + " " + rewrittenQuestion).trim();
        /*
        输入准备好之后，紧接着就是整个方法最关键的一步——统一意图识别：
         */
        // 统一意图判断会一次性识别 graphOnly / analytic / outline / item / structureHint，避免多个关键词函数互相打架。
        DocumentQuestionIntentDecision questionIntent = detectQuestionIntent(
            routeText,
            originalQuestion,
            rewrittenQuestion,
            subQuestions
        );
        // GRAPH_ONLY 仍然使用独立结果对象承载动作和原因，便于后续图查询执行器保持原来的输入语义。 从意图识别结果中取出 graphOnlyIntent
        GraphOnlyIntentDecision graphOnlyIntent = questionIntent.graphOnlyIntent();
        // 分析型问题是 item 分支和 graphOnly 排除的重要信号，统一从 questionIntent 中读取。
        boolean analyticQuestion = questionIntent.analytic();



        /*
        第一分支：GRAPH_ONLY — 结构图直答
        进入 GRAPH_ONLY 需要同时满足两个条件：
        graphOnlyIntent.matched() 为 true：统一意图识别明确判定这是一个结构导航问题
        子问题数量 ≤ 1：如果问题被拆成了多个子问题，说明它可能同时包含结构查询和内容查询，直接走图查询会丢掉其他子问题
        满足条件后，调用 resolveSection 定位目标章节，然后用 graphOnlyIntent.action() 作为导航动作。这个 action 可能是：
        SECTION_ADJACENCY_LOOKUP：查相邻章节（"上一节是什么"）
        CHILD_SECTION_DESCEND：展开子章节（"有哪些小节"）
        这里不是简单地用 asksAdjacency || asksOutline 来判断，而是依赖统一意图识别的结果。统一意图识别内部会综合考虑"是否有分析词阻断"、"是否有正文诉求"、"锚点和方向词是否同时出现"等多个因素，判断更精准。
         */
        // 只有当结构导航意图被明确识别，且问题本身没有被拆成多个子问题时，才允许进入 GRAPH_ONLY。  从意图识别结果中取出 graphOnlyIntent
        boolean singleQuestionGraphOnlyMatched = graphOnlyIntent.matched() && subQuestions.size() <= 1;
        if (singleQuestionGraphOnlyMatched) {
            // 解析出目标章节
            GraphSection section = resolveSection(documentId, originalQuestion, rewrittenQuestion);
            // 构建导航决策，执行模式设为 GRAPH_ONLY
            return buildDecision(
                ExecutionMode.GRAPH_ONLY,
                graphOnlyIntent.action(),
                section,
                null,
                retrievalPlan,
                graphOnlyIntent.reason()
            );
        }


        /*
        第二分支：GRAPH_THEN_EVIDENCE — 图定位 + 取证
        这个分支处理的是"第3步讲了什么"、"第二项的具体内容"这类问题。触发条件：
        itemLookupMatched：要么解析出了显式编号（itemIndex != null），要么统一意图识别判定为步骤/条目型问题
        非分析型问题：如果是"第3步为什么要这样做"，虽然提到了编号，但本质是分析型问题，应该走混合检索
        这类问题的执行策略是"先用结构图定位到章节，再去正文里取证据"，所以叫 GRAPH_THEN_EVIDENCE。
         */
        Integer itemIndex = resolveExplicitItemIndex(routeText);
        boolean itemLookupMatched = itemIndex != null || questionIntent.itemLookup();
        boolean shouldUseGraphThenEvidence = itemLookupMatched && !analyticQuestion;
        if (shouldUseGraphThenEvidence) {
            GraphSection section = resolveSection(documentId, originalQuestion, rewrittenQuestion);
            return buildDecision(
                ExecutionMode.GRAPH_THEN_EVIDENCE,
                DocumentNavigationAction.ITEM_REFERENCE,
                section,
                itemIndex,
                retrievalPlan,
                "编号项或步骤型问题走图定位取证"
            );
        }

        /*
        第三分支：RETRIEVAL — 混合检索
        如果前两个分支都没命中，就走混合检索。这部分的 resolveSection 和最终的 buildDecision 调用已经在后面的文档中详细讲解了，这里只简单说一下触发"结构辅助检索"的条件：
         */
        GraphSection assistedSection = null;
        boolean needsStructureAssistedRetrieval = analyticQuestion
            || questionIntent.outline()
            || itemIndex != null
            || questionIntent.structureHint();
        if (needsStructureAssistedRetrieval) {
            assistedSection = resolveSection(documentId, originalQuestion, rewrittenQuestion);
        }


        return buildDecision(
            ExecutionMode.RETRIEVAL,
            itemIndex != null ? DocumentNavigationAction.ITEM_REFERENCE : DocumentNavigationAction.FRESH_TOPIC,
            assistedSection,
            itemIndex,
            retrievalPlan,
            assistedSection == null
                ? "普通文档问题走混合检索"
                : "结构线索仅作为软提示辅助混合检索"
        );
    }


    /*
    buildDecision：构建最终导航决策
    三种执行模式的判定都会最终调用 buildDecision 来构建结果对象：
     */
    private DocumentNavigationDecision buildDecision(ExecutionMode mode,
                                                     DocumentNavigationAction action,
                                                     GraphSection section,
                                                     Integer itemIndex,
                                                     RetrievalQuestionPlan retrievalPlan,
                                                     String reason) {
        // structureAnchor 既可以表示"强结构锚点"（GRAPH），也可以表示"软结构提示"（SOFT），或完全未定位（NONE）。
        ConversationStructureAnchor structureAnchor = section == null
            ? ConversationStructureAnchor.builder().scopeMode(mode == ExecutionMode.RETRIEVAL ? "NONE" : "GRAPH_UNRESOLVED").build()
            : ConversationStructureAnchor.builder()
                .rootSectionCode(section.getNodeCode())
                .rootSectionTitle(section.getTitle())
                .targetSectionHint(section.displayTitle())
                .structureNodeId(section.getNodeId())
                .canonicalPath(section.getCanonicalPath())
                .scopeMode(mode == ExecutionMode.RETRIEVAL ? "SOFT" : "GRAPH")
                .build();
        // itemAnchor 只在条目型问题下存在，用于表示"第 N 步 / 第 N 项"这类编号定位。
        ConversationItemAnchor itemAnchor = itemIndex == null
            ? null
            : ConversationItemAnchor.builder().itemIndex(itemIndex).build();
        // queryHints 会把检索问题关键词、章节线索和条目线索拼在一起，供下游链路感知结构提示。
        List<String> queryHints = buildQueryHints(retrievalPlan, section, itemIndex);
        String summaryText = "mode=" + mode.name()
            + "; reason=" + reason
            + "; section=" + (section == null ? "" : section.displayTitle())
            + "; itemIndex=" + (itemIndex == null ? "" : itemIndex);
        log.info("文档问答路由完成: mode={}, action={}, section='{}', itemIndex={}, reason='{}'",
            mode,
            action,
            section == null ? "" : section.displayTitle(),
            itemIndex,
            reason);
        return DocumentNavigationDecision.builder()
            .navigationAction(action)
            .executionMode(mode)
            .structureAnchor(structureAnchor)
            .itemAnchor(itemAnchor)
            .retrievalPlan(retrievalPlan)
            .summaryText(summaryText)
            .queryContextHints(queryHints)
            .softSectionHints(section == null ? List.of() : List.of(section.displayTitle()))
            .build();
    }

    /**先看方法的前半部分——本地多维度判断：
     * 统一判断当前问题在 route 阶段需要的多个意图维度，避免 route 同时散落多个关键词函数。
     * 这段代码一口气做了五个本地判断，每个判断对应一个维度。注意这里的顺序是有讲究的：item 判断放在最前面，因为"第3步"这类问题如果被误判成目录展开就麻烦了。
     */
    private DocumentQuestionIntentDecision detectQuestionIntent(String routeText,
                                                                String originalQuestion,
                                                                String rewrittenQuestion,
                                                                List<String> subQuestions) {//多维度意图检测总入口
        // 先把输入规整成单个字符串，后续本地规则和 LLM prompt 都基于同一个判断对象。
        String normalized = safeText(routeText);
        // 空问题没有任何稳定意图，直接返回一份全 false 的保守结果。
        if (normalized.isBlank()) {
            return noQuestionIntent("问题为空，跳过路由意图判断。");
        }

        // 本地先判断 item，因为步骤/条目定位应该优先交给 GRAPH_THEN_EVIDENCE，而不是目录展开。
        boolean itemLookup = looksExplicitItemQuestion(normalized);
        // 本地分析型判断已经区分强分析词和弱关系词，避免"前后关系"误伤成 ANALYTIC。
        boolean analyticQuestion = looksAnalyticQuestion(normalized);
        // 本地目录展开判断要求"结构锚点 + 展开动作 + 非正文诉求"，不再只看"目录/有哪些"。
        boolean outlineQuestion = asksOutline(normalized);
        // 正文诉求用于阻断 GRAPH_ONLY，例如"下面有哪些要求/处理步骤/讲了什么"。
        boolean contentQuestion = looksGraphOnlyExcludedContentQuestion(normalized);
        // 结构软提示用于 RETRIEVAL 分支辅助定位章节，不等价于直接图直答。    structureHint：结构软提示判断  structureHint 的作用和 GRAPH_ONLY 不同——它不是说"这个问题可以用结构图直接回答"，而是说"这个问题带有结构线索，走混合检索时可以用这个线索辅助定位章节"。
        boolean structureHint = mentionsStructure(normalized) || hasGraphOnlyAnchor(normalized) || outlineQuestion;
        // 默认先构造一个未命中的 GRAPH_ONLY 结果，后续规则或 LLM 可以覆盖它。
        GraphOnlyIntentDecision graphOnlyIntent = noGraphOnlyIntent("本地规则未命中结构图直答意图。");


        /*
        GRAPH_ONLY 规则引擎的准入控制  本地维度判断完成后，接下来要决定是否启动 GRAPH_ONLY 规则引擎：
         */
        // 多子问题可能同时包含结构查询和内容查询，直接 GRAPH_ONLY 会过早截断其它问题。
        boolean hasMultipleSubQuestions = subQuestions != null && subQuestions.size() > 1;
        // 只有单问题、非 item、非正文诉求、非强分析问题时，才允许尝试 GRAPH_ONLY 本地规则。
        boolean canTryGraphOnlyRules = !hasMultipleSubQuestions
            && !itemLookup
            && !contentQuestion
            && !(analyticQuestion && looksGraphOnlyBlockingAnalyticQuestion(normalized));
        if (canTryGraphOnlyRules) {
            // 第一层仍然用本地规则处理高置信表达，避免每次都调用模型增加延迟和不确定性。
            graphOnlyIntent = detectGraphOnlyIntentByRules(normalized);
        }
        
        if (graphOnlyIntent.matched()) {
            return buildQuestionIntentDecision(
                graphOnlyIntent,
                analyticQuestion,
                outlineQuestion || graphOnlyIntent.action() == DocumentNavigationAction.CHILD_SECTION_DESCEND,
                itemLookup,
                true,
                contentQuestion,
                graphOnlyIntent.confidence(),
                graphOnlyIntent.reason(),
                graphOnlyIntent.source()
            );
        }
        
        DocumentQuestionIntentDecision localDecision = buildQuestionIntentDecision(
            graphOnlyIntent,
            analyticQuestion,
            outlineQuestion,
            itemLookup,
            structureHint,
            contentQuestion,
            0.65D,
            "本地路由意图规则判断完成。",
            "local-rules"
        );
       
        if (!shouldUseLlmQuestionIntent(normalized, subQuestions, localDecision)) {
            return localDecision;
        }
 
        return classifyQuestionIntentWithModel(originalQuestion, rewrittenQuestion, normalized, localDecision);
    }



    /*
    本地规则匹配
    detectQuestionIntent 方法内部会调用 detectGraphOnlyIntentByRules 来做本地规则匹配。这个方法通过一系列关键词和模式组合来判断：
     */
    private GraphOnlyIntentDecision detectGraphOnlyIntentByRules(String question) {//规则检测图结构意图（邻接词、章节号+方向词等）
        // 旧有相邻章节词仍然是最高置信命中，兼容已有"上一节/下一节"问法。  // 规则1：直接命中相邻章节关键词（上一节、下一节、前一章...）
        if (asksAdjacency(question)) {
            return new GraphOnlyIntentDecision(
                true,
                DocumentNavigationAction.SECTION_ADJACENCY_LOOKUP,
                "命中明确相邻章节表达，结构型问题直接走图查询。",
                1.0D,
                "rule-adjacency-hint"
            );
        }
        // 规则2：章节编号（如 1.2、3.4.5）+ 方向词组合
        boolean hasSectionCode = SECTION_CODE_PATTERN.matcher(question).find();
        boolean hasChineseSectionReference = CHINESE_SECTION_REFERENCE_PATTERN.matcher(question).find();
        boolean hasSectionReference = hasSectionCode || hasChineseSectionReference;
        boolean hasExplicitAdjacencyCue = containsAny(question, GRAPH_ONLY_EXPLICIT_ADJACENCY_HINTS);
        boolean hasAdjacencyAnswerTarget = containsAny(question, GRAPH_ONLY_ADJACENCY_ANSWER_HINTS);
        boolean hasAdjacencyIntentCue = hasExplicitAdjacencyCue || hasAdjacencyAnswerTarget;
        boolean sectionReferenceAdjacencyMatched = hasSectionReference && hasAdjacencyIntentCue;
        if (sectionReferenceAdjacencyMatched) {
            return new GraphOnlyIntentDecision(
                true,
                DocumentNavigationAction.SECTION_ADJACENCY_LOOKUP,
                "命中章节编号与方向词组合，结构相邻关系问题走图查询。",
                0.92D,
                "rule-section-code-direction"
            );
        }
        // 规则3：引号标题锚点 + 方向词组合
        boolean hasQuotedTitle = QUOTED_TEXT_PATTERN.matcher(question).find();
        boolean quotedTitleAdjacencyMatched = hasQuotedTitle && hasAdjacencyIntentCue;
        if (quotedTitleAdjacencyMatched) {
            return new GraphOnlyIntentDecision(
                true,
                DocumentNavigationAction.SECTION_ADJACENCY_LOOKUP,
                "命中标题锚点与方向词组合，结构相邻关系问题走图查询。",
                0.9D,
                "rule-quoted-title-direction"
            );
        }
        // 规则4：代词锚点 + 方向词 + 章节答案目标
        if (containsAny(question, GRAPH_ONLY_PRONOUN_ANCHOR_HINTS)
            && containsAny(question, GRAPH_ONLY_DIRECTION_HINTS)
            && containsAny(question, GRAPH_ONLY_ADJACENCY_ANSWER_HINTS)) {
            return new GraphOnlyIntentDecision(
                true,
                DocumentNavigationAction.SECTION_ADJACENCY_LOOKUP,
                "命中指代锚点、方向词和章节答案目标，结构相邻关系问题走图查询。",
                0.88D,
                "rule-pronoun-direction-answer"
            );
        }
        // 规则5：结构对象词 + 方向关系词
        if (containsAny(question, GRAPH_ONLY_STRUCTURE_OBJECT_HINTS)
            && containsAny(question, GRAPH_ONLY_EXPLICIT_ADJACENCY_HINTS)) {
            return new GraphOnlyIntentDecision(
                true,
                DocumentNavigationAction.SECTION_ADJACENCY_LOOKUP,
                "命中结构对象与方向关系组合，结构相邻关系问题走图查询。",
                0.86D,
                "rule-structure-direction"
            );
        }
        // 规则6：明确的目录展开表达（包含哪些章节、有哪些小节...）
        if (asksOutline(question)) {
            return new GraphOnlyIntentDecision(
                true,
                DocumentNavigationAction.CHILD_SECTION_DESCEND,
                "命中明确章节展开表达，结构型问题直接走图查询。",
                1.0D,
                "rule-outline-hint"
            );
        }
        // 规则7：章节锚点 + 目录展开动作
        if (hasGraphOnlyAnchor(question) && containsAny(question, GRAPH_ONLY_OUTLINE_ACTION_HINTS)) {
            return new GraphOnlyIntentDecision(
                true,
                DocumentNavigationAction.CHILD_SECTION_DESCEND,
                "命中章节锚点与目录展开动作，结构型问题直接走图查询。",
                0.86D,
                "rule-outline-action"
            );
        }
        // 所有规则都没命中
        return noGraphOnlyIntent("本地规则未命中结构图直答意图。");
    }


    /*
    本地辅助判断方法详解  步骤/编号项识别
    两层判断：先看有没有步骤类触发词，再看能不能解析出具体编号。满足任一就认为是步骤/编号项问题。
     */
    private boolean looksExplicitItemQuestion(String question) {
        // 先复用现有 item 触发词，覆盖"哪一步/哪一项/第几步"等自然问法。
        if (asksItemLookup(question)) {
            return true;
        }
        // 再复用现有序号解析逻辑，覆盖"第 3 条/第十二项"等显式编号锚点。
        return resolveExplicitItemIndex(question) != null;
    }
    
    private boolean looksGraphOnlyBlockingAnalyticQuestion(String question) {
        return containsAny(question, GRAPH_ONLY_BLOCKING_ANALYTIC_HINTS);
    }

    /*
    正文诉求排除
     */
    private boolean looksGraphOnlyExcludedContentQuestion(String question) {
        // "有哪些小节/哪些章节"是结构展开；"有哪些要求/内容/步骤"才是正文或编号项问题。
        return containsAny(question, GRAPH_ONLY_CONTENT_HINTS);
    }


    /*
    统一结果构造
    不管是本地规则命中、本地规则未命中直接返回、还是 LLM 兜底返回，最终都通过这个方法构造统一的结果对象
    这个方法做了两个派生处理：
    1.effectiveStructureHint：如果 GRAPH_ONLY 命中了，structureHint 自动为 true。这样即使最终因为其他原因没走 GRAPH_ONLY（比如多子问题），RETRIEVAL 分支也能利用这个结构线索做软提示
    2.graphOnlyOutline：如果 GRAPH_ONLY 的动作是 CHILD_SECTION_DESCEND，outline 自动为 true。保证语义一致性——目录展开动作和 outline 标记不会矛盾
     */
    private DocumentQuestionIntentDecision buildQuestionIntentDecision(GraphOnlyIntentDecision graphOnlyIntent,
                                                                       boolean analytic,
                                                                       boolean outline,
                                                                       boolean itemLookup,
                                                                       boolean structureHint,
                                                                       boolean contentQuestion,
                                                                       double confidence,
                                                                       String reason,
                                                                       String source) {
        // GRAPH_ONLY 命中本身就是强结构线索，后续 retrieval 分支也可以复用这个判断结果。
        boolean effectiveStructureHint = structureHint || (graphOnlyIntent != null && graphOnlyIntent.matched());
        // CHILD_SECTION_DESCEND 对应目录展开，即使本地 outline 没命中，也要把 outline 标出来。
        boolean graphOnlyOutline = graphOnlyIntent != null
            && graphOnlyIntent.action() == DocumentNavigationAction.CHILD_SECTION_DESCEND;
        return new DocumentQuestionIntentDecision(
            graphOnlyIntent == null ? noGraphOnlyIntent("未提供 GRAPH_ONLY 判断结果。") : graphOnlyIntent,
            analytic,
            outline || graphOnlyOutline,
            itemLookup,
            effectiveStructureHint,
            contentQuestion,
            confidence,
            StrUtil.blankToDefault(reason, ""),
            StrUtil.blankToDefault(source, "")
        );
    }


    /*
    要不要调 LLM？
    不是所有问题都值得多调一次模型。LLM 兜底只服务于"有结构导航线索但本地规则搞不定"的模糊场景，普通内容问答不应该为此多等一次网络请求
    整个方法的逻辑可以概括为：先排除不需要的，再确认需要的。_________________前三个 if 是排除逻辑，命中任何一个都直接返回 false：
     */
    private boolean shouldUseLlmQuestionIntent(String question,
                                               List<String> subQuestions,
                                               DocumentQuestionIntentDecision localDecision) {//判断是否需要 LLM 兜底分类
        // 多子问题通常需要混合检索逐个取证，LLM 在这里把它改成 GRAPH_ONLY 的收益很低。
        boolean hasMultipleSubQuestions = subQuestions != null && subQuestions.size() > 1;
        if (hasMultipleSubQuestions) {
            return false;
        }
        // 本地已经识别成步骤/条目或正文内容诉求时，优先走取证/检索，不再让模型重新猜结构图直答。
        boolean localDecisionAlreadyEvidenceBased = localDecision.itemLookup() || localDecision.contentQuestion();
        if (localDecisionAlreadyEvidenceBased) {
            return false;
        }
        // 强分析问题已经很明确需要内容解释，不需要为了图直答再额外调用模型。
        boolean localDecisionIsStrongAnalytic = localDecision.analytic() && looksGraphOnlyBlockingAnalyticQuestion(question);
        if (localDecisionIsStrongAnalytic) {
            return false;
        }


        // 有结构锚点且有方向/层级动作时，说明本地规则可能漏掉了自然表达，值得让 LLM 做兜底。  触发条件：条件一：有结构锚点 + 有导航线索
        /*
        这说明问题里同时出现了"结构锚点"和"导航动作"，但本地规则没能命中（可能是因为表达方式比较自然，不在规则的关键词列表里）。比如"这一块往后还有啥内容"——有指代锚点（"这一块"）、有方向词（"往后"），但不在规则的精确匹配范围内。
         */
        boolean hasStructuralNavigationClue = hasGraphOnlyAnchor(question) && hasGraphOnlyNavigationCue(question);
        if (hasStructuralNavigationClue) {
            return true;
        }
        // "关系/关联"这类弱分析词如果伴随结构线索，可能是在问章节关系，也值得让 LLM 裁决。
        return containsAny(question, ANALYTIC_WEAK_RELATION_HINTS)
            && localDecision.structureHint();
    }


    /*
    结构锚点检测

     */
    private boolean hasGraphOnlyAnchor(String question) {
        // "3.2 / 4.1.1"这类章节号是最强锚点。
        if (SECTION_CODE_PATTERN.matcher(question).find()) {
            return true;
        }
        // "第 3 章 / 第 2 节 / 第 4 小节"同样是明确的章节锚点。
        if (CHINESE_SECTION_REFERENCE_PATTERN.matcher(question).find()) {
            return true;
        }
        // "某某章节"用引号包住时，通常是在提供标题锚点。
        if (QUOTED_TEXT_PATTERN.matcher(question).find()) {
            return true;
        }
        // 明确出现章节、标题、目录、部分等结构对象，也足以触发结构意图判断。
        if (containsAny(question, GRAPH_ONLY_STRUCTURE_OBJECT_HINTS)) {
            return true;
        }
        // "这个/该/它/刚才"只有配合方向或层级动作才会进入 LLM，这里只标记它具备指代锚点。
        return containsAny(question, GRAPH_ONLY_PRONOUN_ANCHOR_HINTS);
    }

    /*
    hasGraphOnlyAnchor 检查五种锚点（章节编号、中文章节引用、引号标题、结构对象词、指代词），hasGraphOnlyNavigationCue 检查两种动作（方向词、展开动作词）。两者同时存在，就说明有结构导航意图，值得让 LLM 做最终裁决。
     */
    private boolean hasGraphOnlyNavigationCue(String question) {
        // 相邻关系动作覆盖"前面/后面/之前/之后/相邻/位置/顺序"等表达。
        if (containsAny(question, GRAPH_ONLY_DIRECTION_HINTS)) {
            return true;
        }
        // 目录展开动作覆盖"下面/下级/子章节/展开/包含哪些/有哪些"等表达。
        return containsAny(question, GRAPH_ONLY_OUTLINE_ACTION_HINTS);
    }


    /*
    这段代码的流程很清晰：
    1.渲染 prompt：用模板引擎把原问题、改写问题、路由文本填入 prompt 模板
    2.调用模型：用低温参数调用 LLM，让它输出结构化的 JSON 分类结果
    3.解析结果：把模型输出解析成 DocumentQuestionIntentDecision
    4.异常回退：如果调用失败或解析失败，直接用本地规则的结论兜底
    关键设计决策
    LLM 兜底是可降级的——它失败了不会影响主链路。这是因为本地规则已经给出了一个保守但安全的结论，LLM 只是在此基础上尝试做更精准的判断。如果 LLM 挂了，用本地结论也不会出大问题，最多就是一些模糊问题走了混合检索而不是图直答。
     */
    private DocumentQuestionIntentDecision classifyQuestionIntentWithModel(String originalQuestion,
                                                                           String rewrittenQuestion,
                                                                           String routeText,
                                                                           DocumentQuestionIntentDecision localDecision) {//调用 LLM 做意图分类（置信度 ≥ 0.75 才接受）
        try {
            // prompt 模板仍然复用 document-graph-only-intent，只是扩展为路由意图 JSON，避免新增第二次 LLM 调用。
            String prompt = promptTemplateService.render(PromptTemplateNames.DOCUMENT_GRAPH_ONLY_INTENT, Map.of(
                "originalQuestion", StrUtil.blankToDefault(originalQuestion, ""),
                "rewrittenQuestion", StrUtil.blankToDefault(rewrittenQuestion, ""),
                "routeText", StrUtil.blankToDefault(routeText, "")
            ));
            // document_question_intent 是 route 阶段的轻量分类标签；这里不传 traceRecorder，避免扩大 route 方法签名
            String raw = observedChatModelService.callText(
                "document_question_intent",
                null,
                prompt,
                buildGraphOnlyIntentCallOptions(),
                null
            );
            // 模型输出必须解析成结构化结果，解析失败会回退 localDecision。
            DocumentQuestionIntentDecision decision = parseQuestionIntentResult(raw, localDecision);
            log.info("文档路由 LLM 兜底判断完成: graphOnly={}, action={}, analytic={}, outline={}, itemLookup={}, structureHint={}, confidence={}, source={}, reason={}, raw={}",
                decision.graphOnlyIntent().matched(),
                decision.graphOnlyIntent().action(),
                decision.analytic(),
                decision.outline(),
                decision.itemLookup(),
                decision.structureHint(),
                decision.confidence(),
                decision.source(),
                decision.reason(),
                StrUtil.blankToDefault(raw, ""));
            return decision;
        }
        catch (Exception exception) {
            // LLM 只是兜底分类器，失败时不能影响主链路，直接使用本地规则结果更安全。
            log.warn("文档路由 LLM 兜底判断失败，回退本地路由意图: question='{}', message={}",
                routeText,
                exception.getMessage());
            return localDecision;
        }
    }


    /*
    模型调用参数
    三个参数的设置意图：
    temperature(0.0)：温度设为 0，让输出尽量确定性，同一个问题每次分类结果一致
    topP(0.1)：进一步收窄采样范围，减少随机性
    thinking: false：关闭思维链，这里只需要一个简短的 JSON 分类结果，不需要模型展示推理过程
         */
    private ChatOptions buildGraphOnlyIntentCallOptions() {
        // 使用 OpenAI 兼容 options，与项目现有 rewrite 阶段的模型参数覆盖方式保持一致。
        return OpenAiChatOptions.builder()
            .temperature(0.0D)
            .topP(0.1D)
            .extraBody(Map.of("thinking", false))
            .build();
    }



    /*
    parseQuestionIntentResult：解析 LLM 输出
    模型返回的是一段 JSON 文本，需要解析成结构化的意图决策对象。这个方法是整个 LLM 兜底链路里最复杂的一环，因为它要处理各种边界情况：模型输出格式不标准、置信度不够高、字段缺失等。
     */
    private DocumentQuestionIntentDecision parseQuestionIntentResult(String raw,
                                                                     DocumentQuestionIntentDecision localDecision) {
        if (StrUtil.isBlank(raw)) {
            return localDecision;
        }
        try {
            // extractJsonObject 兼容模型偶尔包裹 ```json 的情况，保证 ObjectMapper 只读 JSON 对象。
            JsonNode root = objectMapper.readTree(extractJsonObject(raw));
            // intent_type 是模型给出的主分类，用来和 graph_only / action / 多个布尔意图交叉校验。
            String intentType = root.path("intent_type").asText("").trim().toUpperCase(Locale.ROOT);

            /*
            第二步：置信度阈值控制
            这是一道硬门槛：模型输出的置信度必须 ≥ 0.75 才能覆盖本地规则。低于这个阈值说明模型自己也不太确定，这时候用本地规则的保守结论更安全。
             */
            // confidence 支持 0-1 或 0-100 两种输出，统一归一化到 0-1。
            double confidence = normalizeConfidence(root.path("confidence").asDouble(0D));
            // 低置信输出不能覆盖本地规则，避免模型犹豫时把明确的检索问题误导到其它分支。
            if (confidence < GRAPH_ONLY_INTENT_CONFIDENCE_THRESHOLD) {
                return localDecision;
            }


            // reason 会写入 routing summary，方便观察为什么某个问题进入某类路由。
            String reason = StrUtil.blankToDefault(root.path("reason").asText(""), "LLM 判定完成。");
            boolean graphOnly = root.path("graph_only").asBoolean(false);
            DocumentNavigationAction action = resolveModelGraphOnlyAction(root.path("action").asText(""), intentType);
            boolean analytic = root.path("analytic").asBoolean("ANALYTIC".equals(intentType));
            boolean outline = root.path("outline").asBoolean("OUTLINE".equals(intentType));
            boolean itemLookup = root.path("item_lookup").asBoolean("ITEM_LOOKUP".equals(intentType));
            boolean contentQuestion = root.path("content_qa").asBoolean("CONTENT_QA".equals(intentType));
            boolean structureHint = root.path("structure_hint").asBoolean(
                localDecision.structureHint() || "ADJACENCY".equals(intentType) || "OUTLINE".equals(intentType)
            );


            /*
            第四步：GRAPH_ONLY 准入校验
            即使模型说了 graph_only: true，还要满足两个额外条件才能真正进入 GRAPH_ONLY：
            1.action 必须可执行：resolveModelGraphOnlyAction 必须返回非 null 的动作
            2.intent_type 必须是 ADJACENCY 或 OUTLINE：其他类型（ITEM_LOOKUP、CONTENT_QA、ANALYTIC）不允许走图直答
            这是一道安全阀——防止模型在不确定时随意把问题导向图直答。
             */
            // 只有 ADJACENCY / OUTLINE 且 action 可执行时，模型结果才允许进入 GRAPH_ONLY。
            boolean modelGraphOnlyAccepted = graphOnly && action != null
                && ("ADJACENCY".equals(intentType) || "OUTLINE".equals(intentType));
            GraphOnlyIntentDecision graphOnlyIntent = modelGraphOnlyAccepted
                ? new GraphOnlyIntentDecision(
                    true,
                    action,
                    "LLM 兜底判定为结构图直答: " + reason,
                    confidence,
                    "llm-" + intentType
                )
                : noGraphOnlyIntent("LLM 判定不适合结构图直答: " + reason);
            /*
            第五步：构建最终结果
            把所有解析出来的维度组装成统一的 DocumentQuestionIntentDecision 返回。source 字段标记为 "llm-ADJACENCY" 或 "llm-OUTLINE" 等，方便后续追踪是哪个环节做出的判断。
             */
            return buildQuestionIntentDecision(
                graphOnlyIntent,
                analytic,
                outline,
                itemLookup,
                structureHint,
                contentQuestion,
                confidence,
                "LLM 兜底路由意图判断: " + reason,
                "llm-" + intentType
            );
        }
        catch (Exception exception) {
            log.warn("解析文档路由 LLM 输出失败: raw='{}', message={}", raw, exception.getMessage());
            return localDecision;
        }
    }



    /*
    动作映射
    这个方法做的是"模型输出 → 系统枚举"的映射。优先用模型给的 action 字段，如果没给就用 intent_type 推断。只有两种动作是合法的：
    ADJACENCY → SECTION_ADJACENCY_LOOKUP（查相邻章节）
    OUTLINE → CHILD_SECTION_DESCEND（展开子章节）
    其他所有情况返回 null，会阻断 GRAPH_ONLY 的准入。
     */
    private DocumentNavigationAction resolveModelGraphOnlyAction(String rawAction, String intentType) {
        // 优先相信 action 字段，因为它直接对应当前代码里的 DocumentNavigationAction。
        String action = StrUtil.blankToDefault(rawAction, "").trim().toUpperCase(Locale.ROOT);
        if (DocumentNavigationAction.SECTION_ADJACENCY_LOOKUP.name().equals(action)) {
            return DocumentNavigationAction.SECTION_ADJACENCY_LOOKUP;
        }
        if (DocumentNavigationAction.CHILD_SECTION_DESCEND.name().equals(action)) {
            return DocumentNavigationAction.CHILD_SECTION_DESCEND;
        }
        // 如果 action 缺失但 intent_type 明确，也允许由意图类型映射到动作。
        if ("ADJACENCY".equals(intentType)) {
            return DocumentNavigationAction.SECTION_ADJACENCY_LOOKUP;
        }
        if ("OUTLINE".equals(intentType)) {
            return DocumentNavigationAction.CHILD_SECTION_DESCEND;
        }
        // ITEM_LOOKUP / CONTENT_QA / ANALYTIC / UNKNOWN 都不是 GRAPH_ONLY 动作。
        return null;
    }


    /*
    第一步：提取 JSON 对象
    模型有时候会在 JSON 前后加上 ```json 代码块标记或者解释文字，这个方法用正则 \{.*\} 把纯 JSON 对象提取出来
     */
    private String extractJsonObject(String raw) {
        // 正常情况下 prompt 已要求只返回 JSON；这里是为了防御模型偶发的格式漂移。
        Matcher matcher = JSON_OBJECT_PATTERN.matcher(raw.trim());
        if (matcher.find()) {
            return matcher.group();
        }
        return raw.trim();
    }

    /*
    normalizeConfidence 处理了模型输出格式不统一的问题：
    有的模型输出 0.86，有的输出 86，统一归一化到 0-1 区间。
     */
    private double normalizeConfidence(double confidence) {
        // 有些模型可能输出 86 表示 86%，这里统一折算成 0.86。
        if (confidence > 1D) {
            return confidence / 100D;
        }
        return confidence;
    }
    
    private boolean containsAny(String text, List<String> candidates) {
        String normalized = safeText(text);
        if (normalized.isBlank() || candidates == null || candidates.isEmpty()) {
            return false;
        }
        return candidates.stream()
            .filter(StrUtil::isNotBlank)
            .anyMatch(normalized::contains);
    }
    
    private GraphOnlyIntentDecision noGraphOnlyIntent(String reason) {
        return new GraphOnlyIntentDecision(false, null, StrUtil.blankToDefault(reason, ""), 0D, "none");
    }
    
    private DocumentQuestionIntentDecision noQuestionIntent(String reason) {
        return buildQuestionIntentDecision(
            noGraphOnlyIntent(reason),
            false,
            false,
            false,
            false,
            false,
            0D,
            reason,
            "none"
        );
    }



    /**章节解析总入口          不管是哪种执行模式，只要需要定位章节，都会走 resolveSection 这个方法：
     * 解析问题对应的目标章节。
     * 这是“章节解析总入口”，但它自己并不做具体匹配，而是按可靠性从高到低依次尝试三种实现路径：
     *
     * 1. `resolveBySectionCode(...)`
     *    如果问题里显式出现了类似 `1.2.3` 这样的章节编号，就按编号直接精确查找；
     *    这是最强、最可靠的结构信号。
     *
     * 2. `resolveByNavigationIndex(...)`
     *    如果没有显式章节编号，就走专门的导航索引检索；
     *    这一步适合处理“开户流程属于哪个章节”“异常处理在哪一节”之类的结构语义问题。
     *
     * 3. `resolveByLocalStructure(...)`
     *    如果索引也没有命中，就退回到本地章节树做模糊文本匹配；
     *    这一步更像“弱结构兜底”，通过短语和章节标题/路径/正文去估计最可能的章节。
     *
     * 如果以上三条路径都没有稳定命中，则最后退回到 `graphService.findBestSection(...)` 做 best effort 兜底。
     *
     * @param documentId 文档 id
     * @param originalQuestion 原始问题
     * @param rewrittenQuestion 改写问题
     * @return 命中的章节；若无法稳定定位，则返回兜底章节或 null
     */
    private GraphSection resolveSection(Long documentId, String originalQuestion, String rewrittenQuestion) {//定位目标章节（按优先级：章节号→导航索引→本地匹配→最佳匹配）
        if (documentId == null) {
            // 没有文档 id 时，不存在结构图可定位的前提条件。
            return null;
        }
        // 第一层：显式章节编号命中最可靠，因此优先尝试。
        GraphSection byCode = resolveBySectionCode(documentId, originalQuestion, rewrittenQuestion);
        if (byCode != null) {
            return byCode;
        }

        // 第二层：如果没有显式编号，再尝试走导航索引。
        GraphSection indexedMatch = resolveByNavigationIndex(documentId, originalQuestion, rewrittenQuestion);
        if (indexedMatch != null) {
            return indexedMatch;
        }
        // 第三层：再退回到本地结构字段模糊匹配。
        List<String> phrases = buildSectionPhrases(originalQuestion, rewrittenQuestion);
        GraphSection localMatch = resolveByLocalStructure(documentId, phrases);
        if (localMatch != null) {
            return localMatch;
        }

        /*
        兜底层：findBestSection
        前三层都没命中时，最后调用 graphService.findBestSection(documentId, rewrittenQuestion, "") 做 best effort 兜底。
        注意这里传的参数：topic 是改写问题，facet 是空字符串——没有结构视角限制，尽可能宽泛地找一个最相关的章节。
         */
        // 最后让 graphService 自己给一个 best effort 的章节兜底结果。
        return graphService.findBestSection(documentId, rewrittenQuestion, "");
    }



    /**   第一层：显式章节编号精确命中
     * 第一种实现：尝试从问题中直接解析章节编号。
     * 适合处理类似下面这种问题：
     * 1. “1.2.3 章节讲了什么”
     * 2. “3.1.4 这一节属于哪个模块”
     * 3. “在 2.4 节里提到的步骤是什么”
     * ——————————————————————————————————————————————————
     * 用正则 (\d+(?:\.\d+)+) 扫描问题文本，匹配"1.2"、"3.1.4"这类章节编号。同时扫描原问题和改写问题，是因为改写阶段可能会补出结构编号（比如用户说"第三章第一节"，改写后变成"3.1"）。
     *
     * 这类问题最大的特点是：用户已经显式给出了章节 code，
     * 因此不需要再做语义猜测，直接按编号查图结构是最稳的。
     *
     * @param documentId 文档 id
     * @param originalQuestion 原始问题
     * @param rewrittenQuestion 改写问题
     * @return 命中的章节；若没有显式章节编号则返回 null
     */
    private GraphSection resolveBySectionCode(Long documentId, String originalQuestion, String rewrittenQuestion) {
        Matcher matcher = SECTION_CODE_PATTERN.matcher((safeText(originalQuestion) + " " + safeText(rewrittenQuestion)).trim());
        while (matcher.find()) {
            // 只要问题里出现像“1.2.3”这样的章节编号，就直接按编号精确查找。
            // 这里同时扫描原问题与改写问题，避免改写阶段补出的结构编号被漏掉。
            GraphSection section = graphService.findSectionByCode(documentId, matcher.group(1));
            if (section != null) {
                return section;
            }
        }
        return null;
    }

    /**第三层：本地结构模糊匹配
     * 第三种实现：基于本地结构数据做模糊章节匹配。
     * 这是当“显式章节编号”和“导航索引命中”都失败后的本地兜底路径。
     * ————————————————————————————————————————————————————————————————
     * 这一层先从问题中提炼出短语列表（buildSectionPhrases），再对文档的所有章节逐个打分，取分值最高且 ≥ 45 的章节。
     *
     * 它依赖 `buildSectionPhrases(...)` 提炼出的短语，
     * 再与章节的 path/title/anchor/content 做层级化打分，找出最可能相关的章节。
     *
     * @param documentId 文档 id
     * @param phrases 章节短语候选
     * @return 匹配到的章节
     */
    private GraphSection resolveByLocalStructure(Long documentId, List<String> phrases) {
        if (phrases.isEmpty()) {
            return null;
        }
        List<GraphSection> sections = graphService.listSections(documentId);//graphService.listSections(documentId) 的实现和前面讲过的一样：同样走 CompositeDocumentStructureGraphService 选路，Neo4j 可用时用 Cypher 查图数据库，否则回退到 MySQL 全量加载所有 SECTION 类型节点。这里拿到的是文档的完整章节列表，后续对每个章节逐个打分。
        if (sections == null || sections.isEmpty()) {
            return null;
        }
        return sections.stream()
                // 对每个章节打一个本地结构匹配分，再取最高分章节。
                // 这里不是简单 contains，而是综合 path/title/anchor/content 四层信号。
            .map(section -> new SectionScore(section, scoreSection(section, phrases)))
            .filter(score -> score.score() >= 45D)
            .max(Comparator.comparingDouble(SectionScore::score))
            .map(SectionScore::section)
            .orElse(null);
    }



    /*
    第二层：导航索引检索
    第二种实现：通过导航索引检索章节。
     * 这一步介于”显式章节编号精确命中”和”本地结构模糊兜底”之间，
     * 适合处理带明显结构语义、但用户没有直接给章节编号的问题。

     这一层走的是 Elasticsearch 导航索引，适合处理”开户流程属于哪个章节”这类带结构语义但没有显式编号的问题。
         */
    private GraphSection resolveByNavigationIndex(Long documentId, String originalQuestion, String rewrittenQuestion) {
        DocumentNavigationIndexService navigationIndexService = navigationIndexServiceProvider.getIfAvailable();
        if (navigationIndexService == null) {
            // 没有导航索引服务时，直接跳过这一层解析。
            return null;
        }
        // 优先使用改写问题，因为它通常比原问题更适合做检索；若没有改写结果则退回原问题。
        String query = firstNonBlank(rewrittenQuestion, originalQuestion);
        List<DocumentNavigationIndexService.NavigationSectionHit> hits = navigationIndexService.searchSections(
            documentId,
            query,
                // facet 会帮助索引更偏向”章节位置 / 章节树 / 步骤”中的某一种结构理解。
            detectFacet(query),
            "",
            query,
            5
        );
        if (hits == null || hits.isEmpty()) {
            return null;
        }
        // 当前策略只取 top1 hit，再回查结构图中的真实节点对象。  回到 resolveByNavigationIndex，拿到 hits 之后只取 top1，再用 nodeId 回查结构图拿到完整的 GraphSection 对象：
        return graphService.findSectionById(documentId, hits.get(0).nodeId());
    }



    /*
    按优先级分四层

     */
    private double scoreSection(GraphSection section, List<String> phrases) {
        String title = normalize(section.getTitle());
        String path = normalize(section.getSectionPath());
        String anchor = normalize(section.getAnchorText());
        String content = normalize(section.getContentText());
        double best = 0D;
        for (String phrase : phrases) {
            String normalized = normalize(phrase);
            if (normalized.length() < 2) {
                continue;
            }
            if (path.contains(normalized)) {
                // canonical path 命中最稳定，因此分值最高。
                best = Math.max(best, 100D + normalized.length());
            }
            if (title.contains(normalized)) {
                // 标题次之，通常也很强。
                best = Math.max(best, 90D + normalized.length());
            }
            if (anchor.contains(normalized)) {
                // anchor 是较弱但仍有价值的结构提示。
                best = Math.max(best, 80D + normalized.length());
            }
            if (content.contains(normalized)) {
                // 正文命中最弱，只作为兜底结构线索。
                best = Math.max(best, 45D + Math.min(normalized.length(), 20));
            }
        }
        return best;
    }



    /**章节短语提炼
     * 在进入第三层之前，先来看 buildSectionPhrases 是怎么从问题里提炼出短语的。这个方法是第三层本地匹配的"原料准备"步骤，提炼出的短语质量直接决定匹配效果。
     * 从原问题和改写问题中提炼章节匹配短语。
     * 这些短语主要服务于 `resolveByLocalStructure(...)`，
     * 用来和章节标题、路径、锚点、正文做模糊比对。
     */
    private List<String> buildSectionPhrases(String originalQuestion, String rewrittenQuestion) {
        LinkedHashSet<String> phrases = new LinkedHashSet<>();
       /*
       第一步：整句清洗
       把原问题和改写问题整句做清洗后加入。cleanPhrase 会去掉"请问/帮我/这个/章节/小节/是什么/有哪些"等口语化噪声词，只保留更像章节主题的部分。
        比如"请问开户流程属于哪个章节" → 清洗后变成"开户流程属于哪个"（再经过后续 addTextBefore 进一步截取）。
        */
        // 先直接加入原问题和改写问题的清洗版本，保留最原始的章节线索。
        addCleanPhrase(phrases, originalQuestion);
        addCleanPhrase(phrases, rewrittenQuestion);
        /*
        第二步：引导短语提取
        用正则 ["\"']([^"\"']{2,40})["\"'] 提取引号内的内容。引号里的词通常是用户显式强调的章节主题词，比如 "开户流程" 或 "异常处理"，优先级很高。
         */
        // 再把引号中的显式短语提取出来，例如"开户流程""异常处理"这类强结构词。
        addQuotedPhrases(phrases, originalQuestion);
        addQuotedPhrases(phrases, rewrittenQuestion);
       /*
       第三步：结构提示词前缀截取 addTextBefore
        */
        for (String marker : ADJACENCY_HINTS) {
            // 对"上一节/下一节/属于哪个章节"类问题，截取提示词之前的部分作为章节候选短语。
            addTextBefore(phrases, originalQuestion, marker);
            addTextBefore(phrases, rewrittenQuestion, marker);
        }
        for (String marker : OUTLINE_HINTS) {
            // 对提纲型问题，同样截取结构提示词之前的主语短语。
            addTextBefore(phrases, originalQuestion, marker);
            addTextBefore(phrases, rewrittenQuestion, marker);
        }
        /*
        第四步：步骤引用前缀截取
        对"第 N 步"这类步骤引用，截取它前面的文本。比如"开户流程的第三步是什么" → 截取"第三步"前面的"开户流程的" → 清洗后得到"开户流程"。
        最终对所有短语过滤掉长度 < 2 的，最多保留 8 条。
         */
        Matcher stepMatcher = STEP_REFERENCE_PATTERN.matcher(safeText(originalQuestion) + " " + safeText(rewrittenQuestion));
        while (stepMatcher.find()) {
            String all = stepMatcher.group();
            // "第 N 步"前面的文本往往是所属章节线索，也纳入候选短语。
            addTextBefore(phrases, originalQuestion, all);
            addTextBefore(phrases, rewrittenQuestion, all);
        }
        return new ArrayList<>(phrases).stream()
            .filter(item -> normalize(item).length() >= 2)
            .limit(8)
            .toList();
    }


    /*
    第三步：结构提示词前缀截取
     */
    private void addTextBefore(LinkedHashSet<String> phrases, String text, String marker) {
        String normalized = safeText(text);
        if (normalized.isBlank() || marker == null || marker.isBlank()) {
            return;
        }
        int index = normalized.indexOf(marker);
        if (index > 0) {
            // 只截取 marker 之前的前缀，因为它更可能是真正的章节主题词。
            addCleanPhrase(phrases, normalized.substring(0, index));
        }
    }

    private void addQuotedPhrases(LinkedHashSet<String> phrases, String text) {
        Matcher matcher = QUOTED_TEXT_PATTERN.matcher(safeText(text));
        while (matcher.find()) {
            addCleanPhrase(phrases, matcher.group(1));
        }
    }

    private void addCleanPhrase(LinkedHashSet<String> phrases, String text) {
        String cleaned = cleanPhrase(text);
        if (StrUtil.isNotBlank(cleaned)) {
            phrases.add(cleaned);
        }
    }

    private String cleanPhrase(String text) {
        return safeText(text)
            .replace("刚才说的", "")
            .replace("请问", "")
            .replace("帮我", "")
            .replace("这个", "")
            .replace("那个", "")
            .replace("所属的具体章节", "")
            .replace("所属章节", "")
            .replace("具体章节", "")
            .replace("章节", "")
            .replace("小节", "")
            .replace("目录", "")
            .replace("上一节", "")
            .replace("下一节", "")
            .replace("分别是什么", "")
            .replace("是什么", "")
            .replace("有哪些", "")
            .replace("都有哪些", "")
            .replace("包含哪些", "")
            .replace("中的", "")
            .replace("里面的", "")
            .replace("里的", "")
            .replace("中", "")
            .replace("“", "")
            .replace("”", "")
            .replace("?", "")
            .replace("？", "")
            .trim();
    }

    private boolean asksAdjacency(String question) {
        return ADJACENCY_HINTS.stream().anyMatch(question::contains);
    }


    /*
    目录展开识别
    asksOutline 的判断不是简单地看有没有"目录/有哪些章节"，而是加了两道防线来避免误判。比如"下面有哪些处理步骤"问的是正文内容，不是目录结构。
    具体的两道防线：

1.正文诉求排除：先检查是否包含"内容/要求/流程/步骤/怎么做"等正文诉求词，有的话直接排除
2.强弱表达区分：
    强表达（"包含哪些章节/展开目录/列出目录"）→ 直接命中
    弱表达（"下面/有哪些"）→ 必须同时有结构锚点才能命中
     */
    private boolean asksOutline(String question) {
        String normalized = safeText(question);
        // 空文本没有可判断的目录展开意图。
        if (normalized.isBlank()) {
            return false;
        }
        // "下面有哪些处理步骤/有什么要求/讲了什么"问的是正文证据，不是目录展开。
        if (looksGraphOnlyExcludedContentQuestion(normalized)) {
            return false;
        }
        // "有哪些章节/包含哪些小节/展开目录"这类完整短语，是高置信目录展开表达。
        boolean hasExplicitOutlineExpression = containsAny(normalized, OUTLINE_EXPLICIT_HINTS);
        // 结构锚点确保"下面/有哪些"指向章节或目录，而不是普通正文描述。
        boolean hasStructureAnchor = hasGraphOnlyAnchor(normalized);
        // 展开动作覆盖"下面/下级/子章节/列出/包含哪些"等自然表达。
        boolean hasOutlineAction = containsAny(normalized, GRAPH_ONLY_OUTLINE_ACTION_HINTS);
        // 强表达可直接命中；弱表达必须同时有结构锚点和展开动作，降低误判。
        return hasExplicitOutlineExpression || (hasStructureAnchor && hasOutlineAction);
    }

    /*
    逻辑很简单：只要问题里包含"哪一步/哪一项/第几步/第几项/具体步骤/步骤中的"任意一个，就认为用户在问步骤或条目。
     */
    private boolean asksItemLookup(String question) {
        return ITEM_HINTS.stream().anyMatch(question::contains);
    }


    /*
    分析型问题识别
    这个方法的判断逻辑比较精细，区分了强分析词和弱关系词，避免"前后关系"这类结构查询被误伤成分析型问题。
    具体分成了两层：
    强分析词（为什么、原因、区别、对比、解释……）：直接判定为分析型，没有歧义
    弱关系词（关系、关联、联系、相关）：需要进一步判断是"内容语义关系"还是"章节结构关系"
     */
    private boolean looksAnalyticQuestion(String question) {
        String normalized = safeText(question);
        // 空文本不具备分析型意图。
        if (normalized.isBlank()) {
            return false;
        }
        // 强分析词通常要求解释、原因、影响、对比或推理，直接判定为分析型问题。
        if (containsAny(normalized, ANALYTIC_STRONG_HINTS)) {
            return true;
        }
        // 没有"关系/关联/联系/相关"这类弱分析词时，就不需要继续判断。
        if (!containsAny(normalized, ANALYTIC_WEAK_RELATION_HINTS)) {
            return false;
        }
        // 弱分析词如果描述的是章节前后、上下级、父子等结构关系，不应该挡住结构图查询。
        return !looksStructuralRelationQuestion(normalized);
    }


    /*
    弱关系词的进一步判断
     */
    private boolean looksStructuralRelationQuestion(String question) {
        String normalized = safeText(question);
        // 直接包含"前后关系/上下级关系/章节关系"等短语时，可以明确认为是结构关系。
        if (containsAny(normalized, STRUCTURAL_RELATION_HINTS)) {
            return true;
        }
        // 没有章节、标题、目录、编号或指代锚点时，"关系"更可能是内容语义关系。
        if (!hasGraphOnlyAnchor(normalized)) {
            return false;
        }
        // 结构关系还需要前后、相邻、顺序、位置、上级、同级等导航或层级线索配合。
        return containsAny(normalized, GRAPH_ONLY_EXPLICIT_ADJACENCY_HINTS)
            || containsAny(normalized, GRAPH_ONLY_DIRECTION_HINTS);
    }


    /*
    这个方法比 hasGraphOnlyAnchor 更宽泛，只要出现以下任意一种情况就返回 true：
包含"章节/小节/条目/步骤/项"等结构词
包含引号内容（比如 "开户流程" 这类显式强调的短语）
包含章节编号（比如 1.2.3）
structureHint 和 graphOnlyIntent 的区别
这两个经常容易混淆，区别在于：
graphOnlyIntent：判定"这个问题能不能只用结构图回答"，要求很严格，必须是纯结构导航问题
structureHint：判定"这个问题有没有结构线索可以利用"，要求很宽松，只要沾边就行
structureHint 为 true 不代表会走 GRAPH_ONLY，它只是告诉 RETRIEVAL 分支："嘿，这个问题带了结构线索，你可以先解析一下章节，作为软提示传给检索引擎，帮它缩小搜索范围。"
     */
    private boolean mentionsStructure(String question) {
        String normalized = safeText(question);
        return normalized.contains("章节")
            || normalized.contains("小节")
            || normalized.contains("条目")
            || normalized.contains("步骤")
            || normalized.contains("项")
            || QUOTED_TEXT_PATTERN.matcher(normalized).find()
            || SECTION_CODE_PATTERN.matcher(normalized).find();
    }


    /*显式编号解析
    这个方法用两个正则来提取编号：
    STEP_REFERENCE_PATTERN：匹配"第几步"，比如"第3步"、"第十二步"
    ORDINAL_REFERENCE_PATTERN：匹配"第几条/点/项/个"，比如"第二项"、"第5条"

     */
    private Integer resolveExplicitItemIndex(String question) {//从问题中提取条目序号（支持中文数字）
        Matcher stepMatcher = STEP_REFERENCE_PATTERN.matcher(safeText(question));
        if (stepMatcher.find()) {
            return parseChineseNumber(stepMatcher.group(1));
        }
        Matcher ordinalMatcher = ORDINAL_REFERENCE_PATTERN.matcher(safeText(question));
        if (ordinalMatcher.find()) {
            return parseChineseNumber(ordinalMatcher.group(1));
        }
        return null;
    }

    /*
    中文数字转换
    这个方法能处理的范围是 1~99 的中文数字，覆盖了绝大多数文档里的编号场景：
    纯阿拉伯数字直接 parseInt
    "十" → 10
    "十二" → 12（十 + 个位）
    "二十" → 20（十位 × 10）
    "二十三" → 23（十位 × 10 + 个位）
    单个中文数字 "三" → 3
     */
    private Integer parseChineseNumber(String text) {//解析中文数字（一到九十九）
        String normalized = safeText(text);
        if (normalized.isBlank()) {
            return null;
        }
        if (normalized.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(normalized);
        }
        Map<Character, Integer> digitMap = Map.of(
            '一', 1, '二', 2, '三', 3, '四', 4, '五', 5,
            '六', 6, '七', 7, '八', 8, '九', 9
        );
        if ("十".equals(normalized)) {
            return 10;
        }
        if (normalized.startsWith("十") && normalized.length() == 2) {
            return 10 + digitMap.getOrDefault(normalized.charAt(1), 0);
        }
        if (normalized.endsWith("十") && normalized.length() == 2) {
            return digitMap.getOrDefault(normalized.charAt(0), 0) * 10;
        }
        if (normalized.contains("十") && normalized.length() == 3) {
            return digitMap.getOrDefault(normalized.charAt(0), 0) * 10 + digitMap.getOrDefault(normalized.charAt(2), 0);
        }
        return digitMap.getOrDefault(normalized.charAt(0), null);
    }


    /*
    逻辑很简单：如果改写结果里没有子问题，就用主问题作为唯一的子问题，保证 retrievalPlan 里至少有一个可用的检索问题。有子问题的话就去空去重返回。
     */
    private List<String> normalizeSubQuestions(RagRewriteResult rewriteResult, String fallbackQuestion) {
        if (rewriteResult == null || rewriteResult.getSubQuestions() == null || rewriteResult.getSubQuestions().isEmpty()) {
            return List.of(fallbackQuestion);
        }
        return rewriteResult.getSubQuestions().stream()
            .filter(StrUtil::isNotBlank)
            .map(String::trim)
            .distinct()
            .toList();
    }

    private List<String> extractQueryHints(String question) {
        String normalized = safeText(question);
        if (normalized.isBlank()) {
            return List.of();
        }
        return Arrays.stream(normalized.split("[\\s、，,；;：:（）()\\-的和及与或]+"))
            .map(String::trim)
            .filter(item -> item.length() >= 2)
            .distinct()
            .limit(6)
            .toList();
    }

    private List<String> buildQueryHints(RetrievalQuestionPlan retrievalPlan,
                                         GraphSection section,
                                         Integer itemIndex) {
        LinkedHashSet<String> hints = new LinkedHashSet<>();
        if (retrievalPlan != null) {
            hints.addAll(extractQueryHints(retrievalPlan.getRetrievalQuestion()));
        }
        if (section != null) {
            addHint(hints, section.displayTitle());
            addHint(hints, section.getTitle());
            addHint(hints, section.getNodeCode());
        }
        if (itemIndex != null) {
            addHint(hints, "第" + itemIndex + "步");
            addHint(hints, "第" + itemIndex + "项");
        }
        return hints.stream()
            .filter(StrUtil::isNotBlank)
            .limit(10)
            .toList();
    }

    private void addHint(LinkedHashSet<String> hints, String hint) {
        String normalized = safeText(hint);
        if (normalized.isBlank()) {
            return;
        }
        hints.add(normalized);
    }


    /*
    结构视角判断
    根据问题类型给 ES 一个结构视角提示，让检索更偏向对应的方向：
    邻接型问题（”上一节是什么”）→ ”章节位置”
    提纲型问题（”有哪些章节”）→ ”章节”
    条目型问题（”第几步”）→ ”步骤”
    其他 → ””（不加视角限制）
     */
    private String detectFacet(String question) {
        if (asksAdjacency(question)) {
            return "章节位置";
        }
        if (asksOutline(question)) {
            return "章节";
        }
        if (asksItemLookup(question)) {
            return "步骤";
        }
        return "";
    }

    private String normalize(String text) {
        return safeText(text).replaceAll("[\\s>`*#_\\-，,。；;：:（）()“”\"']+", "").toLowerCase();
    }

    private String firstNonBlank(String left, String right) {
        if (StrUtil.isNotBlank(left)) {
            return left.trim();
        }
        return StrUtil.blankToDefault(right, "");
    }

    private String safeText(String text) {
        return text == null ? "" : text.trim();
    }
}
