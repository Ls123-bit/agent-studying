package org.javaup.ai.chatagent.service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.javaup.ai.chatagent.enums.ChatTurnStatus;
import org.javaup.ai.chatagent.model.ConversationTurnView;
import org.javaup.ai.chatagent.model.SearchReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 基于 MySQL 的会话仓储实现。
 *
 * <p>这个类替代了之前的内存版会话仓库，目标有两个：</p>
 * <p>1. 让会话、轮次、引用来源等业务数据在服务重启后仍然存在。</p>
 * <p>2. 让接口层查询到的数据和真实对话过程一致，而不是只活在当前 JVM 里。</p>
 *
 * <p>为了保持数据库结构简单，这里把 thinking steps、references、recommendations、
 * used tools 这些天然是数组/对象集合的字段序列化成 JSON 存到 MySQL。</p>
 */
@Repository
public class JdbcConversationStore implements ConversationStore {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<SearchReference>> REFERENCE_LIST_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
    private final ObjectMapper objectMapper;

    public JdbcConversationStore(JdbcTemplate jdbcTemplate,
                                 NamedParameterJdbcTemplate namedParameterJdbcTemplate,
                                 ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建一轮新的用户提问。
     *
     * <p>这里有两个动作：
     * 先确保 session 存在并标记为 running，
     * 再插入一条状态为 RUNNING 的 turn 记录。
     * 前端后续查询会话详情时，即使这一轮还没跑完，也能看到它已经存在。</p>
     */
    @Override
    @Transactional
    public ConversationTurnView startTurn(String conversationId, String question) {
        Instant now = Instant.now();
        upsertSession(conversationId, true, now);

        KeyHolder keyHolder = new GeneratedKeyHolder();
        int affected = jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                """
                    INSERT INTO chat_turn (
                        conversation_id,
                        question,
                        answer,
                        thinking_steps,
                        `references`,
                        recommendations,
                        used_tools,
                        status,
                        error_message,
                        first_response_time_ms,
                        total_response_time_ms,
                        created_at,
                        updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                Statement.RETURN_GENERATED_KEYS
            );
            statement.setString(1, conversationId);
            statement.setString(2, question);
            statement.setString(3, "");
            statement.setString(4, writeJson(List.of()));
            statement.setString(5, writeJson(List.of()));
            statement.setString(6, writeJson(List.of()));
            statement.setString(7, writeJson(List.of()));
            statement.setString(8, ChatTurnStatus.RUNNING.name());
            statement.setString(9, "");
            statement.setNull(10, Types.BIGINT);
            statement.setNull(11, Types.BIGINT);
            statement.setTimestamp(12, toTimestamp(now));
            statement.setTimestamp(13, toTimestamp(now));
            return statement;
        }, keyHolder);

        if (affected == 0 || keyHolder.getKey() == null) {
            throw new IllegalStateException("创建会话轮次失败");
        }

        long turnId = keyHolder.getKey().longValue();
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
            now,
            now
        );
    }

    /**
     * 补齐某一轮的最终结果。
     *
     * <p>不管本轮是成功、失败还是被用户停止，都会落到这里统一更新 turn，
     * 然后把 session 的 running 标记改回 false。</p>
     */
    @Override
    @Transactional
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
        Instant now = Instant.now();
        int affected = jdbcTemplate.update(
            """
                UPDATE chat_turn
                   SET answer = ?,
                       thinking_steps = ?,
                       `references` = ?,
                       recommendations = ?,
                       used_tools = ?,
                       status = ?,
                       error_message = ?,
                       first_response_time_ms = ?,
                       total_response_time_ms = ?,
                       updated_at = ?
                 WHERE id = ?
                   AND conversation_id = ?
                """,
            ps -> {
                ps.setString(1, safeText(answer));
                ps.setString(2, writeJson(thinkingSteps));
                ps.setString(3, writeJson(references));
                ps.setString(4, writeJson(recommendations));
                ps.setString(5, writeJson(usedTools));
                ps.setString(6, status.name());
                ps.setString(7, safeText(errorMessage));
                setNullableLong(ps, 8, firstResponseTimeMs);
                setNullableLong(ps, 9, totalResponseTimeMs);
                ps.setTimestamp(10, toTimestamp(now));
                ps.setLong(11, turnId);
                ps.setString(12, conversationId);
            }
        );

        if (affected == 0) {
            return;
        }

        jdbcTemplate.update(
            """
                UPDATE chat_session
                   SET running = ?,
                       updated_at = ?
                 WHERE conversation_id = ?
                """,
            false,
            toTimestamp(now),
            conversationId
        );
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SessionRecord> getSessionRecord(String conversationId) {
        List<SessionRow> sessions = jdbcTemplate.query(
            """
                SELECT conversation_id, running, created_at, updated_at
                  FROM chat_session
                 WHERE conversation_id = ?
                """,
            (rs, rowNum) -> new SessionRow(
                rs.getString("conversation_id"),
                rs.getBoolean("running"),
                readInstant(rs.getTimestamp("created_at")),
                readInstant(rs.getTimestamp("updated_at"))
            ),
            conversationId
        );

        if (sessions.isEmpty()) {
            return Optional.empty();
        }

        SessionRow session = sessions.get(0);
        List<ConversationTurnView> turns = loadTurns(List.of(conversationId))
            .getOrDefault(conversationId, List.of());
        return Optional.of(new SessionRecord(
            session.conversationId(),
            session.running(),
            session.createdAt(),
            session.updatedAt(),
            turns
        ));
    }

    /**
     * 查询所有会话，并把所有轮次一次性批量拉回来做分组，避免出现明显的 N+1 查询。
     */
    @Override
    @Transactional(readOnly = true)
    public List<SessionRecord> listSessionRecords() {
        List<SessionRow> sessions = jdbcTemplate.query(
            """
                SELECT conversation_id, running, created_at, updated_at
                  FROM chat_session
                 ORDER BY updated_at DESC
                """,
            (rs, rowNum) -> new SessionRow(
                rs.getString("conversation_id"),
                rs.getBoolean("running"),
                readInstant(rs.getTimestamp("created_at")),
                readInstant(rs.getTimestamp("updated_at"))
            )
        );

        if (sessions.isEmpty()) {
            return List.of();
        }

        List<String> conversationIds = sessions.stream()
            .map(SessionRow::conversationId)
            .toList();
        Map<String, List<ConversationTurnView>> turnMap = loadTurns(conversationIds);

        List<SessionRecord> result = new ArrayList<>(sessions.size());
        for (SessionRow session : sessions) {
            result.add(new SessionRecord(
                session.conversationId(),
                session.running(),
                session.createdAt(),
                session.updatedAt(),
                turnMap.getOrDefault(session.conversationId(), List.of())
            ));
        }
        return result;
    }

    /**
     * 删除整个会话。
     *
     * <p>由于 chat_turn 上挂了外键级联删除，因此删除 session 即可顺带清理所有轮次。</p>
     */
    @Override
    @Transactional
    public void deleteSession(String conversationId) {
        jdbcTemplate.update(
            "DELETE FROM chat_session WHERE conversation_id = ?",
            conversationId
        );
    }

    private void upsertSession(String conversationId, boolean running, Instant now) {
        jdbcTemplate.update(
            """
                INSERT INTO chat_session (conversation_id, running, created_at, updated_at)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    running = VALUES(running),
                    updated_at = VALUES(updated_at)
                """,
            conversationId,
            running,
            toTimestamp(now),
            toTimestamp(now)
        );
    }

    private Map<String, List<ConversationTurnView>> loadTurns(List<String> conversationIds) {
        if (conversationIds == null || conversationIds.isEmpty()) {
            return Map.of();
        }

        Map<String, List<ConversationTurnView>> turnsByConversation = new LinkedHashMap<>();
        namedParameterJdbcTemplate.query(
            """
                SELECT id,
                       conversation_id,
                       question,
                       answer,
                       thinking_steps,
                       `references`,
                       recommendations,
                       used_tools,
                       status,
                       error_message,
                       first_response_time_ms,
                       total_response_time_ms,
                       created_at,
                       updated_at
                  FROM chat_turn
                 WHERE conversation_id IN (:conversationIds)
                 ORDER BY conversation_id ASC, id ASC
                """,
            Map.of("conversationIds", conversationIds),
            rs -> {
                String conversationId = rs.getString("conversation_id");
                turnsByConversation.computeIfAbsent(conversationId, key -> new ArrayList<>())
                    .add(new ConversationTurnView(
                        rs.getLong("id"),
                        safeText(rs.getString("question")),
                        safeText(rs.getString("answer")),
                        readStringList(rs.getString("thinking_steps")),
                        readReferenceList(rs.getString("references")),
                        readStringList(rs.getString("recommendations")),
                        readStringList(rs.getString("used_tools")),
                        ChatTurnStatus.valueOf(rs.getString("status")),
                        safeText(rs.getString("error_message")),
                        readNullableLong(rs.getObject("first_response_time_ms")),
                        readNullableLong(rs.getObject("total_response_time_ms")),
                        readInstant(rs.getTimestamp("created_at")),
                        readInstant(rs.getTimestamp("updated_at"))
                    ));
            }
        );
        return turnsByConversation;
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

    private void setNullableLong(PreparedStatement statement, int index, Long value) throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
            return;
        }
        statement.setLong(index, value);
    }

    private Long readNullableLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private Timestamp toTimestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    private Instant readInstant(Timestamp timestamp) {
        return timestamp != null ? timestamp.toInstant() : Instant.now();
    }

    private String safeText(String text) {
        return text != null ? text : "";
    }

    private record SessionRow(
        String conversationId,
        boolean running,
        Instant createdAt,
        Instant updatedAt
    ) {
    }
}
