请把下面的已有长期摘要和新增对话批次合并成新的长期记忆 JSON。
JSON 结构必须为：
{
  "summary": "一段 180~260 字的中文摘要",
  "conversation_goal": "一句话描述用户长期目标",
  "stable_facts": ["事实1", "事实2"],
  "user_preferences": ["偏好1", "偏好2"],
  "resolved_points": ["已解决点1", "已解决点2"],
  "pending_questions": ["待跟进点1", "待跟进点2"],
  "retrieval_hints": ["系统名或关键词1", "系统名或关键词2"]
}

输出要求：
1. 只返回 JSON，不要输出解释。
2. 不要把寒暄、重复确认和无关聊天写进去。
3. 未确认的信息不要写成既定事实。
4. 每个数组最多保留 6 条，尽量去重。
5. summary 只保留下一轮理解问题真正需要的长期背景。

已有长期摘要 JSON：
<existingSummaryJson>

新增对话批次：
<newConversationBatch>



{#摘要合并用到了两个提示词模板，分别对应系统提示词和用户提示词。 这个是用户提示词模板
模板中有两个占位符，由 buildSummaryMergePrompt 方法填充：
<existingSummaryJson>：旧摘要的 JSON，由 writePayloadJson(normalizePayload(copyPayload(existingPayload))) 生成
<newConversationBatch>：新增批次的对话原文，由 renderCompressionTranscript(batch) 渲染#}