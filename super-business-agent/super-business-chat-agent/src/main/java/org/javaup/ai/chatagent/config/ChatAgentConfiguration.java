package org.javaup.ai.chatagent.config;

import javax.sql.DataSource;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.agent.hook.toolcalllimit.ToolCallLimitHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.toolerror.ToolErrorInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.toolretry.ToolRetryInterceptor;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.CreateOption;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
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
                                             ChatAgentProperties chatAgentProperties) {
        return ReactAgent.builder()
            .name("business_chat_agent")
            .model(chatModel)
            .instruction(chatAgentProperties.getSystemPrompt())
            .tools(tavilySearchToolCallback)
            .saver(mysqlCheckpointSaver)
            .parallelToolExecution(true)
            .maxParallelTools(4)
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
            .interceptors(
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
