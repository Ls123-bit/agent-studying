package org.javaup.ai.chatagent.mcp;

import java.util.List;

/*
单个 MCP server 的装配快照。

为什么需要留存：装配器原来只把装配结果打日志就丢掉了，导致管理端无法回答
“这个 server 到底接上没有、接了哪些工具、失败原因是什么”。
快照跟着一次成功 commit 一起原子替换，因此它永远描述的是“当前生效的工具集来源”。
 */
public record McpServerSnapshot(String serverName,
                                String endpoint,
                                String transport,
                                List<String> toolNames,
                                boolean connected,
                                String failReason) {
}
