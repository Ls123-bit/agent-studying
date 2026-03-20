package org.javaup.route.service;

import lombok.extern.slf4j.Slf4j;
import org.javaup.route.model.RouteChatResponse;
import org.javaup.route.model.RouteDecision;
import org.javaup.route.model.RouteHandledResult;
import org.javaup.route.model.RouteMessage;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 总路由入口。
 * 这层不关心“具体怎么查”，只负责把流程按顺序串起来。
 */
@Slf4j
@Service
public class SmartRouteService {

    private final RouteIntentClassifier routeIntentClassifier;
    private final KnowledgeRouteService knowledgeRouteService;
    private final ToolRouteService toolRouteService;
    private final ConversationRouteService conversationRouteService;
    private final RouteSessionStore routeSessionStore;

    public SmartRouteService(RouteIntentClassifier routeIntentClassifier,
                             KnowledgeRouteService knowledgeRouteService,
                             ToolRouteService toolRouteService,
                             ConversationRouteService conversationRouteService,
                             RouteSessionStore routeSessionStore) {
        this.routeIntentClassifier = routeIntentClassifier;
        this.knowledgeRouteService = knowledgeRouteService;
        this.toolRouteService = toolRouteService;
        this.conversationRouteService = conversationRouteService;
        this.routeSessionStore = routeSessionStore;
    }

    /**
     * 一次完整对话的主流程：
     * 1. 读历史
     * 2. 判意图
     * 3. 分发到对应处理器
     * 4. 把本轮问答写回历史
     */
    public RouteChatResponse chat(String sessionId, String question) {
        String normalizedSessionId = routeSessionStore.normalizeSessionId(sessionId);
        List<RouteMessage> history = routeSessionStore.getHistory(normalizedSessionId);

        // 先分类，再决定这条消息到底该走哪条通道。
        RouteDecision decision = routeIntentClassifier.classify(question, history);

        // 路由的关键点就在这里：同一句话，不同意图会落到完全不同的处理器。
        RouteHandledResult handledResult = switch (decision.getIntent()) {
            case TOOL -> toolRouteService.answer(question, history);
            case CHITCHAT -> conversationRouteService.answerChitchat(question, history);
            case CLARIFY -> conversationRouteService.answerClarify(question, history);
            case KNOWLEDGE -> knowledgeRouteService.answer(question, history);
        };

        // 先保存用户消息，再保存助手回复，便于下一轮继续拿历史做判断。
        routeSessionStore.appendUser(normalizedSessionId, question);
        routeSessionStore.appendAssistant(normalizedSessionId, handledResult.getAnswer());

        log.info("RouteChat | sessionId={} | question={} | intent={} | confidence={} | source={} | route={}",
            normalizedSessionId,
            question,
            decision.getIntent().getCode(),
            decision.getConfidence(),
            decision.getSource(),
            handledResult.getRouteName());

        return new RouteChatResponse(
            normalizedSessionId,
            question,
            handledResult.getAnswer(),
            handledResult.getRouteName(),
            decision.getIntent(),
            decision.getConfidence(),
            decision.getSource(),
            decision.getReason(),
            handledResult.getStandaloneQuestion(),
            handledResult.getReferences(),
            history
        );
    }

    /**
     * 清空指定会话的历史消息。
     */
    public void reset(String sessionId) {
        routeSessionStore.clear(sessionId);
    }
}
