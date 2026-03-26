package org.javaup.ai.chatagent.config;

import javax.sql.DataSource;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.agent.hook.toolcalllimit.ToolCallLimitHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.toolerror.ToolErrorInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.toolretry.ToolRetryInterceptor;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.CreateOption;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import org.javaup.ai.chatagent.support.DashScopeCompatibilityInterceptor;
import org.javaup.ai.chatagent.tool.TavilySearchRequest;
import org.javaup.ai.chatagent.tool.TavilySearchTool;
import org.javaup.ai.chatagent.tool.TavilySearchToolResult;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({ChatAgentProperties.class, TavilySearchProperties.class})
public class ChatAgentConfiguration {

    /**
     * 使用 Spring AI Alibaba 自带的 MysqlSaver 保存 ReactAgent 的 checkpoint。
     *
     * <p>这样做的意义是：不仅业务层会话记录能持久化，
     * Agent 自己的消息状态、工具观察结果、节点推进位置也都能在 MySQL 中保留下来，
     * 从而支持“应用重启后继续对话”。</p>
     */
    @Bean
    public MysqlSaver mysqlCheckpointSaver(DataSource dataSource) {
        /*
         * CREATE_IF_NOT_EXISTS 让应用首次启动时自动补齐 checkpoint 所需表结构，
         * 避免每个环境都要手工先初始化一遍 ReactAgent 的运行态表。
         */
        return MysqlSaver.builder()
            .dataSource(dataSource)
            .createOption(CreateOption.CREATE_IF_NOT_EXISTS)
            .build();
    }

    /**
     * 把 Tavily 搜索能力注册成 ReactAgent 可调用的工具。
     *
     * <p>这里定义的是“工具契约”：
     * 工具名是 `tavily_search`，
     * 输入结构是 TavilySearchRequest，
     * 真正执行逻辑在 TavilySearchTool#search 中。</p>
     */
    @Bean
    public ToolCallback tavilySearchToolCallback(TavilySearchTool tavilySearchTool) {
        /*
         * 这里把普通的 Spring Bean 方法包装成 ReactAgent 可发现的工具。
         * 模型后续看到的工具名、描述和入参结构，都由这段定义决定。
         */
        return FunctionToolCallback
            .builder("tavily_search", tavilySearchTool::search)
            .description("联网搜索最新信息、事实资料和网页来源。输入 query 为搜索问题，可选 topic 和 maxResults，其中 topic 仅允许 general、news、finance。")
            .inputType(TavilySearchRequest.class)
            .build();
    }

    /**
     * 创建业务聊天使用的 ReactAgent。
     *
     * <p>这部分只放通用 Agent 能力：
     * 模型、系统提示词、工具、checkpoint saver、调用次数限制、工具重试等。
     * SSE 协议、推荐问题、会话管理等产品层能力不在这里做，而是在 BusinessChatService 里完成。</p>
     */
    @Bean
    public ReactAgent businessChatReactAgent(ChatModel chatModel,
                                             MysqlSaver mysqlCheckpointSaver,
                                             ToolCallback tavilySearchToolCallback,
                                             ChatAgentProperties chatAgentProperties,
                                             DashScopeCompatibilityInterceptor dashScopeCompatibilityInterceptor) {
        return ReactAgent.builder()
            /*
             * 这一段定义 Agent 的基本身份和主模型行为。
             * instruction 会作为系统提示词参与每轮推理，直接影响 ReAct 的回答策略和工具使用偏好。
             */
            .name("business_chat_agent")
            .model(chatModel)
            .instruction(chatAgentProperties.getSystemPrompt())

            /*
             * 把业务侧提供的搜索工具和 checkpoint 能力挂进 Agent。
             * 从这里开始，ReactAgent 就具备了“调用 Tavily + 记住线程状态”的完整基础能力。
             */
            .tools(tavilySearchToolCallback)
            .saver(mysqlCheckpointSaver)

            /*
             * 允许同一轮里并行执行多个工具调用，提升复杂问题下的吞吐能力。
             * maxParallelTools 只是并发上限，不代表每次都会真的并行到这个数量。
             */
            .parallelToolExecution(true)
            .maxParallelTools(4)

            /*
             * Hook 负责限制模型调用次数和工具调用次数。
             * 这是避免 Agent 死循环或单次请求成本失控的第一道保护网。
             */
            .hooks(
                ModelCallLimitHook.builder()
                    .runLimit(chatAgentProperties.getMaxModelCallsPerRun())
                    .threadLimit(chatAgentProperties.getMaxModelCallsPerThread())
                    .exitBehavior(ModelCallLimitHook.ExitBehavior.END)
                    .build(),
                ToolCallLimitHook.builder()
                    .toolName("tavily_search")
                    .runLimit(chatAgentProperties.getMaxToolCallsPerRun())
                    .threadLimit(chatAgentProperties.getMaxToolCallsPerThread())
                    .exitBehavior(ToolCallLimitHook.ExitBehavior.END)
                    .build()
            )

            /*
             * Interceptor 负责处理工具调用时的异常和重试。
             * 对联网搜索这类外部依赖较强的工具来说，这一层能显著降低偶发网络波动带来的失败率。
             */
            .interceptors(
                dashScopeCompatibilityInterceptor,
                ToolRetryInterceptor.builder()
                    .toolName("tavily_search")
                    .maxRetries(2)
                    .initialDelay(200L)
                    .maxDelay(1200L)
                    .jitter(true)
                    .onFailure(ToolRetryInterceptor.OnFailureBehavior.RETURN_MESSAGE)
                    .build(),
                ToolErrorInterceptor.builder().build()
            )
            .build();
    }
}
