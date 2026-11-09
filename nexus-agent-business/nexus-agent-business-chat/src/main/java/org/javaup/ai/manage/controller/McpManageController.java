package org.javaup.ai.manage.controller;

import io.swagger.v3.oas.annotations.Operation;
import org.javaup.ai.manage.service.McpManageService;
import org.javaup.ai.manage.vo.McpReloadVo;
import org.javaup.ai.manage.vo.McpServerViewVo;
import org.javaup.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/*
MCP 接入的运维入口。

两个接口都在 /manage/** 下，受 AdminAuthInterceptor 保护；
reload 是写操作，因此同时在 PreviewModeInterceptor 的只读黑名单里，线上展示模式无法触发。
 */
@RestController
@RequestMapping("/manage/mcp")
public class McpManageController {

    private final McpManageService mcpManageService;

    public McpManageController(McpManageService mcpManageService) {
        this.mcpManageService = mcpManageService;
    }

    @Operation(summary = "按数据库最新配置重新装配 MCP 工具")
    @PostMapping("/reload")
    public ApiResponse<McpReloadVo> reload() {
        return ApiResponse.ok(mcpManageService.reload());
    }

    @Operation(summary = "查询当前生效的 MCP server 接入情况")
    @GetMapping("/servers")
    public ApiResponse<List<McpServerViewVo>> servers() {
        return ApiResponse.ok(mcpManageService.listServers());
    }
}
