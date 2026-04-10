import { APIError } from '../../api/api'

const STATUS_LABELS = {
  RUNNING: '进行中',
  COMPLETED: '已完成',
  FAILED: '失败',
  STOPPED: '已停止'
}

const STATUS_TONES = {
  RUNNING: 'running',
  COMPLETED: 'completed',
  FAILED: 'failed',
  STOPPED: 'stopped'
}

function asList(value) {
  return Array.isArray(value) ? value.filter(Boolean) : []
}

export function normalizeError(error, fallbackMessage) {
  if (error instanceof APIError && error.message) {
    return error.message
  }
  if (error instanceof Error && error.message) {
    return error.message
  }
  return fallbackMessage
}

export function truncate(value, maxLength) {
  if (!value) {
    return ''
  }
  return value.length > maxLength ? `${value.slice(0, maxLength)}...` : value
}

export function formatTime(value) {
  if (!value) {
    return '刚刚'
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return '刚刚'
  }
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  }).format(date)
}

export function formatDateTime(value) {
  if (!value) {
    return '无'
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return '无'
  }
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit'
  }).format(date)
}

export function formatChatMode(value) {
  if (value === 'DOCUMENT') {
    return '当前文档问答'
  }
  if (value === 'OPEN_CHAT') {
    return '开放式提问'
  }
  return value || '未知模式'
}

export function formatStatusLabel(value) {
  return STATUS_LABELS[value] || value || '未知状态'
}

export function statusTone(value) {
  return STATUS_TONES[value] || 'idle'
}

function latestExchangeQuestion(session) {
  const exchanges = asList(session?.exchanges)
  for (let index = exchanges.length - 1; index >= 0; index -= 1) {
    if (exchanges[index]?.question) {
      return exchanges[index].question
    }
  }
  return ''
}

function latestExchangeAnswer(session) {
  const exchanges = asList(session?.exchanges)
  for (let index = exchanges.length - 1; index >= 0; index -= 1) {
    if (exchanges[index]?.answer) {
      return exchanges[index].answer
    }
  }
  return ''
}

export function sessionTitle(session) {
  const latestUserMessage = session?.latestUserMessage || latestExchangeQuestion(session)
  const latestAssistantMessage = session?.latestAssistantMessage || latestExchangeAnswer(session)
  return truncate(latestUserMessage || latestAssistantMessage || '未命名会话', 28)
}

export function sessionPreview(session) {
  const latestAssistantMessage = session?.latestAssistantMessage || latestExchangeAnswer(session)
  const latestUserMessage = session?.latestUserMessage || latestExchangeQuestion(session)
  return truncate(latestAssistantMessage || latestUserMessage || '暂无内容', 72)
}

export function sessionMessageCount(session) {
  if (session?.messageCount) {
    return session.messageCount
  }
  return asList(session?.exchanges).reduce((count, exchange) => {
    let total = count
    if (exchange?.question) {
      total += 1
    }
    if (exchange?.answer) {
      total += 1
    }
    return total
  }, 0)
}

export function listAssistantExchanges(session) {
  return asList(session?.exchanges).filter((item) => item && item.status)
}

export function resolvePreferredExchange(exchanges, preferredId) {
  if (!exchanges.length) {
    return ''
  }
  const normalizedPreferredId = preferredId ? String(preferredId) : ''
  if (normalizedPreferredId) {
    const matched = exchanges.find((item) => String(item.exchangeId) === normalizedPreferredId)
    if (matched) {
      return String(matched.exchangeId)
    }
  }
  return String(exchanges[exchanges.length - 1].exchangeId)
}

function pushTextBlock(target, label, value, options = {}) {
  if (!value) {
    return
  }
  target.push({
    label,
    value,
    code: Boolean(options.code)
  })
}

function pushListBlock(target, label, items, options = {}) {
  const values = asList(items)
  if (!values.length) {
    return
  }
  target.push({
    label,
    items: values,
    ordered: Boolean(options.ordered)
  })
}

function formatLatency(value) {
  if (value == null || value <= 0) {
    return '无'
  }
  return `${value} ms`
}

function formatConfidence(value) {
  if (value == null || Number.isNaN(Number(value))) {
    return ''
  }
  return `${Math.round(Number(value) * 100)}%`
}

function buildChips(...entries) {
  return entries
    .flat()
    .filter((item) => item && item.value)
    .map((item) => ({
      label: item.label,
      value: item.value,
      tone: item.tone || 'neutral'
    }))
}

function buildMetrics(...entries) {
  return entries
    .flat()
    .filter((item) => item && item.value && item.value !== '无')
    .map((item) => ({
      label: item.label,
      value: item.value,
      mono: Boolean(item.mono)
    }))
}

export function buildExchangeStages(session, exchange) {
  if (!exchange) {
    return []
  }

  const trace = exchange.debugTrace || {}
  const intent = trace.intentResolution || null
  const references = asList(exchange.references)
  const toolTraces = asList(trace.toolTraces)
  const recommendations = asList(exchange.recommendations)
  const thinkingSteps = asList(exchange.thinkingSteps)

  const requestBlocks = []
  pushTextBlock(requestBlocks, '用户原始问题', trace.originalQuestion || exchange.question)
  pushTextBlock(requestBlocks, 'Agent 增强问题', trace.agentQuestion)
  pushTextBlock(requestBlocks, '当前日期锚点', trace.currentDateText)

  const requestLists = []
  const requestChips = buildChips(
    { label: '会话模式', value: formatChatMode(trace.chatMode || session?.chatMode), tone: 'primary' },
    { label: '执行模式', value: trace.executionMode, tone: 'success' },
    { label: '文档范围', value: session?.selectedDocumentName || (trace.selectedDocumentId ? `文档 ${trace.selectedDocumentId}` : ''), tone: 'neutral' },
    { label: '当前日期解释', value: trace.requiresCurrentDateAnchoring ? '需要' : '', tone: 'warning' },
    { label: '优先联网', value: trace.requiresFreshSearch ? '需要' : '', tone: 'warning' }
  )

  const planningBlocks = []
  pushTextBlock(planningBlocks, 'Rewrite 独立问题', trace.rewriteQuestion)
  pushTextBlock(planningBlocks, '检索锚点主问题', trace.retrievalAnchorResolvedQuestion)
  pushTextBlock(planningBlocks, '最终检索问题', trace.retrievalQuestion)
  pushTextBlock(planningBlocks, '长期摘要', trace.longTermSummary, { code: true })
  pushTextBlock(planningBlocks, '回答承接上下文', trace.answerHistoryContext, { code: true })
  pushTextBlock(planningBlocks, '规划历史摘要', trace.historySummary, { code: true })

  const planningLists = []
  pushListBlock(planningLists, 'Rewrite 子问题拆分', trace.rewriteSubQuestions, { ordered: true })
  pushListBlock(planningLists, '最终检索子问题', trace.retrievalSubQuestions, { ordered: true })

  const planningChips = buildChips(
    { label: '关系判定', value: intent?.relationType, tone: 'primary' },
    { label: '主题', value: intent?.resolvedTopic, tone: 'neutral' },
    { label: '面向', value: intent?.resolvedFacet, tone: 'neutral' },
    { label: '答案形态', value: intent?.answerShape, tone: 'neutral' },
    { label: '检索模式', value: intent?.retrievalMode, tone: 'success' },
    { label: '锚点应用', value: trace.retrievalAnchorApplied ? '已命中' : '', tone: 'success' },
    { label: '意图置信度', value: formatConfidence(intent?.confidence), tone: 'warning' },
    { label: '追问识别', value: trace.answerHistoryFollowUpQuestion ? '承接式追问' : '', tone: 'warning' },
    { label: '根章节', value: trace.retrievalAnchorRootSectionCode, tone: 'neutral' },
    { label: '目标章节提示', value: trace.retrievalAnchorTargetSectionHint, tone: 'neutral' },
    { label: '兜底文案', value: trace.noEvidenceReply ? '已配置' : '', tone: 'neutral' }
  )

  if (intent?.informationNeed) {
    pushTextBlock(planningBlocks, '信息需求', intent.informationNeed)
  }
  if (intent?.rationale) {
    pushTextBlock(planningBlocks, '关系判定说明', intent.rationale)
  }
  pushListBlock(planningLists, '软章节提示', intent?.softSectionHints)
  pushListBlock(planningLists, '上下文提示词', intent?.queryContextHints)

  const executionBlocks = []
  pushTextBlock(executionBlocks, '根主题', trace.retrievalAnchorRootTopic)
  pushTextBlock(executionBlocks, '根章节标题', trace.retrievalAnchorRootSectionTitle)
  pushTextBlock(executionBlocks, '编号项文本', trace.retrievalAnchorItemText)

  const executionLists = []
  pushListBlock(executionLists, '执行过程提示', thinkingSteps)
  pushListBlock(executionLists, '检索/Agent 轨迹', trace.retrievalNotes)

  const executionChips = buildChips(
    asList(trace.usedChannels).map((item) => ({ label: '使用通道', value: item, tone: 'success' })),
    asList(exchange.usedTools).map((item) => ({ label: '工具', value: item, tone: 'warning' }))
  )

  const generationBlocks = []
  pushTextBlock(generationBlocks, '系统 Prompt', trace.ragSystemPrompt, { code: true })
  pushTextBlock(generationBlocks, '用户 Prompt', trace.ragUserPrompt, { code: true })
  pushTextBlock(generationBlocks, '回答预览', exchange.answer, { code: true })

  const generationMetrics = buildMetrics(
    { label: '首包耗时', value: formatLatency(exchange.firstResponseTimeMs), mono: true },
    { label: '总耗时', value: formatLatency(exchange.totalResponseTimeMs), mono: true },
    { label: '引用数', value: references.length ? `${references.length}` : '', mono: true },
    { label: '推荐问题', value: recommendations.length ? `${recommendations.length}` : '', mono: true }
  )

  const outcomeBlocks = []
  pushTextBlock(outcomeBlocks, '结束说明', exchange.errorMessage)

  const outcomeLists = []
  pushListBlock(outcomeLists, '推荐追问', recommendations, { ordered: true })

  const stages = [
    {
      key: 'request',
      title: '请求入口',
      subtitle: '确认本轮边界、模式和基础上下文',
      tone: 'primary',
      chips: requestChips,
      metrics: buildMetrics(
        { label: '会话ID', value: session?.conversationId, mono: true },
        { label: '轮次ID', value: exchange.exchangeId ? String(exchange.exchangeId) : '', mono: true }
      ),
      textBlocks: requestBlocks,
      listBlocks: requestLists
    },
    {
      key: 'planning',
      title: '前置编排',
      subtitle: '意图解析、问题改写、锚点与检索计划',
      tone: 'success',
      chips: planningChips,
      metrics: buildMetrics(
        { label: '摘要覆盖轮次', value: trace.historyCoveredExchangeCount != null ? String(trace.historyCoveredExchangeCount) : '', mono: true },
        { label: '摘要压缩次数', value: trace.historyCompressionCount != null ? String(trace.historyCompressionCount) : '', mono: true }
      ),
      textBlocks: planningBlocks,
      listBlocks: planningLists
    },
    {
      key: 'execution',
      title: trace.executionMode === 'REACT_AGENT' ? 'Agent 执行' : '检索执行',
      subtitle: trace.executionMode === 'REACT_AGENT' ? '观察 Agent 如何决定是否调用工具' : '观察检索通道、锚点和证据组织',
      tone: 'warning',
      chips: executionChips,
      metrics: buildMetrics(
        { label: '工具调用次数', value: toolTraces.length ? String(toolTraces.length) : '', mono: true },
        { label: 'Thinking 条数', value: thinkingSteps.length ? String(thinkingSteps.length) : '', mono: true }
      ),
      textBlocks: executionBlocks,
      listBlocks: executionLists,
      toolTraces
    },
    {
      key: 'generation',
      title: 'Prompt 与生成',
      subtitle: '查看进入生成阶段前喂给模型的上下文与耗时',
      tone: 'neutral',
      chips: buildChips(
        { label: '状态', value: formatStatusLabel(exchange.status), tone: statusTone(exchange.status) }
      ),
      metrics: generationMetrics,
      textBlocks: generationBlocks,
      listBlocks: []
    },
    {
      key: 'outcome',
      title: '结果与诊断',
      subtitle: '最终引用、追问和失败信息汇总',
      tone: statusTone(exchange.status),
      chips: buildChips(
        { label: '最终状态', value: formatStatusLabel(exchange.status), tone: statusTone(exchange.status) },
        { label: '最近更新时间', value: formatDateTime(exchange.editTime || exchange.createTime), tone: 'neutral' }
      ),
      metrics: buildMetrics(
        { label: '最终引用数', value: references.length ? `${references.length}` : '', mono: true }
      ),
      textBlocks: outcomeBlocks,
      listBlocks: outcomeLists,
      references
    }
  ]

  return stages.filter((stage) => {
    return stage.chips?.length
      || stage.metrics?.length
      || stage.textBlocks?.length
      || stage.listBlocks?.length
      || stage.toolTraces?.length
      || stage.references?.length
  })
}
