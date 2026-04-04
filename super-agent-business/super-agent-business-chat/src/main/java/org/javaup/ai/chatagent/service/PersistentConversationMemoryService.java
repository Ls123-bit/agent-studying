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
import org.javaup.enums.BusinessStatus;
import org.javaup.enums.ChatTurnStatus;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于数据库快照的生产级会话记忆服务。
 *
 * <p>核心目标不是“把历史拼短一点”，而是让长期会话在下面几个维度真正可用：</p>
 * <p>1. 长期历史不会每轮都从第一句重新扫描。</p>
 * <p>2. 长会话会按批次增量压缩，不会一次把整条会话塞给模型。</p>
 * <p>3. 摘要结果持久化，服务重启后仍然能恢复。</p>
 * <p>4. 即使异步预热没来得及完成，回答前也能同步自愈。</p>
 */
@Slf4j
@Service
public class PersistentConversationMemoryService implements ConversationMemoryService {

    private static final String SUMMARY_SYSTEM_PROMPT = """
        你是企业会话长期记忆压缩助手。
        你的任务不是回答业务问题，而是把已有长期摘要与新增对话批次合并成新的长期记忆。
        你必须只保留跨轮仍然有价值的信息，例如：
        1. 用户真正的目标、范围和限制。
        2. 已经确认的业务事实、术语、系统名、模块名。
        3. 已经解决的结论和仍待继续追问的问题。
        4. 对后续知识检索仍有帮助的关键词。
        不要保留寒暄、重复确认、纯过程性客套话，也不要把失败猜测写成既定事实。
        最终只返回合法 JSON，不要输出 Markdown，不要附加解释。
        """;

    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("\\{.*}", Pattern.DOTALL);
    private static final int MAX_SECTION_ITEMS = 6;
    private static final int MAX_ITEM_LENGTH = 80;
    private static final int MAX_GOAL_LENGTH = 120;
    private static final int MAX_QUESTION_LENGTH = 160;
    private static final int MAX_ANSWER_LENGTH = 320;

    private final ConversationArchiveStore conversationArchiveStore;
    private final SuperAgentChatMemorySummaryMapper summaryMapper;
    private final ObjectMapper objectMapper;
    private final ChatClient chatClient;
    private final ChatRagProperties properties;
    private final ExecutorService chatMemorySummaryExecutorService;
    private final Set<String> refreshingConversationIds = ConcurrentHashMap.newKeySet();

    @Resource
    private UidGenerator uidGenerator;

    public PersistentConversationMemoryService(ConversationArchiveStore conversationArchiveStore,
                                               SuperAgentChatMemorySummaryMapper summaryMapper,
                                               ObjectMapper objectMapper,
                                               ChatModel chatModel,
                                               ChatRagProperties properties,
                                               @Qualifier("chatMemorySummaryExecutorService") ExecutorService chatMemorySummaryExecutorService) {
        this.conversationArchiveStore = conversationArchiveStore;
        this.summaryMapper = summaryMapper;
        this.objectMapper = objectMapper;
        this.chatClient = ChatClient.builder(chatModel).build();
        this.properties = properties;
        this.chatMemorySummaryExecutorService = chatMemorySummaryExecutorService;
    }

    /**
     * 读取当前会话的可用记忆上下文。
     *
     * <p>这里会先尝试增量刷新长期摘要，
     * 再把长期摘要和最近几轮原文窗口拼成最终上下文。</p>
     */
    @Override
    public ConversationMemoryContext loadMemoryContext(String conversationId) {
        if (StrUtil.isBlank(conversationId)) {
            return emptyContext();
        }

        ChatRagProperties.HistorySummaryProperties historySummaryProperties = properties.getHistorySummary();
        if (!historySummaryProperties.isEnabled()) {
            /*
             * 关闭长期摘要时，仍然保留一个“最近原文窗口”的轻量兜底，
             * 这样路由和改写至少还能看到最近几轮上下文，不会完全退化成只看当前一句话。
             */
            String recentTranscript = renderRecentTranscript(
                conversationArchiveStore.listRecentExchanges(conversationId, Math.max(1, properties.getRewriteHistoryTurns() * 3)),
                Math.max(1, properties.getRewriteHistoryTurns()),
                historySummaryProperties.getRecentTranscriptMaxChars()
            );
            return ConversationMemoryContext.builder()
                .assembledHistory(recentTranscript)
                .longTermSummary("")
                .recentTranscript(recentTranscript)
                .summaryPayload(ConversationSummaryPayload.builder().build())
                .coveredExchangeId(0L)
                .coveredExchangeCount(0)
                .compressionCount(0)
                .compressionApplied(false)
                .build();
        }

        /*
         * 这里先做一次同步自愈：
         * 如果异步预热尚未跑完，或者服务刚重启导致内存态丢失，
         * 这一轮依然会基于数据库里的增量历史把摘要补齐。
         */
        SuperAgentChatMemorySummary summaryState = refreshSummaryIfNecessary(
            conversationId,
            findSummary(conversationId).orElse(null)
        );
        ConversationSummaryPayload summaryPayload = readSummaryPayload(summaryState);

        List<ConversationExchangeView> recentExchanges = conversationArchiveStore.listRecentExchanges(
            conversationId,
            recentFetchLimit(historySummaryProperties.getKeepRecentTurns())
        );
        String recentTranscript = renderRecentTranscript(
            recentExchanges,
            historySummaryProperties.getKeepRecentTurns(),
            historySummaryProperties.getRecentTranscriptMaxChars()
        );
        String longTermSummary = summaryState == null ? "" : safeText(summaryState.getSummaryText());

        return ConversationMemoryContext.builder()
            /*
             * assembledHistory 才是最终真正喂给路由、问题改写和知识域解析的文本。
             * 这里把“长期压缩背景”和“最近原文细节”显式分层后再拼接，
             * 能同时保住长期信息和短期追问细节。
             */
            .assembledHistory(assembleHistory(longTermSummary, recentTranscript))
            .longTermSummary(longTermSummary)
            .recentTranscript(recentTranscript)
            .summaryPayload(summaryPayload)
            .coveredExchangeId(summaryState == null ? 0L : defaultLong(summaryState.getCoveredExchangeId()))
            .coveredExchangeCount(summaryState == null ? 0 : safeInt(summaryState.getCoveredExchangeCount()))
            .compressionCount(summaryState == null ? 0 : safeInt(summaryState.getCompressionCount()))
            .compressionApplied(StrUtil.isNotBlank(longTermSummary))
            .build();
    }

    /**
     * 对话结束后异步预热摘要。
     *
     * <p>这一步的目标是把下一轮的等待时间尽量前移到当前轮收尾后，
     * 但它不是唯一入口；真正的一致性仍由 loadMemoryContext(...) 的同步自愈保证。</p>
     */
    @Override
    public void refreshConversationSummaryAsync(String conversationId) {
        if (StrUtil.isBlank(conversationId) || !properties.getHistorySummary().isEnabled()) {
            return;
        }
        /*
         * 同一个实例上，同一条会话只允许挂一个后台摘要任务，
         * 避免回答刚结束时连续触发多次重复压缩。
         */
        if (!refreshingConversationIds.add(conversationId)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                refreshSummaryIfNecessary(conversationId, findSummary(conversationId).orElse(null));
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
        /*
         * 手动重建是“从头重算”而不是“顺手补齐”：
         * 先清掉旧快照，再基于当前历史重新推进压缩游标，
         * 这样更适合后台演示和排查。
         */
        if (!refreshingConversationIds.add(conversationId)) {
            return getConversationSummary(conversationId);
        }
        try {
            summaryMapper.delete(new LambdaQueryWrapper<SuperAgentChatMemorySummary>()
                .eq(SuperAgentChatMemorySummary::getConversationId, conversationId));
            SuperAgentChatMemorySummary rebuiltState = refreshSummaryIfNecessary(conversationId, null);
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

    /**
     * 如果有新增历史已经滑出“最近原文窗口”，就把它们批量压进长期摘要。
     */
    private SuperAgentChatMemorySummary refreshSummaryIfNecessary(String conversationId,
                                                                  SuperAgentChatMemorySummary currentState) {
        ChatRagProperties.HistorySummaryProperties historySummaryProperties = properties.getHistorySummary();
        long coveredExchangeId = currentState == null ? 0L : defaultLong(currentState.getCoveredExchangeId());

        /*
         * 增量读取的关键就在这里：
         * 只查“摘要游标之后”的新增轮次，而不是每一轮都把整条会话历史重新扫一遍。
         */
        List<ConversationExchangeView> incrementalExchanges = conversationArchiveStore.listExchangesAfter(
            conversationId,
            coveredExchangeId
        );
        List<ConversationExchangeView> stableExchanges = incrementalExchanges.stream()
            .filter(this::isStableSummaryExchange)
            .toList();

        /*
         * keepRecentTurns 代表永远保留原文细节的短期窗口，
         * 只有再往前那一部分历史，才允许被压成长期摘要。
         */
        int overflowCount = Math.max(0, stableExchanges.size() - historySummaryProperties.getKeepRecentTurns());
        if (overflowCount <= 0) {
            return currentState;
        }

        List<ConversationExchangeView> overflowExchanges = stableExchanges.subList(0, overflowCount);
        SuperAgentChatMemorySummary workingState = currentState;

        /*
         * 生产级压缩不能指望一次 prompt 吃下所有溢出历史，
         * 所以这里按固定批次滚动推进游标，每批只处理一小段新增历史。
         */
        for (int start = 0; start < overflowExchanges.size(); start += historySummaryProperties.getCompressionBatchTurns()) {
            int end = Math.min(start + historySummaryProperties.getCompressionBatchTurns(), overflowExchanges.size());
            List<ConversationExchangeView> batch = overflowExchanges.subList(start, end);
            ConversationSummaryPayload mergedPayload = mergeSummaryPayload(readSummaryPayload(workingState), batch);
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

    /**
     * 把已有长期摘要和新增对话批次合并成新的结构化摘要。
     */
    private ConversationSummaryPayload mergeSummaryPayload(ConversationSummaryPayload existingPayload,
                                                           List<ConversationExchangeView> batch) {
        try {
            String content = chatClient.prompt()
                .system(SUMMARY_SYSTEM_PROMPT)
                .user(buildSummaryMergePrompt(existingPayload, batch))
                .call()
                .content();
            ConversationSummaryPayload parsedPayload = parseSummaryPayload(content);
            if (parsedPayload != null) {
                return normalizePayload(parsedPayload);
            }
        }
        catch (Exception exception) {
            log.warn("合并会话长期摘要失败，回退到规则压缩: {}", exception.getMessage());
        }
        return fallbackMerge(existingPayload, batch);
    }

    /**
     * 构造一次摘要合并调用的用户提示词。
     */
    private String buildSummaryMergePrompt(ConversationSummaryPayload existingPayload,
                                           List<ConversationExchangeView> batch) {
        String existingJson = writePayloadJson(normalizePayload(copyPayload(existingPayload)));
        return """
            请把下面的已有长期摘要和新增对话批次合并成新的长期记忆 JSON。
            JSON 结构必须为：
            {
              "summary": "一段 180~260 字的中文摘要",
              "conversation_goal": "一句话描述用户长期目标",
              "stable_facts": ["事实1", "事实2"],
              "user_preferences": ["偏好1", "偏好2"],
              "resolved_points": ["已解决点1", "已解决点2"],
              "pending_questions": ["待跟进点1", "待跟进点2"],
              "retrieval_hints": ["系统名或关键词1", "系统名或关键词2"]
            }

            输出要求：
            1. 只返回 JSON，不要输出解释。
            2. 不要把寒暄、重复确认和无关聊天写进去。
            3. 未确认的信息不要写成既定事实。
            4. 每个数组最多保留 6 条，尽量去重。
            5. summary 只保留下一轮理解问题真正需要的长期背景。

            已有长期摘要 JSON：
            %s

            新增对话批次：
            %s
            """.formatted(
            StrUtil.isNotBlank(existingJson) ? existingJson : "{}",
            renderCompressionTranscript(batch)
        );
    }

    /**
     * 当模型输出异常或 JSON 不可解析时，使用规则合并保证摘要功能仍然能推进游标。
     */
    private ConversationSummaryPayload fallbackMerge(ConversationSummaryPayload existingPayload,
                                                     List<ConversationExchangeView> batch) {
        ConversationSummaryPayload mergedPayload = copyPayload(existingPayload);
        String batchHighlight = renderFallbackBatchHighlight(batch);
        String mergedSummary = joinNonBlank(mergedPayload.getSummary(), batchHighlight, "；");
        mergedPayload.setSummary(clipText(mergedSummary, properties.getHistorySummary().getSummaryMaxChars()));

        ConversationExchangeView lastExchange = batch.get(batch.size() - 1);
        if (StrUtil.isBlank(mergedPayload.getConversationGoal()) && StrUtil.isNotBlank(lastExchange.getQuestion())) {
            mergedPayload.setConversationGoal(clipText(lastExchange.getQuestion(), MAX_GOAL_LENGTH));
        }

        List<String> pendingQuestions = new ArrayList<>(safeList(mergedPayload.getPendingQuestions()));
        for (ConversationExchangeView exchange : batch) {
            if (StrUtil.isNotBlank(exchange.getQuestion())) {
                pendingQuestions.add(clipText(exchange.getQuestion(), MAX_ITEM_LENGTH));
            }
        }
        mergedPayload.setPendingQuestions(deduplicateAndLimit(pendingQuestions));

        List<String> retrievalHints = new ArrayList<>(safeList(mergedPayload.getRetrievalHints()));
        if (StrUtil.isNotBlank(lastExchange.getQuestion())) {
            retrievalHints.add(clipText(lastExchange.getQuestion(), MAX_ITEM_LENGTH));
        }
        mergedPayload.setRetrievalHints(deduplicateAndLimit(retrievalHints));
        return normalizePayload(mergedPayload);
    }

    /**
     * 每完成一批压缩，就把结果落成一份可恢复的快照。
     */
    private SuperAgentChatMemorySummary saveSummarySnapshot(String conversationId,
                                                            SuperAgentChatMemorySummary currentState,
                                                            ConversationSummaryPayload payload,
                                                            long coveredExchangeId,
                                                            int coveredExchangeCount,
                                                            Date lastSourceEditTime) {
        SuperAgentChatMemorySummary latestState = findSummary(conversationId).orElse(null);
        long latestCoveredExchangeId = latestState == null ? 0L : defaultLong(latestState.getCoveredExchangeId());

        /*
         * 这里显式防止“旧任务回写覆盖新摘要”：
         * 如果数据库里已经有更靠后的覆盖游标，就说明当前这次保存已经过期，必须放弃回写。
         */
        if (latestCoveredExchangeId > coveredExchangeId) {
            return latestState;
        }
        /*
         * 如果数据库里已经存在相同覆盖游标的有效摘要，
         * 说明这次保存大概率只是异步/同步路径的重复命中，直接复用现有快照即可。
         */
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

        /*
         * 这里不新建多条版本记录，而是持续更新同一条会话快照，
         * 让读取链路始终只关心“当前最新的长期摘要是什么”。
         */
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

    private ConversationSummaryPayload readSummaryPayload(SuperAgentChatMemorySummary summaryState) {
        if (summaryState == null) {
            return ConversationSummaryPayload.builder().build();
        }
        if (StrUtil.isNotBlank(summaryState.getSummaryJson())) {
            ConversationSummaryPayload payload = parseSummaryPayload(summaryState.getSummaryJson());
            if (payload != null) {
                return normalizePayload(payload);
            }
        }
        ConversationSummaryPayload fallbackPayload = ConversationSummaryPayload.builder()
            .summary(summaryState.getSummaryText())
            .build();
        return normalizePayload(fallbackPayload);
    }

    private ConversationSummaryPayload parseSummaryPayload(String raw) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        try {
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

    private String renderRecentTranscript(List<ConversationExchangeView> exchanges,
                                          int keepRecentTurns,
                                          int recentTranscriptMaxChars) {
        List<ConversationExchangeView> renderableExchanges = new ArrayList<>();
        for (ConversationExchangeView exchange : exchanges) {
            if (shouldKeepInRecentWindow(exchange)) {
                renderableExchanges.add(exchange);
            }
        }
        if (renderableExchanges.isEmpty()) {
            return "";
        }
        int fromIndex = Math.max(0, renderableExchanges.size() - keepRecentTurns);
        StringBuilder builder = new StringBuilder("【最近对话原文】\n");
        for (int index = fromIndex; index < renderableExchanges.size(); index++) {
            ConversationExchangeView exchange = renderableExchanges.get(index);
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
            if (exchange.getStatus() == ChatTurnStatus.FAILED && StrUtil.isNotBlank(exchange.getErrorMessage())) {
                builder.append("本轮状态：失败，原因=")
                    .append(clipText(exchange.getErrorMessage(), MAX_ITEM_LENGTH))
                    .append('\n');
            }
            if (exchange.getStatus() == ChatTurnStatus.STOPPED && StrUtil.isNotBlank(exchange.getErrorMessage())) {
                builder.append("本轮状态：已停止，原因=")
                    .append(clipText(exchange.getErrorMessage(), MAX_ITEM_LENGTH))
                    .append('\n');
            }
        }
        /*
         * 最近原文窗口超长时，要优先保留“更靠近当前问题”的尾部内容，
         * 而不是从头截断把最新几轮裁掉。
         */
        return clipRecentTranscript(builder.toString().trim(), recentTranscriptMaxChars);
    }

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

    private String assembleHistory(String longTermSummary, String recentTranscript) {
        return joinNonBlank(longTermSummary, recentTranscript, "\n\n").trim();
    }

    private ConversationSummaryPayload normalizePayload(ConversationSummaryPayload payload) {
        ConversationSummaryPayload workingPayload = payload == null ? ConversationSummaryPayload.builder().build() : payload;
        String normalizedSummary = clipText(safeText(workingPayload.getSummary()), properties.getHistorySummary().getSummaryMaxChars());
        if (StrUtil.isBlank(normalizedSummary)) {
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
        /*
         * 长期摘要只沉淀“已经相对稳定”的轮次：
         * COMPLETED 是最稳的，STOPPED 也可能带有已生成的有效结论；
         * FAILED 则不纳入长期记忆，避免把异常中间态当成事实写进去。
         */
        return (exchange.getStatus() == ChatTurnStatus.COMPLETED || exchange.getStatus() == ChatTurnStatus.STOPPED)
            && StrUtil.isNotBlank(exchange.getQuestion());
    }

    private boolean shouldKeepInRecentWindow(ConversationExchangeView exchange) {
        return exchange != null
            && exchange.getStatus() != ChatTurnStatus.RUNNING
            && (StrUtil.isNotBlank(exchange.getQuestion()) || StrUtil.isNotBlank(exchange.getAnswer()));
    }

    private int recentFetchLimit(int keepRecentTurns) {
        /*
         * 最近窗口查询会先做一轮状态和空值过滤，
         * 所以数据库里实际要多取一点，避免过滤后不够 keepRecentTurns 条。
         */
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
}
