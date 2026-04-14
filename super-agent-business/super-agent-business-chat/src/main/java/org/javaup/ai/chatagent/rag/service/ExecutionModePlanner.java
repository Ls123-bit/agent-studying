package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import org.javaup.ai.chatagent.rag.model.ConversationAnswerShape;
import org.javaup.ai.chatagent.rag.model.ConversationIntentResolution;
import org.javaup.ai.chatagent.rag.model.DocumentNavigationAction;
import org.javaup.ai.chatagent.rag.model.ExecutionMode;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 执行模式判定器。
 *
 * <p>根据导航动作和问题特征决定走 GRAPH_ONLY、GRAPH_THEN_EVIDENCE 还是 RAG_CHAT。</p>
 */
@Component
public class ExecutionModePlanner {

    private static final Set<String> GRAPH_ONLY_PATTERNS = Set.of(
        "属于哪个章节", "上一节", "下一节", "前一节", "后一节",
        "上一个章节", "下一个章节", "章节位置",
        "包含哪些章节", "包含哪些小节", "都有哪些章节",
        "都包含哪些章节", "有哪些章节", "有哪些小节"
    );

    private static final Pattern ITEM_QUERY_PATTERN = Pattern.compile(
        "第\\s*[0-9一二三四五六七八九十百]+\\s*(步|条|项|个).*是什么|哪一步|哪一条|哪一项"
    );

    public ExecutionMode plan(DocumentNavigationAction action,
                              ConversationIntentResolution intentResolution,
                              String question,
                              boolean targetSectionResolved) {
        if (action == DocumentNavigationAction.SECTION_ADJACENCY_LOOKUP) {
            return ExecutionMode.GRAPH_ONLY;
        }
        String normalizedQuestion = question == null ? "" : question.trim();
        if (matchesGraphOnlyPattern(normalizedQuestion)) {
            return ExecutionMode.GRAPH_ONLY;
        }
        if (intentResolution != null && intentResolution.getAnswerShape() == ConversationAnswerShape.OUTLINE) {
            String need = intentResolution.getInformationNeed() == null ? "" : intentResolution.getInformationNeed();
            if (need.contains("章节") || need.contains("列表") || need.contains("包含")) {
                return ExecutionMode.GRAPH_ONLY;
            }
        }
        if (action == DocumentNavigationAction.ITEM_REFERENCE && targetSectionResolved) {
            return ExecutionMode.GRAPH_THEN_EVIDENCE;
        }
        if (targetSectionResolved && ITEM_QUERY_PATTERN.matcher(normalizedQuestion).find()) {
            return ExecutionMode.GRAPH_THEN_EVIDENCE;
        }
        if (targetSectionResolved && normalizedQuestion.contains("哪一步")) {
            return ExecutionMode.GRAPH_THEN_EVIDENCE;
        }
        return ExecutionMode.RAG_CHAT;
    }

    private boolean matchesGraphOnlyPattern(String question) {
        return GRAPH_ONLY_PATTERNS.stream().anyMatch(question::contains);
    }
}
