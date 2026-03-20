package org.javaup.route.service;

import lombok.extern.slf4j.Slf4j;
import org.javaup.route.model.RouteHandledResult;
import org.javaup.route.model.RouteMessage;
import org.javaup.route.repository.RouteDemoRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 知识检索通道：先把问题补全成独立查询，再用内存知识库召回资料。
 * 真实项目里，这里通常会换成“查询改写 + 向量检索 + 重排序 + 回答生成”。
 */
@Slf4j
@Service
public class KnowledgeRouteService {

    private static final String REWRITE_PROMPT = """
        你是检索前置改写助手。请把用户当前问题改写成一个可以独立检索的查询句。

        要求：
        - 如果问题已经完整清晰，直接原样返回。
        - 如果问题里有“它、这个、那个、上节课”等指代，请结合历史补全。
        - 只输出改写后的结果，不要解释。

        历史对话：
        {history}

        当前问题：
        {question}
        """;

    private static final String ANSWER_PROMPT = """
        你是 JavaUp 学习平台的知识库助手，请严格依据给出的参考资料回答。

        回答要求：
        - 优先直接回答用户问题，不要绕弯子。
        - 如果参考资料里有明确条件或时间限制，要说完整。
        - 如果参考资料不足以回答，就直接说明“当前资料里没有明确写到”。
        - 不要编造知识库里没有的政策。

        对话历史：
        {history}

        用户问题：
        {question}

        检索改写：
        {standaloneQuestion}

        参考资料：
        {references}
        """;

    private final ChatClient chatClient;
    private final RouteDemoRepository repository;

    public KnowledgeRouteService(ChatClient.Builder builder, RouteDemoRepository repository) {
        this.chatClient = builder.build();
        this.repository = repository;
    }

    /**
     * 知识通道主流程：
     * 1. 必要时先补全问题
     * 2. 从知识库召回资料
     * 3. 基于资料生成最终回答
     */
    public RouteHandledResult answer(String question, List<RouteMessage> history) {
        String standaloneQuestion = rewriteIfNeeded(question, history);
        List<RouteDemoRepository.KnowledgeDoc> docs = repository.searchKnowledge(standaloneQuestion);
        if (docs.isEmpty()) {
            return new RouteHandledResult(
                "knowledge",
                "这类问题适合先走知识检索，不过当前示例知识库里没有命中资料。你可以试试问发票、回放、证书或退款规则。",
                standaloneQuestion,
                List.of()
            );
        }

        // 这里把命中的文档拼成参考资料，交给大模型做最终组织表达。
        String references = formatReferences(docs);
        String answer = chatClient.prompt()
            .system("你是平台知识库助手，只能依据参考资料回答。")
            .user(user -> user.text(ANSWER_PROMPT)
                .param("history", formatHistory(history))
                .param("question", question)
                .param("standaloneQuestion", standaloneQuestion)
                .param("references", references))
            .call()
            .content();

        return new RouteHandledResult(
            "knowledge",
            answer,
            standaloneQuestion,
            docs.stream().map(RouteDemoRepository.KnowledgeDoc::getTitle).toList()
        );
    }

    /**
     * 多轮对话里如果有指代，就先把问题补全成“检索系统听得懂”的独立句子。
     */
    private String rewriteIfNeeded(String question, List<RouteMessage> history) {
        if (!needsRewrite(question, history)) {
            return question;
        }
        try {
            String rewritten = chatClient.prompt()
                .user(user -> user.text(REWRITE_PROMPT)
                    .param("history", formatHistory(history))
                    .param("question", question))
                .call()
                .content();
            if (StringUtils.hasText(rewritten) && rewritten.length() < 200) {
                return rewritten.strip();
            }
        }
        catch (Exception exception) {
            log.warn("知识检索改写失败，回退原始问题: {}", exception.getMessage());
        }
        return question;
    }

    /**
     * 只在“真的有改写必要”时才调模型，避免给知识通道平白增加一次耗时。
     */
    private boolean needsRewrite(String question, List<RouteMessage> history) {
        if (history == null || history.isEmpty()) {
            return false;
        }
        if (!StringUtils.hasText(question)) {
            return false;
        }
        return question.length() < 10
            || question.contains("它")
            || question.contains("这个")
            || question.contains("那个")
            || question.contains("上节课");
    }

    /**
     * 这里故意保留标题和原文内容，方便示例里直观看到“参考资料长什么样”。
     */
    private String formatReferences(List<RouteDemoRepository.KnowledgeDoc> docs) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            RouteDemoRepository.KnowledgeDoc doc = docs.get(i);
            builder.append(i + 1)
                .append(". ")
                .append(doc.getTitle())
                .append("\n")
                .append(doc.getContent().strip())
                .append("\n\n");
        }
        return builder.toString().strip();
    }

    private String formatHistory(List<RouteMessage> history) {
        if (history == null || history.isEmpty()) {
            return "无历史对话";
        }
        StringBuilder builder = new StringBuilder();
        for (RouteMessage message : history) {
            String role = "assistant".equalsIgnoreCase(message.getRole()) ? "助手" : "用户";
            builder.append(role).append("：").append(message.getContent()).append("\n");
        }
        return builder.toString().strip();
    }
}
