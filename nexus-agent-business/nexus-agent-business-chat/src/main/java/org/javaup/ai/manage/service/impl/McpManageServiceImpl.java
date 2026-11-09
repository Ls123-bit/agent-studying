package org.javaup.ai.manage.service.impl;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.config.ChatAgentProvider;
import org.javaup.ai.chatagent.mcp.McpReloadResult;
import org.javaup.ai.chatagent.mcp.McpServerSnapshot;
import org.javaup.ai.chatagent.mcp.McpToolAssembler;
import org.javaup.ai.manage.service.McpManageService;
import org.javaup.ai.manage.vo.McpReloadVo;
import org.javaup.ai.manage.vo.McpServerViewVo;
import org.springframework.stereotype.Service;

import java.util.List;

/*
MCP 接入运维服务的实现。

这一层很薄，主要价值是把 chatagent 侧的 McpReloadResult / McpServerSnapshot
翻译成面向管理端的 VO，避免 Controller 直接依赖 chatagent 的内部模型。
 */
@Slf4j
@Service
@AllArgsConstructor
public class McpManageServiceImpl implements McpManageService {

    private final ChatAgentProvider chatAgentProvider;

    private final McpToolAssembler mcpToolAssembler;

    @Override
    public McpReloadVo reload() {
        McpReloadResult result = chatAgentProvider.reload();
        log.info("MCP reload 完成：success={}, loaded={}, failed={}, tools={}, elapsed={}ms",
            result.success(), result.loadedServers(), result.failedServers(),
            result.toolCount(), result.elapsedMs());
        return new McpReloadVo(
            result.success(),
            result.loadedServers(),
            result.failedServers(),
            result.conflictedTools(),
            result.toolCount(),
            result.elapsedMs(),
            result.message()
        );
    }

    @Override
    public List<McpServerViewVo> listServers() {
        return mcpToolAssembler.snapshots().stream()
            .map(this::toViewVo)
            .toList();
    }

    private McpServerViewVo toViewVo(McpServerSnapshot snapshot) {
        List<String> toolNames = snapshot.toolNames() == null ? List.of() : snapshot.toolNames();
        return new McpServerViewVo(
            snapshot.serverName(),
            snapshot.endpoint(),
            snapshot.transport(),
            snapshot.connected(),
            toolNames,
            toolNames.size(),
            snapshot.failReason()
        );
    }
}
