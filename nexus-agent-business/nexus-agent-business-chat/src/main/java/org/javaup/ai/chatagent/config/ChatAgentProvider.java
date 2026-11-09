package org.javaup.ai.chatagent.config;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.agent.hook.toolcalllimit.ToolCallLimitHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.toolerror.ToolErrorInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.toolretry.ToolRetryInterceptor;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.mcp.McpReloadResult;
import org.javaup.ai.chatagent.mcp.McpToolAssembler;
import org.javaup.ai.chatagent.skill.LoadSkillTool;
import org.javaup.ai.chatagent.skill.SkillProperties;
import org.javaup.ai.chatagent.skill.SkillRegistry;
import org.javaup.ai.chatagent.support.DashScopeCompatibilityInterceptor;
import org.javaup.ai.chatagent.support.TavilyToolInputFallbackInterceptor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/*
ReactAgent 的持有者与热替换入口。

为什么需要这一层：ReactAgent 是不可变对象——工具列表和系统提示词在构造时就被拷贝进
AgentToolNode / AgentLlmNode，构建完成后没有任何 setter 能改（ReactAgent 里的 toolNode 是
private final，也没有 getter）。所以"MCP 配置改了要生效"只有一条路：重建一个 ReactAgent。

提供商负责三件事：
1. get()：给执行器取当前生效的 Agent；
2. 重建：ChatAgentConfiguration 只负责注册本 Bean，装配逻辑集中在 build() 里；
3. interrupt()：见下面的说明。

一个坑：ReactAgent.interrupt() 实际是往实例内部的 threadIdStateMap 写中断标记，而不是往
共享的 checkpoint 存储写。所以重建之后，老实例的运行中线程必须由老实例去中断，新实例上的
interrupt 对它无效。因此这里除了 current，还保留 retiring 列表，interrupt 时逐个尝试——
只有真正在跑那次 run 的实例会命中，写进其他实例是空操作，安全。
 */
@Slf4j
public class ChatAgentProvider {

    /*
    内置工具名的保留集。MCP 工具不允许和它们重名，否则 ToolCallLimitHook 按名字建限额时会串。
     */
    private static final Set<String> RESERVED_TOOL_NAMES = Set.of("tavily_search", LoadSkillTool.TOOL_NAME);

    /*
    退役 Agent 的保留上限。
    退役实例只是为了让 stop 能中断“reload 之前启动的 run”，而一次对话通常几十秒内就结束，
    没必要无限保留。4 个足够覆盖连续的几次 reload。
     */
    private static final int MAX_RETIRING_AGENTS = 4;

    private final ChatModel chatModel;
    private final MysqlSaver mysqlCheckpointSaver;
    private final ToolCallback tavilySearchToolCallback;
    private final ToolCallback loadSkillToolCallback;
    private final McpToolAssembler mcpToolAssembler;
    private final SkillRegistry skillRegistry;
    private final SkillProperties skillProperties;
    private final ChatAgentProperties chatAgentProperties;
    private final DashScopeCompatibilityInterceptor dashScopeCompatibilityInterceptor;
    private final TavilyToolInputFallbackInterceptor tavilyToolInputFallbackInterceptor;

    /*
    当前生效的 Agent。volatile 保证重建后其他线程立刻看到新实例。
    运行中的请求会在进入执行阶段时取一次并持有本地引用，因此重建不会打断它们。
     */
    private volatile ReactAgent current;

    /*
    退役但仍可能有运行中线程的 Agent。
    reload 时把旧实例放进来，保证 stop 接口能中断"重建前启动"的那次 run。
     */
    private final List<ReactAgent> retiring = new java.util.concurrent.CopyOnWriteArrayList<>();

    public ChatAgentProvider(ChatModel chatModel,
                             MysqlSaver mysqlCheckpointSaver,
                             ToolCallback tavilySearchToolCallback,
                             ToolCallback loadSkillToolCallback,
                             McpToolAssembler mcpToolAssembler,
                             SkillRegistry skillRegistry,
                             SkillProperties skillProperties,
                             ChatAgentProperties chatAgentProperties,
                             DashScopeCompatibilityInterceptor dashScopeCompatibilityInterceptor,
                             TavilyToolInputFallbackInterceptor tavilyToolInputFallbackInterceptor) {
        this.chatModel = chatModel;
        this.mysqlCheckpointSaver = mysqlCheckpointSaver;
        this.tavilySearchToolCallback = tavilySearchToolCallback;
        this.loadSkillToolCallback = loadSkillToolCallback;
        this.mcpToolAssembler = mcpToolAssembler;
        this.skillRegistry = skillRegistry;
        this.skillProperties = skillProperties;
        this.chatAgentProperties = chatAgentProperties;
        this.dashScopeCompatibilityInterceptor = dashScopeCompatibilityInterceptor;
        this.tavilyToolInputFallbackInterceptor = tavilyToolInputFallbackInterceptor;
        // 启动时同步构建首个实例，保证任何执行器第一次 get() 都能拿到可用 Agent。
        this.current = buildAgent();
    }

    public ReactAgent get() {
        return current;
    }

    /*
    重建 Agent，让 MCP 配置改动生效。

    语义（Q8 决策：原子替换）：
    1. prepare 阶段先按新配置建好全部连接和工具，失败任何一个都不落地；
    2. 用新工具集重建 Agent；重建失败同样回滚；
    3. 全部成功才替换 Agent 引用，并把旧连接交给装配器做延迟关闭。

    并发：整个方法加锁，避免两次 reload 交错把状态搞乱。运行中的请求不受影响——
    它们在自己那一轮开头已经拿到了旧 Agent 的本地引用。
     */
    public synchronized McpReloadResult reload() {
        long startTime = System.currentTimeMillis();
        McpToolAssembler.McpToolAssembly candidate = mcpToolAssembler.prepare(RESERVED_TOOL_NAMES);

        if (!candidate.failedServers().isEmpty()) {
            // 人在场的运维动作，不容忍部分失败：一个 server 连不上就整体回滚，保留旧工具集。
            mcpToolAssembler.discard(candidate);
            log.warn("MCP reload 回滚：装配失败 {}，旧工具集保持不变", candidate.failedServers());
            return new McpReloadResult(false, candidate.loadedServers(), candidate.failedServers(),
                candidate.conflictedTools(), currentMcpToolCount(), System.currentTimeMillis() - startTime,
                "以下 MCP server 装配失败，已整体回滚，旧工具集保持不变: " + candidate.failedServers());
        }

        ReactAgent rebuilt;
        try {
            rebuilt = buildAgent(candidate.toolCallbacks());
        } catch (RuntimeException exception) {
            mcpToolAssembler.discard(candidate);
            log.error("MCP reload 回滚：重建 ReactAgent 失败", exception);
            return new McpReloadResult(false, candidate.loadedServers(), candidate.failedServers(),
                candidate.conflictedTools(), currentMcpToolCount(), System.currentTimeMillis() - startTime,
                "重建 ReactAgent 失败，已回滚: " + exception.getMessage());
        }

        ReactAgent previous = current;
        current = rebuilt;
        try {
            mcpToolAssembler.commit(candidate);
        } catch (RuntimeException exception) {
            // commit 理论上不会失败（只是原子换引用 + 调度延迟关闭），仍然回滚以免状态错位。
            current = previous;
            mcpToolAssembler.discard(candidate);
            log.error("MCP reload 回滚：提交连接失败", exception);
            return new McpReloadResult(false, candidate.loadedServers(), candidate.failedServers(),
                candidate.conflictedTools(), currentMcpToolCount(), System.currentTimeMillis() - startTime,
                "提交 MCP 连接失败，已回滚: " + exception.getMessage());
        }
        retireAgent(previous);

        log.info("MCP reload 成功：接入 server={}，工具 {} 个，耗时 {} ms",
            candidate.loadedServers(), candidate.toolCallbacks().size(), System.currentTimeMillis() - startTime);
        return new McpReloadResult(true, candidate.loadedServers(), candidate.failedServers(),
            candidate.conflictedTools(), candidate.toolCallbacks().size(), System.currentTimeMillis() - startTime,
            "reload 成功");
    }

    /*
    把旧 Agent 放进退役列表。
    它不再接受新请求，但还可能有 reload 之前启动、尚未结束的 run，需要能被 interrupt 找到。
     */
    private void retireAgent(ReactAgent previous) {
        if (previous == null || previous == current) {
            return;
        }
        retiring.add(previous);
        while (retiring.size() > MAX_RETIRING_AGENTS) {
            retiring.remove(0);
        }
    }

    /*
    当前生效的 MCP 工具数量。
    只用于 reload 回滚时告诉调用方“旧工具集还在，里面有 N 个工具”。
     */
    private int currentMcpToolCount() {
        return (int) mcpToolAssembler.snapshots().stream()
            .mapToLong(snapshot -> snapshot.toolNames().size())
            .sum();
    }

    /*
    中断指定会话线程。
    只对"确实在这个实例内运行"的线程生效，其他实例收到的是空操作，所以逐个尝试是安全的。
     */
    public void interrupt(RunnableConfig runnableConfig) {
        if (runnableConfig == null) {
            return;
        }
        ReactAgent active = current;
        if (active != null) {
            active.interrupt(runnableConfig);
        }
        for (ReactAgent agent : retiring) {
            try {
                agent.interrupt(runnableConfig);
            } catch (RuntimeException exception) {
                log.debug("中断退役 Agent 失败（忽略）", exception);
            }
        }
    }

    /*
    装配一个全新的 ReactAgent（启动路径）。
    先走完整的装配流程拿到 MCP 工具，再交给统一的构建方法。
     */
    private ReactAgent buildAgent() {
        McpToolAssembler.McpToolAssembly assembly = mcpToolAssembler.assemble(RESERVED_TOOL_NAMES);
        return buildAgent(assembly.toolCallbacks());
    }

    /*
    装配一个全新的 ReactAgent。
    工具面 = 内置 tavily_search + 可选 load_skill + 传入的 MCP 工具；
    系统提示词 = 基础提示词 + 技能目录。
    任何一步的失败降级都在 McpToolAssembler / SkillRegistry 内部完成，这里不做额外兜底。
     */
    private ReactAgent buildAgent(List<ToolCallback> mcpToolCallbacks) {
        /*
        第一步：工具汇总（Function Calling 的完整工具面）。
        1. 内置工具：tavily_search（联网搜索）
        2. load_skill：技能按需加载（技能未启用或目录为空时不注册，避免给模型一个永远调不通的工具）
        3. MCP 动态工具：由 McpToolAssembler 从 nexus_agent_mcp_server 表读取启用配置后装配；
           连接失败/白名单过滤/重名冲突都在装配器内部处理（WARN + 跳过），不会阻塞启动。
           reservedToolNames 把内置工具名传进去，保证 MCP 工具不会和它们重名。
        最终工具名全局唯一——这是 ToolCallLimitHook 按工具名限额的前提。
         */
        List<ToolCallback> toolCallbacks = new ArrayList<>();
        toolCallbacks.add(tavilySearchToolCallback);
        if (skillProperties.isEnabled() && !skillRegistry.isEmpty()) {
            toolCallbacks.add(loadSkillToolCallback);
        }
        if (mcpToolCallbacks != null) {
            toolCallbacks.addAll(mcpToolCallbacks);
        }

        /*
        第二步：系统提示词 = 基础提示词 + 技能目录。
        SkillRegistry.catalogSection() 在没有任何技能时返回空串，提示词保持原样。
         */
        String instruction = chatAgentProperties.getSystemPrompt() + skillRegistry.catalogSection();

        /*
        第三步：Hook 限流。模型调用限额保持不变；工具调用限额按"每个注册的工具各一条"，
        MCP 工具和 load_skill 因此也被 app.chat.max-tool-calls-* 约束，不再绕过治理。
         */
        List<Hook> hooks = new ArrayList<>();
        hooks.add(ModelCallLimitHook.builder()
            .runLimit(chatAgentProperties.getMaxModelCallsPerRun())
            .threadLimit(chatAgentProperties.getMaxModelCallsPerThread())
            .exitBehavior(ModelCallLimitHook.ExitBehavior.END)
            .build());
        toolCallbacks.stream()
            .map(callback -> callback.getToolDefinition() == null ? null : callback.getToolDefinition().name())
            .filter(Objects::nonNull)
            .distinct()
            .forEach(toolName -> hooks.add(ToolCallLimitHook.builder()
                .toolName(toolName)
                .runLimit(chatAgentProperties.getMaxToolCallsPerRun())
                .threadLimit(chatAgentProperties.getMaxToolCallsPerThread())
                .exitBehavior(ToolCallLimitHook.ExitBehavior.END)
                .build()));

        return ReactAgent.builder()
            // Agent 的标识名，用于日志和追踪
            .name("business_chat_agent")
            // 对话模型，OPEN_CHAT 模式的生成引擎
            .model(chatModel)
            // 系统提示词（已拼接技能目录），定义 Agent 的角色和行为规范
            .instruction(instruction)
            // 注册可用工具：tavily_search + load_skill（可选）+ MCP 动态工具
            .tools(toolCallbacks)
            // 状态持久化器，每轮推理结果都会存到 MySQL
            .saver(mysqlCheckpointSaver)
            // 并行工具执行：模型一次推理中发起的多个工具调用可以并行执行
            .parallelToolExecution(true)
            .maxParallelTools(4)
            .hooks(hooks)
            /*
            拦截器链（按顺序执行）：
            1. DashScopeCompatibilityInterceptor：处理 DashScope 的协议兼容问题
            2. TavilyToolInputFallbackInterceptor：为 tavily_search 兜底 query 参数
            3. ToolRetryInterceptor：tavily_search 的重试策略
            4. ToolErrorInterceptor：兜底工具异常（含 load_skill 与 MCP 工具），转成错误消息返回给模型
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
