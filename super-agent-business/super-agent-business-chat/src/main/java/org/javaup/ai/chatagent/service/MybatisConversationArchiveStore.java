package org.javaup.ai.chatagent.service;

import cn.hutool.core.util.StrUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.javaup.ai.chatagent.data.SuperAgentChatDialogue;
import org.javaup.ai.chatagent.data.SuperAgentChatExchange;
import org.javaup.ai.chatagent.mapper.SuperAgentChatDialogueMapper;
import org.javaup.ai.chatagent.mapper.SuperAgentChatExchangeMapper;
import org.javaup.ai.chatagent.model.ConversationExchangeView;
import org.javaup.ai.chatagent.model.SearchReference;
import org.javaup.ai.chatagent.model.debug.ChatDebugTrace;
import org.javaup.enums.BusinessStatus;
import org.javaup.enums.ChatSessionStatus;
import org.javaup.enums.ChatTurnStatus;
import org.javaup.util.DateUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;

/**
 * 基于 MyBatis Plus 的会话归档存储实现。
 *
 * <p>这里持久化的是产品层会话视图，而不是 ReactAgent 自己的 checkpoint。
 * 这层主要负责：
 * 1. 维护会话主表和轮次明细表；
 * 2. 把 JSON 字段在数据库字符串和业务对象之间做转换；
 * 3. 为 BusinessChatService 提供统一的会话读写接口。</p>
 */
@Repository
public class MybatisConversationArchiveStore implements ConversationArchiveStore {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<SearchReference>> REFERENCE_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<ChatDebugTrace> DEBUG_TRACE_TYPE = new TypeReference<>() {
    };

    private final SuperAgentChatDialogueMapper dialogueMapper;
    private final SuperAgentChatExchangeMapper exchangeMapper;
    private final ObjectMapper objectMapper;

    @Resource
    private UidGenerator uidGenerator;

    public MybatisConversationArchiveStore(SuperAgentChatDialogueMapper dialogueMapper,
                                           SuperAgentChatExchangeMapper exchangeMapper,
                                           ObjectMapper objectMapper) {
        this.dialogueMapper = dialogueMapper;
        this.exchangeMapper = exchangeMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建一轮新的用户提问。
     *
     * <p>顺序上依旧是：
     * 先确保会话主表存在并标记为“进行中”，
     * 再插入一条 exchange_state=RUNNING 的轮次明细。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConversationExchangeView startExchange(String conversationId,
                                                  String question,
                                                  Long selectedDocumentId,
                                                  String selectedDocumentName) {
        upsertDialogue(conversationId, ChatSessionStatus.RUNNING, selectedDocumentId, selectedDocumentName);

        long exchangeId = uidGenerator.getUid();

        /*
         * 一开始就把这一轮以“进行中”状态落库，
         * 这样即使用户中途查询，也能看到这轮已经开始执行。
         */
        SuperAgentChatExchange exchange = new SuperAgentChatExchange();
        /*
         * 这里把所有 JSON 快照字段初始化成 [] 而不是 null，
         * 是为了让后续 completeExchange(...) 和前端展示层都可以按“始终有值”的模型处理，
         * 避免到处写判空分支。
         */
        exchange.setId(exchangeId);
        exchange.setConversationId(conversationId);
        exchange.setQuestion(question);
        exchange.setAnswer("");
        exchange.setThinkingSteps(writeJson(List.of()));
        exchange.setReferenceList(writeJson(List.of()));
        exchange.setRecommendationList(writeJson(List.of()));
        exchange.setUsedToolList(writeJson(List.of()));
        exchange.setDebugTraceJson(null);
        exchange.setTurnStatus(ChatTurnStatus.RUNNING.getCode());
        exchange.setErrorMessage("");
        exchange.setFirstResponseTimeMs(null);
        exchange.setTotalResponseTimeMs(null);
        exchange.setStatus(BusinessStatus.YES.getCode());
        exchangeMapper.insert(exchange);

        /*
         * 返回值仍然给服务层一个“运行中视图”，
         * 方便后续直接用 exchangeId 完成本轮的收尾更新。
         */
        return new ConversationExchangeView(
            exchangeId,
            question,
            "",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null,
            ChatTurnStatus.RUNNING,
            "",
            null,
            null, 
             DateUtils.now(),
             DateUtils.now()
        );
    }

    /**
     * 回填某一轮的最终结果，并把会话主状态改回空闲。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void completeExchange(String conversationId,
                                 long exchangeId,
                                 String answer,
                                 List<String> thinkingSteps,
                                 List<SearchReference> references,
                                 List<String> recommendations,
                                 List<String> usedTools,
                                 ChatDebugTrace debugTrace,
                                 ChatTurnStatus status,
                                 String errorMessage,
                                 Long firstResponseTimeMs,
                                 Long totalResponseTimeMs) {
        /*
         * updateById 之前先确认这一轮确实存在并且属于当前会话，
         * 避免误改到别的 conversationId 的数据。
         */
        SuperAgentChatExchange existingExchange = exchangeMapper.selectOne(
            new LambdaQueryWrapper<SuperAgentChatExchange>()
                .eq(SuperAgentChatExchange::getId, exchangeId)
                .eq(SuperAgentChatExchange::getConversationId, conversationId)
                .last("LIMIT 1")
        );
        if (existingExchange == null) {
            /*
             * 这里直接 return 而不是抛异常，
             * 是因为 completeExchange(...) 常发生在 stop/complete/failure 多入口竞争的收尾阶段。
             * 如果该 exchange 已经被别的入口收口掉了，这里静默退出比再次抛错更稳。
             */
            return;
        }

        SuperAgentChatExchange updateExchange = new SuperAgentChatExchange();
        updateExchange.setId(exchangeId);
        updateExchange.setAnswer(safeText(answer));
        updateExchange.setThinkingSteps(writeJson(thinkingSteps));
        updateExchange.setReferenceList(writeJson(references));
        updateExchange.setRecommendationList(writeJson(recommendations));
        updateExchange.setUsedToolList(writeJson(usedTools));
        updateExchange.setDebugTraceJson(writeNullableJson(debugTrace));
        updateExchange.setTurnStatus(status.getCode());
        updateExchange.setErrorMessage(safeText(errorMessage));
        updateExchange.setFirstResponseTimeMs(firstResponseTimeMs);
        updateExchange.setTotalResponseTimeMs(totalResponseTimeMs);
        exchangeMapper.updateById(updateExchange);

        /*
         * turn 收尾后，把会话主状态改回空闲。
         * 这让会话列表里的 running 标识和当前真实执行态保持同步。
         */
        dialogueMapper.update(
            null,
            new LambdaUpdateWrapper<SuperAgentChatDialogue>()
                .eq(SuperAgentChatDialogue::getConversationId, conversationId)
                /*
                 * 会话主表只表达“当前有没有一轮正在跑”，
                 * 所以无论这轮最终是 COMPLETED、FAILED 还是 STOPPED，都会统一收回到 IDLE。
                 */
                .set(SuperAgentChatDialogue::getSessionStatus, ChatSessionStatus.IDLE.getCode())
        );
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ConversationArchiveRecord> getSessionRecord(String conversationId) {
        /*
         * 先查会话主表，再按 conversationId 批量拿 exchange 详情。
         * 这里不直接 join，是为了让主记录查询和明细查询各自保持清晰边界。
         */
        SuperAgentChatDialogue dialogue = dialogueMapper.selectOne(
            activeDialogueByConversation(conversationId)
                .orderByDesc(SuperAgentChatDialogue::getId)
                .last("LIMIT 1")
        );
        if (dialogue == null) {
            return Optional.empty();
        }

        List<ConversationExchangeView> exchanges = loadExchangeViews(List.of(conversationId))
            .getOrDefault(conversationId, List.of());

        return Optional.of(new ConversationArchiveRecord(
            dialogue.getConversationId(),
            ChatSessionStatus.isRunning(dialogue.getSessionStatus()),
            dialogue.getSelectedDocumentId(),
            safeText(dialogue.getSelectedDocumentName()),
            toInstant(dialogue.getCreateTime()),
            toInstant(dialogue.getEditTime()),
            exchanges
        ));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConversationExchangeView> listExchanges(String conversationId) {
        /*
         * 这里返回的是“某个会话下的全部轮次”，
         * 但不再额外拼会话主表，适合会话压缩、推荐问题这类只关心 exchange 的场景。
         */
        return selectConversationExchanges(
            new LambdaQueryWrapper<SuperAgentChatExchange>()
                .eq(SuperAgentChatExchange::getConversationId, conversationId)
                .orderByAsc(SuperAgentChatExchange::getCreateTime)
                .orderByAsc(SuperAgentChatExchange::getId)
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConversationExchangeView> listExchangesAfter(String conversationId, long afterExchangeId) {
        /*
         * 增量摘要只需要读取“上次摘要覆盖游标之后”的新增轮次，
         * 所以这里按 exchangeId 做游标过滤，避免每次都全量重扫整个会话。
         */
        return selectConversationExchanges(
            new LambdaQueryWrapper<SuperAgentChatExchange>()
                .eq(SuperAgentChatExchange::getConversationId, conversationId)
                .gt(afterExchangeId > 0, SuperAgentChatExchange::getId, afterExchangeId)
                .orderByAsc(SuperAgentChatExchange::getCreateTime)
                .orderByAsc(SuperAgentChatExchange::getId)
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConversationExchangeView> listRecentExchanges(String conversationId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        /*
         * 最近窗口查询先按时间倒序取出最近 N 条，再在内存里 reverse 回正序。
         * 这样数据库能高效命中“最新数据”，而上层仍然拿到自然的会话展开顺序。
         */
        List<SuperAgentChatExchange> exchanges = exchangeMapper.selectList(
            new LambdaQueryWrapper<SuperAgentChatExchange>()
                .eq(SuperAgentChatExchange::getConversationId, conversationId)
                .orderByDesc(SuperAgentChatExchange::getCreateTime)
                .orderByDesc(SuperAgentChatExchange::getId)
                .last("LIMIT " + limit)
        );
        if (exchanges == null || exchanges.isEmpty()) {
            return List.of();
        }
        List<ConversationExchangeView> views = new ArrayList<>(exchanges.size());
        for (int index = exchanges.size() - 1; index >= 0; index--) {
            views.add(toExchangeView(exchanges.get(index)));
        }
        return views;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConversationArchiveRecord> listSessionRecords() {
        List<SuperAgentChatDialogue> rawDialogues = dialogueMapper.selectList(
            new LambdaQueryWrapper<SuperAgentChatDialogue>()
                .orderByDesc(SuperAgentChatDialogue::getEditTime)
                .orderByDesc(SuperAgentChatDialogue::getId)
        );
        if (rawDialogues == null || rawDialogues.isEmpty()) {
            return List.of();
        }

        Map<String, SuperAgentChatDialogue> latestDialogues = new LinkedHashMap<>();
        for (SuperAgentChatDialogue dialogue : rawDialogues) {
            /*
             * 这里仍然保留一层 conversationId 去重，是出于稳妥考虑。
             *
             * 虽然当前删除已经改成物理删除，
             * 但如果历史上出现过重复主记录，或者极端并发下产生过多条同会话记录，
             * 这里仍然可以稳定地只取最新一条对外返回。
             *
             * 这里按 editTime/id 倒序拿到列表后，用 putIfAbsent 保留第一条，
             * 等价于“每个 conversationId 只取最新主记录”。
             */
            latestDialogues.putIfAbsent(dialogue.getConversationId(), dialogue);
        }
        List<SuperAgentChatDialogue> dialogues = new ArrayList<>(latestDialogues.values());

        List<String> conversationIds = dialogues.stream()
            .map(SuperAgentChatDialogue::getConversationId)
            .toList();
        /*
         * 会话主表和轮次明细分开查之后，这里再按 conversationId 把结果重新组装回去。
         * 这样 controller 层始终拿到的是一个完整 SessionView，而不用关心底层表结构。
         */
        Map<String, List<ConversationExchangeView>> exchangeViewMap = loadExchangeViews(conversationIds);

        List<ConversationArchiveRecord> result = new ArrayList<>(dialogues.size());
        for (SuperAgentChatDialogue dialogue : dialogues) {
            result.add(new ConversationArchiveRecord(
                dialogue.getConversationId(),
                ChatSessionStatus.isRunning(dialogue.getSessionStatus()),
                dialogue.getSelectedDocumentId(),
                safeText(dialogue.getSelectedDocumentName()),
                toInstant(dialogue.getCreateTime()),
                toInstant(dialogue.getEditTime()),
                exchangeViewMap.getOrDefault(dialogue.getConversationId(), List.of())
            ));
        }
        return result;
    }

    @Override
    @Transactional
    public ConversationArchiveStore.ConversationRemovalResult deleteSession(String conversationId) {
        LambdaQueryWrapper<SuperAgentChatExchange> exchangeQuery = exchangesByConversation(conversationId);
        LambdaQueryWrapper<SuperAgentChatDialogue> dialogueQuery = activeDialogueByConversation(conversationId);

        /*
         * 删除前先做 count，是为了让 reset 接口能把“本次实际删掉了多少条数据”明确反馈出来。
         * 这样比只返回一个 success 更适合排查和前端展示。
         */
        int removedExchangeCount = toInt(exchangeMapper.selectCount(exchangeQuery));
        int removedDialogueCount = toInt(dialogueMapper.selectCount(dialogueQuery));

        if (removedExchangeCount > 0) {
            /*
             * 这里调用的是 MyBatis-Plus 的 delete(wrapper)。
             * 在当前模块移除全局 logic-delete 配置之后，
             * 这里执行的就是真正的物理删除，而不是把 status 改成 0。
             */
            exchangeMapper.delete(exchangesByConversation(conversationId));
        }
        if (removedDialogueCount > 0) {
            dialogueMapper.delete(activeDialogueByConversation(conversationId));
        }

        return new ConversationArchiveStore.ConversationRemovalResult(removedDialogueCount, removedExchangeCount);
    }

    private void upsertDialogue(String conversationId,
                                ChatSessionStatus dialogueStage,
                                Long selectedDocumentId,
                                String selectedDocumentName) {
        SuperAgentChatDialogue dialogue = dialogueMapper.selectOne(
            activeDialogueByConversation(conversationId)
                .orderByDesc(SuperAgentChatDialogue::getId)
                .last("LIMIT 1")
        );

        /*
         * 会话不存在时，创建一条新的主记录；
         * 已存在时，只更新它的业务状态，让同一个会话持续复用。
         */
        if (dialogue == null) {
            SuperAgentChatDialogue newDialogue = new SuperAgentChatDialogue();
            newDialogue.setId(uidGenerator.getUid());
            newDialogue.setConversationId(conversationId);
            newDialogue.setSessionStatus(dialogueStage.getCode());
            newDialogue.setSelectedDocumentId(selectedDocumentId);
            newDialogue.setSelectedDocumentName(selectedDocumentName);
            newDialogue.setStatus(BusinessStatus.YES.getCode());
            /*
             * 新会话主记录只在第一次进入时创建一次，
             * 后续多轮对话都复用同一个 dialogue_code，只更新运行阶段。
             */
            dialogueMapper.insert(newDialogue);
            return;
        }

        /*
         * 只有当目标状态和当前状态不一致时，才执行 update。
         * 这样可以避免每轮都无意义地刷一遍相同状态，减少数据库写入噪音。
         */
        boolean stageChanged = !dialogueStage.equals(ChatSessionStatus.fromCode(dialogue.getSessionStatus()));
        boolean documentScopeChanged = !Objects.equals(selectedDocumentId, dialogue.getSelectedDocumentId())
            || !Objects.equals(safeText(selectedDocumentName), safeText(dialogue.getSelectedDocumentName()));

        if (stageChanged || documentScopeChanged) {
            SuperAgentChatDialogue updateDialogue = new SuperAgentChatDialogue();
            updateDialogue.setId(dialogue.getId());
            updateDialogue.setSessionStatus(dialogueStage.getCode());
            updateDialogue.setSelectedDocumentId(selectedDocumentId);
            updateDialogue.setSelectedDocumentName(selectedDocumentName);
            dialogueMapper.updateById(updateDialogue);
        }
    }

    private Map<String, List<ConversationExchangeView>> loadExchangeViews(List<String> conversationIds) {
        if (conversationIds == null || conversationIds.isEmpty()) {
            return Map.of();
        }

        List<SuperAgentChatExchange> exchanges = exchangeMapper.selectList(
            new LambdaQueryWrapper<SuperAgentChatExchange>()
                .in(SuperAgentChatExchange::getConversationId, conversationIds)
                /*
                 * 对话明细按 createTime + id 正序展开，
                 * 这样前端 mapExchangesToMessages(...) 时能天然得到正确的历史问答顺序。
                 */
                .orderByAsc(SuperAgentChatExchange::getCreateTime)
                .orderByAsc(SuperAgentChatExchange::getConversationId)
                .orderByAsc(SuperAgentChatExchange::getId)
        );

        Map<String, List<ConversationExchangeView>> exchangeViewsByConversation = new LinkedHashMap<>();
        for (SuperAgentChatExchange exchange : exchanges) {
            /*
             * 这里把同一个 conversationId 下的 exchange 重新聚成列表，
             * 方便 getSessionRecord / listSessionRecords 直接复用同一份组装逻辑。
             */
            exchangeViewsByConversation.computeIfAbsent(exchange.getConversationId(), key -> new ArrayList<>())
                .add(toExchangeView(exchange));
        }
        return exchangeViewsByConversation;
    }

    private List<ConversationExchangeView> selectConversationExchanges(LambdaQueryWrapper<SuperAgentChatExchange> queryWrapper) {
        List<SuperAgentChatExchange> exchanges = exchangeMapper.selectList(queryWrapper);
        if (exchanges == null || exchanges.isEmpty()) {
            return List.of();
        }
        List<ConversationExchangeView> result = new ArrayList<>(exchanges.size());
        for (SuperAgentChatExchange exchange : exchanges) {
            result.add(toExchangeView(exchange));
        }
        return result;
    }

    private ConversationExchangeView toExchangeView(SuperAgentChatExchange exchange) {
        /*
         * 这里是“数据库字符串字段 -> 业务视图对象”的最后一步映射。
         * 所有 JSON 字段都会在这里统一反序列化成语义化对象，供 controller 和前端直接使用。
         */
        return new ConversationExchangeView(
            exchange.getId(),
            safeText(exchange.getQuestion()),
            safeText(exchange.getAnswer()),
            readStringList(exchange.getThinkingSteps()),
            readReferenceList(exchange.getReferenceList()),
            readStringList(exchange.getRecommendationList()),
            readStringList(exchange.getUsedToolList()),
            readDebugTrace(exchange.getDebugTraceJson()),
            ChatTurnStatus.fromCode(exchange.getTurnStatus()),
            safeText(exchange.getErrorMessage()),
            exchange.getFirstResponseTimeMs(),
            exchange.getTotalResponseTimeMs(),
            exchange.getCreateTime(),
            exchange.getEditTime()
        );
    }

    private List<String> readStringList(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        try {
            /*
             * 这里统一把 JSON 字符串还原成 List<String>，
             * 避免上层每次都要自己感知字段到底存的是字符串还是 JSON。
             */
            return objectMapper.readValue(json, STRING_LIST_TYPE);
        }
        catch (Exception exception) {
            throw new IllegalStateException("解析字符串列表失败", exception);
        }
    }

    private List<SearchReference> readReferenceList(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        try {
            /*
             * 引用来源列表在数据库里保存的是一份 JSON 快照，
             * 读取时要完整还原成 SearchReference 列表，后面的会话详情和观测页才有足够信息展示。
             */
            return objectMapper.readValue(json, REFERENCE_LIST_TYPE);
        }
        catch (Exception exception) {
            throw new IllegalStateException("解析引用来源列表失败", exception);
        }
    }

    private ChatDebugTrace readDebugTrace(String json) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            /*
             * 调试轨迹是对象，不是列表。
             * 所以这里单独走一套 TypeReference，避免和普通字符串列表的解析逻辑混在一起。
             */
            return objectMapper.readValue(json, DEBUG_TRACE_TYPE);
        }
        catch (Exception exception) {
            throw new IllegalStateException("解析调试轨迹失败", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            /*
             * 所有复杂字段统一在仓储层做 JSON 序列化，
             * service 层就可以始终操作 List/SearchReference 这类语义化对象，而不用感知表字段细节。
             */
            return objectMapper.writeValueAsString(value != null ? value : List.of());
        }
        catch (Exception exception) {
            throw new IllegalStateException("序列化会话字段失败", exception);
        }
    }

    /**
     * 写可空对象 JSON。
     *
     * <p>列表类字段适合用 [] 兜底，但调试轨迹这类对象字段如果写成 []，
     * 后续按对象反序列化时就会结构不匹配。
     * 因此这里单独保留一个“对象为空时直接返回 null”的分支。</p>
     */
    private String writeNullableJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (Exception exception) {
            throw new IllegalStateException("序列化可空会话字段失败", exception);
        }
    }

    private Instant toInstant(Date date) {
        /*
         * 仓储层统一把老式 Date 转成 Instant，
         * 这样对外暴露的会话视图都能使用更明确的时间语义。
         */
        return date != null ? date.toInstant() : null;
    }

    private String safeText(String text) {
        /*
         * 这里统一把 null 字符串规整成空串，
         * 让上层视图对象不需要到处写 if (value == null) 这种样板代码。
         */
        return text != null ? text : "";
    }

    private LambdaQueryWrapper<SuperAgentChatDialogue> activeDialogueByConversation(String conversationId) {
        /*
         * 当前模块已经把“会话是否有效”的含义交给物理删除和主表存在性来表达，
         * 所以这里的 wrapper 非常干净，只按 conversationId 限定。
         */
        return new LambdaQueryWrapper<SuperAgentChatDialogue>()
            .eq(SuperAgentChatDialogue::getConversationId, conversationId);
    }

    private LambdaQueryWrapper<SuperAgentChatExchange> exchangesByConversation(String conversationId) {
        /*
         * 明细表删除和查询也统一围绕同一个 conversationId 做限定，
         * 保证整个会话的数据边界始终清晰一致。
         */
        return new LambdaQueryWrapper<SuperAgentChatExchange>()
            .eq(SuperAgentChatExchange::getConversationId, conversationId);
    }

    private int toInt(Long count) {
        /*
         * MyBatis selectCount 返回 Long，而删除统计对外只需要 int。
         * 这里集中做一次空值和类型转换，避免上层重复处理。
         */
        return count == null ? 0 : count.intValue();
    }
}
