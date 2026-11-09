package org.javaup.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/*
MCP 配置重载结果。
success=false 时 toolCount 表示"旧工具集仍然生效，里面有多少个工具"，方便运维确认回滚后功能没退化。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class McpReloadVo {

    private Boolean success;

    private List<String> loadedServers;

    private List<String> failedServers;

    private List<String> conflictedTools;

    private Integer toolCount;

    private Long elapsedMs;

    private String message;
}
