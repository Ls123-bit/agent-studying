# super-business-chat-agent

这是一个只聚焦后端的业务对话模块，目标是把“可流式输出、可联网搜索、可停止、可查看会话状态、可生成推荐问题”的完整智能对话能力落到当前项目里，并且把通用 ReAct 能力尽量交给 Spring AI Alibaba 1.1.2.0。

## 功能边界

Spring AI Alibaba `ReactAgent` 负责的部分：

- ReAct 多轮推理与工具调用闭环
- 对话线程记忆与 checkpoint 保存
- 流式模型输出
- 最大模型调用次数限制
- 最大工具调用次数限制
- 工具失败重试与工具异常兜底
- 会话中断能力

这个模块自己补的业务外壳：

- 面向前端的 SSE 输出协议
- Tavily 联网搜索工具封装
- 搜索引用来源聚合与去重
- 推荐追问问题生成
- 会话列表、会话详情、会话重置
- 运行中任务管理与主动停止
- MySQL 会话持久化与响应耗时统计
- 统一接口异常返回

## 接口清单

- `POST /api/chat/stream`
  - 流式对话
  - 请求体：`{"question":"...","conversationId":"可选"}`
  - 返回：`text/event-stream`

- `POST /api/chat`
  - 非流式对话
  - 请求体：`{"question":"...","conversationId":"可选"}`
  - 返回：单轮对话结果

- `POST /api/chat/stop/{conversationId}`
  - 主动停止当前会话生成

- `GET /api/chat/sessions`
  - 查看所有会话

- `GET /api/chat/sessions/{conversationId}`
  - 查看单个会话详情

- `DELETE /api/chat/sessions/{conversationId}`
  - 重置会话并清理 MySQL checkpoint

## SSE 事件协议

流式接口统一输出 JSON 字符串，字段结构如下：

```json
{
  "type": "text",
  "content": "分片内容",
  "timestamp": "2026-03-24T08:00:00Z"
}
```

当前支持的 `type`：

- `text`：模型回答正文分片
- `thinking`：工具阶段的提示信息
- `reference`：最终引用来源列表
- `recommend`：最终推荐追问问题
- `status`：停止等状态提示
- `error`：异常信息

## 关键实现

### 1. ReactAgent 装配

见 `ChatAgentConfiguration`：

- 以 `ChatModel` 作为底层模型
- 注入 `tavily_search` 工具
- 使用 `MysqlSaver` 保存线程记忆
- 使用 `ModelCallLimitHook` 限制单次运行和单线程模型调用次数
- 使用 `ToolCallLimitHook` 限制 Tavily 工具调用次数
- 使用 `ToolRetryInterceptor` 做失败重试
- 使用 `ToolErrorInterceptor` 做统一异常兜底

### 2. 流式会话主流程

见 `BusinessChatService#streamChat`：

- 创建会话和当前轮次
- 创建 `Sinks.Many<String>` 作为 SSE 事件通道
- 构造 `RunnableConfig`，把引用容器、思考步骤、已用工具、事件 sink 放进上下文
- 调用 `businessChatReactAgent.stream(question, runnableConfig)`
- 监听 `StreamingOutput`
- 把模型正文转成 `text` 事件
- 在结束时补发 `reference` 和 `recommend`
- 把最终结果保存到 MySQL 会话仓库

### 3. Tavily 搜索工具

见 `TavilySearchTool`：

- 接收 `query/topic/maxResults`
- 直接调用 Tavily `/search`
- 提取 `title/url/content`
- 把来源写回 `RunnableConfig.context()`
- 同步发出 `thinking` 事件提示当前搜索进度

### 4. 推荐问题生成

见 `RecommendationService`：

- 读取最近几轮对话
- 拼接推荐 prompt
- 额外调用一次模型生成追问建议
- 只保留最多 3 条去重后的中文问题

### 5. 主动停止

见 `ChatTaskManager` + `BusinessChatService#stopConversation`：

- 按 `conversationId` 管理当前运行任务
- 调用 `ReactAgent.interrupt(runnableConfig)` 中断运行
- 释放订阅
- 写入 `status` 事件
- 把当前轮次标记为 `STOPPED`

## 运行配置

`application.yaml` 里已经准备好了默认配置，真正运行前至少需要这两个模型/搜索环境变量：

- `SILICONFLOW_API_KEY`
- `TAVILY_API_KEY`

数据库相关配置默认使用 MySQL：

- 数据库名：`ai-business-chat`
- 用户名：`${MYSQL_USERNAME:root}`
- 密码：`${MYSQL_PASSWORD:}`

如果你不想用默认用户名，启动前设置：

- `MYSQL_USERNAME`
- `MYSQL_PASSWORD`

可按需调整的业务配置：

- `app.chat.system-prompt`
- `app.chat.recommendation-enabled`
- `app.chat.max-model-calls-per-run`
- `app.chat.max-tool-calls-per-run`
- `app.chat.history-preview-turns`
- `app.tavily.enabled`
- `app.tavily.max-results`
- `app.tavily.search-depth`

数据库初始化脚本位于：

- `src/main/resources/sql/ai-business-chat.sql`

这个脚本会创建两类表：

- 业务会话表：`chat_session`、`chat_turn`
- ReactAgent checkpoint 表：`GRAPH_THREAD`、`GRAPH_CHECKPOINT`

## 当前状态

当前模块已经完成：

- 代码编译通过
- 错误返回和日志绑定冲突已处理
- 会话和 checkpoint 已切换为 MySQL 持久化
- 代码注释和数据库初始化脚本已补齐

当前验证情况：

- 编译验证已通过
- 启动验证依赖正确的 `MYSQL_USERNAME` / `MYSQL_PASSWORD`
- 当前机器上的 MySQL 可达，但默认 `root` 账号认证失败，因此未完成数据库启动烟测

当前还没有做的内容：

- 前端页面与交互展示
- 更细的鉴权、限流、审计
- 联网搜索和模型调用的集成测试
