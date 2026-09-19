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
import org.javaup.ai.chatagent.support.TavilyToolInputFallbackInterceptor;
import org.javaup.ai.chatagent.tool.TavilySearchRequest;
import org.javaup.ai.chatagent.tool.TavilySearchTool;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/*
这个配置类做了三件事：创建状态存储器、注册工具回调、组装 ReactAgent
 */
@Configuration
@EnableConfigurationProperties({ChatAgentProperties.class, TavilySearchProperties.class})
public class ChatAgentConfiguration {


    /*
    MysqlSaver：会话状态持久化
    MysqlSaver 是 Spring AI Alibaba Graph 框架提供的检查点存储器。它的作用是把 Agent 每一轮推理的状态（包括消息历史、工具调用结果等）持久化到 MySQL。
        这样做的好处是：
        Agent 的多轮对话状态不会丢失
        即使服务重启，也能从上次的状态继续
        CreateOption.CREATE_IF_NOT_EXISTS 表示表不存在时自动建表
     */
    @Bean
    public MysqlSaver mysqlCheckpointSaver(DataSource dataSource) {

        return MysqlSaver.builder()
            .dataSource(dataSource)
            .createOption(CreateOption.CREATE_IF_NOT_EXISTS)
            .build();
    }

    /*
    工具注册
    这里把 TavilySearchTool 的 search 方法包装成了一个 ToolCallback，注册名为 tavily_search。
    关键点：
    description 是给大模型看的——模型根据这段描述来判断什么时候该调用这个工具
    inputType 指定了工具的入参类型，框架会自动把模型生成的 JSON 参数反序列化成 TavilySearchRequest
    方法引用 tavilySearchTool::search 就是工具的实际执行逻辑
     */
    @Bean
    public ToolCallback tavilySearchToolCallback(TavilySearchTool tavilySearchTool) {

        return FunctionToolCallback
            .builder("tavily_search", tavilySearchTool::search)
            .description("联网搜索最新信息、事实资料和网页来源。调用时必须传 JSON 参数，且至少包含非空 query；可选 topic 和 maxResults，其中 topic 仅允许 general、news、finance。")
            .inputType(TavilySearchRequest.class)
            .build();
    }

    @Bean
    public ReactAgent businessChatReactAgent(ChatModel chatModel,
                                             MysqlSaver mysqlCheckpointSaver,
                                             ToolCallback tavilySearchToolCallback,
                                             ChatAgentProperties chatAgentProperties,
                                             DashScopeCompatibilityInterceptor dashScopeCompatibilityInterceptor,
                                             TavilyToolInputFallbackInterceptor tavilyToolInputFallbackInterceptor) {
        return ReactAgent.builder()

                /*
                ReactAgent 的核心装配
                这是最重要的部分，我们拆开来看每一块配置。
                 */
            .name("business_chat_agent")//Agent 的标识名，用于日志和追踪
            .model(chatModel)//Agent 的标识名，用于日志和追踪
            .instruction(chatAgentProperties.getSystemPrompt())//系统提示词，定义了 Agent 的角色和行为规范
            //工具与持久化
            .tools(tavilySearchToolCallback)//注册可用工具，目前只有 tavily_search 一个联网搜索工具
            .saver(mysqlCheckpointSaver)//状态持久化器，每轮推理结果都会存到 MySQL
            //并行工具执行  开启了并行工具调用，最多同时执行 4 个工具。虽然目前只有一个工具，但如果模型在一次推理中决定发起多个搜索请求，它们可以并行执行，提升响应速度。
            .parallelToolExecution(true)
            .maxParallelTools(4)
            //Hook 限流机制
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
                拦截器链
                拦截器按顺序执行，每个都有特定职责：
                1. DashScopeCompatibilityInterceptor（DashScope 兼容拦截器）
                处理阿里云 DashScope API 的兼容性问题：
                关闭 parallelToolCalls 标志（DashScope 不支持）
                移除 streamOptions（DashScope 不支持）
                合并碎片化的工具调用（DashScope 有时会把一个工具调用拆成多个片段返回）
                2. TavilyToolInputFallbackInterceptor（工具入参兜底拦截器）
                确保 tavily_search 工具总是能拿到有效的 query 参数：
                如果模型生成的参数为空或格式不对，自动用当前问题作为兜底查询词
                避免因为模型偶尔的参数生成失误导致工具调用直接失败
                3. ToolRetryInterceptor（工具重试拦截器）
                针对 tavily_search 的重试策略：
                最多重试 2 次
                初始延迟 200ms，最大延迟 1200ms
                开启抖动（jitter）避免重试风暴
                重试全部失败后返回错误消息给模型（而不是抛异常），让模型自己决定下一步
                4. ToolErrorInterceptor（通用错误拦截器）
                兜底的错误处理，把未被前面拦截器捕获的工具异常转换成友好的错误消息返回给模型。
                 */
            .interceptors(
                dashScopeCompatibilityInterceptor,
                tavilyToolInputFallbackInterceptor,
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
