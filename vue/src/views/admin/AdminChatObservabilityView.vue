<template>
  <section class="trace-page">
    <div class="trace-grid">
      <article class="panel-card session-panel">
        <div class="section-header">
          <div>
            <h3>会话轨迹列表</h3>
          </div>
          <button class="ghost-button" type="button" @click="loadSessions">刷新</button>
        </div>

        <div v-if="loadingSessions" class="empty-block">正在加载会话列表...</div>
        <div v-else-if="!sessions.length" class="empty-block">当前还没有可观测的会话记录。</div>

        <div v-else class="session-list">
          <button
            v-for="item in sessions"
            :key="item.conversationId"
            class="session-item"
            :class="{ active: item.conversationId === currentConversationId }"
            type="button"
            @click="selectSession(item.conversationId)"
          >
            <div class="session-main">
              <div class="session-title-row">
                <strong>{{ sessionTitle(item) }}</strong>
                <span v-if="item.running" class="running-dot">运行中</span>
              </div>
              <p>{{ sessionPreview(item) }}</p>
              <div class="session-meta">
                <span>{{ formatTime(item.updatedAt) }}</span>
                <span>{{ sessionMessageCount(item) }} 条消息</span>
              </div>
            </div>
          </button>
        </div>
      </article>

      <article class="panel-card detail-panel">
        <div class="section-header">
          <div>
            <h3>对话执行观测</h3>
          </div>
          <div class="section-actions">
            <button
              class="ghost-button"
              type="button"
              :disabled="!activeSession || rebuildingSummary"
              @click="rebuildSummary"
            >
              {{ rebuildingSummary ? '正在重建摘要...' : '手动重建摘要' }}
            </button>
          </div>
        </div>

        <div v-if="pageError" class="inline-notice error-notice">{{ pageError }}</div>
        <div v-if="loadingSession" class="empty-block">正在加载会话详情...</div>
        <div v-else-if="!activeSession" class="empty-block">请选择左侧一条会话查看执行轨迹。</div>
        <div v-else class="detail-stack">
          <section class="summary-card">
            <div class="summary-grid">
              <div>
                <span class="summary-label">会话ID</span>
                <p>{{ activeSession.conversationId }}</p>
              </div>
              <div>
                <span class="summary-label">最新用户消息</span>
                <p>{{ activeSession.latestUserMessage || '无' }}</p>
              </div>
              <div>
                <span class="summary-label">最近更新时间</span>
                <p>{{ formatDateTime(activeSession.updatedAt) }}</p>
              </div>
              <div>
                <span class="summary-label">Checkpoint / 消息数</span>
                <p>{{ activeSession.checkpointCount || 0 }} / {{ activeSession.messageCount || 0 }}</p>
              </div>
            </div>
          </section>

          <section class="summary-card memory-summary-card">
            <div class="memory-head">
              <div>
                <span class="summary-label">长期摘要快照</span>
                <h4>会话压缩状态</h4>
              </div>
              <span
                class="trace-badge"
                :class="activeSession.memorySummary?.compressionApplied ? 'badge-mode' : 'badge-status'"
              >
                {{ activeSession.memorySummary?.compressionApplied ? '已形成长期摘要' : '尚未形成长期摘要' }}
              </span>
            </div>

            <div class="summary-grid">
              <div>
                <span class="summary-label">covered_exchange_id</span>
                <p>{{ activeSession.memorySummary?.coveredExchangeId ?? 0 }}</p>
              </div>
              <div>
                <span class="summary-label">covered_exchange_count</span>
                <p>{{ activeSession.memorySummary?.coveredExchangeCount ?? 0 }}</p>
              </div>
              <div>
                <span class="summary-label">compression_count / version</span>
                <p>
                  {{ activeSession.memorySummary?.compressionCount ?? 0 }}
                  /
                  {{ activeSession.memorySummary?.summaryVersion ?? 0 }}
                </p>
              </div>
              <div>
                <span class="summary-label">last_source_edit_time</span>
                <p>{{ formatDateTime(activeSession.memorySummary?.lastSourceEditTime) }}</p>
              </div>
            </div>

            <div v-if="activeSession.memorySummary?.compressionApplied" class="trace-section">
              <span class="trace-label">summary_text</span>
              <pre class="prompt-block">{{ activeSession.memorySummary?.summaryText || '无' }}</pre>
            </div>

            <div v-else class="empty-block compact-empty summary-empty">
              当前会话还没有形成长期摘要。常见原因是有效轮次还没有超过“最近原文窗口”，或者摘要尚未预热完成。
            </div>

            <div v-if="activeSession.memorySummary?.summaryPayload?.retrievalHints?.length" class="trace-section">
              <span class="trace-label">检索提示关键词</span>
              <div class="chip-row">
                <span
                  v-for="(item, index) in activeSession.memorySummary.summaryPayload.retrievalHints"
                  :key="`memory-hint-${index}`"
                  class="trace-chip trace-chip-channel"
                >
                  {{ item }}
                </span>
              </div>
            </div>

            <div v-if="activeSession.memorySummary?.summaryPayload?.pendingQuestions?.length" class="trace-section">
              <span class="trace-label">待跟进问题</span>
              <ul class="trace-list">
                <li
                  v-for="(item, index) in activeSession.memorySummary.summaryPayload.pendingQuestions"
                  :key="`memory-pending-${index}`"
                >
                  {{ item }}
                </li>
              </ul>
            </div>
          </section>

          <div v-if="!assistantExchanges.length" class="empty-block compact-empty">
            当前会话还没有助手轮次，无法展示执行轨迹。
          </div>

          <div v-else class="exchange-list">
            <article
              v-for="exchange in assistantExchanges"
              :key="exchange.exchangeId"
              class="exchange-card"
            >
              <div class="exchange-head">
                <div>
                  <h4>{{ truncate(exchange.question || '未记录问题', 48) }}</h4>
                  <p>{{ formatDateTime(exchange.editTime || exchange.createTime) }}</p>
                </div>
                <div class="exchange-badges">
                  <span class="trace-badge badge-status">{{ exchange.status || 'UNKNOWN' }}</span>
                  <span v-if="exchange.debugTrace?.chatMode" class="trace-badge badge-route">
                    {{ formatChatMode(exchange.debugTrace.chatMode) }}
                  </span>
                  <span v-if="exchange.debugTrace?.executionMode" class="trace-badge badge-mode">
                    {{ exchange.debugTrace.executionMode }}
                  </span>
                </div>
              </div>

              <div class="trace-section">
                <span class="trace-label">原始问题</span>
                <p>{{ exchange.debugTrace?.originalQuestion || exchange.question || '无' }}</p>
              </div>

              <div class="trace-section" v-if="exchange.debugTrace?.rewrittenQuestion">
                <span class="trace-label">改写问题</span>
                <p>{{ exchange.debugTrace.rewrittenQuestion }}</p>
              </div>

              <div class="trace-section" v-if="exchange.debugTrace?.subQuestions?.length">
                <span class="trace-label">子问题拆分</span>
                <div class="chip-row">
                  <span v-for="(item, index) in exchange.debugTrace.subQuestions" :key="`${exchange.exchangeId}-sub-${index}`" class="trace-chip">
                    {{ index + 1 }}. {{ item }}
                  </span>
                </div>
              </div>

              <div class="trace-section" v-if="exchange.debugTrace?.selectedDocumentId">
                <span class="trace-label">检索文档范围</span>
                <div class="chip-row">
                  <span class="trace-chip">文档ID {{ exchange.debugTrace.selectedDocumentId }}</span>
                  <span v-if="exchange.debugTrace?.selectedTaskId" class="trace-chip">
                    任务ID {{ exchange.debugTrace.selectedTaskId }}
                  </span>
                </div>
              </div>

              <div class="trace-section" v-if="exchange.debugTrace?.retrievalNotes?.length">
                <span class="trace-label">检索轨迹</span>
                <ul class="trace-list">
                  <li v-for="(item, index) in exchange.debugTrace.retrievalNotes" :key="`${exchange.exchangeId}-note-${index}`">
                    {{ item }}
                  </li>
                </ul>
              </div>

              <div class="trace-section" v-if="exchange.debugTrace?.usedChannels?.length">
                <span class="trace-label">使用通道</span>
                <div class="chip-row">
                  <span v-for="(item, index) in exchange.debugTrace.usedChannels" :key="`${exchange.exchangeId}-channel-${index}`" class="trace-chip trace-chip-channel">
                    {{ item }}
                  </span>
                </div>
              </div>

              <div class="trace-section" v-if="exchange.references?.length">
                <span class="trace-label">最终证据</span>
                <div class="reference-list">
                  <div v-for="(item, index) in exchange.references" :key="`${exchange.exchangeId}-ref-${index}`" class="reference-item">
                    <strong>[{{ item.referenceId || index + 1 }}] {{ item.documentName || item.title || '未命名引用' }}</strong>
                    <p>
                      {{ item.sectionPath || '未识别章节' }}
                      <span v-if="item.channel"> | {{ item.channel }}</span>
                    </p>
                    <p>{{ truncate(item.snippet || '', 220) }}</p>
                  </div>
                </div>
              </div>

              <div class="trace-section" v-if="exchange.debugTrace?.ragSystemPrompt">
                <span class="trace-label">系统 Prompt</span>
                <pre class="prompt-block">{{ exchange.debugTrace.ragSystemPrompt }}</pre>
              </div>

              <div class="trace-section" v-if="exchange.debugTrace?.ragUserPrompt">
                <span class="trace-label">用户 Prompt</span>
                <pre class="prompt-block">{{ exchange.debugTrace.ragUserPrompt }}</pre>
              </div>

              <div class="trace-section">
                <span class="trace-label">回答摘要</span>
                <p>{{ truncate(exchange.answer || '无回答', 320) }}</p>
              </div>
            </article>
          </div>
        </div>
      </article>
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { APIError, chatApi } from '../../api/api'

const sessions = ref([])
const loadingSessions = ref(false)
const loadingSession = ref(false)
const currentConversationId = ref('')
const activeSession = ref(null)
const pageError = ref('')
const rebuildingSummary = ref(false)

const assistantExchanges = computed(() => {
  const exchanges = activeSession.value?.exchanges || []
  return exchanges.filter((item) => item && item.status)
})

async function loadSessions() {
  loadingSessions.value = true
  pageError.value = ''

  try {
    sessions.value = await chatApi.listSessions()
    if (!currentConversationId.value && sessions.value.length > 0) {
      await selectSession(sessions.value[0].conversationId)
    }
  } catch (error) {
    pageError.value = normalizeError(error, '加载会话列表失败')
  } finally {
    loadingSessions.value = false
  }
}

async function selectSession(conversationId) {
  if (!conversationId) {
    return
  }

  loadingSession.value = true
  pageError.value = ''
  currentConversationId.value = conversationId

  try {
    activeSession.value = await chatApi.getSession(conversationId)
  } catch (error) {
    activeSession.value = null
    pageError.value = normalizeError(error, '加载会话轨迹失败')
  } finally {
    loadingSession.value = false
  }
}

async function rebuildSummary() {
  if (!currentConversationId.value || rebuildingSummary.value) {
    return
  }

  rebuildingSummary.value = true
  pageError.value = ''

  try {
    const summary = await chatApi.rebuildConversationSummary(currentConversationId.value)
    if (activeSession.value?.conversationId === currentConversationId.value) {
      activeSession.value = {
        ...activeSession.value,
        memorySummary: summary
      }
    }
    await loadSessions()
  } catch (error) {
    pageError.value = normalizeError(error, '手动重建长期摘要失败')
  } finally {
    rebuildingSummary.value = false
  }
}

function sessionTitle(session) {
  const latestUserMessage = session.latestUserMessage || latestExchangeQuestion(session)
  const latestAssistantMessage = session.latestAssistantMessage || latestExchangeAnswer(session)
  return truncate(latestUserMessage || latestAssistantMessage || '未命名会话', 22)
}

function sessionPreview(session) {
  const latestAssistantMessage = session.latestAssistantMessage || latestExchangeAnswer(session)
  const latestUserMessage = session.latestUserMessage || latestExchangeQuestion(session)
  return truncate(latestAssistantMessage || latestUserMessage || '暂无内容', 48)
}

function sessionMessageCount(session) {
  if (session?.messageCount) {
    return session.messageCount
  }
  return businessMessageCount(session?.exchanges || [])
}

function latestExchangeQuestion(session) {
  const exchanges = session?.exchanges || []
  for (let index = exchanges.length - 1; index >= 0; index -= 1) {
    const question = exchanges[index]?.question
    if (question) {
      return question
    }
  }
  return ''
}

function latestExchangeAnswer(session) {
  const exchanges = session?.exchanges || []
  for (let index = exchanges.length - 1; index >= 0; index -= 1) {
    const answer = exchanges[index]?.answer
    if (answer) {
      return answer
    }
  }
  return ''
}

function businessMessageCount(exchanges = []) {
  return exchanges.reduce((count, exchange) => {
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

function truncate(value, maxLength) {
  if (!value) {
    return ''
  }
  return value.length > maxLength ? `${value.slice(0, maxLength)}...` : value
}

function formatTime(value) {
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

function formatDateTime(value) {
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

function formatChatMode(value) {
  if (value === 'DOCUMENT') {
    return '当前文档问答'
  }
  if (value === 'OPEN_CHAT') {
    return '开放式提问'
  }
  return value || '未知模式'
}

function normalizeError(error, fallbackMessage) {
  if (error instanceof APIError && error.message) {
    return error.message
  }
  if (error instanceof Error && error.message) {
    return error.message
  }
  return fallbackMessage
}

onMounted(loadSessions)
</script>

<style scoped>
.trace-page {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.trace-grid {
  display: grid;
  grid-template-columns: 0.74fr 1.26fr;
  gap: 16px;
}

.panel-card {
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  padding: 20px;
}

.section-header,
.session-title-row,
.session-meta,
.exchange-head,
.summary-grid,
.chip-row {
  display: flex;
}

.section-header,
.exchange-head {
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.section-actions,
.memory-head {
  display: flex;
  align-items: center;
  gap: 12px;
}

.section-header h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--color-text-strong);
}

.memory-head h4 {
  margin: 2px 0 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--color-text-strong);
}

.exchange-head h4 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--color-text-strong);
}

.ghost-button {
  border-radius: var(--radius-sm);
  padding: 8px 16px;
  font-weight: 600;
  color: var(--color-primary);
  background: var(--color-primary-soft);
  border: 1px solid transparent;
}

.ghost-button:hover:not(:disabled) {
  background: rgba(37, 87, 214, 0.14);
}

.session-list,
.detail-stack,
.exchange-list,
.reference-list {
  display: flex;
  flex-direction: column;
}

.session-list,
.exchange-list,
.reference-list {
  gap: 12px;
}

.session-list,
.detail-stack {
  margin-top: 18px;
}

.session-item,
.exchange-card,
.summary-card,
.reference-item {
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  background: var(--color-surface-soft);
}

.session-item {
  padding: 16px 18px;
  text-align: left;
}

.session-item.active {
  border-color: var(--color-primary);
  box-shadow: var(--shadow-sm);
}

.session-main strong,
.reference-item strong {
  color: var(--color-text-strong);
}

.session-item p,
.session-meta,
.exchange-head p,
.summary-card p,
.trace-section p,
.reference-item p,
.trace-list {
  margin: 0;
  color: var(--color-text);
  line-height: 1.7;
}

.session-item p {
  margin-top: 8px;
}

.session-meta {
  gap: 12px;
  margin-top: 10px;
  font-size: 13px;
  color: var(--color-muted);
}

.running-dot,
.trace-badge {
  border-radius: 999px;
  padding: 4px 8px;
  font-size: 12px;
  font-weight: 500;
}

.running-dot {
  background: rgba(37, 87, 214, 0.12);
  color: #1f4ebb;
}

.exchange-card,
.summary-card,
.reference-item {
  padding: 18px 20px;
}

.memory-summary-card {
  background: linear-gradient(180deg, #f8fbff 0%, #ffffff 100%);
}

.exchange-badges,
.summary-grid,
.chip-row {
  gap: 10px;
  flex-wrap: wrap;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px 18px;
}

.summary-label,
.trace-label {
  display: inline-flex;
  margin-bottom: 8px;
  font-size: 12px;
  color: var(--color-muted);
}

.badge-status {
  background: rgba(17, 24, 39, 0.08);
  color: var(--color-text);
}

.badge-route {
  background: rgba(239, 123, 57, 0.12);
  color: #b55b16;
}

.badge-mode {
  background: rgba(37, 87, 214, 0.12);
  color: #1f4ebb;
}

.trace-section {
  margin-top: 16px;
}

.trace-chip {
  padding: 8px 12px;
  border-radius: 999px;
  background: rgba(17, 24, 39, 0.06);
  color: var(--color-text);
  font-size: 13px;
}

.trace-chip-channel {
  background: rgba(37, 87, 214, 0.08);
}

.trace-list {
  padding-left: 18px;
}

.summary-empty {
  margin-top: 16px;
}

.prompt-block {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  background: #ffffff;
  padding: 14px 16px;
  color: var(--color-text);
  line-height: 1.7;
  max-height: 360px;
  overflow: auto;
}

.inline-notice {
  margin-top: 18px;
  padding: 12px 14px;
  border-radius: var(--radius-sm);
  border: 1px solid rgba(37, 87, 214, 0.1);
  background: rgba(37, 87, 214, 0.08);
  color: #1f4ebb;
}

.error-notice {
  border-color: rgba(198, 40, 40, 0.12);
  background: rgba(198, 40, 40, 0.08);
  color: #a12626;
}

.empty-block {
  min-height: 260px;
  display: grid;
  place-items: center;
  text-align: center;
  color: var(--color-muted);
  border-radius: var(--radius-md);
  border: 1px dashed var(--color-border);
  background: var(--color-surface-soft);
  margin-top: 18px;
}

.compact-empty {
  min-height: 120px;
}

@media (max-width: 1180px) {
  .trace-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .summary-grid {
    grid-template-columns: 1fr;
  }

  .section-header,
  .exchange-head {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
