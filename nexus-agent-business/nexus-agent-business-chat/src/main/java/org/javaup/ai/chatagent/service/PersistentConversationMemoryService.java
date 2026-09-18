package org.javaup.ai.chatagent.service;

import cn.hutool.core.util.StrUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.data.SuperAgentChatMemorySummary;
import org.javaup.ai.chatagent.mapper.SuperAgentChatMemorySummaryMapper;
import org.javaup.ai.chatagent.model.ConversationExchangeView;
import org.javaup.ai.chatagent.model.ConversationMemorySummaryView;
import org.javaup.ai.chatagent.model.memory.ConversationMemoryContext;
import org.javaup.ai.chatagent.model.memory.ConversationSummaryPayload;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.prompt.PromptTemplateNames;
import org.javaup.ai.prompt.PromptTemplateService;
import org.javaup.enums.BusinessStatus;
import org.javaup.enums.ChatTurnStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 服务层
 * @author: 阿星不是程序员
 **/
/*
会话记忆管理的核心实现
这是记忆管理的完整实现，负责摘要的持久化、增量压缩、和最近对话窗口的渲染。

 */
@Slf4j
@Service
public class PersistentConversationMemoryService implements ConversationMemoryService {

    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("\\{.*}", Pattern.DOTALL);
    private static final Pattern RETRIEVAL_HINT_PATTERN = Pattern.compile("[a-zA-Z0-9._-]{2,}|[\\p{IsHan}]{2,12}");
    private static final int MAX_SECTION_ITEMS = 6;
    private static final int MAX_ITEM_LENGTH = 80;
    private static final int MAX_GOAL_LENGTH = 120;
    private static final int MAX_QUESTION_LENGTH = 160;
    private static final int MAX_ANSWER_LENGTH = 320;
    private static final int MAX_ANSWER_CONTEXT_ANSWER_LENGTH = 220;

    private final ConversationArchiveStore conversationArchiveStore;
    private final SuperAgentChatMemorySummaryMapper summaryMapper;
    private final ObjectMapper objectMapper;
    private final ChatRagProperties properties;
    private final ExecutorService chatMemorySummaryExecutorService;
    private final ObservedChatModelService observedChatModelService;
    private final PromptTemplateService promptTemplateService;
    private final Set<String> refreshingConversationIds = ConcurrentHashMap.newKeySet();

    @Resource
    private UidGenerator uidGenerator;

    public PersistentConversationMemoryService(ConversationArchiveStore conversationArchiveStore,
                                               SuperAgentChatMemorySummaryMapper summaryMapper,
                                               ObjectMapper objectMapper,
                                               ChatRagProperties properties,
                                               @Qualifier("chatMemorySummaryExecutorService") ExecutorService chatMemorySummaryExecutorService,
                                               ObservedChatModelService observedChatModelService,
                                               PromptTemplateService promptTemplateService) {
        this.conversationArchiveStore = conversationArchiveStore;
        this.summaryMapper = summaryMapper;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.chatMemorySummaryExecutorService = chatMemorySummaryExecutorService;
        this.observedChatModelService = observedChatModelService;
        this.promptTemplateService = promptTemplateService;
    }







    /*
loadMemoryContext() 流程:
1.如果摘要功能关闭 → 只渲染最近几轮原文返回
2.如果开启 → 调用 refreshSummaryIfNecessary() 增量压缩
3.加载最近交换记录
4.渲染最近对话原文（renderRecentTranscript）
5.渲染回答阶段的历史（renderAnswerRecentTranscript）
6.组装完整的 ConversationMemoryContext 返回
     */
    @Override
    public ConversationMemoryContext loadMemoryContext(String conversationId) {
        return loadMemoryContext(conversationId, null);
    }


    /*
    loadMemoryContext 方法是会话记忆装载的核心，它根据配置决定是否开启长期摘要功能。下面是完整的流程图：
     */
    @Override
    public ConversationMemoryContext loadMemoryContext(String conversationId, ConversationTraceRecorder traceRecorder) {
        if (StrUtil.isBlank(conversationId)) {
            // 没有合法会话 id 时，直接返回空上下文，避免后续查询和压缩流程无意义执行。
            return emptyContext();
        }

        ChatRagProperties.HistorySummaryProperties historySummaryProperties = properties.getHistorySummary();
        if (!historySummaryProperties.isEnabled()) {
            // 未开启长期摘要时，直接基于最近若干轮对话构造 rewrite / answer 所需窗口。





            //这个方法有两种处理模式

            /*
            模式一：未开启长期摘要
            如果配置中未开启长期摘要功能，系统会直接基于最近若干轮对话构造窗口：
            这种模式下：
从数据库查询最近 N 轮对话（N 由配置决定）
渲染成两种窗口：recentTranscript（用于问题改写）和 answerRecentTranscript（用于答案生成）
不进行历史压缩，compressionApplied 为 false
             */
            // 未开启长期摘要时，直接基于最近若干轮对话构造 rewrite / answer 所需窗口。
            String recentTranscript = renderRecentTranscript(
                conversationArchiveStore.listRecentExchanges(conversationId, Math.max(1, properties.getRewriteHistoryTurns() * 3)),
                Math.max(1, properties.getRewriteHistoryTurns()),
                historySummaryProperties.getRecentTranscriptMaxChars()
            );
            String answerRecentTranscript = renderAnswerRecentTranscript(
                conversationArchiveStore.listRecentExchanges(conversationId, Math.max(1, properties.getRewriteHistoryTurns() * 3)),
                Math.max(1, properties.getRewriteHistoryTurns()),
                Math.max(1, properties.getAnswerHistoryMaxChars())
            );
            return ConversationMemoryContext.builder()
                .assembledHistory(recentTranscript)
                .longTermSummary("")
                .recentTranscript(recentTranscript)
                .answerRecentTranscript(answerRecentTranscript)
                .summaryPayload(ConversationSummaryPayload.builder().build())
                .coveredExchangeId(0L)
                .coveredExchangeCount(0)
                .compressionCount(0)
                .compressionApplied(false)
                .build();
        }



        /*
        模式二：开启长期摘要  系统会进行增量压缩
        这种模式下：
调用 refreshSummaryIfNecessary 方法检查是否需要刷新摘要
从数据库读取长期摘要
仍然保留最近 N 轮的完整对话窗口
最终的 assembledHistory 是长期摘要与最近窗口的拼接结果
         */
        // 开启长期摘要时，先检查是否需要基于新增稳定轮次刷新摘要快照。   步骤一：刷新摘要快照
        SuperAgentChatMemorySummary summaryState = refreshSummaryIfNecessary(//调用 refreshSummaryIfNecessary 检查是否需要基于新增稳定轮次刷新摘要快照。
            conversationId,
            findSummary(conversationId).orElse(null),
            traceRecorder
        );
        // 将数据库中的摘要快照还原为结构化 payload，供后续规划阶段读取。 步骤二：还原结构化 payload
        ConversationSummaryPayload summaryPayload = readSummaryPayload(summaryState);

        // 最近窗口仍然需要保留，因为长期摘要无法替代最新几轮的细粒度上下文。    步骤三：查询最新轮次  从数据库查询最近 N 轮对话。recentFetchLimit 会把配置的轮次数乘以一个系数（通常是 3），确保拉取足够的数据供后续过滤。
        List<ConversationExchangeView> recentExchanges = conversationArchiveStore.listRecentExchanges(
            conversationId,
            recentFetchLimit(historySummaryProperties.getKeepRecentTurns())
        );


        /*
        步骤四：渲染两种窗口
        渲染两种窗口：
recentTranscript：面向编排的窗口，包含用户问题和助手回答
answerRecentTranscript：面向回答的窗口，只包含用户问题
         */
        String recentTranscript = renderRecentTranscript(
            recentExchanges,
            historySummaryProperties.getKeepRecentTurns(),
            historySummaryProperties.getRecentTranscriptMaxChars()
        );
        String answerRecentTranscript = renderAnswerRecentTranscript(
            recentExchanges,
            historySummaryProperties.getKeepRecentTurns(),
            Math.max(1, properties.getAnswerHistoryMaxChars())
        );
        // 步骤五：提取长期摘要文本 从摘要快照中提取纯文本摘要。
        String longTermSummary = summaryState == null ? "" : safeText(summaryState.getSummaryText());

        return ConversationMemoryContext.builder()
// 最终 assembledHistory 是长期摘要与最近原文窗口的拼接结果，供 rewrite / route 等阶段复用。
                /*
                步骤六：拼接长期摘要与最近窗口 assembleHistory 方法把长期摘要与最近窗口拼接成最终的历史文本：
                 */
            .assembledHistory(assembleHistory(longTermSummary, recentTranscript))
            .longTermSummary(longTermSummary)
            .recentTranscript(recentTranscript)
            .answerRecentTranscript(answerRecentTranscript)
            .summaryPayload(summaryPayload)
            .coveredExchangeId(summaryState == null ? 0L : defaultLong(summaryState.getCoveredExchangeId()))
            .coveredExchangeCount(summaryState == null ? 0 : safeInt(summaryState.getCoveredExchangeCount()))
            .compressionCount(summaryState == null ? 0 : safeInt(summaryState.getCompressionCount()))
            .compressionApplied(StrUtil.isNotBlank(longTermSummary))
            .build();//步骤七：构建 ConversationMemoryContext
    }





    //异步刷新，用 refreshingConversationIds Set 去重防止并发
    @Override
    public void refreshConversationSummaryAsync(String conversationId) {
        if (StrUtil.isBlank(conversationId) || !properties.getHistorySummary().isEnabled()) {
            return;
        }

        if (!refreshingConversationIds.add(conversationId)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                refreshSummaryIfNecessary(conversationId, findSummary(conversationId).orElse(null), null);
            }
            catch (Exception exception) {
                log.warn("异步预热会话摘要失败, conversationId={}", conversationId, exception);
            }
            finally {
                refreshingConversationIds.remove(conversationId);
            }
        }, chatMemorySummaryExecutorService);
    }

    @Override
    public ConversationMemorySummaryView getConversationSummary(String conversationId) {
        if (StrUtil.isBlank(conversationId)) {
            return emptySummaryView("");
        }
        return toSummaryView(conversationId, findSummary(conversationId).orElse(null));
    }

    @Override
    public ConversationMemorySummaryView rebuildConversationSummary(String conversationId) {
        if (StrUtil.isBlank(conversationId)) {
            return emptySummaryView("");
        }

        if (!refreshingConversationIds.add(conversationId)) {
            return getConversationSummary(conversationId);
        }
        try {
            summaryMapper.delete(new LambdaQueryWrapper<SuperAgentChatMemorySummary>()
                .eq(SuperAgentChatMemorySummary::getConversationId, conversationId));
            SuperAgentChatMemorySummary rebuiltState = refreshSummaryIfNecessary(conversationId, null, null);
            return toSummaryView(conversationId, rebuiltState);
        }
        finally {
            refreshingConversationIds.remove(conversationId);
        }
    }

    @Override
    public void deleteConversationSummary(String conversationId) {
        if (StrUtil.isBlank(conversationId)) {
            return;
        }
        summaryMapper.delete(new LambdaQueryWrapper<SuperAgentChatMemorySummary>()
            .eq(SuperAgentChatMemorySummary::getConversationId, conversationId));
    }





    /*
增量摘要压缩    （历史压缩的核心 它采用增量压缩策略，只处理尚未被摘要覆盖的新增轮次：）
这是记忆管理最核心的机制。不是每次都重新压缩全部历史，而是增量处理新增的对话。
流程：
1.查看当前摘要覆盖到了哪个 exchangeId
2.查找之后新增的、已完成的交换记录
3.如果新增数量超过 keepRecentTurns，把溢出部分分批压缩
4.每批调用 mergeSummaryPayload() 做 LLM 合并
5.持久化新的摘要快照
________________________________
在两种模式下，系统都需要渲染最近对话窗口。这里有两个关键方法：renderRecentTranscript 和 renderAnswerRecentTranscript，它们的用途不同。

 */
    private SuperAgentChatMemorySummary refreshSummaryIfNecessary(String conversationId,
                                                                  SuperAgentChatMemorySummary currentState,
                                                                  ConversationTraceRecorder traceRecorder) {
        ChatRagProperties.HistorySummaryProperties historySummaryProperties = properties.getHistorySummary();
        /*
如果 currentState == null，说明这是第一次压缩，coveredExchangeId = 0，表示从头开始
如果 currentState != null，说明之前已经压缩过，coveredExchangeId 就是上次压缩到的最后一轮的 ID
比如，上次压缩覆盖了第 1-6 轮，那么 coveredExchangeId = 6。这次压缩时，只需要从第 7 轮开始拉取新增轮次，避免重复压缩第 1-6 轮。
         */
        long coveredExchangeId = currentState == null ? 0L : defaultLong(currentState.getCoveredExchangeId()); //coveredExchangeId 是"上次压缩覆盖到的最后一轮 ID"，它是增量压缩的核心标记：

        // 步骤一：拉取新增轮次  只拉取"摘要尚未覆盖"的新增轮次，避免重复压缩旧内容。
        List<ConversationExchangeView> incrementalExchanges = conversationArchiveStore.listExchangesAfter(
            conversationId,
            coveredExchangeId// 上次压缩覆盖到的最后一轮 ID
        );
        //步骤二：过滤稳定轮次  只有稳定轮次（已完成的对话）才允许进入长期摘要，运行中或异常中断的轮次不参与压缩。
        List<ConversationExchangeView> stableExchanges = incrementalExchanges.stream()
            .filter(this::isStableSummaryExchange)
            .toList();

        //步骤三：计算溢出轮次 keepRecentTurns 以内的轮次仍保留在最近窗口中，因此只有溢出的老轮次才进入长期摘要。
        int overflowCount = Math.max(0, stableExchanges.size() - historySummaryProperties.getKeepRecentTurns());
        if (overflowCount <= 0) {
            return currentState;// 没有溢出，不需要压缩  比如，如果配置 keepRecentTurns = 5，当前有 8 轮稳定对话，那么只有前 3 轮需要压缩。
        }

        List<ConversationExchangeView> overflowExchanges = stableExchanges.subList(0, overflowCount);
        SuperAgentChatMemorySummary workingState = currentState;

        //步骤四：分批压缩  采用分批压缩，避免一次性向模型塞入过多历史内容。
        /*
        比如，如果配置 compressionBatchTurns = 3，有 10 轮需要压缩，那么会分成 4 批：前 3 轮、第 4-6 轮、第 7-9 轮、第 10 轮。
         */
        for (int start = 0; start < overflowExchanges.size(); start += historySummaryProperties.getCompressionBatchTurns()) {
            int end = Math.min(start + historySummaryProperties.getCompressionBatchTurns(), overflowExchanges.size());
            List<ConversationExchangeView> batch = overflowExchanges.subList(start, end);
            // 每一批都会基于"旧摘要 + 新增批次"生成新的结构化摘要 payload。
            ConversationSummaryPayload mergedPayload = mergeSummaryPayload(readSummaryPayload(workingState), batch, traceRecorder);
            //...保存快照
            ConversationExchangeView lastExchange = batch.get(batch.size() - 1);
            workingState = saveSummarySnapshot(
                conversationId,
                workingState,
                mergedPayload,
                lastExchange.getExchangeId(),
                safeInt(workingState == null ? null : workingState.getCoveredExchangeCount()) + batch.size(),
                resolveSourceTime(lastExchange)
            );
        }
        return workingState;
    }








    /*
    LLM摘要合并   负责把旧摘要和新增批次合并成新的摘要，它采用模型优先、规则兜底的策略：    两种合并策略
    流程：
    1.用 conversation-summary-system 模板构建系统提示词
    2.用 conversation-summary-merge 模板构建用户提示词（已有摘要 JSON + 新对话原文）
    3.调用 LLM 生成合并后的结构化摘要
    4.解析 JSON 结果为 ConversationSummaryPayload
    5.失败时降级到 fallbackMerge()（规则提取）
     */
    private ConversationSummaryPayload mergeSummaryPayload(ConversationSummaryPayload existingPayload,
                                                           List<ConversationExchangeView> batch,
                                                           ConversationTraceRecorder traceRecorder) {
        try {
            /*
            策略1：模型合并（优先）
            调用大模型，把旧摘要和新增批次合并成新的结构化摘要：
             */
            // 摘要合并阶段同样走模型调用，并记录到模型用量追踪中。
            String content = observedChatModelService.callText(
                "summary",
                promptTemplateService.render(PromptTemplateNames.CONVERSATION_SUMMARY_SYSTEM, Map.of()),
                buildSummaryMergePrompt(existingPayload, batch),
                traceRecorder
            );
            ConversationSummaryPayload parsedPayload = parseSummaryPayload(content);
            if (parsedPayload != null) {
                // 模型返回合法结构时，统一再做一轮规范化，防止字段过长或列表膨胀
                return normalizePayload(parsedPayload);
            }
        }
        catch (Exception exception) {
            // 模型压缩失败不会中断主流程，而是回退到规则式压缩。
            log.warn("合并会话长期摘要失败，回退到规则压缩: {}", exception.getMessage());
        }
        return fallbackMerge(existingPayload, batch);
    }



/*构建合并提示词
摘要合并用到了用户提示词模板。
模板中有两个占位符，由 buildSummaryMergePrompt 方法填充：
<existingSummaryJson>：旧摘要的 JSON，由 writePayloadJson(normalizePayload(copyPayload(existingPayload))) 生成
<newConversationBatch>：新增批次的对话原文，由 renderCompressionTranscript(batch) 渲染
————————————————————————————————————————————————————————————————————————————————————
模型会收到两部分输入：
existingSummaryJson：旧摘要的 JSON 格式
newConversationBatch：新增批次的对话原文
——————————————————————————————————————————————————————————————————————————————————————————
这个方法内部又调用了三个方法：
copyPayload(existingPayload)：深拷贝旧摘要，避免修改原对象
normalizePayload(...)：规范化摘要，防止字段过长或列表膨胀
writePayloadJson(...)：序列化为 JSON 字符串
 */
    private String buildSummaryMergePrompt(ConversationSummaryPayload existingPayload,
                                           List<ConversationExchangeView> batch) {
        String existingJson = writePayloadJson(normalizePayload(copyPayload(existingPayload)));
        return promptTemplateService.render(PromptTemplateNames.CONVERSATION_SUMMARY_MERGE, Map.of(
            "existingSummaryJson", StrUtil.isNotBlank(existingJson) ? existingJson : "{}",
            "newConversationBatch", renderCompressionTranscript(batch)
        ));
    }










    /*规则式合并
    规则降级合并：LLM调用失败的兜底方案
把新对话的要点追加到摘要
用正则提取检索提示关键词
更新待解决问题列表
——————————————————————————————————————————————————————————————————————————————————————
规则式合并的逻辑：
1.拼接摘要：把旧摘要与新批次的亮点拼接起来
2.填充会话目标：如果还没有会话目标，用最后一个问题兜底
3.收集待跟进问题：把批次中的所有问题加入待跟进列表
4.提取检索提示词：从最后一个问题中提取关键词
     */
    private ConversationSummaryPayload fallbackMerge(ConversationSummaryPayload existingPayload,
                                                     List<ConversationExchangeView> batch) {
        ConversationSummaryPayload mergedPayload = copyPayload(existingPayload);
        String batchHighlight = renderFallbackBatchHighlight(batch);
        // 先把旧摘要与本批亮点拼接起来，形成新的 summary 主体。
        String mergedSummary = joinNonBlank(mergedPayload.getSummary(), batchHighlight, "；");
        mergedPayload.setSummary(clipText(mergedSummary, properties.getHistorySummary().getSummaryMaxChars()));

        ConversationExchangeView lastExchange = batch.get(batch.size() - 1);
        if (StrUtil.isBlank(mergedPayload.getConversationGoal()) && StrUtil.isNotBlank(lastExchange.getQuestion())) {
            // 若还没有明确会话目标，则用最近一批的最后一个问题兜底填充。
            mergedPayload.setConversationGoal(clipText(lastExchange.getQuestion(), MAX_GOAL_LENGTH));
        }

        List<String> pendingQuestions = new ArrayList<>(safeList(mergedPayload.getPendingQuestions()));
        for (ConversationExchangeView exchange : batch) {
            if (StrUtil.isNotBlank(exchange.getQuestion())) {
                // 批次中的问题默认都进入待跟进候选，再统一去重和限长。
                pendingQuestions.add(clipText(exchange.getQuestion(), MAX_ITEM_LENGTH));
            }
        }
        mergedPayload.setPendingQuestions(deduplicateAndLimit(pendingQuestions));

        List<String> retrievalHints = new ArrayList<>(safeList(mergedPayload.getRetrievalHints()));
        if (StrUtil.isNotBlank(lastExchange.getQuestion())) {
            // 从最后一个问题中提取检索提示词，帮助后续改写和路由理解主题。
            retrievalHints.addAll(extractRetrievalHints(lastExchange.getQuestion()));
        }
        mergedPayload.setRetrievalHints(deduplicateAndLimit(retrievalHints));
        return normalizePayload(mergedPayload);
    }




    //持久化摘要快照，带乐观并发检查
    private SuperAgentChatMemorySummary saveSummarySnapshot(String conversationId,
                                                            SuperAgentChatMemorySummary currentState,
                                                            ConversationSummaryPayload payload,
                                                            long coveredExchangeId,
                                                            int coveredExchangeCount,
                                                            Date lastSourceEditTime) {
        SuperAgentChatMemorySummary latestState = findSummary(conversationId).orElse(null);
        long latestCoveredExchangeId = latestState == null ? 0L : defaultLong(latestState.getCoveredExchangeId());

        if (latestCoveredExchangeId > coveredExchangeId) {
            return latestState;
        }

        if (latestState != null
            && latestCoveredExchangeId == coveredExchangeId
            && StrUtil.isNotBlank(latestState.getSummaryText())) {
            return latestState;
        }

        String summaryText = buildLongTermSummaryText(payload);
        String summaryJson = writePayloadJson(payload);

        if (latestState == null) {
            SuperAgentChatMemorySummary newState = new SuperAgentChatMemorySummary();
            newState.setId(uidGenerator.getUid());
            newState.setConversationId(conversationId);
            newState.setCoveredExchangeId(coveredExchangeId);
            newState.setCoveredExchangeCount(Math.max(coveredExchangeCount, 0));
            newState.setCompressionCount(1);
            newState.setSummaryVersion(1);
            newState.setSummaryText(summaryText);
            newState.setSummaryJson(summaryJson);
            newState.setLastSourceEditTime(lastSourceEditTime);
            newState.setStatus(BusinessStatus.YES.getCode());
            summaryMapper.insert(newState);
            return newState;
        }

        SuperAgentChatMemorySummary updateState = new SuperAgentChatMemorySummary();
        updateState.setId(latestState.getId());
        updateState.setCoveredExchangeId(coveredExchangeId);
        updateState.setCoveredExchangeCount(Math.max(coveredExchangeCount, safeInt(latestState.getCoveredExchangeCount())));
        updateState.setCompressionCount(safeInt(latestState.getCompressionCount()) + 1);
        updateState.setSummaryVersion(safeInt(latestState.getSummaryVersion()) + 1);
        updateState.setSummaryText(summaryText);
        updateState.setSummaryJson(summaryJson);
        updateState.setLastSourceEditTime(lastSourceEditTime);
        summaryMapper.updateById(updateState);

        latestState.setCoveredExchangeId(updateState.getCoveredExchangeId());
        latestState.setCoveredExchangeCount(updateState.getCoveredExchangeCount());
        latestState.setCompressionCount(updateState.getCompressionCount());
        latestState.setSummaryVersion(updateState.getSummaryVersion());
        latestState.setSummaryText(updateState.getSummaryText());
        latestState.setSummaryJson(updateState.getSummaryJson());
        latestState.setLastSourceEditTime(updateState.getLastSourceEditTime());
        return latestState;
    }

    private Optional<SuperAgentChatMemorySummary> findSummary(String conversationId) {
        return Optional.ofNullable(summaryMapper.selectOne(
            new LambdaQueryWrapper<SuperAgentChatMemorySummary>()
                .eq(SuperAgentChatMemorySummary::getConversationId, conversationId)
                .orderByDesc(SuperAgentChatMemorySummary::getId)
                .last("LIMIT 1")
        ));
    }


    /*
    readSummaryPayload 方法把数据库中的摘要快照还原为结构化 ConversationSummaryPayload 对象：
    这个方法有两种处理路径：
优先路径：如果 summaryJson 不为空，解析 JSON 并规范化
兜底路径：如果 JSON 为空（老数据或异常数据），至少保留纯文本摘要
     */
    private ConversationSummaryPayload readSummaryPayload(SuperAgentChatMemorySummary summaryState) {
        if (summaryState == null) {
            return ConversationSummaryPayload.builder().build();
        }
        if (StrUtil.isNotBlank(summaryState.getSummaryJson())) {
            ConversationSummaryPayload payload = parseSummaryPayload(summaryState.getSummaryJson());
            if (payload != null) {
                // 优先相信结构化 JSON，并再做一轮规范化。
                return normalizePayload(payload);
            }
        }
        // 老数据或异常数据没有 JSON 时，至少保留纯文本摘要。
        ConversationSummaryPayload fallbackPayload = ConversationSummaryPayload.builder()
            .summary(summaryState.getSummaryText())
            .build();
        return normalizePayload(fallbackPayload);
    }

    /*
    模型返回的是一段文本，parseSummaryPayload 负责把它解析成结构化的 ConversationSummaryPayload 对象：
    -——————————————————————————————————————————————————————————————————————————————————————————————————
    这个方法有两个关键细节
    1. 先提取 JSON 对象再解析
extractJsonObject(raw) 会从模型输出中提取最外层的 JSON 对象。这是因为某些模型会在 JSON 前后附加说明文字，比如：
    以下是合并后的长期记忆：
{"summary": "...", "conversation_goal": "..."}              extractJsonObject 会把 {...} 部分提取出来，避免直接解析失败。

2. 解析失败返回 null，由上层 fallback 接管
如果解析失败，方法返回 null，调用方会检查这个返回值：
ConversationSummaryPayload parsedPayload = parseSummaryPayload(content);
if (parsedPayload != null) {
    return normalizePayload(parsedPayload);
}
// parsedPayload 为 null，走到 catch 外面的 fallbackMerge
解析成功后，还会调用 normalizePayload 做一轮规范化，防止字段过长或列表膨胀。

策略2：规则合并（兜底）
如果模型调用失败或返回格式不合法，系统会回退到规则式合并：
return fallbackMerge(existingPayload, batch);
规则式合并会从问题、回答和关键词中拼出一份可用摘要，虽然质量不如模型合并，但能保证系统不会因为模型失败而中断。

     */
    private ConversationSummaryPayload parseSummaryPayload(String raw) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        try {
            // 某些模型输出可能包裹了说明文本，这里会先提取最外层 JSON 对象再解析。
            JsonNode root = objectMapper.readTree(extractJsonObject(raw));
            ConversationSummaryPayload payload = ConversationSummaryPayload.builder()
                .summary(root.path("summary").asText(""))
                .conversationGoal(root.path("conversation_goal").asText(""))
                .stableFacts(readStringArray(root.path("stable_facts")))
                .userPreferences(readStringArray(root.path("user_preferences")))
                .resolvedPoints(readStringArray(root.path("resolved_points")))
                .pendingQuestions(readStringArray(root.path("pending_questions")))
                .retrievalHints(readStringArray(root.path("retrieval_hints")))
                .build();
            return normalizePayload(payload);
        }
        catch (Exception exception) {
            // 解析失败仅记录 debug，后续由上层 fallback 接管。
            log.debug("解析会话长期摘要 JSON 失败: {}", raw, exception);
            return null;
        }
    }

    private List<String> readStringArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        node.forEach(item -> {
            String text = item.asText("").trim();
            if (StrUtil.isNotBlank(text)) {
                result.add(text);
            }
        });
        return result;
    }

    /*
    渲染对话原文
    ————————————————————————————————————————————————————————————————————————————
    把新增批次的对话渲染成文本格式，供模型理解。渲染示例：
    用户：Java 的特点是什么？
    助手：Java 是一种面向对象的编程语言...
    用户：它的性能怎么样？
    助手：Java 的性能在企业级应用中表现优秀...
     */
    private String renderCompressionTranscript(List<ConversationExchangeView> batch) {
        StringBuilder builder = new StringBuilder();
        for (ConversationExchangeView exchange : batch) {
            if (StrUtil.isNotBlank(exchange.getQuestion())) {
                builder.append("用户：")
                    .append(clipText(exchange.getQuestion(), MAX_QUESTION_LENGTH))
                    .append('\n');
            }
            if (StrUtil.isNotBlank(exchange.getAnswer())) {
                builder.append("助手：")
                    .append(clipText(exchange.getAnswer(), MAX_ANSWER_LENGTH))
                    .append('\n');
            }
            if (exchange.getStatus() == ChatTurnStatus.STOPPED && StrUtil.isNotBlank(exchange.getErrorMessage())) {
                builder.append("补充说明：本轮被停止，说明=")
                    .append(clipText(exchange.getErrorMessage(), MAX_ITEM_LENGTH))
                    .append('\n');
            }
        }
        return builder.toString().trim();
    }

    private String renderFallbackBatchHighlight(List<ConversationExchangeView> batch) {
        List<String> highlights = new ArrayList<>();
        for (ConversationExchangeView exchange : batch) {
            if (StrUtil.isNotBlank(exchange.getQuestion())) {
                highlights.add("用户关注：" + clipText(exchange.getQuestion(), MAX_ITEM_LENGTH));
            }
            if (StrUtil.isNotBlank(exchange.getAnswer())) {
                highlights.add("已有结论：" + clipText(exchange.getAnswer(), MAX_ITEM_LENGTH));
            }
            if (highlights.size() >= 4) {
                break;
            }
        }
        return String.join("；", highlights);
    }





//渲染最近对话原文，保留尾部，控制字符预算
    /*
    面向编排的窗口
    这个方法渲染用于问题改写和路由的最近对话窗口，包含用户问题和助手回答：
    ————————————————————————————————————————————————————————————————————————————————————————
    关键逻辑：
1.过滤可渲染轮次：调用 shouldKeepInRecentWindow 方法，过滤掉不应该出现在窗口中的轮次
2.截取最近 N 轮：只保留最近 keepRecentTurns 轮，避免窗口无限膨胀
3.渲染问题和回答：
用户问题：直接渲染，最多保留 MAX_QUESTION_LENGTH 字符
助手回答：只保留已完成的回答（status == COMPLETED），避免把中途输出或异常轮次当作稳定上下文
4.截断窗口：最终按 recentTranscriptMaxChars 截断，控制总长度
————————————————————————————————————————————————————————————————————
渲染实例
【最近对话原文】
用户：Java 的特点是什么？
助手：Java 是一种面向对象的编程语言...
用户：它的性能怎么样？
助手：Java 的性能在企业级应用中表现优秀...
     */
    private String renderRecentTranscript(List<ConversationExchangeView> exchanges,
                                          int keepRecentTurns,
                                          int recentTranscriptMaxChars) {
        List<ConversationExchangeView> renderableExchanges = new ArrayList<>();
        for (ConversationExchangeView exchange : exchanges) {
            //判断一个轮次是否应保留在最近窗口中。
            if (shouldKeepInRecentWindow(exchange)) {
                renderableExchanges.add(exchange);
            }
        }
        if (renderableExchanges.isEmpty()) {
            return "";
        }
        // 只保留最近 keepRecentTurns 轮，避免窗口无限膨胀。
        int fromIndex = Math.max(0, renderableExchanges.size() - keepRecentTurns);
        StringBuilder builder = new StringBuilder("【最近对话原文】\n");
        for (int index = fromIndex; index < renderableExchanges.size(); index++) {
            ConversationExchangeView exchange = renderableExchanges.get(index);
            if (StrUtil.isNotBlank(exchange.getQuestion())) {
                builder.append("用户：")
                    .append(clipText(exchange.getQuestion(), MAX_QUESTION_LENGTH))
                    .append('\n');
            }
            if (exchange.getStatus() == ChatTurnStatus.COMPLETED && StrUtil.isNotBlank(exchange.getAnswer())) {
                // rewrite / route 窗口只保留已完成回答，避免把中途输出或异常轮次当作稳定上下文。
                builder.append("助手：")
                    .append(clipText(exchange.getAnswer(), MAX_ANSWER_LENGTH))
                    .append('\n');
            }
        }

        return clipRecentTranscript(builder.toString().trim(), recentTranscriptMaxChars);
    }




    //渲染仅含问题的最近历史（给回答阶段用）
    /*
    面向回答的窗口
    这个方法渲染用于答案生成的最近对话窗口，只包含用户问题，不包含助手回答：
    关键逻辑
1.过滤可渲染轮次：过滤掉运行中的轮次（status != RUNNING）和空问题
2.截取最近 N 轮：只保留最近 keepRecentTurns 轮
3.只渲染用户问题：不渲染助手回答，避免把旧回答误当成事实证据
4.截断窗口：最终按 maxChars 截断，控制总长度

——————————————————————————————————————————————————————————————————————————
为什么只渲染用户问题？
回答窗口的目的是服务于"追问承接"，而非事实检索。比如：
用户第1轮："Java 的特点是什么？"
系统回答："Java 是一种面向对象的编程语言..."
用户第2轮："那 Python 呢？"
在回答第2轮时，系统需要知道用户之前问过 Java，才能理解"那 Python 呢？"是在对比 Java 和 Python。但系统不需要把第1轮的回答再次注入，因为：
1.避免重复：第1轮的回答已经在检索结果中了，再次注入会浪费 Token
2.避免误导：如果第1轮的回答有误，再次注入会让模型误以为这是事实
——————————————————————————————————————————————————————————————————————
渲染示例：
【最近相关对话】
用户：Java 的特点是什么？
用户：它的性能怎么样？
     */
    private String renderAnswerRecentTranscript(List<ConversationExchangeView> exchanges,
                                                int keepRecentTurns,
                                                int maxChars) {
        if (exchanges == null || exchanges.isEmpty()) {
            return "";
        }
        List<ConversationExchangeView> renderableExchanges = new ArrayList<>();
        for (ConversationExchangeView exchange : exchanges) {
            if (exchange != null
                && exchange.getStatus() != ChatTurnStatus.RUNNING
                && StrUtil.isNotBlank(exchange.getQuestion())) {
                renderableExchanges.add(exchange);
            }
        }
        if (renderableExchanges.isEmpty()) {
            return "";
        }
        // 回答窗口只取最近若干轮的用户问题，服务于"追问承接"而非事实检索。
        int fromIndex = Math.max(0, renderableExchanges.size() - keepRecentTurns);
        StringBuilder builder = new StringBuilder("【最近相关对话】\n");
        for (int index = fromIndex; index < renderableExchanges.size(); index++) {
            ConversationExchangeView exchange = renderableExchanges.get(index);
            if (StrUtil.isNotBlank(exchange.getQuestion())) {
                builder.append("用户：")
                    .append(clipText(exchange.getQuestion(), MAX_QUESTION_LENGTH))
                    .append('\n');
            }

        }
        return clipRecentTranscript(builder.toString().trim(), maxChars);
    }






    //把结构化摘要渲染成可读文本
    private String buildLongTermSummaryText(ConversationSummaryPayload payload) {
        ConversationSummaryPayload normalizedPayload = normalizePayload(payload);
        StringBuilder builder = new StringBuilder();
        appendSection(builder, "长期会话摘要", normalizedPayload.getSummary());
        appendSection(builder, "会话目标", normalizedPayload.getConversationGoal());
        appendBulletSection(builder, "已确认事实", normalizedPayload.getStableFacts());
        appendBulletSection(builder, "用户偏好与约束", normalizedPayload.getUserPreferences());
        appendBulletSection(builder, "已解决问题", normalizedPayload.getResolvedPoints());
        appendBulletSection(builder, "待跟进问题", normalizedPayload.getPendingQuestions());
        appendBulletSection(builder, "检索提示", normalizedPayload.getRetrievalHints());
        return clipText(builder.toString().trim(), properties.getHistorySummary().getSummaryMaxChars());
    }

    /*
    assembleHistory 方法把长期摘要与最近窗口拼接成最终的历史文本：拼接逻辑很简单：如果两者都不为空，用换行符连接；如果其中一个为空，只返回非空的那个。
     */
    private String assembleHistory(String longTermSummary, String recentTranscript) {
        return joinNonBlank(longTermSummary, recentTranscript, "\n\n").trim();
    }





    //标准化,规范化摘要：裁剪字段长度、去重列表、合成空摘要
    /*
    规范化做了三件事：
    截断过长字段：clipText 把超长字段截断到配置的最大长度
    去重限长：deduplicateAndLimit 对列表字段去重、限长、限条数（最多 6 条）
    合成摘要：如果 summary 为空，从其他字段合成一个
     */
    private ConversationSummaryPayload normalizePayload(ConversationSummaryPayload payload) {
        ConversationSummaryPayload workingPayload = payload == null ? ConversationSummaryPayload.builder().build() : payload;
        String normalizedSummary = clipText(safeText(workingPayload.getSummary()), properties.getHistorySummary().getSummaryMaxChars());
        if (StrUtil.isBlank(normalizedSummary)) {
            // 如果模型没有给出 summary 主体，则从目标 / 事实 / 待跟进项中合成一个。
            normalizedSummary = synthesizeSummaryFromSections(workingPayload);
        }
        return ConversationSummaryPayload.builder()
            .summary(normalizedSummary)
            .conversationGoal(clipText(safeText(workingPayload.getConversationGoal()), MAX_GOAL_LENGTH))
            .stableFacts(deduplicateAndLimit(workingPayload.getStableFacts()))
            .userPreferences(deduplicateAndLimit(workingPayload.getUserPreferences()))
            .resolvedPoints(deduplicateAndLimit(workingPayload.getResolvedPoints()))
            .pendingQuestions(deduplicateAndLimit(workingPayload.getPendingQuestions()))
            .retrievalHints(deduplicateAndLimit(workingPayload.getRetrievalHints()))
            .build();
    }

    private String synthesizeSummaryFromSections(ConversationSummaryPayload payload) {
        List<String> parts = new ArrayList<>();
        if (StrUtil.isNotBlank(payload.getConversationGoal())) {
            parts.add("目标：" + clipText(payload.getConversationGoal(), MAX_ITEM_LENGTH));
        }
        if (!safeList(payload.getStableFacts()).isEmpty()) {
            parts.add("事实：" + String.join("；", safeList(payload.getStableFacts())));
        }
        if (!safeList(payload.getPendingQuestions()).isEmpty()) {
            parts.add("待跟进：" + String.join("；", safeList(payload.getPendingQuestions())));
        }
        return clipText(String.join("；", parts), properties.getHistorySummary().getSummaryMaxChars());
    }

    private List<String> deduplicateAndLimit(List<String> values) {
        LinkedHashSet<String> deduplicated = new LinkedHashSet<>();
        for (String value : safeList(values)) {
            String text = clipText(safeText(value), MAX_ITEM_LENGTH);
            if (StrUtil.isNotBlank(text)) {
                deduplicated.add(text);
            }
            if (deduplicated.size() >= MAX_SECTION_ITEMS) {
                break;
            }
        }
        return new ArrayList<>(deduplicated);
    }

    private ConversationSummaryPayload copyPayload(ConversationSummaryPayload payload) {
        if (payload == null) {
            return ConversationSummaryPayload.builder().build();
        }
        return ConversationSummaryPayload.builder()
            .summary(payload.getSummary())
            .conversationGoal(payload.getConversationGoal())
            .stableFacts(new ArrayList<>(safeList(payload.getStableFacts())))
            .userPreferences(new ArrayList<>(safeList(payload.getUserPreferences())))
            .resolvedPoints(new ArrayList<>(safeList(payload.getResolvedPoints())))
            .pendingQuestions(new ArrayList<>(safeList(payload.getPendingQuestions())))
            .retrievalHints(new ArrayList<>(safeList(payload.getRetrievalHints())))
            .build();
    }

    /*
    序列化摘要为JSON
    使用 Jackson 的 objectMapper 把 ConversationSummaryPayload 对象序列化为 JSON 字符串。
     */
    private String writePayloadJson(ConversationSummaryPayload payload) {
        try {
            return objectMapper.writeValueAsString(normalizePayload(payload));
        }
        catch (Exception exception) {
            throw new IllegalStateException("序列化会话长期摘要失败", exception);
        }
    }

    private String extractJsonObject(String raw) {
        Matcher matcher = JSON_OBJECT_PATTERN.matcher(raw.trim());
        if (matcher.find()) {
            return matcher.group();
        }
        return raw.trim();
    }

    private boolean isStableSummaryExchange(ConversationExchangeView exchange) {
        if (exchange == null || exchange.getStatus() == null) {
            return false;
        }

        return exchange.getStatus() == ChatTurnStatus.COMPLETED
            && StrUtil.isNotBlank(exchange.getQuestion());
    }

    private boolean shouldKeepInRecentWindow(ConversationExchangeView exchange) {
        return exchange != null
            && exchange.getStatus() != ChatTurnStatus.RUNNING
            && (StrUtil.isNotBlank(exchange.getQuestion()) || StrUtil.isNotBlank(exchange.getAnswer()));
    }

    private int recentFetchLimit(int keepRecentTurns) {

        return Math.max(keepRecentTurns * 3, keepRecentTurns + 4);
    }

    private void appendSection(StringBuilder builder, String title, String content) {
        if (StrUtil.isBlank(content)) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append('【').append(title).append("】\n")
            .append(content.trim())
            .append('\n');
    }

    private void appendBulletSection(StringBuilder builder, String title, List<String> values) {
        List<String> normalizedValues = safeList(values);
        if (normalizedValues.isEmpty()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append('【').append(title).append("】\n");
        for (String value : normalizedValues) {
            builder.append("- ").append(value).append('\n');
        }
    }

    private String clipText(String text, int maxChars) {
        String normalized = safeText(text);
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxChars - 1)) + "…";
    }

    private String clipRecentTranscript(String text, int maxChars) {
        String normalized = safeText(text);
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        int startIndex = Math.max(0, normalized.length() - Math.max(0, maxChars - 1));
        return "…" + normalized.substring(startIndex);
    }

    private String joinNonBlank(String left, String right, String delimiter) {
        if (StrUtil.isBlank(left)) {
            return safeText(right);
        }
        if (StrUtil.isBlank(right)) {
            return safeText(left);
        }
        return safeText(left) + delimiter + safeText(right);
    }

    private List<String> safeList(List<String> values) {
        return values == null ? Collections.emptyList() : values;
    }

    private String safeText(String text) {
        return text == null ? "" : text.trim();
    }

    private long defaultLong(Long value) {
        return value == null ? 0L : value;
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private Date resolveSourceTime(ConversationExchangeView exchange) {
        if (exchange == null) {
            return null;
        }
        return exchange.getEditTime() != null ? exchange.getEditTime() : exchange.getCreateTime();
    }

    private Instant toInstant(Date date) {
        return date == null ? null : date.toInstant();
    }

    private ConversationMemoryContext emptyContext() {
        return ConversationMemoryContext.builder()
            .assembledHistory("")
            .longTermSummary("")
            .recentTranscript("")
            .answerRecentTranscript("")
            .summaryPayload(ConversationSummaryPayload.builder().build())
            .coveredExchangeId(0L)
            .coveredExchangeCount(0)
            .compressionCount(0)
            .compressionApplied(false)
            .build();
    }

    private ConversationMemorySummaryView toSummaryView(String conversationId, SuperAgentChatMemorySummary summaryState) {
        if (summaryState == null) {
            return emptySummaryView(conversationId);
        }
        return new ConversationMemorySummaryView(
            conversationId,
            StrUtil.isNotBlank(summaryState.getSummaryText()),
            defaultLong(summaryState.getCoveredExchangeId()),
            safeInt(summaryState.getCoveredExchangeCount()),
            safeInt(summaryState.getCompressionCount()),
            safeInt(summaryState.getSummaryVersion()),
            safeText(summaryState.getSummaryText()),
            readSummaryPayload(summaryState),
            toInstant(summaryState.getLastSourceEditTime()),
            toInstant(summaryState.getEditTime())
        );
    }

    private ConversationMemorySummaryView emptySummaryView(String conversationId) {
        return new ConversationMemorySummaryView(
            conversationId,
            false,
            0L,
            0,
            0,
            0,
            "",
            ConversationSummaryPayload.builder().build(),
            null,
            null
        );
    }

    private List<String> extractRetrievalHints(String question) {
        if (StrUtil.isBlank(question)) {
            return List.of();
        }
        LinkedHashSet<String> hints = new LinkedHashSet<>();
        Matcher matcher = RETRIEVAL_HINT_PATTERN.matcher(question);
        while (matcher.find()) {
            String hint = matcher.group().trim();
            if (hint.length() >= 2 && !isNoiseHint(hint)) {
                hints.add(clipText(hint, MAX_ITEM_LENGTH));
            }
            if (hints.size() >= MAX_SECTION_ITEMS) {
                break;
            }
        }
        return new ArrayList<>(hints);
    }

    private boolean isNoiseHint(String value) {
        return "请问".equals(value)
            || "帮我".equals(value)
            || "一下".equals(value)
            || "如何".equals(value)
            || "怎么".equals(value)
            || "什么".equals(value)
            || "哪个".equals(value)
            || "这个".equals(value)
            || "那个".equals(value)
            || "可以".equals(value)
            || "需要".equals(value);
    }
}
