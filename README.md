# Nexus Agent · MCP 接入与 Skills 管理（二次开发）

> **上游归属**：本项目基于 [java-up-up/super-agent](https://github.com/java-up-up/super-agent)（Nexus Agent，Apache-2.0）二次开发。
> 上游提供了完整的 RAG 流水线、文档中台、ReAct Agent 骨架与前端控制台，版权与原始设计归上游作者所有。
> **本仓库的主要贡献是在上游业务主链路中落地了 MCP 工具协议接入与 SKILL.md 技能管理体系**，并补齐了对应的单元测试、热重载、调用追踪与配置治理。

<p align="center">
  <img alt="Java" src="https://img.shields.io/badge/Java-17-df5b46?style=flat-square&logo=openjdk" />
  <img alt="Spring Boot" src="https://img.shields.io/badge/Spring%20Boot-3.5.6-6db33f?style=flat-square&logo=springboot&logoColor=white" />
  <img alt="Spring AI" src="https://img.shields.io/badge/Spring%20AI-1.1.0-2d6a8a?style=flat-square" />
  <img alt="MCP" src="https://img.shields.io/badge/MCP-Streamable%20HTTP-4f6bed?style=flat-square" />
  <img alt="Skills" src="https://img.shields.io/badge/Skills-SKILL.md-b56e7a?style=flat-square" />
  <img alt="License" src="https://img.shields.io/badge/license-Apache--2.0-4a9b8f?style=flat-square" />
</p>

## 项目定位

Nexus Agent 是一个企业知识问答 + 开放式 Agent 的混合平台：**RAG 流水线做主干**（文档入库 → 知识路由 → 混合检索 → 证据驱动生成），**只有开放式问答（OPEN_CHAT）走 ReAct Agent**。

本仓库的贡献集中在 Agent 的工具面：让 OPEN_CHAT 模式下的 ReactAgent 不再只有硬编码的联网搜索，而是可以**动态接入外部 MCP 工具**、**按需加载 SKILL.md 技能**。

```
用户提问 → 前置编排（记忆/改写/路由）
             ├─ 知识问答 → RAG 执行器（向量 + 关键词双通道，证据驱动）
             └─ 开放式问答 → ReactAgentExecutor
                              └─ ReactAgent 工具面
                                   ├─ tavily_search（内置联网搜索）
                                   ├─ load_skill（技能按需加载）★ 本仓库贡献
                                   └─ MCP 外部工具（动态装配）★ 本仓库贡献
```

## 贡献一：MCP 工具协议接入

代码位于 `nexus-agent-business-chat` 的 `chatagent/mcp` 包与 `manage` 模块。

- **数据库驱动的 server 配置**：`nexus_agent_mcp_server` 表维护 server 地址、传输类型、工具白名单、鉴权请求头、独立超时与启停状态；启动时自动建表（`CREATE TABLE IF NOT EXISTS`）。
- **动态工具装配**：`McpToolAssembler` 读取启用配置，基于 Spring AI MCP Client（`HttpClientStreamableHttpTransport`，v1 仅实现 STREAMABLE 传输）建连、发现工具，与内置工具合并后注入 `ReactAgent.builder().tools(...)`；内置工具名做保留字冲突防护。
- **原子热重载**：`POST /manage/mcp/reload` 触发 prepare → rebuild → commit 三段式替换。prepare 阶段任一 server 装配失败则整体不落地；commit 用原子引用 swap 换入新工具集，旧连接按宽限期（默认 30s）延迟关闭，避免打断并行执行中的工具调用；异常路径回滚，旧工具集保持不变。
- **坏配置不拖垮启动**：启动装配采用"能连几个算几个"策略，单个 server 失败只记录并跳过。
- **调用追踪**：`TracedToolCallback` 装饰每个 MCP 工具，把入参（截断防撑爆）、出参、耗时写入全链路 Trace 的工具调用记录，与内置工具共用同一套观测面板。
- **管理端点**：`GET /manage/mcp/servers` 查看当前接入快照；配置行通过 SQL 维护（v1 不提供增删改 UI）。
- **测试**：`McpToolAssemblerTest`（10 例）+ `TracedToolCallbackTest`（4 例）覆盖装配、白名单、失败跳过与追踪装饰。

## 贡献二：SKILL.md 技能管理

代码位于 `chatagent/skill` 包，技能目录在仓库根 `skills/`。

- **声明式技能定义**：每个技能一个子目录，`SKILL.md` 的 frontmatter 声明 `name` / `description`，正文是完整执行指令；目录内其他 `.md` 作为参考资料。
- **启动扫描 + 容错**：`SkillRegistry` 在 `@PostConstruct` 扫描技能根目录，解析后缓存为只读注册表；目录缺失、单个技能解析失败只告警跳过，不影响启动。
- **两级加载控制 prompt 成本**：系统提示词只注入技能目录（名称 + 描述），模型判断命中后调用 `load_skill` 工具取回完整指令与参考资料，单次返回受 `max-load-chars`（默认 8000 字符）预算约束——技能数量增长时 prompt 成本增长最平缓。
- **条件注册**：技能功能关闭或目录为空时不注册 `load_skill`，避免给模型一个永远调不通的工具。
- **示例技能**：`skills/weekly-report`（周报生成，含 `template.md` 参考模板）。
- **测试**：`SkillRegistryTest`（5 例）覆盖扫描、frontmatter 解析与容错路径。

## 与上游的差异说明

| 项 | 上游 README 宣称 | 本仓库实际实现 |
| :--- | :--- | :--- |
| MCP 传输 | 未限定 | v1 仅 STREAMABLE（SSE/STDIO 仅表内预留） |
| MCP 配置管理 | — | SQL 维护 + reload/servers 端点，无 CRUD UI |
| Skills 热插拔 | 宣称支持 | 启动扫描，修改技能文件需重启 |
| Skills 脚本引用 | 宣称支持脚本 | 当前仅加载 `.md` 参考资料 |

## 快速启动（后端）

1. 准备中间件：MySQL、Redis、Kafka、MinIO、PostgreSQL(PGVector)、Elasticsearch、Neo4j（均可选，按 `application.yaml` 开关）。
2. 所有密钥通过环境变量注入，仓库不含任何真实凭据：

   ```bash
   export OPENAI_API_KEY=<你的模型密钥>          # 阿里云百炼兼容协议
   export MYSQL_PASSWORD=... REDIS_PASSWORD=...
   export MINIO_SECRET_KEY=... PGVECTOR_PASSWORD=...
   export ELASTICSEARCH_PASSWORD=... NEO4J_PASSWORD=...
   export TAVILY_API_KEY=<你的Tavily密钥>        # 可选，联网搜索
   export NEXUS_AGENT_ADMIN_PASSWORD=... NEXUS_AGENT_ADMIN_TOKEN_SECRET=...
   ```

3. 构建并启动：

   ```bash
   mvn compile -DskipTests
   # 启动 nexus-agent-business-chat 的 NexusBusinessChatAgentApplication（端口 9082）
   ```

4. 接入 MCP server：向 `nexus_agent_mcp_server` 插入配置行（见 `src/main/resources/sql/nexus_agent_mcp_server.sql` 内的示例），然后 `POST /manage/mcp/reload`。仓库内 `ai-example/ai-example-mcp` 提供了一个可直接联调的 office-tools 演示 server（端口 7090）。
5. 新增技能：在 `skills/` 下新建子目录并写入 `SKILL.md`，重启应用生效。

## 仓库结构

```
nexus-agent-business/nexus-agent-business-chat   业务主模块（对话 + 文档中台 + MCP/Skills）
nexus-agent-common                               公共框架（web/frame）
nexus-agent-id-generator-framework               分布式 ID（百度 uid + Snowflake）
nexus-agent-redisson-framework                   Redisson 公共配置 + 会话租约
ai-example                                       上游示例模块（含 MCP 演示 server/client）
skills/                                          SKILL.md 技能目录
vue/                                             前端（Vue 3，上游提供）
sql/                                             MySQL / PGVector 建表脚本
```

## 致谢与许可

- 上游项目：[java-up-up/super-agent](https://github.com/java-up-up/super-agent)，Apache-2.0。本仓库同样以 Apache-2.0 分发，保留上游署名。
- 本仓库在上游基础上的增量：MCP 接入（动态装配 / 原子热重载 / 调用追踪）、Skills 管理（声明式定义 / 两级加载）、对应单元测试，以及仓库瘦身（移除未接入主流程的框架模块与死代码）与凭据治理（全部密钥改为环境变量注入）。