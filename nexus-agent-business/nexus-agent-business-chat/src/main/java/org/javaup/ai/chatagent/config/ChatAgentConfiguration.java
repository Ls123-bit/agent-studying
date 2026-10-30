package org.javaup.ai.chatagent.config;

import javax.sql.DataSource;

import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.CreateOption;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import org.javaup.ai.chatagent.mcp.McpProperties;
import org.javaup.ai.chatagent.mcp.McpToolAssembler;
import org.javaup.ai.chatagent.skill.LoadSkillRequest;
import org.javaup.ai.chatagent.skill.LoadSkillTool;
import org.javaup.ai.chatagent.skill.SkillProperties;
import org.javaup.ai.chatagent.skill.SkillRegistry;
import org.javaup.ai.chatagent.support.DashScopeCompatibilityInterceptor;
import org.javaup.ai.chatagent.support.TavilyToolInputFallbackInterceptor;
import org.javaup.ai.chatagent.tool.TavilySearchRequest;
import org.javaup.ai.chatagent.tool.TavilySearchTool;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/*
这个配置类只负责"注册 Bean"，不负责装配 Agent：

- MysqlSaver：会话状态持久化
- tavilySearchToolCallback / loadSkillToolCallback：内置工具回调
- ChatAgentProvider：ReactAgent 的持有者，装配逻辑（工具汇总、提示词拼装、Hook 限流、拦截器链）
  全部收在 ChatAgentProvider.buildAgent() 里。

为什么要拆出 Provider：ReactAgent 不可变（工具列表和提示词构造后无法修改），
而 MCP 配置需要支持运行期 reload，因此必须能重建 Agent 实例并把新实例暴露给执行器。
 */
@Configuration
@EnableConfigurationProperties({ChatAgentProperties.class, TavilySearchProperties.class, McpProperties.class, SkillProperties.class})
public class ChatAgentConfiguration {


    /*
    MysqlSaver：会话状态持久化
    MysqlSaver 是 Spring AI Alibaba Graph 框架提供的检查点存储器。它的作用是把 Agent 每一轮推理的状态（包括消息历史、工具调用结果等）持久化到 MySQL。
        这样做的好处是：
        Agent 的多轮对话状态不会丢失
        即使服务重启，也能从上次的状态继续
        CreateOption.CREATE_IF_NOT_EXISTS 表示表不存在时自动建表
     */
    @Bean
    public MysqlSaver mysqlCheckpointSaver(DataSource dataSource) {

        return MysqlSaver.builder()
            .dataSource(dataSource)
            .createOption(CreateOption.CREATE_IF_NOT_EXISTS)
            .build();
    }

    /*
    工具注册
    这里把 TavilySearchTool 的 search 方法包装成了一个 ToolCallback，注册名为 tavily_search。
    关键点：
    description 是给大模型看的——模型根据这段描述来判断什么时候该调用这个工具
    inputType 指定了工具的入参类型，框架会自动把模型生成的 JSON 参数反序列化成 TavilySearchRequest
    方法引用 tavilySearchTool::search 就是工具的实际执行逻辑
     */
    @Bean
    public ToolCallback tavilySearchToolCallback(TavilySearchTool tavilySearchTool) {

        return FunctionToolCallback
            .builder("tavily_search", tavilySearchTool::search)
            .description("联网搜索最新信息、事实资料和网页来源。调用时必须传 JSON 参数，且至少包含非空 query；可选 topic 和 maxResults，其中 topic 仅允许 general、news、finance。")
            .inputType(TavilySearchRequest.class)
            .build();
    }

    /*
    load_skill 工具注册（Skills 两阶段按需加载的执行端）。
    系统提示词里只注入技能目录（名称 + 描述），模型判断命中某个技能时，
    调用 load_skill 取回完整指令再执行。技能未启用或目录为空时这个工具不会注册。
     */
    @Bean
    public ToolCallback loadSkillToolCallback(LoadSkillTool loadSkillTool) {

        return FunctionToolCallback
            .builder(LoadSkillTool.TOOL_NAME, loadSkillTool::loadSkill)
            .description("加载指定技能的完整执行指令。当用户请求命中系统提示词\"可用技能目录\"中的某个技能时调用；调用时必须传 JSON 参数 {\"skillName\":\"技能名\"}，技能名只能取自目录。")
            .inputType(LoadSkillRequest.class)
            .build();
    }

    /*
    MCP 配置表兜底建表专用的 JdbcTemplate。

    为什么不能直接注入 JdbcTemplate：容器里 documentManagePgVectorJdbcTemplate 也是 JdbcTemplate，
    而 Spring Boot 的 jdbcTemplate 自动配置因为已存在 JdbcOperations 而退避，
    结果“不限定就注入”会拿到 PG 的模板——MySQL 的 COMMENT 语法发到 PG 上直接报语法错误，
    建表失败后 MyBatis 又会在 MySQL 上查一个不存在的表。
    这里显式绑定主（MySQL）DataSource，让建表与 MyBatis 查询落在同一个库上。
     */
    @Bean
    public JdbcTemplate mcpBootstrapJdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /*
    ReactAgent 持有者。
    注意这里是显式构造而不是靠类型注入：容器里有两个同类型的 ToolCallback Bean
    （tavily_search 与 load_skill），自动注入会歧义，显式传参最清楚。
     */
    @Bean
    public ChatAgentProvider chatAgentProvider(ChatModel chatModel,
                                               MysqlSaver mysqlCheckpointSaver,
                                               @Qualifier("tavilySearchToolCallback") ToolCallback tavilySearchToolCallback,
                                               @Qualifier("loadSkillToolCallback") ToolCallback loadSkillToolCallback,
                                               McpToolAssembler mcpToolAssembler,
                                               SkillRegistry skillRegistry,
                                               SkillProperties skillProperties,
                                               ChatAgentProperties chatAgentProperties,
                                               DashScopeCompatibilityInterceptor dashScopeCompatibilityInterceptor,
                                               TavilyToolInputFallbackInterceptor tavilyToolInputFallbackInterceptor) {
        // 用 @Qualifier 显式绑定两个同类型 ToolCallback Bean，不依赖参数名推断。
        return new ChatAgentProvider(
            chatModel,
            mysqlCheckpointSaver,
            tavilySearchToolCallback,
            loadSkillToolCallback,
            mcpToolAssembler,
            skillRegistry,
            skillProperties,
            chatAgentProperties,
            dashScopeCompatibilityInterceptor,
            tavilyToolInputFallbackInterceptor
        );
    }
}
