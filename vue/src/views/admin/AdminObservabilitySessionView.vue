<template>
  <section class="observability-session">
    <div class="detail-toolbar">
      <RouterLink :to="{ name: 'AdminObservabilityList' }" class="back-link">
        <ArrowLeftIcon class="tool-icon" />
        返回会话列表
      </RouterLink>

      <div class="toolbar-actions">
        <span v-if="activeSession?.running || pollingSession" class="live-chip">
          <span class="live-dot"></span>
          {{ pollingSession ? '实时轮询中' : '会话运行中' }}
        </span>
        <button class="ghost-button" type="button" :disabled="loadingSession" @click="loadSession()">
          <ArrowPathIcon class="tool-icon" />
          {{ loadingSession ? '刷新中...' : '刷新会话详情' }}
        </button>
        <button
          class="primary-button"
          type="button"
          :disabled="!activeSession || rebuildingSummary"
          @click="rebuildSummary"
        >
          <SparklesIcon class="tool-icon" />
          {{ rebuildingSummary ? '正在重建摘要...' : '重建长期摘要' }}
        </button>
      </div>
    </div>

    <div v-if="pageError" class="inline-notice error-notice">{{ pageError }}</div>
    <div v-if="loadingSession && !activeSession" class="empty-card">正在加载会话详情...</div>
    <div v-else-if="!activeSession" class="empty-card">没有找到这条会话，请返回列表重新选择。</div>

    <template v-else>
      <header class="session-hero">
        <div class="hero-copy">
          <span class="hero-kicker">Conversation Chain</span>
          <h2>{{ activeSession.selectedDocumentName || sessionTitle(activeSession) }}</h2>
          <p>
            这个页面只负责看整条会话里的每次问答，不展示单轮内部细节。
            先从下方轮次列表里找到你关心的那一轮，再进入专门的轮次详情页。
          </p>
        </div>

        <div class="hero-chip-row">
          <span class="hero-chip hero-chip-primary">{{ formatChatMode(activeSession.chatMode) }}</span>
          <span v-if="activeSession.running" class="hero-chip hero-chip-running">当前会话仍在执行</span>
          <span v-else-if="activeSession.latestTurnStatus" class="hero-chip" :class="`hero-chip-${statusTone(activeSession.latestTurnStatus)}`">
            最近一轮{{ formatStatusLabel(activeSession.latestTurnStatus) }}
          </span>
          <span class="hero-chip hero-chip-neutral">会话ID {{ activeSession.conversationId }}</span>
        </div>

        <div class="hero-metric-grid">
          <article v-for="item in sessionMetrics" :key="item.label" class="hero-metric-card">
            <span>{{ item.label }}</span>
            <strong>{{ item.value }}</strong>
          </article>
        </div>
      </header>

      <section class="background-grid">
        <article class="background-card">
          <span class="section-kicker">Session Context</span>
          <h3>会话级背景</h3>
          <p class="section-desc">只解释整条会话的上下文、最近状态和记忆压缩，不进入某一轮内部链路。</p>

          <div class="meta-stack">
            <div class="meta-row">
              <span>最近用户问题</span>
              <strong>{{ activeSession.latestUserMessage || '无' }}</strong>
            </div>
            <div class="meta-row">
              <span>最近助手回答</span>
              <p>{{ sessionPreview(activeSession) }}</p>
            </div>
            <div class="meta-row">
              <span>Checkpoint / 消息数</span>
              <strong>{{ activeSession.checkpointCount || 0 }} / {{ activeSession.messageCount || 0 }}</strong>
            </div>
          </div>
        </article>

        <article class="background-card memory-card">
          <span class="section-kicker">Memory</span>
          <h3>长期摘要快照</h3>
          <p class="section-desc">用于判断这条会话有没有记忆、压缩了多少历史，以及后续追问是否可能受它影响。</p>

          <div v-if="activeSession.memorySummary?.compressionApplied" class="memory-stack">
            <div class="memory-chip-row">
              <span class="memory-chip">covered {{ activeSession.memorySummary?.coveredExchangeCount ?? 0 }}</span>
              <span class="memory-chip">version {{ activeSession.memorySummary?.summaryVersion ?? 0 }}</span>
              <span class="memory-chip">compress {{ activeSession.memorySummary?.compressionCount ?? 0 }}</span>
            </div>
            <pre class="code-block">{{ activeSession.memorySummary?.summaryText || '无' }}</pre>
          </div>

          <div v-else class="memory-empty">
            当前会话还没有形成长期摘要。常见原因是轮次还不够，或者摘要预热尚未完成。
          </div>
        </article>
      </section>

      <section class="round-list-section">
        <div class="section-header-card">
          <div>
            <span class="section-kicker">Round List</span>
            <h3>本会话的每次一来一回</h3>
            <p class="section-desc">这里是整条会话的轮次总览，点击某一轮后会跳转到独立的轮次详情页。</p>
          </div>
        </div>

        <div v-if="!assistantExchanges.length" class="empty-card compact-empty">
          当前会话还没有助手轮次，无法展示执行链路。
        </div>

        <div v-else class="round-table">
          <RouterLink
            v-for="(exchange, index) in assistantExchanges"
            :key="exchange.exchangeId"
            class="round-row"
            :to="exchangeTarget(exchange)"
          >
            <div class="round-row-main">
              <div class="round-row-meta">
                <span class="record-seq">第 {{ index + 1 }} 轮</span>
                <span class="record-time">{{ formatDateTime(exchange.editTime || exchange.createTime) }}</span>
              </div>
              <div class="qa-block">
                <span class="qa-label">用户问题</span>
                <strong>{{ exchange.question || '未记录问题' }}</strong>
              </div>
              <div class="qa-block">
                <span class="qa-label">模型回答</span>
                <p>{{ truncate(exchange.answer || '还没有回答内容', 220) }}</p>
              </div>
            </div>

            <div class="round-row-side">
              <div class="record-badges">
                <span class="record-badge" :class="`tone-${statusTone(exchange.status)}`">
                  {{ formatStatusLabel(exchange.status) }}
                </span>
                <span v-if="exchange.debugTrace?.executionMode" class="record-badge tone-neutral">
                  {{ formatExecutionMode(exchange.debugTrace.executionMode) }}
                </span>
              </div>

              <div class="record-metrics">
                <span>耗时 {{ exchange.totalResponseTimeMs ? `${exchange.totalResponseTimeMs} ms` : '无' }}</span>
                <span>引用 {{ exchange.references?.length || 0 }}</span>
                <span>推荐 {{ exchange.recommendations?.length || 0 }}</span>
                <span>Token {{ exchangeTokenCount(exchange) }}</span>
                <span>成本 {{ exchangeCost(exchange) }}</span>
              </div>

              <div class="record-footer">
                <span class="record-link">进入这一轮的详情页</span>
              </div>
            </div>
          </RouterLink>
        </div>
      </section>
    </template>
  </section>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { ArrowLeftIcon, ArrowPathIcon, SparklesIcon } from '@heroicons/vue/24/outline'
import { chatApi } from '../../api/api'
import {
  formatChatMode,
  formatDateTime,
  formatExecutionMode,
  formatStatusLabel,
  listAssistantExchanges,
  normalizeError,
  sessionPreview,
  sessionTitle,
  statusTone,
  truncate
} from './observabilityHelpers'

const route = useRoute()

const loadingSession = ref(false)
const pollingSession = ref(false)
const activeSession = ref(null)
const pageError = ref('')
const rebuildingSummary = ref(false)

const POLL_INTERVAL_MS = 2500
let pollTimer = 0
let sessionRequestInFlight = false

const conversationId = computed(() => String(route.params.conversationId || ''))
const assistantExchanges = computed(() => listAssistantExchanges(activeSession.value))

const sessionMetrics = computed(() => {
  if (!activeSession.value) {
    return []
  }
  return [
    {
      label: '助手轮次',
      value: assistantExchanges.value.length
    },
    {
      label: '会话消息数',
      value: activeSession.value.messageCount || 0
    },
    {
      label: '长期摘要',
      value: activeSession.value.memorySummary?.compressionApplied ? '已形成' : '未形成'
    },
    {
      label: '最近更新时间',
      value: formatDateTime(activeSession.value.updatedAt)
    }
  ]
})

async function loadSession(options = {}) {
  if (!conversationId.value || sessionRequestInFlight) {
    return
  }

  const silent = Boolean(options.silent)
  sessionRequestInFlight = true
  if (silent) {
    pollingSession.value = true
  } else {
    loadingSession.value = true
  }
  pageError.value = ''

  try {
    activeSession.value = await chatApi.getSession(conversationId.value)
  } catch (error) {
    activeSession.value = null
    pageError.value = normalizeError(error, '加载会话详情失败')
  } finally {
    sessionRequestInFlight = false
    loadingSession.value = false
    pollingSession.value = false
    schedulePolling()
  }
}

function schedulePolling() {
  clearTimeout(pollTimer)
  if (!activeSession.value?.running) {
    return
  }
  pollTimer = window.setTimeout(() => {
    loadSession({ silent: true })
  }, POLL_INTERVAL_MS)
}

async function rebuildSummary() {
  if (!conversationId.value || rebuildingSummary.value) {
    return
  }

  rebuildingSummary.value = true
  pageError.value = ''

  try {
    const summary = await chatApi.rebuildConversationSummary(conversationId.value)
    if (activeSession.value?.conversationId === conversationId.value) {
      activeSession.value = {
        ...activeSession.value,
        memorySummary: summary
      }
    }
  } catch (error) {
    pageError.value = normalizeError(error, '手动重建长期摘要失败')
  } finally {
    rebuildingSummary.value = false
  }
}

function exchangeTarget(exchange) {
  return {
    name: 'AdminObservabilityExchangeDetail',
    params: {
      conversationId: conversationId.value,
      exchangeId: String(exchange.exchangeId)
    }
  }
}

function exchangeTokenCount(exchange) {
  const traces = exchange?.debugTrace?.modelUsageTraces || []
  const total = traces.reduce((sum, item) => sum + Number(item?.totalTokens || 0), 0)
  return total || '无'
}

function exchangeCost(exchange) {
  const traces = exchange?.debugTrace?.modelUsageTraces || []
  const total = traces.reduce((sum, item) => sum + Number(item?.estimatedCost || 0), 0)
  return total > 0 ? `¥ ${total.toFixed(4)}` : '无'
}

watch(conversationId, () => {
  activeSession.value = null
  loadSession()
}, { immediate: true })

onMounted(() => {
  schedulePolling()
})

onUnmounted(() => {
  clearTimeout(pollTimer)
})
</script>

<style scoped>
.observability-session {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.detail-toolbar,
.toolbar-actions,
.hero-chip-row,
.hero-metric-grid,
.background-grid,
.record-head,
.record-index,
.record-badges,
.record-metrics,
.record-footer {
  display: flex;
}

.detail-toolbar,
.record-head {
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.toolbar-actions,
.hero-chip-row,
.record-badges,
.record-metrics {
  flex-wrap: wrap;
  gap: 10px;
}

.tool-icon {
  width: 18px;
  height: 18px;
}

.back-link,
.ghost-button,
.primary-button {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  border-radius: 14px;
  padding: 10px 14px;
  font-weight: 600;
}

.back-link,
.ghost-button {
  border: 1px solid var(--color-border);
  background: #fff;
  color: var(--color-text);
}

.back-link:hover,
.ghost-button:hover:not(:disabled) {
  border-color: rgba(37, 87, 214, 0.22);
  background: rgba(255, 255, 255, 0.92);
}

.primary-button {
  border: none;
  color: #fff;
  background: linear-gradient(135deg, #17304f, #0d7c7c);
  box-shadow: 0 12px 28px rgba(13, 124, 124, 0.22);
}

.primary-button:hover:not(:disabled) {
  transform: translateY(-1px);
}

.primary-button:disabled,
.ghost-button:disabled {
  opacity: 0.65;
}

.live-chip,
.hero-chip,
.memory-chip,
.record-badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  border-radius: 999px;
  padding: 7px 12px;
  font-size: 12px;
  font-weight: 600;
}

.live-chip {
  background: rgba(13, 124, 124, 0.12);
  color: #0d7c7c;
}

.live-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: currentColor;
  box-shadow: 0 0 0 6px rgba(13, 124, 124, 0.12);
}

.session-hero,
.background-card,
.section-header-card,
.round-row,
.empty-card {
  position: relative;
  overflow: hidden;
  border-radius: 24px;
  border: 1px solid rgba(23, 48, 79, 0.08);
  background: #fff;
  box-shadow: 0 20px 50px rgba(15, 23, 42, 0.06);
}

.session-hero {
  padding: 28px;
  background:
    radial-gradient(circle at top right, rgba(37, 87, 214, 0.14), transparent 30%),
    radial-gradient(circle at left bottom, rgba(13, 124, 124, 0.1), transparent 36%),
    linear-gradient(180deg, rgba(255, 255, 255, 0.99), rgba(246, 249, 253, 0.98));
}

.hero-kicker,
.section-kicker,
.qa-label,
.record-seq {
  display: block;
  color: var(--color-muted);
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: 0.08em;
}

.hero-kicker,
.record-seq {
  font-family: 'Fira Code', var(--font-sans);
}

.hero-copy h2,
.section-header-card h3,
.background-card h3 {
  margin: 12px 0 10px;
  color: var(--color-text-strong);
  line-height: 1.16;
}

.hero-copy h2 {
  font-size: clamp(30px, 3vw, 40px);
}

.section-header-card h3,
.background-card h3 {
  font-size: 24px;
}

.hero-copy p,
.section-desc,
.background-card p,
.empty-card,
.inline-notice,
.record-time,
.record-metrics,
.record-link {
  margin: 0;
  color: var(--color-muted-strong);
  line-height: 1.75;
}

.hero-chip-primary {
  background: rgba(37, 87, 214, 0.08);
  color: var(--color-primary-strong);
}

.hero-chip-neutral,
.record-badge.tone-neutral {
  background: rgba(23, 48, 79, 0.08);
  color: #17304f;
}

.hero-chip-running,
.record-badge.tone-running {
  background: rgba(13, 124, 124, 0.12);
  color: #0d7c7c;
}

.hero-chip-completed,
.record-badge.tone-completed {
  background: rgba(21, 115, 91, 0.12);
  color: var(--color-success);
}

.hero-chip-failed,
.record-badge.tone-failed {
  background: rgba(179, 76, 47, 0.12);
  color: var(--color-danger);
}

.hero-chip-stopped,
.record-badge.tone-stopped,
.record-badge.tone-warning {
  background: rgba(239, 123, 57, 0.12);
  color: #c2410c;
}

.hero-metric-grid {
  margin-top: 22px;
  flex-wrap: wrap;
  gap: 14px;
}

.hero-metric-card {
  min-width: 140px;
  padding: 14px 16px;
  border-radius: 18px;
  border: 1px solid rgba(23, 48, 79, 0.08);
  background: rgba(255, 255, 255, 0.84);
}

.hero-metric-card span {
  display: block;
  font-size: 12px;
  color: var(--color-muted);
}

.hero-metric-card strong {
  display: block;
  margin-top: 8px;
  font-size: 24px;
  color: var(--color-text-strong);
}

.background-grid {
  gap: 18px;
}

.background-card,
.section-header-card {
  padding: 20px;
}

.background-card {
  flex: 1;
}

.memory-card {
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.99), rgba(37, 87, 214, 0.03));
}

.meta-stack,
.memory-stack,
.round-table {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.meta-row {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.meta-row strong {
  color: var(--color-text-strong);
}

.memory-chip {
  background: rgba(23, 48, 79, 0.08);
  color: #17304f;
}

.memory-chip-row {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.code-block {
  margin: 0;
  padding: 14px;
  border-radius: 14px;
  background: rgba(15, 23, 42, 0.06);
  color: var(--color-text);
  white-space: pre-wrap;
  line-height: 1.7;
  font-family: 'Fira Code', var(--font-sans);
}

.memory-empty {
  padding: 14px;
  border-radius: 16px;
  background: rgba(245, 248, 252, 0.96);
  color: var(--color-muted-strong);
}

.round-list-section {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.round-row {
  width: 100%;
  text-align: left;
  padding: 18px;
  display: flex;
  align-items: stretch;
  justify-content: space-between;
  gap: 14px;
  transition: transform 0.2s ease, border-color 0.2s ease, box-shadow 0.2s ease;
}

.round-row:hover {
  transform: translateY(-2px);
  border-color: rgba(37, 87, 214, 0.24);
  box-shadow: 0 18px 32px rgba(15, 23, 42, 0.09);
}

.round-row-main,
.round-row-side {
  display: flex;
  flex-direction: column;
}

.round-row-main {
  min-width: 0;
  flex: 1;
  gap: 12px;
}

.round-row-side {
  width: min(340px, 36%);
  gap: 12px;
  align-items: flex-end;
}

.record-index {
  flex-direction: column;
  gap: 4px;
}

.qa-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.qa-block strong {
  color: var(--color-text-strong);
  line-height: 1.5;
}

.qa-block p {
  margin: 0;
  color: var(--color-muted-strong);
  line-height: 1.75;
}

.record-metrics {
  font-size: 13px;
  justify-content: flex-end;
}

.record-footer {
  padding-top: 10px;
  border-top: 1px solid rgba(23, 48, 79, 0.08);
}

.record-link {
  color: var(--color-primary-strong);
  font-weight: 600;
}

.empty-card {
  padding: 48px 24px;
  text-align: center;
  color: var(--color-muted);
  line-height: 1.8;
}

.compact-empty {
  padding: 28px 20px;
}

.inline-notice {
  padding: 14px 16px;
  border-radius: 14px;
}

.error-notice {
  color: var(--color-danger);
  background: rgba(179, 76, 47, 0.08);
  border: 1px solid rgba(179, 76, 47, 0.12);
}

@media (max-width: 980px) {
  .background-grid {
    flex-direction: column;
  }

  .round-row {
    flex-direction: column;
  }

  .round-row-side {
    width: 100%;
    align-items: flex-start;
  }
}

@media (max-width: 760px) {
  .detail-toolbar {
    flex-direction: column;
    align-items: stretch;
  }

  .session-hero,
  .background-card,
  .section-header-card,
  .round-row,
  .empty-card {
    border-radius: 20px;
  }

  .session-hero,
  .background-card,
  .section-header-card {
    padding: 18px;
  }

  .record-head {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
