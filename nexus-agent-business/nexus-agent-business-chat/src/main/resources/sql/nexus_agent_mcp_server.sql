-- =====================================================================
-- MCP server 配置表
-- 作用：business 主链路启动时读取 status=1 的行，动态建立 MCP 客户端连接，
--       并把 server 暴露的工具装配进 ReactAgent。
-- 说明：应用启动时也会按下面的 DDL 自动建表（CREATE TABLE IF NOT EXISTS），
--       本文件用于人工评审和手工初始化；数据行通过 SQL 手工维护（不提供增删改接口）。
-- 生效方式：新增/修改配置后调用 POST /manage/mcp/reload 重新装配，无需重启。
--           reload 是原子替换：任何一个 server 装配失败都会整体回滚，旧工具集保持不变。
--           查看当前接入情况：GET /manage/mcp/servers。
-- =====================================================================
CREATE TABLE IF NOT EXISTS nexus_agent_mcp_server
(
    id             BIGINT       NOT NULL COMMENT '主键（uid，手工插入时自行编号即可）',
    server_name    VARCHAR(64)  NOT NULL COMMENT 'MCP server 唯一标识，用于日志与追踪',
    endpoint       VARCHAR(512) NOT NULL COMMENT 'MCP 服务地址，例如 http://localhost:7090/mcp',
    transport      VARCHAR(32)  NOT NULL DEFAULT 'STREAMABLE' COMMENT '传输类型：v1 仅支持 STREAMABLE',
    tool_whitelist VARCHAR(2048) NULL COMMENT '允许暴露的工具名 JSON 数组，例如 ["check_attendance"]；NULL 或 [] 表示全部暴露',
    auth_headers   VARCHAR(2048) NULL COMMENT '鉴权请求头 JSON 对象，例如 {"Authorization":"Bearer xxx"}；整条连接共享',
    timeout_ms     BIGINT       NULL COMMENT '该 server 的调用超时（毫秒），NULL 时使用 app.mcp.request-timeout-ms',
    remark         VARCHAR(512) NULL COMMENT '备注',
    status         INT          NOT NULL DEFAULT 1 COMMENT '是否启用：1 启用 0 停用',
    create_time    DATETIME     NULL COMMENT '创建时间',
    edit_time      DATETIME     NULL COMMENT '编辑时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_mcp_server_name (server_name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='MCP server 接入配置';

-- ---------------------------------------------------------------------
-- 示例：接入 ai-example 里的 office-tools 演示 server（需先启动该应用，端口 7090）
-- ---------------------------------------------------------------------
INSERT INTO nexus_agent_mcp_server (id, server_name, endpoint, transport, tool_whitelist, auth_headers, timeout_ms, remark, status, create_time, edit_time)
VALUES (1001, 'office-tools', 'http://localhost:7090/mcp', 'STREAMABLE', '[]', NULL, NULL, '本地 office-tools 演示 server（考勤/会议室）', 1, NOW(), NOW());
