package org.javaup.ai.chatagent.mcp;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.data.McpServerConfig;
import org.javaup.ai.chatagent.mapper.McpServerConfigMapper;
import org.javaup.ai.chatagent.support.StreamEventWriter;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/*
MCP 工具装配器（business 主链路接入 MCP 的核心）。

职责：从 nexus_agent_mcp_server 读取启用状态的 server 配置，逐个建立 MCP 客户端连接，
把每个 server 暴露的工具转成 Spring AI 的 ToolCallback，交给 ChatAgentProvider 合并进 ReactAgent。

四个关键设计（与整体方案的治理约定一一对应）：
1. 白名单：tool_whitelist 为 NULL 或空数组时暴露该 server 全部工具，否则只暴露名单内的工具。
2. 冲突剔除：所有工具名（含内置的 tavily_search / load_skill）先到先得，重名的后到者剔除并 WARN，
   避免多个 server 暴露同名工具让模型选择混乱。
3. 故障降级：某个 server 连不上只记 WARN 并跳过，绝不阻塞应用启动；连不上的 server 不产生任何工具。
4. 全量兜底：整个装配过程任何一步出错（配置表不存在、DB 异常等）都降级为"本次启动没有 MCP 工具"。

启动 vs reload 的语义差异（这是两个方法的由来）：
- 启动用 assemble()：prepare + commit 一步到位，能连几个算几个，不能让坏配置拖垮应用。
- reload 用 prepare() → 上层重建 Agent → commit()：全部成功才落地，任何一个 server 失败就
  整体回滚（discard），保留旧工具集。reload 是有人在场的运维动作，可以重试，没有理由容忍部分失败。
 */
@Slf4j
@Component
public class McpToolAssembler {

    /*
    v1 支持的传输类型。
    配置表里预留了 SSE / STDIO 等类型，但当前只实现 STREAMABLE（streamable-http），
    其他类型的配置行会在装配时按失败处理（WARN + 跳过），不会拖垮启动。
     */
    public static final String TRANSPORT_STREAMABLE = "STREAMABLE";

    private static final String CLIENT_NAME = "nexus-agent-business-chat";

    private final McpServerConfigMapper serverConfigMapper;
    private final McpProperties properties;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final StreamEventWriter streamEventWriter;

    /*
    旧连接的延迟关闭器。
    reload 之后旧 client 不立即关闭，否则会打断正在飞行的工具调用（并行执行最多 4 个）。
    单线程守护线程即可——它只做 close，不承担任何业务。
     */
    private final ScheduledExecutorService connectionReleaser = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mcp-connection-releaser");
        thread.setDaemon(true);
        return thread;
    });

    /*
    当前生效的装配结果。
    只通过 commit() 原子替换，其他线程读到的永远是同一个不可变对象。
     */
    private volatile McpToolAssembly committed = McpToolAssembly.empty();

    public McpToolAssembler(McpServerConfigMapper serverConfigMapper,
                            McpProperties properties,
                            ObjectMapper objectMapper,
                            // 必须显式限定为主（MySQL）数据源上的模板：
                            // 容器里还有一个 PG 的 JdbcTemplate（documentManagePgVectorJdbcTemplate），
                            // 不限定就会把 MySQL 的建表 DDL 发到 PG 上（COMMENT 语法错误）。
                            @Qualifier("mcpBootstrapJdbcTemplate") JdbcTemplate jdbcTemplate,
                            StreamEventWriter streamEventWriter) {
        this.serverConfigMapper = serverConfigMapper;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.streamEventWriter = streamEventWriter;
    }

    /*
    一次装配的结果汇总。
    toolCallbacks：最终 accepted 的工具回调，直接并入 ReactAgent；
    clients：本次建立并需要被托管生命周期的连接（成功提交后由装配器负责关闭）；
    snapshots：每个 server 的接入情况（含失败的），用于管理端只读查询。
     */
    public record McpToolAssembly(List<ToolCallback> toolCallbacks,
                                  List<String> loadedServers,
                                  List<String> failedServers,
                                  List<String> conflictedTools,
                                  List<McpSyncClient> clients,
                                  List<McpServerSnapshot> snapshots) {

        public static McpToolAssembly empty() {
            return new McpToolAssembly(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }

        public boolean isEmpty() {
            return toolCallbacks.isEmpty();
        }
    }

    /*
    启动路径：装配并立即生效。
    任何异常都降级为空装配，保证主链路在没有 MCP 的环境下照常启动。
     */
    public McpToolAssembly assemble(Set<String> reservedToolNames) {
        McpToolAssembly candidate = prepare(reservedToolNames);
        commit(candidate);
        return candidate;
    }

    /*
    reload 路径第一步：只建连、只收集，不改变任何已生效状态。
    reservedToolNames：内置工具名（tavily_search / load_skill），MCP 工具不允许和它们重名。
    */
    public McpToolAssembly prepare(Set<String> reservedToolNames) {
        if (!properties.isEnabled()) {
            log.info("MCP 工具接入未启用（app.mcp.enabled=false），跳过装配");
            return McpToolAssembly.empty();
        }

        // 建表是幂等的兜底动作：表不存在时自动创建；建表失败只 WARN，后面读取还会再兜底。
        ensureTable();

        List<McpServerConfig> rows;
        try {
            rows = serverConfigMapper.selectList(new LambdaQueryWrapper<McpServerConfig>()
                .eq(McpServerConfig::getStatus, 1));
        } catch (Exception exception) {
            log.warn("读取 MCP server 配置失败，本次不接入任何 MCP 工具", exception);
            return McpToolAssembly.empty();
        }
        if (rows == null || rows.isEmpty()) {
            log.info("没有启用状态的 MCP server 配置，本次不接入 MCP 工具");
            return McpToolAssembly.empty();
        }

        // occupiedToolNames 是全局占位表：内置工具名先占坑，之后每个 MCP 工具名先到先得。
        Set<String> occupiedToolNames = new HashSet<>();
        if (reservedToolNames != null) {
            occupiedToolNames.addAll(reservedToolNames);
        }

        List<ToolCallback> acceptedCallbacks = new ArrayList<>();
        List<String> loadedServers = new ArrayList<>();
        List<String> failedServers = new ArrayList<>();
        List<String> conflictedTools = new ArrayList<>();
        List<McpSyncClient> clients = new ArrayList<>();
        Map<String, McpServerSnapshot> snapshots = new LinkedHashMap<>();

        for (McpServerConfig row : rows) {
            McpSyncClient client = null;
            try {
                client = buildClient(row);
                List<String> serverToolNames =
                    collectCallbacks(row, client, occupiedToolNames, acceptedCallbacks, conflictedTools);
                if (serverToolNames.isEmpty()) {
                    log.warn("MCP server [{}] 没有暴露任何可用工具（可能被白名单过滤或全部重名），该 server 不参与本次装配",
                        row.getServerName());
                    snapshots.put(row.getServerName(), snapshot(row, List.of(), false,
                        "没有暴露任何可用工具（白名单过滤或工具重名）"));
                    // 没有工具可用的 server 不保留连接，及时释放。
                    closeQuietly(client);
                    client = null;
                    failedServers.add(row.getServerName());
                } else {
                    clients.add(client);
                    loadedServers.add(row.getServerName());
                    snapshots.put(row.getServerName(), snapshot(row, serverToolNames, true, null));
                }
            } catch (Exception exception) {
                failedServers.add(row.getServerName());
                String failReason = describe(exception);
                snapshots.put(row.getServerName(), snapshot(row, List.of(), false, failReason));
                log.warn("MCP server [{}] 装配失败，已降级跳过（endpoint={}）: {}",
                    row.getServerName(), row.getEndpoint(), failReason);
                closeQuietly(client);
            }
        }

        log.info("MCP 工具装配完成：成功 server={}，失败 server={}，接入工具 {} 个，重名剔除 {}",
            loadedServers, failedServers, acceptedCallbacks.size(), conflictedTools);
        return new McpToolAssembly(List.copyOf(acceptedCallbacks), List.copyOf(loadedServers),
            List.copyOf(failedServers), List.copyOf(conflictedTools), List.copyOf(clients),
            List.copyOf(snapshots.values()));
    }

    /*
    reload 路径第二步：原子替换。
    只有上层已经成功用这份装配结果重建了 Agent，才会走到这里。
    旧连接进入延迟关闭队列，宽限期后统一关闭，避免打断在飞的调用。
     */
    public void commit(McpToolAssembly candidate) {
        McpToolAssembly previous = committed;
        committed = candidate == null ? McpToolAssembly.empty() : candidate;
        if (previous != null && previous != candidate && !previous.clients().isEmpty()) {
            retire(previous.clients());
        }
    }

    /*
    reload 路径的失败出口：丢弃候选装配并立刻关闭它的连接。
    旧工具集完全不受影响。
     */
    public void discard(McpToolAssembly candidate) {
        if (candidate == null) {
            return;
        }
        candidate.clients().forEach(this::closeQuietly);
    }

    /*
    当前生效的装配结果（只读）。
     */
    public McpToolAssembly current() {
        return committed;
    }

    /*
    当前生效的工具列表，供只读查询接口使用。
    没有启用任何 server 时返回空列表，不返回 null。
     */
    public List<McpServerSnapshot> snapshots() {
        return committed == null ? List.of() : committed.snapshots();
    }

    /*
    应用关闭时把仍在使用的连接一起关掉。
    宽限期里排队等待关闭的旧连接也一并取消，避免线程池残留。
     */
    @PreDestroy
    public void shutdown() {
        try {
            connectionReleaser.shutdownNow();
        } catch (RuntimeException exception) {
            log.debug("关闭 MCP 连接释放线程池失败（忽略）", exception);
        }
        McpToolAssembly currentAssembly = committed;
        if (currentAssembly != null) {
            currentAssembly.clients().forEach(this::closeQuietly);
        }
    }

    private void retire(List<McpSyncClient> clients) {
        long graceMs = Math.max(0L, properties.getDrainGraceMs());
        if (graceMs <= 0L) {
            clients.forEach(this::closeQuietly);
            return;
        }
        try {
            connectionReleaser.schedule(() -> clients.forEach(this::closeQuietly), graceMs, TimeUnit.MILLISECONDS);
            log.info("MCP 旧连接进入宽限期：{} 个连接将在 {} ms 后关闭", clients.size(), graceMs);
        } catch (RuntimeException exception) {
            // 调度失败（例如容器已经关闭线程池）时退化为立刻关闭，至少不泄漏连接。
            log.warn("MCP 旧连接延迟关闭调度失败，改为立即关闭", exception);
            clients.forEach(this::closeQuietly);
        }
    }

    /*
    建立单个 MCP server 的同步客户端连接。
    endpoint 拆成 base + endpoint 两段传给 transport（例如 http://localhost:7090/mcp
    → base=http://localhost:7090，endpoint=/mcp），与 ai-example 手动建连方式一致。
    initialize() 真正发起连接握手，连不上会抛异常，由上层捕获降级。
     */
    private McpSyncClient buildClient(McpServerConfig row) {
        String transportType = StrUtil.blankToDefault(row.getTransport(), TRANSPORT_STREAMABLE)
            .trim()
            .toUpperCase(Locale.ROOT);
        if (!TRANSPORT_STREAMABLE.equals(transportType)) {
            throw new IllegalStateException("暂不支持的 transport 类型: " + transportType + "（v1 仅支持 STREAMABLE）");
        }
        if (StrUtil.isBlank(row.getEndpoint())) {
            throw new IllegalStateException("endpoint 未配置");
        }

        URI uri = URI.create(row.getEndpoint().trim());
        String baseUrl = uri.getScheme() + "://" + uri.getRawAuthority();
        String endpoint = StrUtil.isNotBlank(uri.getRawPath()) ? uri.getRawPath() : "/mcp";

        HttpClientStreamableHttpTransport.Builder transportBuilder = HttpClientStreamableHttpTransport
            .builder(baseUrl)
            .endpoint(endpoint)
            // 建连超时只约束握手，和工具调用超时解耦（否则一个丢包的 server 能拖住启动几十秒）。
            .connectTimeout(Duration.ofMillis(Math.max(1000L, properties.getConnectTimeoutMs())));

        // 鉴权头：整条连接上每个请求都带上（MCP 的握手、初始化、工具调用都会经过这套 header）。
        Map<String, String> authHeaders = parseAuthHeaders(row);
        if (!authHeaders.isEmpty()) {
            transportBuilder.customizeRequest(requestBuilder ->
                authHeaders.forEach(requestBuilder::header));
        }

        long timeoutMs = row.getTimeoutMs() != null && row.getTimeoutMs() > 0
            ? row.getTimeoutMs()
            : properties.getRequestTimeoutMs();

        McpSyncClient client = McpClient.sync(transportBuilder.build())
            .clientInfo(new McpSchema.Implementation(CLIENT_NAME, "1.0.0"))
            .requestTimeout(Duration.ofMillis(timeoutMs))
            .build();

        client.initialize();
        return client;
    }

    /*
    把单个 server 的工具回调收集进 accepted 列表，返回该 server 实际接入的工具名。
    每个 server 单独建一个 provider，方便把白名单和冲突判断精确关联到 server 行。
     */
    private List<String> collectCallbacks(McpServerConfig row,
                                          McpSyncClient client,
                                          Set<String> occupiedToolNames,
                                          List<ToolCallback> acceptedCallbacks,
                                          List<String> conflictedTools) {
        SyncMcpToolCallbackProvider provider = SyncMcpToolCallbackProvider.builder()
            .mcpClients(List.of(client))
            .build();
        return acceptCallbacks(row, List.of(provider.getToolCallbacks()), occupiedToolNames,
            acceptedCallbacks, conflictedTools);
    }

    /*
    白名单过滤 + 重名剔除的纯逻辑，与 MCP 建连无关。
    单独拆出来是为了能脱离真实 server 单测——这两条规则是治理的核心，值得被固定测试。
     */
    List<String> acceptCallbacks(McpServerConfig row,
                                 List<ToolCallback> providedCallbacks,
                                 Set<String> occupiedToolNames,
                                 List<ToolCallback> acceptedCallbacks,
                                 List<String> conflictedTools) {
        Set<String> whitelist = parseWhitelist(row);
        List<String> acceptedToolNames = new ArrayList<>();
        for (ToolCallback callback : providedCallbacks) {
            String toolName = toolNameOf(callback);
            if (StrUtil.isBlank(toolName)) {
                continue;
            }
            // 白名单为空集合表示全部暴露；非空时只放行名单内的工具。
            if (!whitelist.isEmpty() && !whitelist.contains(toolName)) {
                log.info("MCP server [{}] 的工具 [{}] 不在白名单内，跳过暴露", row.getServerName(), toolName);
                continue;
            }
            // 工具名先到先得：与内置工具或其他 server 重名的工具被剔除，保证模型看到的工具名全局唯一。
            if (!occupiedToolNames.add(toolName)) {
                conflictedTools.add(toolName);
                log.warn("MCP server [{}] 的工具 [{}] 与已有工具重名，按先到先得原则剔除",
                    row.getServerName(), toolName);
                continue;
            }
            // 每个 MCP 工具都套一层 TracedToolCallback，让它能像内置工具一样进
            // ChatToolTrace / USED_TOOLS / thinking 事件（装饰器透传定义与元数据，不影响限额与 returnDirect）。
            acceptedCallbacks.add(TracedToolCallback.wrap(row.getServerName(), callback, streamEventWriter));
            acceptedToolNames.add(toolName);
        }
        return acceptedToolNames;
    }

    private McpServerSnapshot snapshot(McpServerConfig row, List<String> toolNames, boolean connected, String failReason) {
        return new McpServerSnapshot(
            row.getServerName(),
            row.getEndpoint(),
            StrUtil.blankToDefault(row.getTransport(), TRANSPORT_STREAMABLE),
            List.copyOf(toolNames),
            connected,
            StrUtil.blankToDefault(failReason, "")
        );
    }

    /*
    把异常转成可排查的描述。
    java.net.ConnectException 这类异常的 getMessage() 是 null，直接打日志会变成“装配失败: null”，
    运维完全看不出是连不上还是协议不对，所以这里退化到异常类名。
     */
    private static String describe(Throwable exception) {
        if (exception == null) {
            return "未知错误";
        }
        String message = exception.getMessage();
        return StrUtil.isNotBlank(message)
            ? message
            : exception.getClass().getSimpleName() + "（无详细信息，通常表示目标地址不可达或拒绝连接）";
    }

    /*
    解析白名单 JSON（["tool_a","tool_b"]）。
    解析失败按空白名单处理（全部暴露）并 WARN——白名单是治理手段，配置错误不应该演变成启动失败，
    但 WARN 会提醒使用者及时修正。
     */
    private Set<String> parseWhitelist(McpServerConfig row) {
        if (StrUtil.isBlank(row.getToolWhitelist())) {
            return Set.of();
        }
        try {
            List<String> tools = objectMapper.readValue(row.getToolWhitelist(), new TypeReference<>() {
            });
            Set<String> whitelist = new LinkedHashSet<>();
            if (tools != null) {
                tools.stream()
                    .filter(StrUtil::isNotBlank)
                    .map(String::trim)
                    .forEach(whitelist::add);
            }
            return whitelist;
        } catch (Exception exception) {
            log.warn("MCP server [{}] 的 tool_whitelist 不是合法 JSON 数组（{}），按全部暴露处理",
                row.getServerName(), row.getToolWhitelist(), exception);
            return Set.of();
        }
    }

    /*
    解析鉴权请求头 JSON（{"Authorization":"Bearer xxx"}）。
    失败按"无鉴权头"处理并 WARN，与白名单保持一致的容错口径：
    配置错误不应该让一个 server 直接不可用，但必须留下可排查的痕迹。
     */
    private Map<String, String> parseAuthHeaders(McpServerConfig row) {
        if (StrUtil.isBlank(row.getAuthHeaders())) {
            return Map.of();
        }
        try {
            Map<String, String> headers = objectMapper.readValue(row.getAuthHeaders(), new TypeReference<>() {
            });
            if (headers == null || headers.isEmpty()) {
                return Map.of();
            }
            Map<String, String> cleaned = new LinkedHashMap<>();
            headers.forEach((name, value) -> {
                if (StrUtil.isNotBlank(name) && StrUtil.isNotBlank(value)) {
                    cleaned.put(name.trim(), value.trim());
                }
            });
            return Collections.unmodifiableMap(cleaned);
        } catch (Exception exception) {
            log.warn("MCP server [{}] 的 auth_headers 不是合法 JSON 对象（{}），本次按无鉴权头处理",
                row.getServerName(), row.getAuthHeaders(), exception);
            return Map.of();
        }
    }

    /*
    幂等建表。
    和 MysqlSaver 的 CREATE_IF_NOT_EXISTS 思路一致：表结构由代码兜底，第一次启动即可用，
    不强依赖使用者先手工执行 SQL（resources/sql/nexus_agent_mcp_server.sql 是它的评审版本）。
     */
    private void ensureTable() {
        try {
            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS nexus_agent_mcp_server (
                    id             BIGINT       NOT NULL COMMENT '主键（uid，手工插入时自行编号即可）',
                    server_name    VARCHAR(64)  NOT NULL COMMENT 'MCP server 唯一标识，用于日志与追踪',
                    endpoint       VARCHAR(512) NOT NULL COMMENT 'MCP 服务地址，例如 http://localhost:7090/mcp',
                    transport      VARCHAR(32)  NOT NULL DEFAULT 'STREAMABLE' COMMENT '传输类型：v1 仅支持 STREAMABLE',
                    tool_whitelist VARCHAR(2048) NULL COMMENT '允许暴露的工具名 JSON 数组；NULL 或 [] 表示全部暴露',
                    auth_headers   VARCHAR(2048) NULL COMMENT '鉴权请求头 JSON 对象，例如 {"Authorization":"Bearer xxx"}',
                    timeout_ms     BIGINT       NULL COMMENT '该 server 的调用超时（毫秒），NULL 时使用 app.mcp.request-timeout-ms',
                    remark         VARCHAR(512) NULL COMMENT '备注',
                    status         INT          NOT NULL DEFAULT 1 COMMENT '是否启用：1 启用 0 停用',
                    create_time    DATETIME     NULL COMMENT '创建时间',
                    edit_time      DATETIME     NULL COMMENT '编辑时间',
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_mcp_server_name (server_name)
                ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4
                """);
        } catch (Exception exception) {
            log.warn("nexus_agent_mcp_server 建表失败，将直接尝试读取配置。"
                + "如果后续报 Table doesn't exist，请确认 mcpBootstrapJdbcTemplate 绑定的是主（MySQL）数据源，"
                + "或手工执行 resources/sql/nexus_agent_mcp_server.sql", exception);
        }
    }

    /*
    取工具名：Spring AI 1.x 的 ToolCallback 没有直接的 getName()，
    名字在 ToolDefinition 里（MCP 工具回调的 name 就是 MCP server 暴露的工具名）。
     */
    private static String toolNameOf(ToolCallback callback) {
        if (callback == null) {
            return null;
        }
        ToolDefinition definition = callback.getToolDefinition();
        return definition == null ? null : definition.name();
    }

    private void closeQuietly(McpSyncClient client) {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (Exception exception) {
            log.debug("关闭 MCP 客户端失败（忽略）", exception);
        }
    }
}
