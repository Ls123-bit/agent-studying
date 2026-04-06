package org.javaup.ai.chatagent.rag.service;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.javaup.enums.ChatRouteType;
import org.javaup.ai.chatagent.support.TimeSensitiveQueryHelper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;

/**
 * 聊天前置路由服务。
 *
 * <p>这一层先做一个“轻量分诊”：
 * 判断当前问题更像知识问答、开放式对话，还是应该先追问用户。
 * 只有明确适合知识问答的问题，才会继续进入后续的改写、知识域收缩和检索链路。</p>
 */
@Slf4j
@Service
public class ChatRouteService {

    private static final Set<String> CHITCHAT_WORDS = Set.of(
        "你好", "您好", "hello", "hi", "在吗", "谢谢", "感谢", "拜拜", "再见"
    );

    private static final Set<String> CLARIFY_WORDS = Set.of(
        "这个", "那个", "怎么弄", "怎么搞", "推荐", "帮我看看", "帮我查查", "系统", "流程"
    );

    private static final Set<String> KNOWLEDGE_WORDS = Set.of(
        "规则", "流程", "说明", "支持", "怎么", "是什么", "有哪些", "为什么", "是否", "可以", "配置", "文档"
    );

    private static final String ROUTE_PROMPT = """
        你是业务聊天路由器。
        请根据历史上下文和当前问题，只返回以下三个词中的一个：
        knowledge
        clarify
        open_chat

        历史上下文：
        {history}

        当前问题：
        {question}
        """;

    private final ChatClient chatClient;

    public ChatRouteService(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel).build();
    }

    /**
     * 判定当前问题应该进入哪条主路径。
     */
    public ChatRouteType route(String question, String historySummary, boolean hasRetrievableDocuments) {
        /*
         * 先把用户输入做最基础的归一化，后面规则判断都基于这个版本做 contains 检测。
         */
        String normalized = normalize(question);
        if (StrUtil.isBlank(normalized)) {
            return ChatRouteType.CLARIFY;
        }

        /*
         * 寒暄类问题不值得走知识检索。
         * 如果这里不先拦掉，后面很容易把“你好”“谢谢”之类的问题误送进知识库。
         */
        if (CHITCHAT_WORDS.stream().anyMatch(normalized::contains)) {
            return ChatRouteType.OPEN_CHAT;
        }

        /*
         * “没有可检索文档”不应该自动等价于“开放聊天”。
         * 对内部知识型问题来说，更合理的行为是继续保持 evidence-first：
         * - 能联网的时效问题可以后续降级成 web-only RAG
         * - 非时效知识问题则可以在没有证据时明确返回 no-evidence
         *
         * 因此这里只把“明显开放式 / 闲聊”问题留给 OPEN_CHAT，
         * 而不是因为知识库为空就直接放弃证据边界。
         */
        if (!hasRetrievableDocuments) {
            if (TimeSensitiveQueryHelper.requiresFreshSearch(question)) {
                return ChatRouteType.KNOWLEDGE;
            }
            if (KNOWLEDGE_WORDS.stream().anyMatch(normalized::contains)) {
                return ChatRouteType.KNOWLEDGE;
            }
            if (isClearlyClarify(normalized, historySummary)) {
                return ChatRouteType.CLARIFY;
            }
            return routeByLlm(question, historySummary);
        }

        if (isClearlyClarify(normalized, historySummary)) {
            return ChatRouteType.CLARIFY;
        }

        /*
         * 这里和之前最大的不同，是不再把“最新/今天”类问题一刀切丢给 OPEN_CHAT。
         * 如果当前系统里本来就有可检索文档，那这类问题很可能需要“内部文档 + 外部网页”混合证据，
         * 后续 WebSearchChannel 会在知识检索链路里补充联网来源。
         */
        if (TimeSensitiveQueryHelper.requiresFreshSearch(question)) {
            return ChatRouteType.KNOWLEDGE;
        }

        /*
         * 命中这些知识型关键词时，优先认为它是知识问答。
         * 这里仍然只是“路由判断”，并不代表后面一定能检索到证据。
         */
        if (KNOWLEDGE_WORDS.stream().anyMatch(normalized::contains)) {
            return ChatRouteType.KNOWLEDGE;
        }

        /*
         * 到这里说明规则还不够确定，再让模型补一次轻量判断。
         * 这样可以兼顾：
         * 1. 规则命中时的低成本
         * 2. 规则兜不住时的语义理解能力
         */
        return routeByLlm(question, historySummary);
    }

    /**
     * 用规则快速识别“明显需要追问”的场景。
     */
    private boolean isClearlyClarify(String normalized, String historySummary) {
        /*
         * 只有极短输入（<=2 字符，即单个汉字）才直接判为澄清。
         * 中文里 3 个字（如”查规则””怎么用””是什么”）已经是合理的知识问答问题，
         * 不应该被拦截。
         */
        if (normalized.length() <= 2) {
            return true;
        }
        /*
         * 短问题（<=4 字符）如果命中了知识型关键词，说明用户意图已经足够明确，
         * 不应该再追问。只有短且不含知识词的问题才归为澄清。
         */
        if (normalized.length() <= 4 && KNOWLEDGE_WORDS.stream().noneMatch(normalized::contains)) {
            return true;
        }
        /*
         * 命中”这个/那个/系统/流程”这类模糊指代词，且当前又没有历史上下文时，
         * 继续往知识检索走大概率会答偏，所以直接归为澄清。
         */
        return CLARIFY_WORDS.stream().anyMatch(normalized::contains) && StrUtil.isBlank(historySummary);
    }

    /**
     * 规则不够明确时，再交给模型做一次轻量分类。
     */
    private ChatRouteType routeByLlm(String question, String historySummary) {
        try {
            /*
             * 模型这里只做极轻量分类，不做复杂推理。
             * 我们让它只返回固定枚举词，目的是把输出约束到最小范围，减少跑偏概率。
             */
            String content = chatClient.prompt()
                .user(user -> user.text(ROUTE_PROMPT)
                    .param("history", StrUtil.isNotBlank(historySummary) ? historySummary : "无历史上下文")
                    .param("question", question))
                .call()
                .content();
            return parseRoute(content);
        }
        catch (Exception exception) {
            /*
             * 路由阶段失败不能影响主链路可用性。
             * 这里统一回退到 knowledge，是因为知识检索路径比开放式乱答更可控。
             */
            log.warn("聊天路由识别失败，默认回到 knowledge: {}", exception.getMessage());
            return ChatRouteType.KNOWLEDGE;
        }
    }

    private ChatRouteType parseRoute(String raw) {
        /*
         * 不管模型多规矩，最后都要再做一次字符串归一化和显式判定，
         * 避免它返回带解释的话污染路由结果。
         */
        String normalized = normalize(raw);
        if (normalized.contains(ChatRouteType.CLARIFY.getDesc())) {
            return ChatRouteType.CLARIFY;
        }
        if (normalized.contains(ChatRouteType.OPEN_CHAT.getDesc()) || normalized.contains("open")) {
            return ChatRouteType.OPEN_CHAT;
        }
        return ChatRouteType.KNOWLEDGE;
    }

    private String normalize(String text) {
        /*
         * 路由阶段只关心“语义关键词能否命中”，
         * 所以这里的归一化非常克制：只做 trim 和小写，不做额外的词法处理。
         */
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }
}
