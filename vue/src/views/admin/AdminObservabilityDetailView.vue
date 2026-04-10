<template>
  <section class="observability-detail">
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
          {{ loadingSession ? '刷新中...' : '刷新详情' }}
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

    <div v-else class="detail-shell">
      <header class="detail-hero">
        <div class="hero-copy">
          <span class="hero-kicker">Observation Detail</span>
          <h2>{{ sessionTitle(activeSession) }}</h2>
          <p>{{ sessionPreview(activeSession) }}</p>
        </div>

        <div class="hero-chip-row">
          <span class="hero-chip hero-chip-primary">{{ formatChatMode(activeSession.chatMode) }}</span>
          <span v-if="activeSession.running" class="hero-chip hero-chip-running">当前有实时执行</span>
          <span v-else-if="activeSession.latestTurnStatus" class="hero-chip" :class="`hero-chip-${statusTone(activeSession.latestTurnStatus)}`">
            最近一轮{{ formatStatusLabel(activeSession.latestTurnStatus) }}
          </span>
          <span v-if="activeSession.selectedDocumentName" class="hero-chip hero-chip-neutral">
            {{ activeSession.selectedDocumentName }}
          </span>
        </div>

        <div class="hero-metric-grid">
          <article v-for="item in heroMetrics" :key="item.label" class="hero-metric-card">
            <span>{{ item.label }}</span>
            <strong>{{ item.value }}</strong>
          </article>
        </div>
      </header>

      <div class="detail-grid">
        <aside class="detail-rail">
          <section class="rail-card">
            <div class="rail-card-head">
              <div>
                <span class="rail-kicker">Session</span>
                <h3>会话概况</h3>
              </div>
              <CommandLineIcon class="rail-icon" />
            </div>

            <div class="meta-stack">
              <div class="meta-row">
                <span>会话ID</span>
                <code>{{ activeSession.conversationId }}</code>
              </div>
              <div class="meta-row">
                <span>最近更新时间</span>
                <strong>{{ formatDateTime(activeSession.updatedAt) }}</strong>
              </div>
              <div class="meta-row">
                <span>Checkpoint / 消息数</span>
                <strong>{{ activeSession.checkpointCount || 0 }} / {{ activeSession.messageCount || 0 }}</strong>
              </div>
            </div>
          </section>

          <section class="rail-card memory-card">
            <div class="rail-card-head">
              <div>
                <span class="rail-kicker">Memory</span>
                <h3>长期摘要快照</h3>
              </div>
              <DocumentTextIcon class="rail-icon" />
            </div>

            <div v-if="activeSession.memorySummary?.compressionApplied" class="memory-stack">
              <div class="memory-chip-row">
                <span class="memory-chip">covered {{ activeSession.memorySummary?.coveredExchangeCount ?? 0 }}</span>
                <span class="memory-chip">version {{ activeSession.memorySummary?.summaryVersion ?? 0 }}</span>
                <span class="memory-chip">compress {{ activeSession.memorySummary?.compressionCount ?? 0 }}</span>
              </div>
              <pre class="code-block">{{ activeSession.memorySummary?.summaryText || '无' }}</pre>

              <div v-if="activeSession.memorySummary?.summaryPayload?.retrievalHints?.length" class="support-block">
                <span class="support-label">检索提示关键词</span>
                <div class="mini-chip-row">
                  <span
                    v-for="(item, index) in activeSession.memorySummary.summaryPayload.retrievalHints"
                    :key="`memory-hint-${index}`"
                    class="mini-chip"
                  >
                    {{ item }}
                  </span>
                </div>
              </div>

              <div v-if="activeSession.memorySummary?.summaryPayload?.pendingQuestions?.length" class="support-block">
                <span class="support-label">待跟进问题</span>
                <ul class="plain-list">
                  <li
                    v-for="(item, index) in activeSession.memorySummary.summaryPayload.pendingQuestions"
                    :key="`memory-question-${index}`"
                  >
                    {{ item }}
                  </li>
                </ul>
              </div>
            </div>

            <div v-else class="memory-empty">
              当前会话还没有形成长期摘要。常见原因是有效轮次还不够，或者摘要预热尚未完成。
            </div>
          </section>

          <section class="rail-card exchange-card">
            <div class="rail-card-head">
              <div>
                <span class="rail-kicker">Exchange Rail</span>
                <h3>单轮导航</h3>
              </div>
              <CpuChipIcon class="rail-icon" />
            </div>

            <div v-if="!assistantExchanges.length" class="memory-empty">
              当前会话还没有助手轮次，无法展示执行观测。
            </div>

            <div v-else class="exchange-list">
              <button
                v-for="exchange in assistantExchanges"
                :key="exchange.exchangeId"
                class="exchange-item"
                :class="{ active: String(exchange.exchangeId) === selectedExchangeId }"
                type="button"
                @click="selectExchange(exchange.exchangeId)"
              >
                <div class="exchange-item-head">
                  <strong>{{ truncate(exchange.question || '未记录问题', 26) }}</strong>
                  <span class="exchange-status" :class="`status-${statusTone(exchange.status)}`">
                    {{ formatStatusLabel(exchange.status) }}
                  </span>
                </div>
                <p>{{ formatDateTime(exchange.editTime || exchange.createTime) }}</p>
              </button>
            </div>
          </section>
        </aside>

        <div class="detail-main">
          <div v-if="!activeExchange" class="empty-card">请选择一条轮次查看执行阶段详情。</div>

          <template v-else>
            <section class="exchange-hero">
              <div class="exchange-hero-copy">
                <span class="hero-kicker">Active Exchange</span>
                <h3>{{ activeExchange.question || '未记录问题' }}</h3>
                <p>
                  这块详情会按“请求入口、前置编排、执行路径、Prompt 与生成、结果与诊断”五段来展开，
                  让每一块内容都有明确归属。
                </p>
              </div>

              <div class="exchange-hero-chips">
                <span class="hero-chip" :class="`hero-chip-${statusTone(activeExchange.status)}`">
                  {{ formatStatusLabel(activeExchange.status) }}
                </span>
                <span v-if="activeExchange.debugTrace?.executionMode" class="hero-chip hero-chip-neutral">
                  {{ activeExchange.debugTrace.executionMode }}
                </span>
                <span class="hero-chip hero-chip-neutral">
                  exchange {{ activeExchange.exchangeId }}
                </span>
              </div>

              <div class="hero-metric-grid compact-grid">
                <article v-for="item in activeExchangeMetrics" :key="item.label" class="hero-metric-card">
                  <span>{{ item.label }}</span>
                  <strong>{{ item.value }}</strong>
                </article>
              </div>
            </section>

            <section class="stage-list">
              <article
                v-for="stage in exchangeStages"
                :key="stage.key"
                class="stage-card"
                :class="`tone-${stage.tone || 'neutral'}`"
              >
                <div class="stage-head">
                  <div>
                    <span class="stage-kicker">{{ stage.key }}</span>
                    <h4>{{ stage.title }}</h4>
                    <p>{{ stage.subtitle }}</p>
                  </div>

                  <div v-if="stage.chips?.length" class="stage-chip-row">
                    <span
                      v-for="item in stage.chips"
                      :key="`${stage.key}-${item.label}-${item.value}`"
                      class="stage-chip"
                      :class="`stage-chip-${item.tone || 'neutral'}`"
                    >
                      <small>{{ item.label }}</small>
                      <strong>{{ item.value }}</strong>
                    </span>
                  </div>
                </div>

                <div v-if="stage.metrics?.length" class="metric-row">
                  <div v-for="item in stage.metrics" :key="`${stage.key}-${item.label}`" class="metric-pill">
                    <span>{{ item.label }}</span>
                    <strong :class="{ mono: item.mono }">{{ item.value }}</strong>
                  </div>
                </div>

                <div v-if="stage.textBlocks?.length" class="text-grid">
                  <div v-for="item in stage.textBlocks" :key="`${stage.key}-${item.label}`" class="text-card">
                    <span class="block-label">{{ item.label }}</span>
                    <pre v-if="item.code" class="code-block">{{ item.value }}</pre>
                    <p v-else>{{ item.value }}</p>
                  </div>
                </div>

                <div v-if="stage.listBlocks?.length" class="list-grid">
                  <section v-for="item in stage.listBlocks" :key="`${stage.key}-${item.label}`" class="list-card">
                    <span class="block-label">{{ item.label }}</span>
                    <ol v-if="item.ordered" class="plain-list ordered-list">
                      <li v-for="(entry, index) in item.items" :key="`${stage.key}-${item.label}-${index}`">
                        {{ entry }}
                      </li>
                    </ol>
                    <ul v-else class="plain-list">
                      <li v-for="(entry, index) in item.items" :key="`${stage.key}-${item.label}-${index}`">
                        {{ entry }}
                      </li>
                    </ul>
                  </section>
                </div>

                <div v-if="stage.toolTraces?.length" class="tool-grid">
                  <article v-for="(item, index) in stage.toolTraces" :key="`${stage.key}-tool-${index}`" class="tool-card">
                    <div class="tool-card-head">
                      <strong>{{ item.toolName || '未知工具' }}</strong>
                      <span class="exchange-status" :class="`status-${statusTone(item.status)}`">
                        {{ formatStatusLabel(item.status) }}
                      </span>
                    </div>
                    <p v-if="item.inputSummary"><span>输入摘要</span>{{ item.inputSummary }}</p>
                    <p v-if="item.effectiveInput"><span>有效输入</span>{{ item.effectiveInput }}</p>
                    <p v-if="item.outputSummary"><span>结果摘要</span>{{ item.outputSummary }}</p>
                    <p v-if="item.topic"><span>工具主题</span>{{ item.topic }}</p>
                    <p v-if="item.referenceCount != null"><span>来源数</span>{{ item.referenceCount }}</p>
                    <p v-if="item.durationMs != null"><span>耗时</span>{{ item.durationMs }} ms</p>
                    <p v-if="item.errorMessage" class="tool-error"><span>异常</span>{{ item.errorMessage }}</p>
                  </article>
                </div>

                <div v-if="stage.references?.length" class="reference-list">
                  <article v-for="(item, index) in stage.references" :key="`${stage.key}-ref-${index}`" class="reference-card">
                    <strong>[{{ item.referenceId || index + 1 }}] {{ item.documentName || item.title || '未命名引用' }}</strong>
                    <p>
                      {{ item.sectionPath || item.url || '未识别来源' }}
                      <span v-if="item.channel"> | {{ item.channel }}</span>
                    </p>
                    <p>{{ item.snippet || '无摘要' }}</p>
                  </article>
                </div>
              </article>
            </section>
          </template>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import {
  ArrowLeftIcon,
  ArrowPathIcon,
  CommandLineIcon,
  CpuChipIcon,
  DocumentTextIcon,
  SparklesIcon
} from '@heroicons/vue/24/outline'
import { chatApi } from '../../api/api'
import {
  buildExchangeStages,
  formatChatMode,
  formatDateTime,
  formatStatusLabel,
  normalizeError,
  resolvePreferredExchange,
  sessionPreview,
  sessionTitle,
  statusTone,
  truncate,
  listAssistantExchanges
} from './observabilityHelpers'

const route = useRoute()
const router = useRouter()

const loadingSession = ref(false)
const pollingSession = ref(false)
const activeSession = ref(null)
const pageError = ref('')
const rebuildingSummary = ref(false)
const selectedExchangeId = ref('')

const POLL_INTERVAL_MS = 2500
let pollTimer = 0
let sessionRequestInFlight = false

const conversationId = computed(() => String(route.params.conversationId || ''))

const assistantExchanges = computed(() => listAssistantExchanges(activeSession.value))

const activeExchange = computed(() => {
  return assistantExchanges.value.find((item) => String(item.exchangeId) === selectedExchangeId.value) || null
})

const exchangeStages = computed(() => buildExchangeStages(activeSession.value, activeExchange.value))

const heroMetrics = computed(() => {
  if (!activeSession.value) {
    return []
  }
  return [
    {
      label: '会话消息数',
      value: activeSession.value.messageCount || 0
    },
    {
      label: 'Checkpoint',
      value: activeSession.value.checkpointCount || 0
    },
    {
      label: '最近轮次',
      value: activeSession.value.latestExchangeId || '--'
    },
    {
      label: '最近更新时间',
      value: formatDateTime(activeSession.value.updatedAt)
    }
  ]
})

const activeExchangeMetrics = computed(() => {
  if (!activeExchange.value) {
    return []
  }
  return [
    {
      label: '首包耗时',
      value: activeExchange.value.firstResponseTimeMs ? `${activeExchange.value.firstResponseTimeMs} ms` : '无'
    },
    {
      label: '总耗时',
      value: activeExchange.value.totalResponseTimeMs ? `${activeExchange.value.totalResponseTimeMs} ms` : '无'
    },
    {
      label: '引用数',
      value: activeExchange.value.references?.length || 0
    },
    {
      label: '推荐问题',
      value: activeExchange.value.recommendations?.length || 0
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
    syncSelectedExchange(route.query.exchangeId)
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

function syncSelectedExchange(preferredExchangeId) {
  const nextExchangeId = resolvePreferredExchange(assistantExchanges.value, preferredExchangeId)
  if (!nextExchangeId) {
    selectedExchangeId.value = ''
    return
  }
  selectedExchangeId.value = nextExchangeId
}

function selectExchange(exchangeId) {
  const normalizedExchangeId = String(exchangeId || '')
  if (!normalizedExchangeId) {
    return
  }
  selectedExchangeId.value = normalizedExchangeId
  router.replace({
    query: {
      ...route.query,
      exchangeId: normalizedExchangeId
    }
  })
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

watch(conversationId, () => {
  selectedExchangeId.value = ''
  activeSession.value = null
  loadSession()
}, { immediate: true })

watch(() => route.query.exchangeId, (value) => {
  syncSelectedExchange(value)
})

onMounted(() => {
  schedulePolling()
})

onUnmounted(() => {
  clearTimeout(pollTimer)
})
</script>

<style scoped>
.observability-detail {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.detail-toolbar,
.toolbar-actions,
.hero-chip-row,
.hero-metric-grid,
.detail-grid,
.exchange-hero-chips,
.stage-head,
.stage-chip-row,
.metric-row,
.tool-grid,
.reference-list,
.mini-chip-row,
.exchange-item-head,
.memory-chip-row {
  display: flex;
}

.detail-toolbar,
.stage-head,
.exchange-item-head {
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.toolbar-actions,
.hero-chip-row,
.exchange-hero-chips,
.stage-chip-row,
.memory-chip-row,
.mini-chip-row {
  flex-wrap: wrap;
  gap: 10px;
}

.tool-icon,
.rail-icon {
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
.mini-chip,
.exchange-status,
.stage-chip {
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

.detail-shell {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.detail-hero,
.rail-card,
.exchange-hero,
.stage-card,
.empty-card {
  position: relative;
  overflow: hidden;
  border-radius: 24px;
  border: 1px solid rgba(23, 48, 79, 0.08);
  background: #fff;
  box-shadow: 0 20px 50px rgba(15, 23, 42, 0.06);
}

.detail-hero {
  padding: 30px;
  background:
    radial-gradient(circle at top right, rgba(37, 87, 214, 0.14), transparent 28%),
    radial-gradient(circle at left bottom, rgba(13, 124, 124, 0.1), transparent 34%),
    linear-gradient(180deg, rgba(255, 255, 255, 0.99), rgba(246, 249, 253, 0.98));
}

.hero-kicker,
.rail-kicker,
.stage-kicker,
.block-label {
  display: block;
  color: var(--color-muted);
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: 0.08em;
}

.hero-kicker,
.stage-kicker {
  font-family: 'Fira Code', var(--font-sans);
}

.detail-hero h2,
.exchange-hero h3 {
  margin: 14px 0 10px;
  color: var(--color-text-strong);
  line-height: 1.16;
}

.detail-hero h2 {
  font-size: clamp(30px, 3vw, 40px);
}

.exchange-hero h3 {
  font-size: 24px;
}

.detail-hero p,
.exchange-hero p,
.meta-row span,
.text-card p,
.tool-card p,
.reference-card p,
.memory-empty,
.empty-card,
.inline-notice {
  margin: 0;
  color: var(--color-muted-strong);
  line-height: 1.75;
}

.hero-chip-primary {
  background: rgba(37, 87, 214, 0.08);
  color: var(--color-primary-strong);
}

.hero-chip-running {
  background: rgba(13, 124, 124, 0.12);
  color: #0d7c7c;
}

.hero-chip-neutral {
  background: rgba(23, 48, 79, 0.08);
  color: #17304f;
}

.hero-chip-completed {
  background: rgba(21, 115, 91, 0.12);
  color: var(--color-success);
}

.hero-chip-failed {
  background: rgba(179, 76, 47, 0.12);
  color: var(--color-danger);
}

.hero-chip-stopped {
  background: rgba(168, 101, 32, 0.12);
  color: var(--color-warning);
}

.hero-metric-grid {
  margin-top: 24px;
  flex-wrap: wrap;
  gap: 14px;
}

.compact-grid {
  margin-top: 20px;
}

.hero-metric-card,
.metric-pill {
  min-width: 140px;
  padding: 14px 16px;
  border-radius: 18px;
  border: 1px solid rgba(23, 48, 79, 0.08);
  background: rgba(255, 255, 255, 0.82);
}

.hero-metric-card span,
.metric-pill span {
  display: block;
  font-size: 12px;
  color: var(--color-muted);
}

.hero-metric-card strong,
.metric-pill strong {
  display: block;
  margin-top: 8px;
  font-size: 22px;
  color: var(--color-text-strong);
}

.mono {
  font-family: 'Fira Code', var(--font-sans);
}

.detail-grid {
  align-items: flex-start;
  gap: 18px;
}

.detail-rail {
  width: 320px;
  flex: none;
  display: flex;
  flex-direction: column;
  gap: 18px;
  position: sticky;
  top: 24px;
}

.detail-main {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.rail-card,
.exchange-hero {
  padding: 20px;
}

.rail-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 18px;
}

.rail-card-head h3,
.stage-head h4 {
  margin: 6px 0 0;
  color: var(--color-text-strong);
}

.rail-card-head h3 {
  font-size: 20px;
}

.stage-head h4 {
  font-size: 22px;
}

.rail-icon {
  width: 20px;
  height: 20px;
  color: var(--color-primary-strong);
}

.meta-stack,
.memory-stack,
.exchange-list,
.stage-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.meta-row {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.meta-row code,
.code-block {
  font-family: 'Fira Code', var(--font-sans);
}

.meta-row code {
  font-size: 12px;
  color: #17304f;
  word-break: break-all;
}

.meta-row strong {
  color: var(--color-text-strong);
}

.memory-card {
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.98), rgba(37, 87, 214, 0.03));
}

.memory-chip,
.mini-chip {
  background: rgba(23, 48, 79, 0.08);
  color: #17304f;
}

.support-block {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.support-label {
  color: var(--color-muted);
  font-size: 12px;
  letter-spacing: 0.04em;
  text-transform: uppercase;
}

.memory-empty {
  padding: 14px;
  border-radius: 16px;
  background: rgba(245, 248, 252, 0.96);
}

.exchange-item {
  width: 100%;
  text-align: left;
  padding: 14px 16px;
  border-radius: 18px;
  border: 1px solid rgba(23, 48, 79, 0.08);
  background: rgba(255, 255, 255, 0.92);
}

.exchange-item:hover {
  border-color: rgba(37, 87, 214, 0.22);
  box-shadow: 0 16px 28px rgba(15, 23, 42, 0.08);
}

.exchange-item.active {
  border-color: rgba(37, 87, 214, 0.3);
  background: linear-gradient(135deg, rgba(37, 87, 214, 0.1), rgba(13, 124, 124, 0.06));
}

.exchange-item strong {
  color: var(--color-text-strong);
}

.exchange-item p {
  margin: 8px 0 0;
  color: var(--color-muted);
  font-size: 13px;
}

.exchange-status {
  background: rgba(23, 48, 79, 0.08);
  color: #17304f;
}

.status-running {
  background: rgba(13, 124, 124, 0.12);
  color: #0d7c7c;
}

.status-completed {
  background: rgba(21, 115, 91, 0.12);
  color: var(--color-success);
}

.status-failed {
  background: rgba(179, 76, 47, 0.12);
  color: var(--color-danger);
}

.status-stopped {
  background: rgba(168, 101, 32, 0.12);
  color: var(--color-warning);
}

.stage-list {
  gap: 18px;
}

.stage-card {
  padding: 22px;
}

.stage-card::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 4px;
  background: rgba(23, 48, 79, 0.12);
}

.tone-primary::before {
  background: linear-gradient(180deg, #2557d6, rgba(37, 87, 214, 0.18));
}

.tone-success::before {
  background: linear-gradient(180deg, #0d7c7c, rgba(13, 124, 124, 0.18));
}

.tone-warning::before {
  background: linear-gradient(180deg, #ef7b39, rgba(239, 123, 57, 0.18));
}

.tone-running::before {
  background: linear-gradient(180deg, #0d7c7c, rgba(13, 124, 124, 0.18));
}

.tone-failed::before {
  background: linear-gradient(180deg, #b34c2f, rgba(179, 76, 47, 0.18));
}

.stage-head p {
  margin-top: 8px;
}

.stage-chip {
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  border-radius: 18px;
  padding: 10px 12px;
}

.stage-chip small {
  color: inherit;
  opacity: 0.76;
}

.stage-chip strong {
  font-size: 13px;
}

.stage-chip-neutral {
  background: rgba(23, 48, 79, 0.08);
  color: #17304f;
}

.stage-chip-primary {
  background: rgba(37, 87, 214, 0.08);
  color: var(--color-primary-strong);
}

.stage-chip-success {
  background: rgba(13, 124, 124, 0.12);
  color: #0d7c7c;
}

.stage-chip-warning {
  background: rgba(239, 123, 57, 0.12);
  color: #c2410c;
}

.stage-chip-running {
  background: rgba(13, 124, 124, 0.12);
  color: #0d7c7c;
}

.stage-chip-completed {
  background: rgba(21, 115, 91, 0.12);
  color: var(--color-success);
}

.stage-chip-failed {
  background: rgba(179, 76, 47, 0.12);
  color: var(--color-danger);
}

.stage-chip-stopped {
  background: rgba(168, 101, 32, 0.12);
  color: var(--color-warning);
}

.metric-row {
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 18px;
}

.text-grid,
.list-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
  margin-top: 18px;
}

.text-card,
.list-card,
.tool-card,
.reference-card {
  padding: 16px;
  border-radius: 18px;
  border: 1px solid rgba(23, 48, 79, 0.08);
  background: rgba(255, 255, 255, 0.9);
}

.text-card p {
  margin-top: 10px;
  white-space: pre-wrap;
}

.code-block {
  margin: 10px 0 0;
  padding: 14px;
  border-radius: 14px;
  background: rgba(15, 23, 42, 0.06);
  color: var(--color-text);
  white-space: pre-wrap;
  line-height: 1.7;
}

.plain-list {
  margin: 10px 0 0;
  padding-left: 18px;
  color: var(--color-text);
  line-height: 1.8;
}

.ordered-list {
  list-style: decimal;
}

.tool-grid {
  flex-wrap: wrap;
  gap: 14px;
  margin-top: 18px;
}

.tool-card {
  flex: 1 1 280px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.tool-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.tool-card strong,
.reference-card strong {
  color: var(--color-text-strong);
}

.tool-card p span {
  display: block;
  color: var(--color-muted);
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.tool-error {
  color: var(--color-danger);
}

.reference-list {
  flex-direction: column;
  gap: 12px;
  margin-top: 18px;
}

.reference-card p {
  margin-top: 8px;
}

.empty-card {
  padding: 48px 24px;
  text-align: center;
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

@media (max-width: 1280px) {
  .detail-grid {
    flex-direction: column;
  }

  .detail-rail {
    width: 100%;
    position: static;
  }

  .text-grid,
  .list-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 820px) {
  .detail-toolbar {
    flex-direction: column;
    align-items: stretch;
  }

  .toolbar-actions {
    justify-content: space-between;
  }

  .detail-hero,
  .rail-card,
  .exchange-hero,
  .stage-card,
  .empty-card {
    border-radius: 20px;
  }

  .detail-hero,
  .rail-card,
  .exchange-hero,
  .stage-card {
    padding: 18px;
  }

  .stage-head {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
