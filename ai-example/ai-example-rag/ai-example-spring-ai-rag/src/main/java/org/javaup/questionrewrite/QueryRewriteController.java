package org.javaup.questionrewrite;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 问题改写演示 Controller
 * <p>
 * 配置好 apiKey 后直接调用即可测试，每个接口都内置了模拟对话数据。
 * <p>
 * 测试地址：
 * - 自定义改写：GET /rag/rewrite/custom?question=那它有没有证书
 * - Compression：GET /rag/rewrite/compression?question=那它有没有证书
 * - Rewriter：  GET /rag/rewrite/rewriter?question=ES查询太慢了怎么搞
 * - Expand：    GET /rag/rewrite/expand?question=Redis持久化方式有哪些
 * - HyDE：     GET /rag/rewrite/hyde?question=微服务之间怎么通信
 */
@Slf4j
@RestController
@RequestMapping("/rag/rewrite")
public class QueryRewriteController {

    private final ChatClient chatClient;
    private final ChatClient.Builder chatClientBuilder;
    private final QueryRewriteService queryRewriteService;

    /**
     * 模拟的对话历史（演示指代消解和上下文补全）
     */
    private static final List<Message> MOCK_HISTORY = List.of(
            new UserMessage("Python入门课多少钱？"),
            new AssistantMessage("Python入门课目前售价299元，包含60课时的视频教程和3个实战项目。")
    );

    public QueryRewriteController(ChatClient.Builder chatClientBuilder,
                                  QueryRewriteService queryRewriteService) {
        this.chatClientBuilder = chatClientBuilder;
        this.chatClient = chatClientBuilder.build();
        this.queryRewriteService = queryRewriteService;
    }

    /**
     * 方式一：自定义改写服务
     * 指代消解 + 上下文补全 + 口语转书面，带启发式判断和兜底
     * <p>
     * 测试：GET /rag/rewrite/custom?question=那它有没有证书
     * 预期改写：Python入门课是否提供结业证书？
     */
    @GetMapping("/custom")
    public Map<String, Object> custom(@RequestParam("question") String question) {
        long start = System.currentTimeMillis();
        String rewritten = queryRewriteService.safeRewrite(question, MOCK_HISTORY);
        long latency = System.currentTimeMillis() - start;

        Map<String, Object> result = new HashMap<>();
        result.put("original", question);
        result.put("rewritten", rewritten);
        result.put("history", formatHistory(MOCK_HISTORY));
        result.put("latencyMs", latency);
        return result;
    }

    /**
     * 方式二：Spring AI 内置 CompressionQueryTransformer
     * 处理多轮对话中的指代和省略，把对话历史压缩进查询
     * <p>
     * 测试：GET /rag/rewrite/compression?question=那它有没有证书
     * 预期改写：Python入门课是否提供结业证书？
     */
    @GetMapping("/compression")
    public Map<String, Object> compression(@RequestParam("question") String question) {
        CompressionQueryTransformer compression = CompressionQueryTransformer.builder()
                .chatClientBuilder(chatClientBuilder)
                .build();

        Query query = new Query(question, MOCK_HISTORY, Collections.emptyMap());
        long start = System.currentTimeMillis();
        Query rewritten = compression.transform(query);
        long latency = System.currentTimeMillis() - start;

        Map<String, Object> result = new HashMap<>();
        result.put("original", question);
        result.put("rewritten", rewritten.text());
        result.put("history", formatHistory(MOCK_HISTORY));
        result.put("latencyMs", latency);
        return result;
    }

    /**
     * 方式三：Spring AI 内置 RewriteQueryTransformer
     * 优化查询表达（口语转书面），不处理指代消解
     * <p>
     * 测试：GET /rag/rewrite/rewriter?question=ES查询太慢了怎么搞
     * 预期改写：Elasticsearch查询性能优化方案
     */
    @GetMapping("/rewriter")
    public Map<String, Object> rewriter(@RequestParam("question") String question) {
        RewriteQueryTransformer rewriter = RewriteQueryTransformer.builder()
                .chatClientBuilder(chatClientBuilder)
                .build();

        Query query = new Query(question);
        long start = System.currentTimeMillis();
        Query rewritten = rewriter.transform(query);
        long latency = System.currentTimeMillis() - start;

        Map<String, Object> result = new HashMap<>();
        result.put("original", question);
        result.put("rewritten", rewritten.text());
        result.put("latencyMs", latency);
        return result;
    }

    /**
     * 方式四：Spring AI 内置 MultiQueryExpander
     * 一个问题扩展成多个，适合复合问题场景
     * <p>
     * 测试：GET /rag/rewrite/expand?question=Redis持久化方式有哪些
     * 预期：扩展为3-4个不同角度的查询
     */
    @GetMapping("/expand")
    public Map<String, Object> expand(@RequestParam("question") String question) {
        MultiQueryExpander expander = MultiQueryExpander.builder()
                .chatClientBuilder(chatClientBuilder)
                .numberOfQueries(3)
                .includeOriginal(true)
                .build();

        Query query = new Query(question);
        long start = System.currentTimeMillis();
        List<Query> expanded = expander.expand(query);
        long latency = System.currentTimeMillis() - start;

        Map<String, Object> result = new HashMap<>();
        result.put("original", question);
        result.put("expanded", expanded.stream().map(Query::text).collect(Collectors.toList()));
        result.put("count", expanded.size());
        result.put("latencyMs", latency);
        return result;
    }

    /**
     * 方式五：HyDE 假设性回答
     * 先让大模型生成假设性回答，实际项目中用假设回答去向量库检索
     * 这里只演示假设回答的生成
     * <p>
     * 测试：GET /rag/rewrite/hyde?question=微服务之间怎么通信
     * 预期：生成一段包含专业术语的假设性回答
     */
    @GetMapping("/hyde")
    public Map<String, Object> hyde(@RequestParam("question") String question) {
        long start = System.currentTimeMillis();
        String hypothetical = chatClient.prompt()
                .user(u -> u.text("""
                    请根据以下问题，生成一段可能的回答。
                    这段回答不需要完全准确，但应该包含相关的专业术语和概念。
                    直接输出回答内容，不要加任何前缀或解释。

                    问题：{question}
                    """).param("question", question))
                .call()
                .content();
        long latency = System.currentTimeMillis() - start;

        Map<String, Object> result = new HashMap<>();
        result.put("original", question);
        result.put("hypotheticalAnswer", hypothetical);
        result.put("latencyMs", latency);
        result.put("tip", "实际项目中，用这段假设回答的向量去检索，命中率比用原始短问题高");
        return result;
    }

    private List<String> formatHistory(List<Message> history) {
        return history.stream()
                .map(msg -> {
                    String role = msg instanceof UserMessage ? "用户" : "助手";
                    return role + "：" + msg.getText();
                })
                .collect(Collectors.toList());
    }
}
