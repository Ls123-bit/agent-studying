package org.javaup.ai.chatagent.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/*
MCP 接入配置（app.mcp）。
MCP server 的清单不在这里配置——它们维护在数据库表 nexus_agent_mcp_server 里；
这里只保留全局开关和兜底超时，保证不改代码就能整体关闭 MCP 能力。
 */
@ConfigurationProperties(prefix = "app.mcp")
public class McpProperties {

    /*
    是否启用 MCP 工具接入。
    关闭后 McpToolAssembler 直接返回空装配，ReactAgent 只保留内置工具，应用启动不受影响。
     */
    private boolean enabled = true;

    /*
    MCP 工具调用的默认请求超时（毫秒）。
    单个 server 可以在 nexus_agent_mcp_server.timeout_ms 里单独覆盖。
    注意：这是“调用工具”的超时，不是“建立连接”的超时，后者见 connectTimeoutMs。
     */
    private long requestTimeoutMs = 60000L;

    /*
    MCP 建连超时（毫秒）。
    只作用于 initialize() 握手，默认 5 秒。
    为什么不复用 requestTimeoutMs：如果配置的 server 是“丢包但不拒绝”的状态，
    用 60 秒的请求超时去握手会让启动被 N×60 秒拖住；而工具调用本身又确实需要较长的超时。
    两者语义不同，必须分开。
     */
    private long connectTimeoutMs = 5000L;

    /*
    reload 时旧连接的宽限期（毫秒）。
    原子替换后旧 client 不会立即关闭，而是等这么久再关，避免打断正在进行中的工具调用
    （并行工具执行最多 4 个调用同时在飞）。默认 30 秒——超过这个时长的调用本身也快触达
    requestTimeoutMs 了，再等收益很低。
     */
    private long drainGraceMs = 30000L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    public void setRequestTimeoutMs(long requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
    }

    public long getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(long connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public long getDrainGraceMs() {
        return drainGraceMs;
    }

    public void setDrainGraceMs(long drainGraceMs) {
        this.drainGraceMs = drainGraceMs;
    }
}
