package org.javaup.ai.manage.service;

import org.javaup.ai.manage.vo.McpReloadVo;
import org.javaup.ai.manage.vo.McpServerViewVo;

import java.util.List;

/*
MCP 接入的运维接口。

只做两件事：触发一次重新装配、查看当前装配结果。
不提供配置的增删改——写接口会带出"DB 与内存不一致"的一致性问题，
而配置本身用 SQL 维护的成本已经很低。
 */
public interface McpManageService {

    /**
     * 按数据库里的最新配置重新装配 MCP 工具并重建 Agent。
     * 任何一个 server 装配失败都会整体回滚，旧工具集保持不变。
     */
    McpReloadVo reload();

    /**
     * 查询当前生效的 MCP server 接入情况。
     */
    List<McpServerViewVo> listServers();
}
