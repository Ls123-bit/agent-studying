package org.javaup.ai.chatagent.mcp;

import java.util.List;

/*
一次 MCP reload 的结果。

success=false 表示整体回滚：新配置里只要有一个 server 连不上，就保留旧工具集不动，
并把失败明细返回给调用方，而不是像启动时那样“能连几个算几个”。
原因：reload 是有人在场的运维动作，可以重试；启动时则不能因为一个坏配置拖垮整个应用。
 */
public record McpReloadResult(boolean success,
                              List<String> loadedServers,
                              List<String> failedServers,
                              List<String> conflictedTools,
                              int toolCount,
                              long elapsedMs,
                              String message) {
}
