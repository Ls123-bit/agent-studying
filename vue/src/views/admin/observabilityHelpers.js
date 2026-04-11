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

const EXECUTION_MODE_LABELS = {
  RAG_CHAT: '文档检索问答',
  REACT_AGENT: 'Agent 自主执行'
}

const RELATION_TYPE_LABELS = {
  FOLLOW_UP: '承接上文追问',
  TOPIC_SWITCH: '切换到新主题',
  FRESH_TOPIC: '独立新问题',
  UNKNOWN: '未识别'
}

const RETRIEVAL_MODE_LABELS = {
  DIRECT_QUERY: '直接检索',
  SECTION_FOCUSED: '定向查章节',
  ANALYTIC_DECOMPOSITION: '拆成多个子问题',
  UNKNOWN: '未识别'
}

const ANSWER_SHAPE_LABELS = {
  LIST: '列表型回答',
  STEPS: '步骤型回答',
  OUTLINE: '提纲型回答',
  COMPARISON: '对比型回答',
  EXPLANATION: '解释型回答',
  JUDGMENT: '判断型回答',
  FACT: '事实型回答',
  UNKNOWN: '未识别'
}

const CHANNEL_LABELS = {
  keyword: '关键词检索',
  vector: '向量检索',
  rerank: '重排精排',
  hybrid: '融合结果',
  'web-search': '网页搜索'
}

const TOOL_LABELS = {
  tavily_search: 'Tavily 联网搜索',
  keyword: '关键词检索通道',
  vector: '向量检索通道',
  rerank: '重排精排'
}

function asList(value) {
  return Array.isArray(value) ? value.filter(Boolean) : []
}

function mapLabel(value, mapping, fallback = '未识别') {
  if (!value) {
    return fallback
  }
  return mapping[value] || value
}

function uniqueStrings(values) {
  const result = []
  const seen = new Set()
  values.filter(Boolean).forEach((item) => {
    if (seen.has(item)) {
      return
    }
    seen.add(item)
    result.push(item)
  })
  return result
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

export function formatExecutionMode(value) {
  return mapLabel(value, EXECUTION_MODE_LABELS)
}

export function formatRelationType(value) {
  return mapLabel(value, RELATION_TYPE_LABELS)
}

export function formatRetrievalMode(value) {
  return mapLabel(value, RETRIEVAL_MODE_LABELS)
}

export function formatAnswerShape(value) {
  return mapLabel(value, ANSWER_SHAPE_LABELS)
}

export function formatChannelName(value) {
  return mapLabel(value, CHANNEL_LABELS, value || '未知通道')
}

export function formatToolName(value) {
  return mapLabel(value, TOOL_LABELS, value || '未知工具')
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

function buildOutcomeSummary(exchange, references) {
  if (!exchange) {
    return ''
  }
  if (exchange.status === 'FAILED') {
    return exchange.errorMessage
      ? `本轮执行失败，结束原因是：${exchange.errorMessage}`
      : '本轮执行失败，但当前没有拿到更具体的错误说明。'
  }
  if (exchange.status === 'STOPPED') {
    return exchange.errorMessage
      ? `本轮被主动停止，结束说明是：${exchange.errorMessage}`
      : '本轮被主动停止。'
  }
  if (exchange.status === 'COMPLETED') {
    if (references.length > 0) {
      return `本轮已完成，并基于 ${references.length} 条最终证据生成回答。排障时优先核对这些引用是否真的支撑了答案。`
    }
    return '本轮已完成，但没有看到最终引用，适合继续检查检索或 Prompt 组装阶段。'
  }
  return '这是一条正在执行中的轮次，建议优先关注执行过程提示和实时状态。'
}

export function buildExchangeStatusNarrative(exchange) {
  if (!exchange) {
    return ''
  }
  const trace = exchange.debugTrace || {}
  const intent = trace.intentResolution || null
  const parts = [
    `当前查看的是 exchange ${exchange.exchangeId}。`,
    `执行路径是“${formatExecutionMode(trace.executionMode)}”。`
  ]

  if (intent?.relationType) {
    parts.push(`系统把这句判定为“${formatRelationType(intent.relationType)}”。`)
  }
  if (intent?.retrievalMode) {
    parts.push(`检索策略是“${formatRetrievalMode(intent.retrievalMode)}”。`)
  }
  if (exchange.status === 'FAILED' && exchange.errorMessage) {
    parts.push(`当前结束原因：${exchange.errorMessage}`)
  }
  else if (exchange.status === 'COMPLETED') {
    parts.push('这轮已经成功完成，默认先看“结果与诊断”和“执行过程”两块。')
  }
  return parts.join(' ')
}

export function buildExchangeStages(session, exchange) {
  if (!exchange) {
    return []
  }

  const trace = exchange.debugTrace || {}
  const intent = trace.intentResolution || null
  const references = asList(exchange.references)
  const recommendations = asList(exchange.recommendations)
  const thinkingSteps = asList(exchange.thinkingSteps)
  const retrievalNotes = asList(trace.retrievalNotes)
  const executionNotes = uniqueStrings([...thinkingSteps, ...retrievalNotes])
  const toolTraces = asList(trace.toolTraces).map((item) => ({
    ...item,
    toolName: formatToolName(item?.toolName),
    topic: item?.topic || ''
  }))

  const requestPrimaryBlocks = []
  pushTextBlock(requestPrimaryBlocks, '用户原始问题', trace.originalQuestion || exchange.question)
  pushTextBlock(requestPrimaryBlocks, '当前日期锚点', trace.currentDateText)

  const requestAdvancedBlocks = []
  pushTextBlock(requestAdvancedBlocks, 'Agent 增强问题', trace.agentQuestion, { code: true })

  const planningPrimaryBlocks = []
  pushTextBlock(planningPrimaryBlocks, '系统理解后的问题', trace.retrievalQuestion)
  pushTextBlock(planningPrimaryBlocks, '信息需求', intent?.informationNeed)
  pushTextBlock(planningPrimaryBlocks, '判定说明', intent?.rationale)
  pushTextBlock(planningPrimaryBlocks, '检索锚点主问题', trace.retrievalAnchorResolvedQuestion)

  const planningPrimaryLists = []
  if (asList(trace.retrievalSubQuestions).length > 1) {
    pushListBlock(planningPrimaryLists, '最终检索子问题', trace.retrievalSubQuestions, { ordered: true })
  }

  const planningAdvancedBlocks = []
  pushTextBlock(planningAdvancedBlocks, 'Rewrite 独立问题', trace.rewriteQuestion)
  pushTextBlock(planningAdvancedBlocks, '长期摘要', trace.longTermSummary, { code: true })
  pushTextBlock(planningAdvancedBlocks, '回答承接上下文', trace.answerHistoryContext, { code: true })
  pushTextBlock(planningAdvancedBlocks, '规划历史摘要', trace.historySummary, { code: true })
  pushTextBlock(planningAdvancedBlocks, '根主题', trace.retrievalAnchorRootTopic)
  pushTextBlock(planningAdvancedBlocks, '根章节标题', trace.retrievalAnchorRootSectionTitle)
  pushTextBlock(planningAdvancedBlocks, '目标章节提示', trace.retrievalAnchorTargetSectionHint)
  pushTextBlock(planningAdvancedBlocks, '编号项文本', trace.retrievalAnchorItemText)

  const planningAdvancedLists = []
  pushListBlock(planningAdvancedLists, 'Rewrite 子问题拆分', trace.rewriteSubQuestions, { ordered: true })
  pushListBlock(planningAdvancedLists, '软章节提示', intent?.softSectionHints)
  pushListBlock(planningAdvancedLists, '上下文提示词', intent?.queryContextHints)

  const executionPrimaryLists = []
  pushListBlock(executionPrimaryLists, '关键执行节点', executionNotes)

  const executionAdvancedLists = []
  pushListBlock(executionAdvancedLists, '原始 thinking 事件', thinkingSteps)
  pushListBlock(executionAdvancedLists, '原始检索/Agent 轨迹', retrievalNotes)

  const generationPrimaryBlocks = []
  pushTextBlock(generationPrimaryBlocks, '回答预览', exchange.answer, { code: true })

  const generationAdvancedBlocks = []
  pushTextBlock(generationAdvancedBlocks, '系统 Prompt', trace.ragSystemPrompt, { code: true })
  pushTextBlock(generationAdvancedBlocks, '用户 Prompt', trace.ragUserPrompt, { code: true })

  const outcomePrimaryBlocks = []
  pushTextBlock(outcomePrimaryBlocks, '排障结论', buildOutcomeSummary(exchange, references))
  pushTextBlock(outcomePrimaryBlocks, '结束说明', exchange.errorMessage)

  const outcomeAdvancedLists = []
  pushListBlock(outcomeAdvancedLists, '推荐追问', recommendations, { ordered: true })

  const stages = [
    {
      key: 'outcome',
      eyebrow: '1. 排障结论',
      title: '结果与诊断',
      subtitle: '先看这块，快速判断这轮到底是成功、失败、停止，还是证据不足。',
      tone: statusTone(exchange.status),
      chips: buildChips(
        { label: '最终状态', value: formatStatusLabel(exchange.status), tone: statusTone(exchange.status) },
        { label: '引用情况', value: references.length ? `${references.length} 条证据` : '未看到最终引用', tone: references.length ? 'success' : 'warning' }
      ),
      metrics: buildMetrics(
        { label: '最终引用数', value: references.length ? `${references.length}` : '', mono: true },
        { label: '推荐追问', value: recommendations.length ? `${recommendations.length}` : '', mono: true }
      ),
      textBlocks: outcomePrimaryBlocks,
      listBlocks: [],
      references,
      advancedTextBlocks: [],
      advancedListBlocks: outcomeAdvancedLists
    },
    {
      key: 'execution',
      eyebrow: '2. 执行过程',
      title: trace.executionMode === 'REACT_AGENT' ? 'Agent 执行' : '检索执行',
      subtitle: trace.executionMode === 'REACT_AGENT'
        ? '如果结果不对，先看 Agent 有没有调用工具、工具回来了什么。'
        : '如果结果不对，先看检索通道、执行节点和最终证据组织是否正常。',
      tone: 'warning',
      chips: buildChips(
        asList(trace.usedChannels).map((item) => ({ label: '使用通道', value: formatChannelName(item), tone: 'success' })),
        asList(exchange.usedTools).map((item) => ({ label: '使用组件', value: formatToolName(item), tone: 'warning' }))
      ),
      metrics: buildMetrics(
        { label: '关键节点数', value: executionNotes.length ? String(executionNotes.length) : '', mono: true },
        { label: '工具调用次数', value: toolTraces.length ? String(toolTraces.length) : '', mono: true }
      ),
      textBlocks: [],
      listBlocks: executionPrimaryLists,
      toolTraces,
      advancedTextBlocks: [],
      advancedListBlocks: executionAdvancedLists
    },
    {
      key: 'planning',
      eyebrow: '3. 系统理解',
      title: '前置编排',
      subtitle: '当你怀疑系统“理解错问题”时，看这块最直接。',
      tone: 'success',
      chips: buildChips(
        { label: '会话关系', value: formatRelationType(intent?.relationType), tone: 'primary' },
        { label: '检索方式', value: formatRetrievalMode(intent?.retrievalMode), tone: 'success' },
        { label: '答案形态', value: formatAnswerShape(intent?.answerShape), tone: 'neutral' },
        { label: '意图置信度', value: formatConfidence(intent?.confidence), tone: 'warning' },
        { label: '锚点应用', value: trace.retrievalAnchorApplied ? '已使用锚点' : '未使用锚点', tone: trace.retrievalAnchorApplied ? 'success' : 'neutral' }
      ),
      metrics: buildMetrics(
        { label: '摘要覆盖轮次', value: trace.historyCoveredExchangeCount != null ? String(trace.historyCoveredExchangeCount) : '', mono: true },
        { label: '摘要压缩次数', value: trace.historyCompressionCount != null ? String(trace.historyCompressionCount) : '', mono: true }
      ),
      textBlocks: planningPrimaryBlocks,
      listBlocks: planningPrimaryLists,
      advancedTextBlocks: planningAdvancedBlocks,
      advancedListBlocks: planningAdvancedLists
    },
    {
      key: 'request',
      eyebrow: '4. 请求边界',
      title: '请求入口',
      subtitle: '确认用户原始问题、模式和文档边界有没有偏掉。',
      tone: 'primary',
      chips: buildChips(
        { label: '回答模式', value: formatChatMode(trace.chatMode || session?.chatMode), tone: 'primary' },
        { label: '执行路径', value: formatExecutionMode(trace.executionMode), tone: 'success' },
        { label: '文档范围', value: session?.selectedDocumentName || (trace.selectedDocumentId ? `文档 ${trace.selectedDocumentId}` : ''), tone: 'neutral' },
        { label: '时间解释', value: trace.requiresCurrentDateAnchoring ? '按当前日期解释' : '', tone: 'warning' },
        { label: '实时核实', value: trace.requiresFreshSearch ? '优先核实最新事实' : '', tone: 'warning' }
      ),
      metrics: buildMetrics(
        { label: '会话ID', value: session?.conversationId, mono: true },
        { label: '轮次ID', value: exchange.exchangeId ? String(exchange.exchangeId) : '', mono: true }
      ),
      textBlocks: requestPrimaryBlocks,
      listBlocks: [],
      advancedTextBlocks: requestAdvancedBlocks,
      advancedListBlocks: []
    },
    {
      key: 'generation',
      eyebrow: '5. 高级生成',
      title: '生成回答',
      subtitle: '默认只看耗时和回答预览；Prompt 已折叠到高级技术细节里。',
      tone: 'neutral',
      chips: buildChips(
        { label: '当前状态', value: formatStatusLabel(exchange.status), tone: statusTone(exchange.status) }
      ),
      metrics: buildMetrics(
        { label: '首包耗时', value: formatLatency(exchange.firstResponseTimeMs), mono: true },
        { label: '总耗时', value: formatLatency(exchange.totalResponseTimeMs), mono: true },
        { label: '引用数', value: references.length ? `${references.length}` : '', mono: true }
      ),
      textBlocks: generationPrimaryBlocks,
      listBlocks: [],
      advancedTextBlocks: generationAdvancedBlocks,
      advancedListBlocks: []
    }
  ]

  return stages.filter((stage) => {
    return stage.chips?.length
      || stage.metrics?.length
      || stage.textBlocks?.length
      || stage.listBlocks?.length
      || stage.toolTraces?.length
      || stage.references?.length
      || stage.advancedTextBlocks?.length
      || stage.advancedListBlocks?.length
  })
}

export function stageHasAdvancedDetails(stage) {
  if (!stage) {
    return false
  }
  return Boolean(
    stage.advancedTextBlocks?.length
    || stage.advancedListBlocks?.length
    || stage.advancedToolTraces?.length
    || stage.advancedReferences?.length
  )
}
