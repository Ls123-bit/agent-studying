package org.javaup.ai.chatagent.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.javaup.database.data.BaseTableData;

/*
MCP server 接入配置表（nexus_agent_mcp_server）。
business 主链路启动时读取 status=1 的行，动态建立 MCP 客户端连接，
并把每个 server 暴露的工具装配进 ReactAgent。

字段约定：
- transport：v1 仅支持 STREAMABLE（streamable-http），其他值会在装配时被跳过。
- toolWhitelist：JSON 数组，控制该 server 允许暴露给模型的工具白名单；NULL 或空数组表示全部暴露。
- authHeaders：JSON 对象，鉴权请求头，v1 预留字段，暂未参与建连。
- timeoutMs：该 server 单独的调用超时；NULL 时回退到 app.mcp.request-timeout-ms。
- status：继承 BaseTableData 的通用状态位，这里当作启用开关（1 启用 0 停用）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("nexus_agent_mcp_server")
@EqualsAndHashCode(callSuper = true)
public class McpServerConfig extends BaseTableData {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    @TableField("server_name")
    private String serverName;

    @TableField("endpoint")
    private String endpoint;

    @TableField("transport")
    private String transport;

    @TableField("tool_whitelist")
    private String toolWhitelist;

    @TableField("auth_headers")
    private String authHeaders;

    @TableField("timeout_ms")
    private Long timeoutMs;

    @TableField("remark")
    private String remark;
}
