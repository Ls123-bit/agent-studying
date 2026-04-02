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
    public ConversationExchangeView startExchange(String conversationId, String question) {
        upsertDialogue(conversationId, ChatSessionStatus.RUNNING);

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
            toInstant(dialogue.getCreateTime()),
            toInstant(dialogue.getEditTime()),
            exchanges
        ));
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
        Map<String, List<ConversationExchangeView>> exchangeViewMap = loadExchangeViews(conversationIds);

        List<ConversationArchiveRecord> result = new ArrayList<>(dialogues.size());
        for (SuperAgentChatDialogue dialogue : dialogues) {
            result.add(new ConversationArchiveRecord(
                dialogue.getConversationId(),
                ChatSessionStatus.isRunning(dialogue.getSessionStatus()),
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

    private void upsertDialogue(String conversationId, ChatSessionStatus dialogueStage) {
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
            newDialogue.setStatus(BusinessStatus.YES.getCode());
            /*
             * 新会话主记录只在第一次进入时创建一次，
             * 后续多轮对话都复用同一个 dialogue_code，只更新运行阶段。
             */
            dialogueMapper.insert(newDialogue);
            return;
        }

        if (!dialogueStage.equals(ChatSessionStatus.fromCode(dialogue.getSessionStatus()))) {
            SuperAgentChatDialogue updateDialogue = new SuperAgentChatDialogue();
            updateDialogue.setId(dialogue.getId());
            updateDialogue.setSessionStatus(dialogueStage.getCode());
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
            exchangeViewsByConversation.computeIfAbsent(exchange.getConversationId(), key -> new ArrayList<>())
                .add(toExchangeView(exchange));
        }
        return exchangeViewsByConversation;
    }

    private ConversationExchangeView toExchangeView(SuperAgentChatExchange exchange) {
        return new ConversationExchangeView(
            exchange.getId(),
            safeText(exchange.getQuestion()),
            safeText(exchange.getAnswer()),
            readStringList(exchange.getThinkingSteps()),
            readReferenceList(exchange.getReferenceList()),
            readStringList(exchange.getRecommendationList()),
            readStringList(exchange.getUsedToolList()),
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
            return objectMapper.readValue(json, REFERENCE_LIST_TYPE);
        }
        catch (Exception exception) {
            throw new IllegalStateException("解析引用来源列表失败", exception);
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

    private Instant toInstant(Date date) {
        return date != null ? date.toInstant() : null;
    }

    private String safeText(String text) {
        return text != null ? text : "";
    }

    private LambdaQueryWrapper<SuperAgentChatDialogue> activeDialogueByConversation(String conversationId) {
        return new LambdaQueryWrapper<SuperAgentChatDialogue>()
            .eq(SuperAgentChatDialogue::getConversationId, conversationId);
    }

    private LambdaQueryWrapper<SuperAgentChatExchange> exchangesByConversation(String conversationId) {
        return new LambdaQueryWrapper<SuperAgentChatExchange>()
            .eq(SuperAgentChatExchange::getConversationId, conversationId);
    }

    private int toInt(Long count) {
        return count == null ? 0 : count.intValue();
    }
}
