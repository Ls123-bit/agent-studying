package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.model.memory.ConversationMemoryContext;
import org.javaup.ai.chatagent.model.memory.ConversationSummaryPayload;
import org.javaup.ai.chatagent.model.trace.ConversationTraceStageCode;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.chatagent.rag.model.AnswerHistoryContext;
import org.javaup.ai.chatagent.rag.model.ConversationExecutionPlan;
import org.javaup.ai.chatagent.rag.model.DocumentNavigationDecision;
import org.javaup.ai.chatagent.rag.model.ExecutionMode;
import org.javaup.ai.chatagent.rag.model.HistoryPlanningContext;
import org.javaup.ai.chatagent.rag.model.RagRewriteResult;
import org.javaup.ai.chatagent.service.ConversationMemoryService;
import org.javaup.ai.chatagent.service.ConversationTraceRecorder;
import org.javaup.ai.chatagent.service.TaskInfo;
import org.javaup.ai.chatagent.support.TimeSensitiveQueryHelper;
import org.javaup.ai.manage.model.KnowledgeDocumentDescriptor;
import org.javaup.ai.manage.model.route.DocumentRouteCandidate;
import org.javaup.ai.manage.model.route.KnowledgeRouteDecision;
import org.javaup.ai.manage.service.DocumentKnowledgeService;
import org.javaup.ai.manage.service.KnowledgeRouteService;
import org.javaup.enums.ChatQueryMode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;



/*ChatPreparationOrchestrator 是对话链路"执行前的总编排器",位于真正调模型/检索之前。它对外只有一个方法 prepare(TaskInfo),输入本轮会话运行态,输出一份执行器可直接消费的 ConversationExecutionPlan。
总编排器 这是准备阶段的总入口，只有一个对外方法，但内部逻辑很丰富。
聊天准备编排器
该组件负责把一轮原始对话请求整理成后续执行器真正可以消费的 {@link ConversationExecutionPlan}。
 * 核心职责包括：
 * 1. 装载会话记忆与历史上下文
 * 2. 生成面向检索的改写问题
 * 3. 在自动文档模式下进行知识范围路由
 * 4. 判断是否需要澄清
 * 5. 判定最终执行模式是 Agent、RAG、结构图直答还是结构图取证
 * 6. 汇总所有中间结果并生成最终执行计划
 */
@Slf4j
@Service
public class ChatPreparationOrchestrator {
//硬编码的意图识别关键词表
    private static final Set<String> CAPABILITY_HINTS = Set.of(
        "你都能干什么", "你能做什么", "你可以做什么", "你会什么", "你是谁", "怎么用你", "你能帮我什么"
    );

    private static final Set<String> OPEN_CHAT_HINTS = Set.of(
        "天气", "温度", "下雨", "新闻", "股价", "汇率", "热搜", "今天", "明天", "最新", "现在"
    );

    private static final Set<String> CHITCHAT_HINTS = Set.of(
        "你好", "您好", "hello", "hi", "谢谢", "感谢", "再见", "拜拜"
    );


    /*
    他的调用顺序是：
1.ConversationMemoryService → 加载会话记忆（长期摘要 + 最近窗口）
2.AnswerHistoryContextAssembler → 组装回答用的历史上下文
3.ChatQueryRewriteService → 把用户问题改写成检索友好的表达
4.KnowledgeRouteService → 自动文档模式下，路由到目标文档
5.DocumentQuestionRouter → 判断走图查询还是混合检
     */
    private final ChatRagProperties properties;// RAG 配置
    private final ConversationMemoryService conversationMemoryService;// 会话记忆
    private final AnswerHistoryContextAssembler answerHistoryContextAssembler;// 历史上下文组装
    private final ChatQueryRewriteService chatQueryRewriteService;// 问题改写
    private final DocumentQuestionRouter documentQuestionRouter;// 文档问题路由
    private final KnowledgeRouteService knowledgeRouteService;// 知识范围路由
    private final DocumentKnowledgeService documentKnowledgeService;// 知识范围路由

    public ChatPreparationOrchestrator(ChatRagProperties properties,
                                       ConversationMemoryService conversationMemoryService,
                                       AnswerHistoryContextAssembler answerHistoryContextAssembler,
                                       ChatQueryRewriteService chatQueryRewriteService,
                                       DocumentQuestionRouter documentQuestionRouter,
                                       KnowledgeRouteService knowledgeRouteService,
                                       DocumentKnowledgeService documentKnowledgeService) {
        this.properties = properties;
        this.conversationMemoryService = conversationMemoryService;
        this.answerHistoryContextAssembler = answerHistoryContextAssembler;
        this.chatQueryRewriteService = chatQueryRewriteService;
        this.documentQuestionRouter = documentQuestionRouter;
        this.knowledgeRouteService = knowledgeRouteService;
        this.documentKnowledgeService = documentKnowledgeService;
    }

    //五步决策链的总入口，返回最终执行计划
    // ChatPreparationOrchestrator.java —— 编排器核心逻辑 编排器内部的prepare()方法做了很多事，我们拆开来看核心逻辑
    /*
    prepare方法的整体流程
    准备一轮对话执行计划。
 * 这是进入真正执行器之前的总编排入口，会把用户问题、历史记忆、路由结果、改写结果和时间敏感性判断统一汇总。
 * @param taskInfo 当前会话运行态
 * @return 后续执行器可直接消费的执行计划
     */
    public ConversationExecutionPlan prepare(TaskInfo taskInfo) {
        // 先把本轮编排需要反复使用的运行态字段拆出来，避免后续链路里层层从 taskInfo 取值影响可读性。
        String conversationId = taskInfo.conversationId();
        String question = taskInfo.question();
        ChatQueryMode chatMode = taskInfo.chatMode();
        Long selectedDocumentId = taskInfo.selectedDocumentId();
        String selectedDocumentName = taskInfo.selectedDocumentName();
        Long selectedTaskId = taskInfo.selectedTaskId();
        LocalDate currentDate = taskInfo.currentDate();
        String currentDateText = taskInfo.currentDateText();
        ConversationTraceRecorder traceRecorder = taskInfo.traceRecorder();

        ConversationTraceRecorder.StageHandle memoryStage = traceRecorder == null
            ? null
            : traceRecorder.startStage(ConversationTraceStageCode.MEMORY, chatMode == null ? "" : chatMode.name(), "正在装载会话记忆与最近窗口。", null);
        ConversationMemoryContext memoryContext;
        try {
            // 第一步：加载会话记忆（长期摘要 + 最近对话窗口） 加载会话的长期摘要、近期对话窗口、回答窗口和压缩信息。这些信息会用于后续的问题改写和历史上下文构建。
            memoryContext = summarizeHistory(conversationId, traceRecorder);
            if (traceRecorder != null) {
                // 会话记忆装载完成后，把压缩覆盖范围、长期摘要和最近对话窗口写入追踪快照。
                traceRecorder.completeStage(memoryStage, "会话记忆装载完成。", Map.of(
                    "compressionApplied", memoryContext != null && memoryContext.isCompressionApplied(),
                    "coveredExchangeId", memoryContext == null ? 0L : memoryContext.getCoveredExchangeId(),
                    "coveredExchangeCount", memoryContext == null ? 0 : memoryContext.getCoveredExchangeCount(),
                    "compressionCount", memoryContext == null ? 0 : memoryContext.getCompressionCount(),
                    "longTermSummary", memoryContext == null ? "" : safeText(memoryContext.getLongTermSummary()),
                    "recentTranscript", memoryContext == null ? "" : safeText(memoryContext.getRecentTranscript()),
                    "answerRecentTranscript", memoryContext == null ? "" : safeText(memoryContext.getAnswerRecentTranscript())
                ));
            }
        }
        catch (RuntimeException exception) {
            if (traceRecorder != null) {
                traceRecorder.failStage(memoryStage, "会话记忆装载失败。", exception.getMessage(), null);
            }
            throw exception;
        }

        /*
        这段代码做了三件事：
1.启动阶段追踪——记录会话记忆装载的开始时间
2.调用 summarizeHistory——真正的装载逻辑
3.记录追踪信息——把装载结果写入追踪快照

什么是 ConversationMemoryContext？
ConversationMemoryContext 是会话记忆上下文对象，它包含了以下信息：
longTermSummary：长期摘要，总结了整个会话的主题和关键信息
recentTranscript：近期对话窗口，包含最近几轮的完整对话
answerRecentTranscript：回答窗口，只包含最近几轮的回答部分
compressionApplied：是否应用了历史压缩
coveredExchangeId：压缩覆盖到的最后一轮 ID
coveredExchangeCount：压缩覆盖的轮次数量
compressionCount：压缩次数
         */
        //————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————






        // 第二步：构建历史上下文，供后续问题改写和回答使用（summarizeHistory 执行完毕后，prepare 方法得到了 ConversationMemoryContext 对象。接下来，prepare 方法继续执行，基于这个记忆上下文构建两种历史上下文：）
        /*基于会话记忆构建两种历史上下文：
面向编排的历史摘要：用于问题改写和路由判断
面向回答的历史上下文：用于最终的答案生成
——————————————————————————————————————————————————
这段代码在 prepare 方法中，summarizeHistory 之后执行，构建了两种历史上下文：
1.面向编排的历史摘要（historySummary）：用于问题改写和路由判断
2.面向回答的历史上下文（answerHistoryContext）：用于答案生成
         */
        // 基于记忆上下文构造"面向编排的历史摘要"和"面向回答的历史上下文"，两者用途不同。
        HistoryPlanningContext historyPlanningContext = buildHistoryPlanningContext(memoryContext);
        String historySummary = buildPlanningHistory(memoryContext, historyPlanningContext);
        AnswerHistoryContext answerHistoryContext = buildAnswerHistoryContext(
            question,
            memoryContext == null ? "" : memoryContext.getAnswerRecentTranscript()
        );

        // 第三步：判断时效性——用户问的是不是"今天""最新"这类需要实时信息的问题  识别时间敏感词 这会影响后续的路由和提示词
        boolean requiresCurrentDateAnchoring = TimeSensitiveQueryHelper.requiresCurrentDateAnchoring(question);
        boolean requiresFreshSearch = TimeSensitiveQueryHelper.requiresFreshSearch(question);
        if (chatMode == null) {
            throw new IllegalArgumentException("chatMode 不能为空");
        }


        /*开放式问答的快速路由代码
        整个逻辑很简单,就三步:
1.构建基础执行计划 - 调用 basePlan 方法
2.设置执行模式为 ReactAgent - 通过 .mode(ExecutionMode.REACT_AGENT) 指定
3.记录路由追踪 - 把路由决策记录到 traceRecorder 里,方便后续调试
        */
        // 第四步：根据聊天模式走不同分支 根据chatmode判断路由  如果是 OPEN_CHAT 模式（开放式问答），直接返回 ReactAgent 执行计划，不需要后续的文档检索流程。
        if (chatMode == ChatQueryMode.OPEN_CHAT) {
            //开放式问答直接走 ReactAgent 路径，不做文档检索编排。
            ConversationExecutionPlan plan = basePlan(question, chatMode, memoryContext, historyPlanningContext, historySummary, answerHistoryContext, currentDate, currentDateText,
                requiresCurrentDateAnchoring, requiresFreshSearch)
                .mode(ExecutionMode.REACT_AGENT)//在 basePlan 返回的 Builder 对象上,代码紧接着调用了 .mode(ExecutionMode.REACT_AGENT),这一步非常关键——它决定了后续会用哪个执行器来处理这个问题。
                .build();



            /*
            路由追踪记录 代码的最后一部分是把路由决策记录到 traceRecorder 里:
            这段代码做了两件事:
1.开启一个 ROUTE 阶段 - 通过 startStage 方法
2.立即完成这个阶段 - 通过 completeStage 方法,并把关键信息记录下来
——————————————————————————————————————————————————————————
记录的信息包括:
chatMode: 当前聊天模式(OPEN_CHAT)
executionMode: 选择的执行模式(REACT_AGENT)
requiresFreshSearch: 是否需要最新搜索
requiresCurrentDateAnchoring: 是否需要日期锚定
——————————————————————————————————————————————————————————
为什么要记录这些？
在调试时,如果发现某个问题没有走文档检索链路,可以通过查看 trace 记录快速定位原因——是因为 chatMode 是 OPEN_CHAT,还是因为其他路由逻辑判断的结果。
             */
            if (traceRecorder != null) {
                // 对开放式模式单独打 route 轨迹,方便调试时看出为什么没有进入文档链路。
                ConversationTraceRecorder.StageHandle routeStage = traceRecorder.startStage(ConversationTraceStageCode.ROUTE, ExecutionMode.REACT_AGENT.name(), "路由到开放式 Agent。", null);
                traceRecorder.completeStage(routeStage, "已判定走开放式 Agent 路径。", Map.of(
                    "chatMode", chatMode.name(),
                    "executionMode", ExecutionMode.REACT_AGENT.name(),
                    "requiresFreshSearch", requiresFreshSearch,
                    "requiresCurrentDateAnchoring", requiresCurrentDateAnchoring
                ));
            }


            return plan;
        }


        /*
        文档问答模式的配置检查
        第一步：检查RAG是否开启
        这段代码很简单，就是检查 properties.isEnabled() 是否为 true。这个 properties 是 ChatRagProperties 类型的配置对象，里面有个 enabled 字段，用来控制整个 RAG 功能的开关。
        ——————————————————————————————————————
        为什么要这个检查？
        如果用户选择了文档问答模式，但是系统配置里 RAG 功能是关闭的，那后续的检索、路由等操作都无法进行。与其让程序在后面某个环节报错，不如在这里就直接抛出异常，告诉用户"你得先开启 RAG 功能"。

         */
        // 到这里说明进入的是文档知识问答链路；若 RAG 未开启，则直接报配置错误。
        if (!properties.isEnabled()) {
            throw new IllegalStateException("当前文档问答模式未启用，请先开启聊天侧 RAG 编排");
        }
        /*
        文档问答模式的配置检查第二步：检查文档范围是否明确
        这个检查只针对 DOCUMENT 模式（单文档问答）。在这个模式下，用户必须先选择一个具体的文档，系统才知道去哪里检索。如果 selectedDocumentId 或 selectedTaskId 为空，说明用户没有选择文档，那就没法继续了。

         */
        if (chatMode == ChatQueryMode.DOCUMENT && (selectedDocumentId == null || selectedTaskId == null)) {
            // 当前文档问答模式必须具备明确文档范围，否则无法继续往下路由。
            throw new IllegalArgumentException("当前文档问答模式缺少有效的文档范围");
        }


        /*
        问题改写的入口
        通过了配置检查之后，系统就要开始准备检索了。但是用户的原始问题往往不适合直接拿去检索，原因有这么几个：
1.上下文省略 - "它是什么？"、"再详细点"，这种问题脱离了上下文就没法理解
2.指代不明 - "这个功能怎么用？"，"这个"指的是什么？
3.口语化表达 - "能不能给我讲讲那个啥来着"，太随意了
4.多问题混在一起 - "A 是什么？B 怎么用？C 和 D 有什么区别？"，一次问了好几个问题
所以系统要先把用户问题"整理"一下，变成更适合检索的表达。这个过程就叫问题改写（Query Rewrite）。
——————————————————————————————————————————————————————————————————
这段代码的结构很清晰：
1.开启 REWRITE 追踪阶段 - 记录改写开始的时间和初始快照
2.调用改写服务 - chatQueryRewriteService.rewrite(question, historySummary, traceRecorder)
3.记录改写结果 - 把改写后的问题、子问题、模型原始输出都记录下来
4.异常处理 - 如果改写失败，记录失败信息并抛出异常
         */
        ConversationTraceRecorder.StageHandle rewriteStage = traceRecorder == null//// 对用户问题做"检索友好型改写"，把上下文省略、指代和口语化表达整理成更适合检索的问题。
            ? null
            : traceRecorder.startStage(
                ConversationTraceStageCode.REWRITE,
                ExecutionMode.RETRIEVAL.name(),
                "正在生成检索友好的问题表达。",
                buildRewriteStageSnapshot(question, historySummary, null)
            );
        RagRewriteResult rewriteResult;
        try {//第五步：问题改写  根据历史上下文生成检索友好的问题表达   chatQueryRewriteService.rewrite 方法，把用户的口语化问题改写成更适合检索的问题。
            rewriteResult = chatQueryRewriteService.rewrite(question, historySummary, traceRecorder);
            if (traceRecorder != null) {
                // 把原始问题、历史上下文、改写问题、子问题和原始模型输出都记录下来，方便调试改写质量。
                traceRecorder.completeStage(rewriteStage, "问题改写完成。", buildRewriteStageSnapshot(question, historySummary, rewriteResult));
            }
        }
        catch (RuntimeException exception) {
            if (traceRecorder != null) {
                traceRecorder.failStage(
                    rewriteStage,
                    "问题改写失败。",
                    exception.getMessage(),
                    buildRewriteStageSnapshot(question, historySummary, null)
                );
            }
            throw exception;
        }

        // 如果改写结果为空，则退回原问题；子问题同理，至少保证有一个检索问题可用。
        /*
        第一行代码：rewriteQuestion 的提取逻辑 这行代码用了一个三元表达式，分两种情况：
        情况一：改写结果为 null   如果 rewriteResult == null，说明改写服务调用失败或返回了空结果，这时候直接用 safeText(question) 作为改写问题。
        情况二：改写结果不为 null
        如果改写结果存在，就调用 firstNonBlank(rewriteResult.getRewrittenQuestion(), safeText(question))，这个方法会：
        1.先尝试取 rewriteResult.getRewrittenQuestion()
        2.如果这个值为空，就退回到 safeText(question)
         */
        /*
        第二行：rewriteSubQuestions 的提取逻辑 这行代码也是一个三元表达式，判断条件更复杂一些：
触发兜底的三种情况



只要满足以下任意一个条件，就会触发兜底逻辑：
1.rewriteResult == null - 改写结果为空
2.rewriteResult.getSubQuestions() == null - 子问题列表为 null
3.rewriteResult.getSubQuestions().isEmpty() - 子问题列表为空

兜底逻辑
如果触发兜底，就用 List.of(rewriteQuestion) 创建一个只包含改写问题的列表。这样可以保证：
子问题列表永远不为 null
子问题列表至少有一个元素
即使改写失败，也能用原问题继续后续流程

         */
        String rewriteQuestion = rewriteResult == null ? safeText(question) : firstNonBlank(rewriteResult.getRewrittenQuestion(), safeText(question));
        List<String> rewriteSubQuestions = rewriteResult == null || rewriteResult.getSubQuestions() == null || rewriteResult.getSubQuestions().isEmpty()
            ? List.of(rewriteQuestion)
            : rewriteResult.getSubQuestions();





        /*
        路由文档的初始化
        提取完改写结果后，代码紧接着初始化了一组路由文档相关的变量：
        前三行：初始化单个路由文档 这三个变量的初始值都来自 taskInfo，也就是用户当前选择的文档。注释里说得很清楚：先把"已选择文档"当作默认路由结果。
        后两行：初始化路由文档列表 这两行代码把单个文档 ID 和任务 ID 转换成列表形式：
        如果 routedDocumentId 为 null，就创建一个空列表
        如果不为 null，就创建一个包含这个 ID 的单元素列表
        __________________________________________
        为什么要有单个和列表两种形式？
        单个形式（routedDocumentId）- 表示"最终选定的那一个文档"，用于后续的检索和回答
        列表形式（routedDocumentIds）- 表示"所有候选文档"，在自动路由模式下可能有多个候选，用于扩大检索范围或提供澄清选项
        在 DOCUMENT 模式下，用户已经明确选择了文档，所以单个和列表是一致的。但在 AUTO_DOCUMENT 模式下，系统会自动路由，可能会有多个候选文档，这时候列表就会包含多个元素。
         */
        // 先把"已选择文档"当作默认路由结果，AUTO_DOCUMENT 模式下后续再尝试动态改写它。
        Long routedDocumentId = selectedDocumentId;//路由到的文档 ID
        String routedDocumentName = selectedDocumentName;//路由到的文档名称
        Long routedTaskId = selectedTaskId;//路由到的文档对应的索引任务 ID
        List<Long> routedDocumentIds = routedDocumentId == null ? List.of() : List.of(routedDocumentId);
        List<Long> routedTaskIds = routedTaskId == null ? List.of() : List.of(routedTaskId);




        /*AUTO_DOCUMENT 模式的入口判断

         */
        if (chatMode == ChatQueryMode.AUTO_DOCUMENT) {// 第六步：文档路由（AUTO_DOCUMENT 模式） 如果是 AUTO_DOCUMENT 模式（自动知识问答），系统会自动判断应该在哪个文档中检索。如果候选文档不稳定或置信度不足，会返回澄清执行计划，让用户选择具体文档。
            // 自动文档模式下，先根据原问题和改写问题做知识范围路由。
            KnowledgeRouteDecision routeDecision = knowledgeRouteService.route(question, rewriteQuestion);//第一步：调用知识范围路由服务 返回一个 KnowledgeRouteDecision 对象，包含了路由决策的所有信息。
            /*这行代码把路由结果记录到数据库里，用于后续分析和优化。记录的信息包括：
会话 ID 和轮次 ID
原始问题和改写问题
路由决策的完整结果（知识域、主题、文档候选）
置信度和路由状态
为什么要记录路由结果？
自动路由是一个需要不断优化的功能。通过记录每次路由的结果，可以：
1.分析路由准确率 - 看看系统选择的文档是否符合用户预期
2.发现问题模式 - 哪些类型的问题路由效果不好
3.优化路由算法 - 根据历史数据调整打分权重
4.A/B 测试 - 对比不同路由策略的效果
             */
            knowledgeRouteService.recordAutoRoute(conversationId, taskInfo.exchangeId(), question, rewriteQuestion, routeDecision);//第二步：记录自动路由结果
            /*
            这行代码调用了 selectAutoCandidates 方法，从路由决策中选择出最终的候选文档列表。这个方法的逻辑比较复杂，我们下一篇会详细讲解。
            简单来说，这个方法会：
            1.根据置信度决定保留几个候选（高置信度保留 3 个，低置信度保留 5 个）
            2.如果路由结果为空或置信度过低，会调用 fallback 机制
            3.返回一个排序好的候选文档列表
             */
            List<DocumentRouteCandidate> candidateDocuments = selectAutoCandidates(routeDecision, question, rewriteQuestion);//第三步：选择候选文档



            /*
            澄清执行计划的构建  当 shouldAskClarification 返回 true 时，系统会构建一个澄清执行计划：
             */
            if (shouldAskClarification(routeDecision, candidateDocuments)) {// 第四步：判断是否需要澄清
                // 如果候选不稳定、置信度不足或前两名差距太小，则优先返回澄清问题，而不是冒险误选文档。
                /*
                shouldAskClarification 方法会检查以下几种情况：
                1.候选文档列表为空
                2.路由决策为空或失败
                3.置信度低于 0.55
                4.前两名候选得分差距太小且知识域不同
                _________________________________
                如果满足任意一个条件，就会返回一个 ExecutionMode.CLARIFICATION 的执行计划，让系统先向用户确认要查询哪个文档。为什么要这么保守？
                自动路由如果选错文档，会导致：
                1.检索不到相关内容 - 在错误的文档里检索，肯定找不到答案
                2.回答质量下降 - 基于错误文档生成的回答会答非所问
                3.用户体验变差 - 用户会觉得系统"不智能"
                所以系统采取保守策略：宁可多问一句，也不要冒险选错。
                 */
                return basePlan(question, chatMode, memoryContext, historyPlanningContext, historySummary, answerHistoryContext, currentDate, currentDateText,
                    requiresCurrentDateAnchoring, requiresFreshSearch)
                        // 明确把执行模式切到 CLARIFICATION，后续执行器只负责返回确认问题，不再继续检索正文答案。
                    .mode(ExecutionMode.CLARIFICATION)
                        //构建澄清执行计划   // 澄清分支依然保留 rewrite 结果，方便前端和调试面板看到“系统到底按什么问题在理解用户意图”。
                    .rewriteQuestion(rewriteQuestion)
                    .rewriteSubQuestions(rewriteSubQuestions)
                    .retrievalQuestion(rewriteQuestion)
                    .retrievalSubQuestions(rewriteSubQuestions)
                        // 即使当前不直接选中具体文档，也把候选文档 id 带进计划中，方便前端渲染澄清选项或后续继续路由。
                    .retrievalDocumentIds(candidateDocuments.stream()
                        .map(DocumentRouteCandidate::getDocumentId)
                        .filter(StrUtil::isNotBlank)
                        .map(Long::valueOf)
                        .toList())
                        // 候选任务 id 同理也提前带上，避免用户确认后还要重新做一轮候选解析。
                    .retrievalTaskIds(candidateDocuments.stream()
                        .map(DocumentRouteCandidate::getLastIndexTaskId)
                        .filter(StrUtil::isNotBlank)
                        .map(Long::valueOf)
                        .toList())
                        // clarificationReply 是真正返回给用户看的澄清正文。   澄清回复正文（执行计划的三个关键字段之一）
                    .clarificationReply(buildClarificationReply(question, routeDecision, candidateDocuments))
                        // clarificationOptions 则是给前端做快捷按钮或建议回复使用的候选项。  澄清选项列表 （执行计划的三个关键字段之一）
                    .clarificationOptions(buildClarificationOptions(candidateDocuments))
                        // clarificationReason 主要给调试和状态展示使用，解释“为什么这一轮没直接回答而是先来确认”。  澄清说明原因  （执行计划的三个关键字段之一）
                    .clarificationReason(buildClarificationReason(routeDecision, candidateDocuments))
                    .build();
            }



            /*
            最终路由文档的确定
            在澄清判断之后，如果不需要澄清，系统会继续确定最终要使用的路由文档：
             */
            /*第一步：判断是否有可信的 top1 文档
            判断条件：
            路由决策不为空
            置信度不为空
            置信度 ≥ 0.55
            只有同时满足这三个条件，才认为有一个可信的 top1 文档。
            为什么阈值是 0.55？
            这个阈值和澄清判断的阈值一致。如果置信度 < 0.55，系统会触发澄清；如果置信度 ≥ 0.55，系统会继续使用 top1 文档。
            这样可以保证逻辑的一致性：
            置信度 < 0.55：触发澄清，不确定最终文档
            置信度 ≥ 0.55：不触发澄清，使用 top1 文档
             */
            // 只有在 top1 文档置信度足够高时，才真正把它作为当前路由文档。
            boolean confidentTopDocument = routeDecision != null
                && routeDecision.getConfidence() != null
                && routeDecision.getConfidence().doubleValue() >= 0.55D;


            /*第二步：提取 top1 文档
            如果有可信的 top1 文档，且候选列表不为空，就提取第一个候选作为 top1 文档；否则 topDocument 为 null。
             */
            DocumentRouteCandidate topDocument = confidentTopDocument && !candidateDocuments.isEmpty() ? candidateDocuments.get(0) : null;
            /*
            第三步:更新路由文档变量
            如果 top1 文档有效（不为 null，且 documentId 和 lastIndexTaskId 都不为空），就更新路由文档变量：
            routedDocumentId - 路由到的文档 ID
            routedDocumentName - 路由到的文档名称
            routedTaskId - 路由到的索引任务 ID
            否则，把这些变量设置为 null 或空字符串，表示没有确定最终文档。
            为什么要检查 documentId 和 lastIndexTaskId？
            这两个字段是后续检索必需的：
            documentId - 用于标识文档
            lastIndexTaskId - 用于定位文档的向量索引
            如果这两个字段有任何一个为空，就无法进行检索，所以必须检查。
             */
            if (topDocument != null && StrUtil.isNotBlank(topDocument.getDocumentId()) && StrUtil.isNotBlank(topDocument.getLastIndexTaskId())) {
                routedDocumentId = Long.valueOf(topDocument.getDocumentId());
                routedDocumentName = topDocument.getDocumentName();
                routedTaskId = Long.valueOf(topDocument.getLastIndexTaskId());
            }
            else {
                routedDocumentId = null;
                routedDocumentName = "";
                routedTaskId = null;
            }


            /*
            构建路由文档列表
            这段代码从候选文档列表中提取所有文档 ID 和任务 ID，构建成两个列表：
            routedDocumentIds - 所有候选文档的 ID 列表
            routedTaskIds - 所有候选文档的任务 ID 列表
            注意这里的注释：无论 top1 是否足够稳定，候选列表都会保留下来。
            这意味着：
            即使 routedDocumentId 为 null（没有确定最终文档）
            routedDocumentIds 列表仍然包含所有候选文档
            这样设计的好处：
            1.扩大检索范围 - 如果 top1 不够稳定，可以在所有候选文档中检索
            2.调试信息 - 保留所有候选，方便调试和分析
            3.兜底机制 - 如果 top1 检索失败，可以尝试其他候选
             */
            // 无论 top1 是否足够稳定，候选列表都会保留下来，供后续检索范围或调试信息使用。
            routedDocumentIds = candidateDocuments.stream()
                .map(DocumentRouteCandidate::getDocumentId)
                .filter(StrUtil::isNotBlank)
                .map(Long::valueOf)
                .toList();
            routedTaskIds = candidateDocuments.stream()
                .map(DocumentRouteCandidate::getLastIndexTaskId)
                .filter(StrUtil::isNotBlank)
                .map(Long::valueOf)
                .toList();

            /*
            路由追踪记录
             */
            if (traceRecorder != null) {
                // 将自动路由的置信度、状态、候选数以及 top1 文档写入追踪信息。
                traceRecorder.completeStage(
                    traceRecorder.startStage(ConversationTraceStageCode.ROUTE, "AUTO_DOCUMENT", "正在生成知识范围候选。", null),
                    "知识范围路由完成。",
                    Map.of(
                        "confidence", routeDecision == null || routeDecision.getConfidence() == null ? "" : routeDecision.getConfidence().toPlainString(),
                        "routeStatus", routeDecision == null ? "" : StrUtil.blankToDefault(routeDecision.getRouteStatus(), ""),
                        "candidateDocumentCount", candidateDocuments.size(),
                        "confidentTopDocument", confidentTopDocument,
                        "topDocumentId", topDocument == null ? "" : StrUtil.blankToDefault(topDocument.getDocumentId(), ""),
                        "topDocumentName", topDocument == null ? "" : StrUtil.blankToDefault(topDocument.getDocumentName(), "")
                    )
                );
            }
        }
        /*
        DOCUMENT 模式的影子路由 （在 DOCUMENT 模式下（用户手动选择文档），系统不会真正使用自动路由的结果，但仍然会调用 recordShadowRoute 方法记录一次"影子路由"。
        ）
         */
        else if (chatMode == ChatQueryMode.DOCUMENT) {
            // 当前文档模式虽然不会真正改路由，但仍记录一次 shadow route，便于后续分析自动路由表现。
            knowledgeRouteService.recordShadowRoute(conversationId, taskInfo.exchangeId(), selectedDocumentId, question, rewriteQuestion);
        }

        ConversationTraceRecorder.StageHandle routeStage = traceRecorder == null
            ? null
            : traceRecorder.startStage(ConversationTraceStageCode.ROUTE, ExecutionMode.RETRIEVAL.name(), "正在判定图查询还是混合检索。", null);
        DocumentNavigationDecision navigationDecision;
        try {//第七步：文档问题路由
            /*调用 docume
结构图直答：直接从文档结构图中提取答案
结构图取证：先从结构图定位，再检索详细内容
混合检索：标准的 RAG 路径ntQuestionRouter.route 方法，判断这个问题应该用什么方式回答：

             */
            navigationDecision = documentQuestionRouter.route(routedDocumentId, question, rewriteResult);
            if (traceRecorder != null) {
                traceRecorder.completeStage(routeStage, "执行路由完成。", Map.of(
                    "executionMode", navigationDecision == null || navigationDecision.getExecutionMode() == null ? "" : navigationDecision.getExecutionMode().name(),
                    "targetSectionHint", navigationDecision == null || navigationDecision.getStructureAnchor() == null ? "" : StrUtil.blankToDefault(navigationDecision.getStructureAnchor().getTargetSectionHint(), ""),
                    "targetItemIndex", navigationDecision == null || navigationDecision.getItemAnchor() == null || navigationDecision.getItemAnchor().getItemIndex() == null
                        ? ""
                        : String.valueOf(navigationDecision.getItemAnchor().getItemIndex()),
                    "navigationSummary", navigationDecision == null ? "" : StrUtil.blankToDefault(navigationDecision.getSummaryText(), "")
                ));
            }
        }
        catch (RuntimeException exception) {
            if (traceRecorder != null) {
                traceRecorder.failStage(routeStage, "执行路由失败。", exception.getMessage(), null);
            }
            throw exception;
        }


        /*
        执行模式兜底
        documentQuestionRouter.route 理论上总会返回一个 executionMode，但为了防止意外情况（比如路由器返回 null），这里做了一个兜底：
        如果没有明确的执行模式，就默认走 RETRIEVAL（普通混合检索）。这是最保守也最安全的选择。
         */
        // 第八步：确定最终执行模式  根据文档问题路由的结果，确定最终的执行模式（RETRIEVAL、GRAPH_ONLY、GRAPH_THEN_EVIDENCE 等） // 如果导航阶段没有给出明确 executionMode，则默认退回普通 RETRIEVAL 模式。
        ExecutionMode executionMode = navigationDecision == null || navigationDecision.getExecutionMode() == null
            ? ExecutionMode.RETRIEVAL
            : navigationDecision.getExecutionMode();
        /*
        检索问题的最终确定
        这里有个细节：retrievalQuestion 和 rewriteQuestion 是两个不同的字段。
        rewriteQuestion：改写阶段产出的问题，用于记录"改写了什么"
        retrievalQuestion：最终用于检索的问题，可能被导航阶段进一步微调
        导航阶段（documentQuestionRouter）在构建 retrievalPlan 时，可能会对检索问题做进一步调整，比如加入章节上下文、精简问题表达等。如果导航阶段没有调整，就直接沿用改写结果。
        子问题同理：优先用导航阶段的子问题，没有就用改写阶段的。
         */
        // retrievalQuestion / retrievalSubQuestions 允许导航阶段进一步微调，若没有则沿用改写结果。
        String retrievalQuestion = navigationDecision == null || navigationDecision.getRetrievalPlan() == null
            ? rewriteQuestion
            : firstNonBlank(navigationDecision.getRetrievalPlan().getRetrievalQuestion(), rewriteQuestion);
        List<String> retrievalSubQuestions = navigationDecision == null || navigationDecision.getRetrievalPlan() == null
            || navigationDecision.getRetrievalPlan().getSubQuestions() == null || navigationDecision.getRetrievalPlan().getSubQuestions().isEmpty()
            ? rewriteSubQuestions
            : navigationDecision.getRetrievalPlan().getSubQuestions();




        log.info("聊天编排完成: conversationId={}, chatMode={}, originalQuestion='{}', rewriteQuestion='{}', retrievalQuestion='{}', executionMode={}, targetSection='{}'",
            conversationId,
            chatMode,
            safeText(question),
            rewriteQuestion,
            retrievalQuestion,
            executionMode,
            navigationDecision == null || navigationDecision.getStructureAnchor() == null ? "" : safeText(navigationDecision.getStructureAnchor().getTargetSectionHint()));

        /*
        执行计划装配
        // 最终把记忆、改写、路由、时间敏感信息和无证据回复一起装配成执行计划。
         */
        // 第九步：构建执行计划   把前面所有步骤的结果汇总，构建一个完整的 ConversationExecutionPlan 对象，返回给上层。
        return basePlan(question, chatMode, memoryContext, historyPlanningContext, historySummary, answerHistoryContext, currentDate, currentDateText,
            requiresCurrentDateAnchoring, requiresFreshSearch)
                // 这里开始在 basePlan 的公共骨架上，补上"本轮文档链路真正判定出来的执行模式"。
            .mode(executionMode)
                // 把文档路由器给出的结构导航决策挂进去，后续执行器会根据它决定走图查询还是混合检索。
            .navigationDecision(navigationDecision)
                // 用 rewrite 阶段产出的改写问题覆盖 basePlan 中"默认等于原问题"的占位值。
            .rewriteQuestion(rewriteQuestion)
            .rewriteSubQuestions(rewriteSubQuestions)
                // retrievalQuestion / retrievalSubQuestions 允许被导航阶段进一步调整，因此这里写入的是最终检索版本。
            .retrievalQuestion(retrievalQuestion)
            .retrievalSubQuestions(retrievalSubQuestions)
                // 下面这组 selectedDocument* 字段表示"当前真正路由到的主文档作用域"。
            .selectedDocumentId(routedDocumentId)
            .selectedDocumentName(routedDocumentName)
            .selectedTaskId(routedTaskId)
                // 而 retrievalDocumentIds / retrievalTaskIds 表示"候选检索范围"，可以比 selectedDocument* 更宽。
            .retrievalDocumentIds(routedDocumentIds)
            .retrievalTaskIds(routedTaskIds)
                // 文档模式下的无证据回复需要按问题类型做定制提示，因此在这里覆盖 basePlan 的默认兜底文案。
            .noEvidenceReply(buildDocumentModeNoEvidenceReply(question, requiresFreshSearch))//无证据回复 （当检索没有找到相关内容时，系统需要给用户一个友好的回复。buildDocumentModeNoEvidenceReply 会根据问题类型和是否需要实时搜索，生成定制化的兜底文案，覆盖 basePlan 里的默认文案。）
            .build();
    }

    /*
    构建基础执行方法
    basePlan 是一个私有方法,它的作用是把前面准备好的各种上下文信息打包成一个 ConversationExecutionPlan.Builder 对象。这个方法接收的参数非常多,因为它要把所有必要的信息都装进执行计划里:
    这个方法返回的是一个 Builder 对象,所以调用方可以继续链式调用 .mode() 等方法来补充更多信息。
    从代码可以看到,basePlan 把前面几个步骤准备好的所有上下文都打包进去了:
    ————————————————————————————————————————————————————————————————————
    注意
    开放式问答模式下,rewriteQuestion 和 retrievalQuestion 都直接等于原问题,因为不需要改写、也不需要检索。这些字段在文档问答模式下才会被真正用到。
     */
    private ConversationExecutionPlan.ConversationExecutionPlanBuilder basePlan(String question,
                                                                                ChatQueryMode chatMode,
                                                                                ConversationMemoryContext memoryContext,
                                                                                HistoryPlanningContext historyPlanningContext,
                                                                                String historySummary,
                                                                                AnswerHistoryContext answerHistoryContext,
                                                                                LocalDate currentDate,
                                                                                String currentDateText,
                                                                                boolean requiresCurrentDateAnchoring,
                                                                                boolean requiresFreshSearch) {
        return ConversationExecutionPlan.builder()
            .chatMode(chatMode)
            .originalQuestion(question)//用户输入            用户的原始问题
            .agentQuestion(question)//初始值同 originalQuestion     后续会被重新渲染,加入时间锚定等信息
            .rewriteQuestion(question)//初始值同 originalQuestion  开放式问答不需要改写,所以直接用原问题
            .rewriteSubQuestions(List.of(question))//List.of(question)  子问题列表,开放式问答只有一个
            .retrievalQuestion(question)
            .retrievalSubQuestions(List.of(question))
            .historySummary(historySummary)//第 133 行构建  面向编排的历史摘要
            .longTermSummary(memoryContext.getLongTermSummary())//memoryContext  长期记忆摘要
            .historyPlanningContext(historyPlanningContext)
            .recentHistoryTranscript(memoryContext.getRecentTranscript())//memoryContext  近期对话窗口
            .answerRecentTranscript(memoryContext.getAnswerRecentTranscript())//memoryContext  回答窗口
            .answerHistoryContext(answerHistoryContext)//第 134-137 行构建   面向回答的历史上下文
            .historyCompressionApplied(memoryContext.isCompressionApplied())
            .historyCoveredExchangeId(memoryContext.getCoveredExchangeId())
            .historyCoveredExchangeCount(memoryContext.getCoveredExchangeCount())
            .historyCompressionCount(memoryContext.getCompressionCount())
            .currentDate(currentDate)//taskInfo  当前日期信息
            .currentDateText(currentDateText)
            .requiresCurrentDateAnchoring(requiresCurrentDateAnchoring)//第 140 行识别  是否需要日期锚定
            .requiresFreshSearch(requiresFreshSearch)//第 141 行识别  是否需要最新搜索
            .noEvidenceReply(properties.getNoEvidenceReply());
    }


    /*
    构建改写快照  这个方法负责把改写相关的信息打包成一个 Map，方便后续追踪和调试：
    ————————————————————————————————————————————————————————————————
    为什么要记录这么详细？
    问题改写的质量直接影响后续检索的效果。如果改写得不好，检索就会找不到相关文档，最终导致回答质量下降。所以系统把改写的输入、输出、模型参数都记录下来，方便后续分析"为什么改写成这样"、"是不是模型参数设置有问题"。

     */
    private Map<String, Object> buildRewriteStageSnapshot(String question,
                                                          String historySummary,
                                                          RagRewriteResult rewriteResult) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("originalQuestion", StrUtil.blankToDefault(question, ""));//用户的原始问题
        snapshot.put("historyContext", StrUtil.blankToDefault(historySummary, ""));//历史摘要上下文
        snapshot.put("rewriteQuestion", rewriteResult == null ? "" : StrUtil.blankToDefault(rewriteResult.getRewrittenQuestion(), ""));//改写后的问题
        snapshot.put("subQuestions", rewriteResult == null || rewriteResult.getSubQuestions() == null ? List.of() : rewriteResult.getSubQuestions());//拆分出的子问题列表
        snapshot.put("rawModelOutput", rewriteResult == null ? "" : StrUtil.blankToDefault(rewriteResult.getRawModelOutput(), ""));//模型的原始输出（JSON 格式）

        ChatRagProperties.RewriteOptionsProperties rewriteOptions = properties == null ? null : properties.getRewriteOptions();
        boolean overrideEnabled = rewriteOptions != null && rewriteOptions.isEnabled();
        snapshot.put("rewriteOverrideEnabled", overrideEnabled);//是否覆盖了默认的模型参数
        snapshot.put("rewriteTemperature", rewriteOptions == null ? null : rewriteOptions.getTemperature());//改写时使用的 temperature 参数
        snapshot.put("rewriteTopP", rewriteOptions == null ? null : rewriteOptions.getTopP());//改写时使用的 topP 参数
        snapshot.put("rewriteThinking", rewriteOptions == null ? null : rewriteOptions.getThinking());//改写时是否开启了 thinking 模式
        return snapshot;
    }


    /*
    summarizeHistory 方法是会话记忆装载的入口，它的实现非常简洁：
    这个方法直接委托给 ConversationMemoryService.loadMemoryContext 方法。
    真正的装载逻辑在 PersistentConversationMemoryService.loadMemoryContext 方法中。
     */
    private ConversationMemoryContext summarizeHistory(String conversationId, ConversationTraceRecorder traceRecorder) {
        return conversationMemoryService.loadMemoryContext(conversationId, traceRecorder);
    }


    /*
    buildHistoryPlanningContext 方法把 ConversationMemoryContext 中的结构化摘要（summaryPayload）转换为面向规划的 HistoryPlanningContext 对象：
    这个方法的作用是把 ConversationSummaryPayload 中的结构化字段提取出来，构建成 HistoryPlanningContext 对象。
    ————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————————
    为什么要显式复制列表？
    注意代码中的 new ArrayList<>(payload.getStableFacts())，这是显式复制列表，避免后续流程对 builder 结果做修改时反向污染原始 payload。

     */
    private HistoryPlanningContext buildHistoryPlanningContext(ConversationMemoryContext memoryContext) {//从摘要中提取目标、事实、待解决问题、检索提示
        ConversationSummaryPayload payload = memoryContext == null ? null : memoryContext.getSummaryPayload();
        if (payload == null) {
            // 没有任何摘要载荷时，返回空上下文对象，让后续流程按"无长期记忆"分支自然退化。
            return HistoryPlanningContext.builder().build();
        }
        return HistoryPlanningContext.builder()
                // conversationGoal 直接沿用摘要中的目标描述，供规划阶段理解"当前会话整体在推进什么事情"。
            .conversationGoal(payload.getConversationGoal())
                // 这里显式复制列表，避免后续流程对 builder 结果做修改时反向污染原始 payload。
            .stableFacts(payload.getStableFacts() == null ? List.of() : new ArrayList<>(payload.getStableFacts()))
            .pendingQuestions(payload.getPendingQuestions() == null ? List.of() : new ArrayList<>(payload.getPendingQuestions()))
            .retrievalHints(payload.getRetrievalHints() == null ? List.of() : new ArrayList<>(payload.getRetrievalHints()))
                // 当前实现里 queryContextHints 先复用 retrievalHints，
                // 语义上表示"这些提示既可以指导检索，也可以帮助理解当前问题的上下文"。
            .queryContextHints(payload.getRetrievalHints() == null ? List.of() : new ArrayList<>(payload.getRetrievalHints()))
            .build();
    }



    /*
    buildPlanningHistory 方法把"结构化长期摘要"和"最近几轮原文窗口"混合成一段适合送入模型的历史文本：
    ————————————————————————————————————————————————————————————————————————————————————————
    设计思路
这个方法的设计思路是：
1.长期摘要负责保留会话主线、稳定事实和待跟进问题
2.最近窗口负责保留当前轮最敏感、最细粒度的上下文
3.两者按预算拼接，避免只保留摘要导致最近指代丢失，也避免只保留最近窗口导致长期主线断掉

     */
    private String buildPlanningHistory(ConversationMemoryContext memoryContext,
                                        HistoryPlanningContext historyPlanningContext) {//组合结构化历史和最近原文，控制字符预算
        // 先把结构化上下文渲染成稳定摘要块。
        String structuredHistory = buildStructuredPlanningHistory(historyPlanningContext);
        // 再取最近窗口原文，用于承接短期上下文和指代。
        String recentTranscript = memoryContext == null ? "" : safeText(memoryContext.getRecentTranscript());
        int maxChars = Math.max(1, properties.getPlanningHistoryMaxChars());
        if (recentTranscript.isBlank()) {
            // 如果没有最近窗口，只保留结构化历史摘要。
            return clipHead(structuredHistory, maxChars);
        }

        /*
        预算分配策略
         最近窗口通常对当前问题更敏感，因此会给它保留更大的预算比例。
         这行代码的意思是：
        最近窗口至少占 50% 的预算（maxChars / 2）
        最近窗口最多占 65% 的预算（maxChars * 0.65）
        取两者中的较大值，但不超过总预算
        ____________________________________
        比如，如果 maxChars = 1000：
        maxChars / 2 = 500
        maxChars * 0.65 = 650
        取较大值 650，所以最近窗口占 650 字符，结构化摘要占 350 字符
        __________________________________________________
        裁剪策略
        代码中使用了两种不同的裁剪策略：
        1.结构化摘要用 clipHead(...)：从头部截断，因为摘要通常是"越前面越重要"的总览信息
        2.最近窗口用 clipTail(...)：从尾部截断，因为最近发生的对话通常比更早的原文更相关
         */
        int recentBudget = Math.min(Math.max(maxChars / 2, (int) Math.round(maxChars * 0.65D)), maxChars);

        String recentPart = clipTail(recentTranscript, recentBudget);
        // 结构化摘要占用剩余预算，扣掉中间空行分隔带来的字符成本。
        int structuredBudget = Math.max(0, maxChars - recentPart.length() - (recentPart.isBlank() ? 0 : 2));
        String structuredPart = clipHead(structuredHistory, structuredBudget);
        // 最终输出按"摘要在前、最近窗口在后"的顺序拼接，符合从总览到局部的阅读顺序。
        return joinNonBlank(structuredPart, recentPart);
    }

    /*
    buildAnswerHistoryContext 方法构建"面向回答阶段"的历史上下文：
    这个方法委托给 AnswerHistoryContextAssembler，让其根据"是否像追问""最近用户问题窗口是否存在"等条件，返回一份更轻量的回答承接上下文。
     */
    private AnswerHistoryContext buildAnswerHistoryContext(String question,
                                                           String answerRecentTranscript) {
        return answerHistoryContextAssembler.assemble(question, answerRecentTranscript);
    }


    /*
    buildStructuredPlanningHistory 方法把 HistoryPlanningContext 渲染成文本：
    渲染示例：
    【会话目标】
了解 Java 和 Python 的区别

【已确认事实】
- Java 是一种面向对象的编程语言
- Python 语法更简洁

【待跟进问题】
- 哪个更适合高并发场景？

【检索提示】
- Java
- Python
- 并发模型
     */
    private String buildStructuredPlanningHistory(HistoryPlanningContext historyPlanningContext) {
        StringBuilder builder = new StringBuilder();
        if (historyPlanningContext == null) {
            return "";
        }
        appendSection(builder, "会话目标", historyPlanningContext.getConversationGoal());
        appendBulletSection(builder, "已确认事实", historyPlanningContext.getStableFacts());
        appendBulletSection(builder, "待跟进问题", historyPlanningContext.getPendingQuestions());
        appendBulletSection(builder, "检索提示", historyPlanningContext.getRetrievalHints());
        return builder.toString().trim();
    }

    private void appendSection(StringBuilder builder, String title, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append("【").append(title).append("】\n").append(content.trim()).append('\n');
    }

    private void appendBulletSection(StringBuilder builder, String title, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append("【").append(title).append("】\n");
        values.stream()
            .filter(item -> item != null && !item.isBlank())
            .limit(5)
            .forEach(item -> builder.append("- ").append(item.trim()).append('\n'));
    }

    private String clipHead(String text, int maxChars) {
        String normalized = safeText(text);
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        if (maxChars <= 1) {
            return "";
        }
        return normalized.substring(0, maxChars - 1) + "…";
    }

    private String clipTail(String text, int maxChars) {
        String normalized = safeText(text);
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        if (maxChars <= 1) {
            return "";
        }
        int start = Math.max(0, normalized.length() - (maxChars - 1));
        return "…" + normalized.substring(start);
    }

    private String joinNonBlank(String left, String right) {
        if (left == null || left.isBlank()) {
            return safeText(right);
        }
        if (right == null || right.isBlank()) {
            return safeText(left);
        }
        return left.trim() + "\n\n" + right.trim();
    }

/*
safeText 和 firstNonBlank 工具方法
这两个工具方法在代码里经常出现，它们的作用是保证字符串不为 null：
safeText 就是把 null 转成空字符串，firstNonBlank 则是从多个候选值里找第一个非空的。
为什么要这么多层兜底？
改写服务可能会失败（模型调用失败、解析失败等），即使成功了，模型返回的 rewrittenQuestion 字段也可能为空。系统通过多层兜底，保证无论发生什么情况，都至少有一个可用的问题文本，不会让后续流程因为空值而中断。
 */

    private String safeText(String text) {//安全地获取文本，null 转空字符串。
        return text == null ? "" : text.trim();
    }

    private String firstNonBlank(String left, String right) {//返回第一个非空文本。
        if (StrUtil.isNotBlank(left)) {
            return left.trim();
        }
        return safeText(right);
    }



    /**
     * 从路由决策中选择最终的候选文档列表。
     *
     * @param routeDecision 路由决策
     * @param question 原始问题
     * @param rewriteQuestion 改写问题
     * @return 候选文档列表
     */
    private List<DocumentRouteCandidate> selectAutoCandidates(KnowledgeRouteDecision routeDecision,
                                                              String question,
                                                              String rewriteQuestion) {//AUTO_DOCUMENT 模式下选择候选文档
        /*
        第一步：空值检查和fallback
        如果路由决策为空，或者文档候选列表为空，就直接调用 fallbackDocuments 方法进行兜底。
         */
        if (routeDecision == null || routeDecision.getDocuments() == null || routeDecision.getDocuments().isEmpty()) {
            // 路由结果为空时，直接退回到 fallback 元数据匹配。
            return fallbackDocuments(question, rewriteQuestion, 5);
        }
        /*
        第二步：根据置信度调整候选数量
         */
        // 高置信度时候选收窄到 3 个；置信度一般时保留 5 个，减少误杀。
        int candidateLimit = routeDecision.getConfidence() != null && routeDecision.getConfidence().doubleValue() >= 0.80D ? 3 : 5;
        List<DocumentRouteCandidate> candidates = routeDecision.getDocuments().stream()
            .filter(item -> StrUtil.isNotBlank(item.getDocumentId()) && StrUtil.isNotBlank(item.getLastIndexTaskId()))
            .limit(candidateLimit)
            .toList();
        /*
        第三步：候选不完整的兜底
        如果过滤后的候选列表为空（所有候选都不完整），也要退回到 fallback
         */
        if (candidates.isEmpty()) {
            // 路由返回的候选若都不完整，也要退回到 fallback。
            return fallbackDocuments(question, rewriteQuestion, candidateLimit);
        }
        /*
        第四步：低置信度时的候选合并
        如果置信度低于 0.55，说明路由结果不太可靠。这时候会采用一个保守扩范围的策略：
        1.保留路由返回的主候选
        2.调用 fallbackDocuments 获取兜底候选
        3.把两者合并，去重后返回
         */
        if (routeDecision.getConfidence() != null && routeDecision.getConfidence().doubleValue() < 0.55D) {
            // 低置信度时，把主候选与 fallback 候选合并，尽量保守扩范围。
            return mergeCandidates(candidates, fallbackDocuments(question, rewriteQuestion, candidateLimit), candidateLimit);
        }
        return candidates;
    }

    /** 兜底侯选选择
     * 基于文档元数据做保守 fallback 候选选择。
     *
     * @param question 原始问题
     * @param rewriteQuestion 改写问题
     * @param limit 返回上限
     * @return fallback 候选列表
     *
     * fallback 得分的计算比较简单，主要是基于关键词匹配：
     * 文档名包含关键词：加分
     * 知识域名包含关键词：加分
     * 业务分类包含关键词：加分
     * 文档标签包含关键词：加分
     */
    private List<DocumentRouteCandidate> fallbackDocuments(String question,
                                                           String rewriteQuestion,
                                                           int limit) {//路由失败时的兜底：对所有文档做元数据打分
        List<KnowledgeDocumentDescriptor> descriptors = documentKnowledgeService.listRetrievableDocuments();
        if (descriptors == null || descriptors.isEmpty()) {
            return List.of();
        }
        // 从原问题与改写问题中提取关键词，用于与文档名、知识域、标签等元数据做模糊匹配。
        List<String> queryTerms = extractFallbackTerms(question, rewriteQuestion);
        return descriptors.stream()
            .sorted((left, right) -> Double.compare(
                fallbackDescriptorScore(right, queryTerms),
                fallbackDescriptorScore(left, queryTerms)
            ))
            .limit(Math.max(1, limit))
            .map(item -> new DocumentRouteCandidate(
                String.valueOf(item.getDocumentId()),
                item.getDocumentName(),
                item.getLastIndexTaskId() == null ? "" : String.valueOf(item.getLastIndexTaskId()),
                StrUtil.blankToDefault(item.getKnowledgeScopeCode(), ""),
                StrUtil.blankToDefault(item.getKnowledgeScopeName(), ""),
                StrUtil.blankToDefault(item.getBusinessCategory(), ""),
                StrUtil.blankToDefault(item.getDocumentTags(), ""),
                BigDecimal.valueOf(fallbackDescriptorScore(item, queryTerms)).setScale(4, RoundingMode.HALF_UP),
                "低置信度时基于文档元数据进行保守扩范围候选"
            ))
            .toList();
    }

    /**候选合并
     * 合并主候选与 fallback 候选，并按文档 id 去重。
     *
     * @param primary 主候选
     * @param secondary 次候选
     * @param limit 返回上限
     * @return 合并后的候选列表
     * 合并逻辑：
     * 1.创建 LinkedHashMap（保持插入顺序）
     * 2.先放入主候选（主候选优先级更高）
     * 3.再放入次候选（使用 putIfAbsent，避免重复）
     * 4.截断到上限
     */
    private List<DocumentRouteCandidate> mergeCandidates(List<DocumentRouteCandidate> primary,
                                                         List<DocumentRouteCandidate> secondary,
                                                         int limit) {
        LinkedHashMap<String, DocumentRouteCandidate> merged = new LinkedHashMap<>();
        primary.forEach(item -> merged.put(item.getDocumentId(), item));
        secondary.forEach(item -> merged.putIfAbsent(item.getDocumentId(), item));
        return merged.values().stream().limit(Math.max(1, limit)).toList();
    }


    /**澄清判断逻辑       这个方法包含了五个判断条件，只要满足任意一个，就会触发澄清
     * 判断是否应该先向用户发起澄清。
     *
     * @param routeDecision 路由结果
     * @param candidateDocuments 候选文档
     * @return true 表示应先澄清，false 表示可直接继续
     */
    private boolean shouldAskClarification(KnowledgeRouteDecision routeDecision,
                                           List<DocumentRouteCandidate> candidateDocuments) {//判断是否需要反问（置信度 < 0.55 或 top-2 分数模糊）
        if (candidateDocuments == null || candidateDocuments.isEmpty()) {//条件一：候选文档为空+如果候选文档列表为空，说明路由完全失败，没有找到任何相关文档。这时候必须向用户澄清。
            // 连候选都没有时，必须先澄清。
            return true;
        }
        if (routeDecision == null || routeDecision.getDocuments() == null || routeDecision.getDocuments().isEmpty()) {//条件二：路由决策无效+如果路由决策为空，或者路由决策中的文档列表为空，说明路由系统出了问题，也需要澄清。
            // 路由器没有给出有效结果时，同样先澄清。
            return true;
        }
        if (routeDecision.getConfidence() == null || routeDecision.getConfidence().doubleValue() < 0.55D) {//条件三：置信度过低 + 如果置信度低于 0.55，说明路由结果不够可靠，不应该冒险自动选择文档。
            /*为什么阈值是 0.55？
            这个阈值是经过实验调优的：
            太高（如 0.70）：会导致过多的澄清，影响用户体验
            太低（如 0.40）：会导致错误路由，回答质量下降
            0.55 是一个平衡点，既能保证路由准确性，又不会过度澄清。
             */
            // 整体置信度过低时，不冒险自动选文档。
            return true;
        }
        if (candidateDocuments.size() < 2) {//条件四：只有一个候选时不澄清 + 如果只有一个候选文档，说明没有歧义，直接使用这个文档即可，不需要澄清。
            // 只有一个候选时，通常无需澄清。
            return false;
        }

        //条件五：前两名得分接近且知识域不同
        /*
        这是最精细的判断条件，它检查：
        1.前两名得分差距 - 如果差距 ≤ 3 分，说明得分很接近
        2.知识域是否相同 - 如果知识域不同，说明存在跨领域的歧义
        只有同时满足这两个条件，才会触发澄清。

        为什么要检查知识域？
        如果前两名候选都属于同一个知识域，即使得分接近，也说明问题的领域是明确的，只是不确定具体是哪个文档。这种情况下，即使选错了，也不会偏离太远。
        但如果前两名候选属于不同的知识域（比如一个是 Java 开发，一个是前端技术），说明存在真实的跨领域歧义，必须让用户确认。
         */
        BigDecimal topScore = candidateDocuments.get(0).getScore();
        BigDecimal secondScore = candidateDocuments.get(1).getScore();
        if (topScore == null || secondScore == null) {
            return false;
        }
        // 前两名分差过小且知识域不同，说明存在真实歧义，优先让用户确认。
        return topScore.subtract(secondScore).doubleValue() <= 3D
            && !Objects.equals(candidateDocuments.get(0).getKnowledgeScopeCode(), candidateDocuments.get(1).getKnowledgeScopeCode());
    }



    /**
     * 构造澄清回复正文。
     *
     * @param originalQuestion 原始问题
     * @param routeDecision 路由结果
     * @param candidateDocuments 候选文档
     * @return 澄清回复
     * ————————————————————————————————————————————————————————
     * 这个方法生成的澄清回复分两种情况：
     * 情况一：没有候选文档        当前我还不能稳定判断你想问哪份知识文档。请补充更具体的文档名、主题词，或者直接切换到"当前文档问答"后指定文档。    这是一个通用的澄清话术，引导用户提供更多信息或切换模式。
     * 情况二：有候选文档
     * 这个问题目前存在文档范围歧义，我先确认你想问哪一份：
     * 1. 《Spring Boot官方文档》（Java开发）
     * 2. 《MyBatis使用指南》（Java开发）
     * 3. 《React最佳实践》（前端技术）
     * 你可以直接回复文档名，或者改用"当前文档问答"模式明确指定文档。
     * 这个回复列出了前 3 个候选文档，每个文档显示：
     * 序号
     * 文档名称
     * 知识域名称（如果有）
     */
    private String buildClarificationReply(String originalQuestion,
                                           KnowledgeRouteDecision routeDecision,
                                           List<DocumentRouteCandidate> candidateDocuments) {//构建反问消息，列出候选文档供用户选择
        List<DocumentRouteCandidate> topCandidates = candidateDocuments == null ? List.of() : candidateDocuments.stream().limit(3).toList();
        if (topCandidates.isEmpty()) {
            // 没有任何候选时，返回通用型澄清话术。
            return "当前我还不能稳定判断你想问哪份知识文档。请补充更具体的文档名、主题词，或者直接切换到“当前文档问答”后指定文档。";
        }
        StringBuilder builder = new StringBuilder("这个问题目前存在文档范围歧义，我先确认你想问哪一份：\n");
        for (int index = 0; index < topCandidates.size(); index++) {
            DocumentRouteCandidate item = topCandidates.get(index);
            builder.append(index + 1)
                .append(". 《")
                .append(StrUtil.blankToDefault(item.getDocumentName(), item.getDocumentId()))
                .append("》");
            if (StrUtil.isNotBlank(item.getKnowledgeScopeName()) || StrUtil.isNotBlank(item.getKnowledgeScopeCode())) {
                builder.append("（")
                    .append(StrUtil.blankToDefault(item.getKnowledgeScopeName(), item.getKnowledgeScopeCode()))
                    .append("）");
            }
            builder.append('\n');
        }
        builder.append("你可以直接回复文档名，或者改用“当前文档问答”模式明确指定文档。");
        return builder.toString();
    }

    /**
     * 构造澄清候选项，供前端做快捷选择。
     *
     * @param candidateDocuments 候选文档
     * @return 澄清选项列表
     * 这个方法生成的澄清选项是一个字符串列表，每个选项的格式是：  我想问《文档名称》
     * 比如:
     * [
     *   "我想问《Spring Boot官方文档》",
     *   "我想问《MyBatis使用指南》",
     *   "我想问《React最佳实践》"
     * ]
     * 这些选项会被前端渲染成快捷按钮，用户可以直接点击选择，而不需要手动输入。
     */
    private List<String> buildClarificationOptions(List<DocumentRouteCandidate> candidateDocuments) {
        if (candidateDocuments == null || candidateDocuments.isEmpty()) {
            return List.of();
        }
        return candidateDocuments.stream()
            .limit(3)
            .map(item -> "我想问《" + StrUtil.blankToDefault(item.getDocumentName(), item.getDocumentId()) + "》")
            .toList();
    }


    /**
     * 构造澄清原因说明。
     *
     * @param routeDecision 路由结果
     * @param candidateDocuments 候选文档
     * @return 澄清原因文本
     *
     * 这个方法生成的原因说明分两种情况：
     * 情况一：路由失败     当前自动知识路由没有形成稳定候选，已改为先向用户确认文档范围。
     * 情况二：置信度不足   当前自动知识路由置信度为 0.45，候选文档数为 3，为避免误选文档，先返回澄清问题。      这个原因说明主要用于调试和日志记录，帮助开发者理解"为什么触发了澄清"。
     */
    private String buildClarificationReason(KnowledgeRouteDecision routeDecision,
                                            List<DocumentRouteCandidate> candidateDocuments) {
        if (routeDecision == null || routeDecision.getDocuments() == null || routeDecision.getDocuments().isEmpty()) {
            return "当前自动知识路由没有形成稳定候选，已改为先向用户确认文档范围。";
        }
        String confidenceText = routeDecision.getConfidence() == null ? "-" : routeDecision.getConfidence().toPlainString();
        int candidateCount = candidateDocuments == null ? 0 : candidateDocuments.size();
        return "当前自动知识路由置信度为 " + confidenceText + "，候选文档数为 " + candidateCount + "，为避免误选文档，先返回澄清问题。";
    }

    private List<String> extractFallbackTerms(String question, String rewriteQuestion) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        String routingText = (safeText(question) + " " + safeText(rewriteQuestion)).trim();
        for (String segment : routingText.split("[\\s、，,；;：:（）()\\-的和及与或]+")) {
            String trimmed = segment.trim();
            if (trimmed.length() >= 2) {
                terms.add(trimmed);
                if (trimmed.length() >= 4) {
                    int maxGram = Math.min(6, trimmed.length());
                    for (int gram = 2; gram <= maxGram; gram++) {
                        for (int start = 0; start + gram <= trimmed.length(); start++) {
                            terms.add(trimmed.substring(start, start + gram));
                        }
                    }
                }
            }
        }
        return terms.stream().limit(40).toList();
    }

    private double fallbackDescriptorScore(KnowledgeDocumentDescriptor descriptor, List<String> queryTerms) {
        String content = normalizeFallbackText(String.join(" ",
            StrUtil.blankToDefault(descriptor.getDocumentName(), ""),
            StrUtil.blankToDefault(descriptor.getKnowledgeScopeCode(), ""),
            StrUtil.blankToDefault(descriptor.getKnowledgeScopeName(), ""),
            StrUtil.blankToDefault(descriptor.getBusinessCategory(), ""),
            StrUtil.blankToDefault(descriptor.getDocumentTags(), "")
        ));
        if (queryTerms == null || queryTerms.isEmpty() || content.isBlank()) {
            return 0D;
        }
        double score = 0D;
        List<String> sortedTerms = queryTerms.stream()
            .map(this::normalizeFallbackText)
            .filter(StrUtil::isNotBlank)
            .distinct()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();
        List<String> matched = new ArrayList<>();
        for (String term : sortedTerms) {
            if (term.length() < 2) {
                continue;
            }
            boolean covered = matched.stream().anyMatch(existing -> existing.contains(term));
            if (covered) {
                continue;
            }
            if (content.contains(term)) {
                matched.add(term);
                if (term.length() >= 8) {
                    score += 12D;
                }
                else if (term.length() >= 5) {
                    score += 8D;
                }
                else if (term.length() >= 3) {
                    score += 4D;
                }
                else {
                    score += 2D;
                }
            }
        }
        return score;
    }

    private String normalizeFallbackText(String value) {
        return StrUtil.blankToDefault(value, "")
            .replaceAll("[\\s>`*#_\\-，,。；;：:（）()“”\"'\\[\\]]+", "")
            .toLowerCase(Locale.ROOT);
    }

    private String buildDocumentModeNoEvidenceReply(String question, boolean requiresFreshSearch) {//检测能力类问题和闲聊问题，给出合适的无证据回复
        String normalizedQuestion = safeText(question);
        if (looksLikeCapabilityQuestion(normalizedQuestion)) {
            return "当前你正在使用“当前文档问答”模式，我会优先基于所选文档回答。这个问题更像是在询问助手能力，而不是当前文档内容。如果你想了解我能做什么，请切换到“开放式提问”模式。";
        }
        if (looksLikeOpenChatQuestion(normalizedQuestion, requiresFreshSearch)) {
            return "当前你正在使用“当前文档问答”模式，我只能基于所选文档回答。这个问题更像开放式提问，例如天气、最新信息或一般交流。如果你想继续问这类问题，请切换到“开放式提问”模式。";
        }
        return StrUtil.blankToDefault(
            properties.getNoEvidenceReply(),
            "当前没有从当前文档中检索到足够证据，暂时不能给出可靠结论。你可以补充更具体的标题、术语或关键词后再试。"
        );
    }

    private boolean looksLikeCapabilityQuestion(String normalizedQuestion) {
        if (StrUtil.isBlank(normalizedQuestion)) {
            return false;
        }
        return CAPABILITY_HINTS.stream().anyMatch(normalizedQuestion::contains);
    }

    private boolean looksLikeOpenChatQuestion(String normalizedQuestion, boolean requiresFreshSearch) {
        if (StrUtil.isBlank(normalizedQuestion)) {
            return false;
        }
        if (requiresFreshSearch) {
            return true;
        }
        if (OPEN_CHAT_HINTS.stream().anyMatch(normalizedQuestion::contains)) {
            return true;
        }
        return CHITCHAT_HINTS.stream().anyMatch(normalizedQuestion::contains);
    }
}
