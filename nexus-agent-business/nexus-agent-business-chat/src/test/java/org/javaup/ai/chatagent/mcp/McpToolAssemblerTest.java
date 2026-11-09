package org.javaup.ai.chatagent.mcp;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.javaup.ai.chatagent.data.McpServerConfig;
import org.javaup.ai.chatagent.mapper.McpServerConfigMapper;
import org.javaup.ai.chatagent.support.StreamEventWriter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/*
McpToolAssembler 的单元测试。

覆盖三类约定：
1. 降级：DB 挂掉、server 连不上、transport 不支持，都不能抛异常拖垮启动；
2. 治理：白名单过滤、工具名重名先到先得（用 acceptCallbacks 纯逻辑验证，不碰网络）；
3. 生命周期：commit 原子替换、discard 不影响已生效结果。

不依赖真实数据库和 MCP server：mapper / jdbcTemplate / StreamEventWriter 全部 mock。
 */
class McpToolAssemblerTest {

    private McpToolAssembler newAssembler(McpServerConfigMapper mapper) {
        // ensureTable() 走 jdbcTemplate.execute(String)，mock 后默认什么都不做，等价于建表成功。
        return new McpToolAssembler(mapper, new McpProperties(), new ObjectMapper(),
            mock(JdbcTemplate.class), mock(StreamEventWriter.class));
    }

    private McpServerConfig serverRow(String name, String endpoint, String transport) {
        McpServerConfig row = new McpServerConfig();
        row.setServerName(name);
        row.setEndpoint(endpoint);
        row.setTransport(transport);
        row.setTimeoutMs(2000L);
        return row;
    }

    private static ToolCallback toolCallback(String name) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                    .name(name)
                    .description("test tool " + name)
                    .inputSchema("{\"type\":\"object\"}")
                    .build();
            }

            @Override
            public String call(String toolInput) {
                return "ok";
            }
        };
    }

    @Test
    void disabledFlagSkipsAssemblyWithoutTouchingDatabase() {
        McpServerConfigMapper mapper = mock(McpServerConfigMapper.class);
        McpProperties properties = new McpProperties();
        properties.setEnabled(false);
        McpToolAssembler assembler = new McpToolAssembler(mapper, properties, new ObjectMapper(),
            mock(JdbcTemplate.class), mock(StreamEventWriter.class));

        McpToolAssembler.McpToolAssembly assembly = assembler.assemble(Set.of("tavily_search"));

        assertTrue(assembly.isEmpty());
        verifyNoInteractions(mapper);
    }

    @Test
    void databaseFailureDegradesToEmptyAssembly() {
        McpServerConfigMapper mapper = mock(McpServerConfigMapper.class);
        when(mapper.selectList(any(Wrapper.class))).thenThrow(new RuntimeException("db down"));

        McpToolAssembler.McpToolAssembly assembly = newAssembler(mapper).assemble(Set.of());

        assertTrue(assembly.isEmpty());
        assertTrue(assembly.toolCallbacks().isEmpty());
    }

    @Test
    void unreachableServerIsSkippedNotFatal() {
        McpServerConfigMapper mapper = mock(McpServerConfigMapper.class);
        when(mapper.selectList(any(Wrapper.class)))
            .thenReturn(List.of(serverRow("ghost", "http://127.0.0.1:1/mcp", "STREAMABLE")));

        McpToolAssembler.McpToolAssembly assembly = newAssembler(mapper).assemble(Set.of("tavily_search"));

        assertTrue(assembly.isEmpty());
        assertEquals(List.of("ghost"), assembly.failedServers());
        assertTrue(assembly.loadedServers().isEmpty());
        // 连不上的 server 也要留下快照，否则管理端看不到"配置了但没接上"。
        assertEquals(1, assembly.snapshots().size());
        assertFalse(assembly.snapshots().get(0).connected());
        assertFalse(assembly.snapshots().get(0).failReason().isBlank());
    }

    @Test
    void unsupportedTransportIsRejectedAsFailure() {
        McpServerConfigMapper mapper = mock(McpServerConfigMapper.class);
        when(mapper.selectList(any(Wrapper.class)))
            .thenReturn(List.of(serverRow("stdio-server", "http://127.0.0.1:1/mcp", "STDIO")));

        McpToolAssembler.McpToolAssembly assembly = newAssembler(mapper).assemble(Set.of("tavily_search"));

        assertTrue(assembly.isEmpty());
        assertEquals(List.of("stdio-server"), assembly.failedServers());
    }

    @Test
    void whitelistFiltersOutToolsNotListed() {
        McpServerConfig row = serverRow("office", "http://localhost:7090/mcp", "STREAMABLE");
        row.setToolWhitelist("[\"check_attendance\"]");

        List<ToolCallback> accepted = new ArrayList<>();
        List<String> conflicted = new ArrayList<>();
        List<String> acceptedNames = newAssembler(mock(McpServerConfigMapper.class)).acceptCallbacks(
            row, List.of(toolCallback("check_attendance"), toolCallback("book_meeting_room")),
            new HashSet<>(), accepted, conflicted);

        assertEquals(List.of("check_attendance"), acceptedNames);
        assertEquals(1, accepted.size());
        assertTrue(conflicted.isEmpty());
    }

    @Test
    void emptyWhitelistExposesEveryTool() {
        McpServerConfig row = serverRow("office", "http://localhost:7090/mcp", "STREAMABLE");
        row.setToolWhitelist("[]");

        List<ToolCallback> accepted = new ArrayList<>();
        List<String> acceptedNames = newAssembler(mock(McpServerConfigMapper.class)).acceptCallbacks(
            row, List.of(toolCallback("a"), toolCallback("b")),
            new HashSet<>(), accepted, new ArrayList<>());

        assertEquals(List.of("a", "b"), acceptedNames);
    }

    @Test
    void invalidWhitelistFallsBackToExposeAll() {
        McpServerConfig row = serverRow("office", "http://localhost:7090/mcp", "STREAMABLE");
        row.setToolWhitelist("not-a-json-array");

        List<ToolCallback> accepted = new ArrayList<>();
        List<String> acceptedNames = newAssembler(mock(McpServerConfigMapper.class)).acceptCallbacks(
            row, List.of(toolCallback("a"), toolCallback("b")),
            new HashSet<>(), accepted, new ArrayList<>());

        assertEquals(List.of("a", "b"), acceptedNames);
    }

    @Test
    void duplicateToolNameIsDroppedFirstComeFirstServed() {
        McpServerConfig row = serverRow("office", "http://localhost:7090/mcp", "STREAMABLE");

        // 内置工具名先占坑，MCP 暴露同名工具时必须被剔除，否则按名字建限额的 Hook 会串。
        Set<String> occupied = new HashSet<>(Set.of("tavily_search"));
        List<ToolCallback> accepted = new ArrayList<>();
        List<String> conflicted = new ArrayList<>();
        List<String> acceptedNames = newAssembler(mock(McpServerConfigMapper.class)).acceptCallbacks(
            row, List.of(toolCallback("tavily_search"), toolCallback("check_attendance")),
            occupied, accepted, conflicted);

        assertEquals(List.of("check_attendance"), acceptedNames);
        assertEquals(List.of("tavily_search"), conflicted);
    }

    @Test
    void acceptedCallbacksAreWrappedWithTraceDecorator() {
        McpServerConfig row = serverRow("office", "http://localhost:7090/mcp", "STREAMABLE");

        List<ToolCallback> accepted = new ArrayList<>();
        newAssembler(mock(McpServerConfigMapper.class)).acceptCallbacks(
            row, List.of(toolCallback("check_attendance")),
            new HashSet<>(), accepted, new ArrayList<>());

        // 不包装就会在工具轨迹里彻底消失，这里把这条约定固定下来。
        assertInstanceOf(TracedToolCallback.class, accepted.get(0));
        assertEquals("check_attendance", accepted.get(0).getToolDefinition().name());
    }

    @Test
    void commitReplacesCurrentAndDiscardKeepsItUntouched() {
        McpToolAssembler assembler = newAssembler(mock(McpServerConfigMapper.class));
        assertTrue(assembler.current().isEmpty());

        McpServerSnapshot snapshot = new McpServerSnapshot(
            "office", "http://localhost:7090/mcp", "STREAMABLE", List.of("check_attendance"), true, "");
        McpToolAssembler.McpToolAssembly committed = new McpToolAssembler.McpToolAssembly(
            List.of(toolCallback("check_attendance")), List.of("office"), List.of(), List.of(),
            List.of(), List.of(snapshot));

        assembler.commit(committed);
        assertSame(committed, assembler.current());
        assertEquals(1, assembler.snapshots().size());

        // reload 失败走 discard：候选不落地，已生效的工具集必须原封不动。
        McpToolAssembler.McpToolAssembly candidate = McpToolAssembler.McpToolAssembly.empty();
        assembler.discard(candidate);
        assertSame(committed, assembler.current());
        assertEquals(List.of("office"), assembler.current().loadedServers());
    }
}
