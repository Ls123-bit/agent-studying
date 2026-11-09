package org.javaup.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/*
单个 MCP server 的接入情况。
用来回答"这个 server 到底接上没有、接进来哪些工具、为什么没接上"——在此之前这些信息只在启动日志里。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class McpServerViewVo {

    private String serverName;

    private String endpoint;

    private String transport;

    /**
     * 本次装配是否成功建立连接并至少接入一个工具。
     */
    private Boolean connected;

    private List<String> toolNames;

    private Integer toolCount;

    /**
     * 未接入时的原因；已接入时为空串。
     */
    private String failReason;
}
