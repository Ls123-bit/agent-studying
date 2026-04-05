package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import org.javaup.ai.chatagent.model.ConversationExchangeView;
import org.javaup.ai.chatagent.model.debug.ChatDebugTrace;
import org.javaup.ai.chatagent.rag.model.KnowledgeScopeOption;
import org.javaup.ai.chatagent.service.ConversationArchiveStore;
import org.javaup.enums.ChatTurnStatus;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 知识域粘性继承服务。
 *
 * <p>当用户的追问包含指代词（"这个"、"那个"等），
 * 且 {@link KnowledgeScopeResolver} 判定需要澄清时，
 * 这一层会尝试从最近一次成功的知识问答轮次继承知识域，
 * 避免用户在同一话题下被反复追问"你想问的是哪个业务系统"。</p>
 */
@Service
public class KnowledgeScopeInheritanceService {

    private static final String[] FOLLOW_UP_PRONOUNS = {
        "它", "这个", "那个", "上面", "前面", "刚才", "之前", "该", "上述", "此"
    };

    private static final Set<String> SHORT_AMBIGUOUS_HINTS = Set.of(
        "怎么配", "怎么用", "怎么走", "在哪", "入口", "支持吗", "流程", "还有"
    );

    /**
     * 继承窗口：只在最近 N 个稳定轮次内查找可继承的知识域。
     * 超过这个距离，认为用户可能已经切换话题。
     */
    private static final int MAX_INHERIT_DISTANCE = 4;

    private final ConversationArchiveStore conversationArchiveStore;

    public KnowledgeScopeInheritanceService(ConversationArchiveStore conversationArchiveStore) {
        this.conversationArchiveStore = conversationArchiveStore;
    }

    /**
     * 尝试从最近的成功知识问答轮次继承知识域。
     *
     * @param conversationId 会话 ID
     * @param question       当前问题（可以是改写后的）
     * @return 如果可以继承，返回上一轮的知识域范围；否则返回 empty
     */
    public Optional<InheritedScope> tryInherit(String conversationId, String question) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(question)) {
            return Optional.empty();
        }
        if (!looksLikeFollowUp(question)) {
            return Optional.empty();
        }
        List<ConversationExchangeView> recentExchanges =
            conversationArchiveStore.listRecentExchanges(conversationId, 6);
        if (CollUtil.isEmpty(recentExchanges)) {
            return Optional.empty();
        }
        /*
         * 倒序遍历最近的稳定轮次，找到第一条成功的知识问答记录。
         * 限制在 MAX_INHERIT_DISTANCE 个稳定轮次内，防止话题已经漂移太远。
         */
        int stableCount = 0;
        for (int i = recentExchanges.size() - 1; i >= 0; i--) {
            ConversationExchangeView exchange = recentExchanges.get(i);
            if (exchange == null || exchange.getStatus() == null
                || exchange.getStatus() == ChatTurnStatus.RUNNING) {
                continue;
            }
            stableCount++;
            if (stableCount > MAX_INHERIT_DISTANCE) {
                break;
            }
            ChatDebugTrace trace = exchange.getDebugTrace();
            if (trace == null) {
                continue;
            }
            if ("KNOWLEDGE".equalsIgnoreCase(trace.getRouteType())
                && CollUtil.isNotEmpty(trace.getSelectedDocumentIds())) {
                return Optional.of(new InheritedScope(
                    trace.getScopeOptions(),
                    trace.getSelectedDocumentIds(),
                    trace.getSelectedTaskIds()
                ));
            }
        }
        return Optional.empty();
    }

    private boolean looksLikeFollowUp(String question) {
        String trimmed = question.trim();
        if (Arrays.stream(FOLLOW_UP_PRONOUNS).anyMatch(trimmed::contains)) {
            return true;
        }
        return trimmed.length() <= 8
            && SHORT_AMBIGUOUS_HINTS.stream().anyMatch(trimmed::contains);
    }

    public record InheritedScope(
        List<KnowledgeScopeOption> scopeOptions,
        List<Long> selectedDocumentIds,
        List<Long> selectedTaskIds
    ) {
    }
}
