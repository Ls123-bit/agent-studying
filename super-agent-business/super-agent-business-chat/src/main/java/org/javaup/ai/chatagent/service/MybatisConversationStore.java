package org.javaup.ai.chatagent.service;

import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.javaup.ai.chatagent.data.SuperAgentChatSession;
import org.javaup.ai.chatagent.data.SuperAgentChatTurn;
import org.javaup.enums.ChatSessionStatus;
import org.javaup.enums.ChatTurnStatus;
import org.javaup.ai.chatagent.mapper.SuperAgentChatSessionMapper;
import org.javaup.ai.chatagent.mapper.SuperAgentChatTurnMapper;
import org.javaup.ai.chatagent.model.ConversationTurnView;
import org.javaup.ai.chatagent.model.SearchReference;
import org.javaup.enums.BusinessStatus;
import org.javaup.util.DateUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 基于 MyBatis Plus 的会话仓储实现。
 *
 * <p>这里持久化的是产品层会话视图，而不是 ReactAgent 自己的 checkpoint。
 * 这层主要负责：
 * 1. 维护会话主表和轮次明细表；
 * 2. 把 JSON 字段在数据库字符串和业务对象之间做转换；
 * 3. 为 BusinessChatService 提供统一的会话读写接口。</p>
 */
@Repository
public class MybatisConversationStore implements ConversationStore {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<SearchReference>> REFERENCE_LIST_TYPE = new TypeReference<>() {
    };

    private final SuperAgentChatSessionMapper sessionMapper;
    private final SuperAgentChatTurnMapper turnMapper;
    private final ObjectMapper objectMapper;

    @Resource
    private UidGenerator uidGenerator;

    public MybatisConversationStore(SuperAgentChatSessionMapper sessionMapper,
                                    SuperAgentChatTurnMapper turnMapper,
                                    ObjectMapper objectMapper) {
        this.sessionMapper = sessionMapper;
        this.turnMapper = turnMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建一轮新的用户提问。
     *
     * <p>顺序上依旧是：
     * 先确保会话主表存在并标记为“进行中”，
     * 再插入一条 turn_status=RUNNING 的轮次明细。</p>
     */
    @Override
    @Transactional
    public ConversationTurnView startTurn(String conversationId, String question) {
        upsertSession(conversationId, ChatSessionStatus.RUNNING);

        Date now = new Date();
        long turnId = uidGenerator.getUid();

        /*
         * 一开始就把这一轮以“进行中”状态落库，
         * 这样即使用户中途查询，也能看到这轮已经开始执行。
         */
        SuperAgentChatTurn turn = new SuperAgentChatTurn();
        turn.setId(turnId);
        turn.setConversationId(conversationId);
        turn.setQuestion(question);
        turn.setAnswer("");
        turn.setThinkingSteps(writeJson(List.of()));
        turn.setReferenceList(writeJson(List.of()));
        turn.setRecommendationList(writeJson(List.of()));
        turn.setUsedToolList(writeJson(List.of()));
        turn.setTurnStatus(ChatTurnStatus.RUNNING.getCode());
        turn.setErrorMessage("");
        turn.setFirstResponseTimeMs(null);
        turn.setTotalResponseTimeMs(null);
        turn.setStatus(BusinessStatus.YES.getCode());
        turnMapper.insert(turn);

        /*
         * 返回值仍然给服务层一个“运行中视图”，
         * 方便后续直接用 turnId 完成本轮的收尾更新。
         */
        return new ConversationTurnView(
            turnId,
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
    public void completeTurn(String conversationId,
                             long turnId,
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
        SuperAgentChatTurn existingTurn = turnMapper.selectOne(
            new LambdaQueryWrapper<SuperAgentChatTurn>()
                .eq(SuperAgentChatTurn::getId, turnId)
                .eq(SuperAgentChatTurn::getConversationId, conversationId)
                .last("LIMIT 1")
        );
        if (existingTurn == null) {
            return;
        }

        SuperAgentChatTurn updateTurn = new SuperAgentChatTurn();
        updateTurn.setId(turnId);
        updateTurn.setAnswer(safeText(answer));
        updateTurn.setThinkingSteps(writeJson(thinkingSteps));
        updateTurn.setReferenceList(writeJson(references));
        updateTurn.setRecommendationList(writeJson(recommendations));
        updateTurn.setUsedToolList(writeJson(usedTools));
        updateTurn.setTurnStatus(status.getCode());
        updateTurn.setErrorMessage(safeText(errorMessage));
        updateTurn.setFirstResponseTimeMs(firstResponseTimeMs);
        updateTurn.setTotalResponseTimeMs(totalResponseTimeMs);
        turnMapper.updateById(updateTurn);

        /*
         * turn 收尾后，把会话主状态改回空闲。
         * 这让会话列表里的 running 标识和当前真实执行态保持同步。
         */
        sessionMapper.update(
            null,
            new LambdaUpdateWrapper<SuperAgentChatSession>()
                .eq(SuperAgentChatSession::getConversationId, conversationId)
                .set(SuperAgentChatSession::getSessionStatus, ChatSessionStatus.IDLE.getCode())
        );
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SessionRecord> getSessionRecord(String conversationId) {
        SuperAgentChatSession session = sessionMapper.selectOne(
            new LambdaQueryWrapper<SuperAgentChatSession>()
                .eq(SuperAgentChatSession::getConversationId, conversationId)
                .last("LIMIT 1")
        );
        if (session == null) {
            return Optional.empty();
        }

        List<ConversationTurnView> turns = loadTurns(List.of(conversationId))
            .getOrDefault(conversationId, List.of());

        return Optional.of(new SessionRecord(
            session.getConversationId(),
            ChatSessionStatus.isRunning(session.getSessionStatus()),
            toInstant(session.getCreateTime()),
            toInstant(session.getEditTime()),
            turns
        ));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SessionRecord> listSessionRecords() {
        List<SuperAgentChatSession> sessions = sessionMapper.selectList(
            new LambdaQueryWrapper<SuperAgentChatSession>()
                .orderByDesc(SuperAgentChatSession::getEditTime)
        );
        if (sessions == null || sessions.isEmpty()) {
            return List.of();
        }

        List<String> conversationIds = sessions.stream()
            .map(SuperAgentChatSession::getConversationId)
            .toList();
        Map<String, List<ConversationTurnView>> turnMap = loadTurns(conversationIds);

        List<SessionRecord> result = new ArrayList<>(sessions.size());
        for (SuperAgentChatSession session : sessions) {
            result.add(new SessionRecord(
                session.getConversationId(),
                ChatSessionStatus.isRunning(session.getSessionStatus()),
                toInstant(session.getCreateTime()),
                toInstant(session.getEditTime()),
                turnMap.getOrDefault(session.getConversationId(), List.of())
            ));
        }
        return result;
    }

    /**
     * 重置会话时直接物理删除业务会话表。
     *
     * <p>这里没有走逻辑删除，而是显式 hard delete：
     * 这样同一个 conversationId 后续还能被重新创建，不会因为 status=0 的旧记录残留而撞唯一键。</p>
     */
    @Override
    @Transactional
    public void deleteSession(String conversationId) {
        turnMapper.hardDeleteByConversationId(conversationId);
        sessionMapper.hardDeleteByConversationId(conversationId);
    }

    private void upsertSession(String conversationId, ChatSessionStatus sessionStatus) {
        SuperAgentChatSession session = sessionMapper.selectOne(
            new LambdaQueryWrapper<SuperAgentChatSession>()
                .eq(SuperAgentChatSession::getConversationId, conversationId)
                .last("LIMIT 1")
        );

        /*
         * 会话不存在时，创建一条新的主记录；
         * 已存在时，只更新它的业务状态，让同一个会话持续复用。
         */
        if (session == null) {
            SuperAgentChatSession newSession = new SuperAgentChatSession();
            newSession.setId(uidGenerator.getUid());
            newSession.setConversationId(conversationId);
            newSession.setSessionStatus(sessionStatus.getCode());
            newSession.setStatus(BusinessStatus.YES.getCode());
            sessionMapper.insert(newSession);
            return;
        }

        if (!sessionStatus.equals(ChatSessionStatus.fromCode(session.getSessionStatus()))) {
            SuperAgentChatSession updateSession = new SuperAgentChatSession();
            updateSession.setId(session.getId());
            updateSession.setSessionStatus(sessionStatus.getCode());
            sessionMapper.updateById(updateSession);
        }
    }

    private Map<String, List<ConversationTurnView>> loadTurns(List<String> conversationIds) {
        if (conversationIds == null || conversationIds.isEmpty()) {
            return Map.of();
        }

        List<SuperAgentChatTurn> turns = turnMapper.selectList(
            new LambdaQueryWrapper<SuperAgentChatTurn>()
                .in(SuperAgentChatTurn::getConversationId, conversationIds)
                .orderByAsc(SuperAgentChatTurn::getConversationId)
                .orderByAsc(SuperAgentChatTurn::getId)
        );

        Map<String, List<ConversationTurnView>> turnsByConversation = new LinkedHashMap<>();
        for (SuperAgentChatTurn turn : turns) {
            turnsByConversation.computeIfAbsent(turn.getConversationId(), key -> new ArrayList<>())
                .add(toTurnView(turn));
        }
        return turnsByConversation;
    }

    private ConversationTurnView toTurnView(SuperAgentChatTurn turn) {
        return new ConversationTurnView(
            turn.getId(),
            safeText(turn.getQuestion()),
            safeText(turn.getAnswer()),
            readStringList(turn.getThinkingSteps()),
            readReferenceList(turn.getReferenceList()),
            readStringList(turn.getRecommendationList()),
            readStringList(turn.getUsedToolList()),
            ChatTurnStatus.fromCode(turn.getTurnStatus()),
            safeText(turn.getErrorMessage()),
            turn.getFirstResponseTimeMs(),
            turn.getTotalResponseTimeMs(),
            turn.getCreateTime(),
            turn.getEditTime()
        );
    }

    private List<String> readStringList(String json) {
        if (!StringUtils.hasText(json)) {
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
        if (!StringUtils.hasText(json)) {
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
}
