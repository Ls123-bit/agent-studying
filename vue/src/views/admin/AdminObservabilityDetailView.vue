<template>
  <section class="round-detail-page">
    <div class="detail-toolbar">
      <RouterLink
        :to="{ name: 'AdminObservabilitySession', params: { conversationId } }"
        class="back-link"
      >
        <ArrowLeftIcon class="tool-icon" />
        返回会话轮次列表
      </RouterLink>

      <div class="toolbar-actions">
        <button class="ghost-button" type="button" :disabled="loadingPage" @click="loadPage()">
          <ArrowPathIcon class="tool-icon" />
          {{ loadingPage ? '刷新中...' : '刷新这一轮详情' }}
        </button>
      </div>
    </div>

    <div v-if="pageError" class="inline-notice error-notice">{{ pageError }}</div>
    <div v-if="loadingPage && !activeExchangeDetail" class="empty-card">正在加载轮次详情...</div>
    <div v-else-if="!activeExchange" class="empty-card">没有找到这条轮次，请返回会话页重新选择。</div>

    <template v-else>
      <header class="round-hero">
        <div class="hero-copy">
          <span class="hero-kicker">Round Detail</span>
          <h2>{{ activeExchange.question || '未记录问题' }}</h2>
          <p>{{ currentExchangeNarrative }}</p>
        </div>

        <div class="hero-chip-row">
          <span class="hero-chip" :class="`hero-chip-${statusTone(activeExchange.status)}`">
            {{ formatStatusLabel(activeExchange.status) }}
          </span>
          <span class="hero-chip hero-chip-primary">{{ formatChatMode(activeSession?.chatMode) }}</span>
          <span v-if="activeExchange.debugTrace?.executionMode" class="hero-chip hero-chip-neutral">
            {{ formatExecutionMode(activeExchange.debugTrace.executionMode) }}
          </span>
          <span class="hero-chip hero-chip-neutral">会话 {{ conversationId }}</span>
          <span class="hero-chip hero-chip-neutral">轮次 {{ exchangeId }}</span>
        </div>

        <div class="hero-context">
          <div class="context-item">
            <span>文档范围</span>
            <strong>{{ activeSession?.selectedDocumentName || '未绑定文档' }}</strong>
          </div>
          <div class="context-item">
            <span>执行时间</span>
            <strong>{{ formatDateTime(activeExchange.editTime || activeExchange.createTime) }}</strong>
          </div>
          <div class="context-item">
            <span>总耗时</span>
            <strong>{{ activeExchange.totalResponseTimeMs ? `${activeExchange.totalResponseTimeMs} ms` : '无' }}</strong>
          </div>
          <div class="context-item">
            <span>引用 / 推荐</span>
            <strong>{{ activeExchange.references?.length || 0 }} / {{ activeExchange.recommendations?.length || 0 }}</strong>
          </div>
          <div class="context-item">
            <span>总 Token / 成本</span>
            <strong>{{ totalTokenCount }} / {{ totalCostText }}</strong>
          </div>
        </div>
      </header>

      <section class="timeline-section">
        <div class="section-head">
          <div>
            <span class="section-kicker">Trace Timeline</span>
            <h3>执行阶段时间线</h3>
            <p>先浏览整个执行顺序，再点击某个阶段进入子页面查看这个阶段的详细过程。</p>
          </div>
        </div>

        <div v-if="!stageTraces.length" class="empty-card compact-empty">
          当前轮次还没有可展示的阶段轨迹。
        </div>

        <div v-else class="timeline-list">
          <button
            v-for="trace in stageTraces"
            :key="trace.stageId"
            class="timeline-item"
            :class="{ active: String(trace.stageId) === selectedTraceStageId }"
            type="button"
            @click="openTraceDetail(trace.stageId)"
          >
            <div class="timeline-main">
              <div class="timeline-row">
                <strong>{{ trace.stageName }}</strong>
                <span class="timeline-summary">{{ trace.summaryText || '当前阶段已记录。' }}</span>
              </div>
              <div class="timeline-bar-track">
                <div class="timeline-bar-fill" :style="{ width: traceBarWidth(trace) }"></div>
              </div>
              <div class="timeline-meta">
                <span>{{ formatDateTime(trace.startTime) }}</span>
                <span>{{ trace.durationMs ? `${trace.durationMs} ms` : '无耗时' }}</span>
              </div>
            </div>

            <div class="timeline-side">
              <span class="record-badge" :class="`tone-${statusTone(trace.stageState)}`">
                {{ formatStatusLabel(trace.stageState) }}
              </span>
              <span class="timeline-link">查看这个阶段</span>
            </div>
          </button>
        </div>
      </section>

      <section class="round-summary-section">
        <div class="section-head">
          <div>
            <span class="section-kicker">Round Summary</span>
            <h3>这轮回答的关键结果</h3>
            <p>这里是当前轮次的摘要信息，帮助你快速判断这轮是否正常，再决定要点开哪个阶段。</p>
          </div>
        </div>

        <div class="summary-stack">
          <article v-for="stage in exchangeStages" :key="stage.key" class="summary-row">
            <div class="summary-row-head">
              <div>
                <span class="summary-kicker">{{ stage.eyebrow || stage.key }}</span>
                <h4>{{ stage.title }}</h4>
                <p>{{ stage.subtitle }}</p>
              </div>
              <div v-if="stage.chips?.length" class="summary-chip-row">
                <span
                  v-for="item in stage.chips"
                  :key="`${stage.key}-${item.label}-${item.value}`"
                  class="record-badge"
                  :class="`tone-${item.tone || 'neutral'}`"
                >
                  {{ item.label }}：{{ item.value }}
                </span>
              </div>
            </div>

            <div v-if="stage.metrics?.length" class="summary-metrics">
              <span v-for="item in stage.metrics" :key="`${stage.key}-${item.label}`">
                {{ item.label }}：{{ item.value }}
              </span>
            </div>

            <div v-if="stage.textBlocks?.length" class="summary-pairs">
              <div v-for="item in stage.textBlocks.slice(0, 2)" :key="`${stage.key}-${item.label}`" class="summary-pair">
                <span>{{ item.label }}</span>
                <strong>{{ item.code ? truncate(item.value, 90) : item.value }}</strong>
              </div>
            </div>

            <div v-if="stage.listBlocks?.length" class="summary-list-preview">
              <span>{{ stage.listBlocks[0].label }}</span>
              <p>{{ stage.listBlocks[0].items.slice(0, 2).join('；') || '无' }}</p>
            </div>

            <div class="summary-row-foot">
              <button
                v-if="canOpenStage(stage)"
                class="inline-link"
                type="button"
                @click="openSummaryStage(stage)"
              >
                查看这个阶段的执行过程
              </button>
            </div>
          </article>
        </div>
      </section>

      <div
        v-if="traceDetailOpen && overlayInspector"
        class="trace-overlay"
        @click="closeTraceDetail"
      >
        <section class="trace-sheet" @click.stop>
          <div class="trace-sheet-head">
            <div>
              <span class="section-kicker">Trace Detail</span>
              <h3>{{ overlayInspector.title }}</h3>
              <p class="section-desc">{{ overlayInspector.summary || '这个阶段已经执行完成，下面是它记录下来的结构化细节。' }}</p>
            </div>
            <button class="sheet-close" type="button" @click="closeTraceDetail">关闭</button>
          </div>

          <div class="summary-metrics detail-metrics">
            <span>状态：{{ formatStatusLabel(overlayInspector.status) }}</span>
            <span>开始：{{ formatDateTime(overlayInspector.startTime) }}</span>
            <span>结束：{{ formatDateTime(overlayInspector.endTime) }}</span>
            <span>耗时：{{ overlayInspector.durationMs ? `${overlayInspector.durationMs} ms` : '无' }}</span>
          </div>

          <div v-if="overlayInspector.summaryItems?.length" class="detail-grid">
            <div v-for="item in overlayInspector.summaryItems" :key="`trace-item-${item.label}`" class="detail-block">
              <span>{{ item.label }}</span>
              <pre v-if="item.code" class="code-block">{{ item.value }}</pre>
              <strong v-else>{{ item.value }}</strong>
            </div>
          </div>

          <div v-if="overlayInspector.listSections?.length" class="detail-list-stack">
            <section v-for="item in overlayInspector.listSections" :key="`trace-list-${item.label}`" class="detail-list-block">
              <span>{{ item.label }}</span>
              <ol v-if="item.ordered" class="plain-list ordered-list">
                <li v-for="(entry, index) in item.items" :key="`trace-list-${item.label}-${index}`">
                  {{ entry }}
                </li>
              </ol>
              <ul v-else class="plain-list">
                <li v-for="(entry, index) in item.items" :key="`trace-list-${item.label}-${index}`">
                  {{ entry }}
                </li>
              </ul>
            </section>
          </div>

          <div v-if="overlayInspector.tableSections?.length" class="table-section-stack">
            <section v-for="table in overlayInspector.tableSections" :key="`trace-table-${table.label}`" class="table-section">
              <span class="table-label">{{ table.label }}</span>
              <div class="table-wrapper">
                <table class="detail-table">
                  <thead>
                    <tr>
                      <th v-for="column in table.columns" :key="`col-${column}`">{{ column }}</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="(row, rowIndex) in table.rows" :key="`row-${table.label}-${rowIndex}`">
                      <td v-for="(cell, cellIndex) in row.cells" :key="`cell-${rowIndex}-${cellIndex}`">{{ cell }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </section>
          </div>

          <details v-if="overlayInspector.advancedItems?.length" class="advanced-panel">
            <summary>查看这个阶段的原始快照</summary>
            <div class="detail-grid advanced-grid">
              <div v-for="item in overlayInspector.advancedItems" :key="`trace-advanced-${item.label}`" class="detail-block advanced-block">
                <span>{{ item.label }}</span>
                <pre v-if="item.code" class="code-block">{{ item.value }}</pre>
                <strong v-else>{{ item.value }}</strong>
              </div>
            </div>
          </details>
        </section>
      </div>
    </template>
  </section>
</template>

<script setup>
import { computed, ref, watch, watchEffect } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { ArrowLeftIcon, ArrowPathIcon } from '@heroicons/vue/24/outline'
import { chatApi } from '../../api/api'
import {
  buildExchangeStages,
  buildExchangeStatusNarrative,
  buildTraceStageInspector,
  buildUsageStageInspector,
  formatChatMode,
  formatDateTime,
  formatExecutionMode,
  formatRelationType,
  formatRetrievalMode,
  formatStatusLabel,
  normalizeError,
  stageHasAdvancedDetails,
  statusTone,
  truncate
} from './observabilityHelpers'

const route = useRoute()

const loadingPage = ref(false)
const activeSession = ref(null)
const activeExchangeDetail = ref(null)
const pageError = ref('')
const traceDetailOpen = ref(false)
const selectedTraceStageId = ref('')
const overlayInspector = ref(null)

const conversationId = computed(() => String(route.params.conversationId || ''))
const exchangeId = computed(() => String(route.params.exchangeId || ''))
const activeExchange = computed(() => activeExchangeDetail.value?.exchange || null)
const stageTraces = computed(() => activeExchangeDetail.value?.stageTraces || [])
const activeTraceStage = computed(() => {
  if (!selectedTraceStageId.value) {
    return stageTraces.value[0] || null
  }
  return stageTraces.value.find((item) => String(item.stageId) === selectedTraceStageId.value) || stageTraces.value[0] || null
})
const activeTraceInspector = computed(() => buildTraceStageInspector(activeTraceStage.value, activeExchange.value))
const exchangeStages = computed(() => buildExchangeStages(activeSession.value, activeExchange.value))

const currentExchangeNarrative = computed(() => {
  if (!activeExchange.value) {
    return '这页只负责看这一轮的执行链路。'
  }
  return buildExchangeStatusNarrative(activeExchange.value)
})

const totalTokenCount = computed(() => {
  const traces = activeExchange.value?.debugTrace?.modelUsageTraces || []
  return traces.reduce((sum, item) => sum + Number(item?.totalTokens || 0), 0)
})

const totalCostText = computed(() => {
  const traces = activeExchange.value?.debugTrace?.modelUsageTraces || []
  const total = traces.reduce((sum, item) => sum + Number(item?.estimatedCost || 0), 0)
  return total > 0 ? `¥ ${total.toFixed(4)}` : '无'
})

const maxTraceDuration = computed(() => {
  return stageTraces.value.reduce((max, item) => Math.max(max, Number(item?.durationMs || 0)), 0)
})

async function loadPage() {
  if (!conversationId.value || !exchangeId.value) {
    return
  }
  loadingPage.value = true
  pageError.value = ''
  try {
    const [session, exchangeDetail] = await Promise.all([
      chatApi.getSession(conversationId.value),
      chatApi.getExchangeDetail(conversationId.value, exchangeId.value)
    ])
    activeSession.value = session
    activeExchangeDetail.value = exchangeDetail
    selectedTraceStageId.value = String(exchangeDetail?.stageTraces?.[0]?.stageId || '')
  } catch (error) {
    activeSession.value = null
    activeExchangeDetail.value = null
    pageError.value = normalizeError(error, '加载轮次详情失败')
  } finally {
    loadingPage.value = false
  }
}

function openTraceDetail(stageId) {
  selectedTraceStageId.value = String(stageId || '')
  overlayInspector.value = buildTraceStageInspector(activeTraceStage.value, activeExchange.value)
  traceDetailOpen.value = true
}

function closeTraceDetail() {
  traceDetailOpen.value = false
  overlayInspector.value = null
}

function traceBarWidth(trace) {
  const duration = Number(trace?.durationMs || 0)
  const maxDuration = maxTraceDuration.value
  if (!duration || !maxDuration) {
    return '6%'
  }
  return `${Math.max((duration / maxDuration) * 100, 6)}%`
}

function findStageTrace(stageTitle) {
  if (!stageTitle) {
    return null
  }
  if (stageTitle.includes('检索执行')) {
    return stageTraces.value.find((item) => item.stageCode === 'RAG_RETRIEVE' || item.stageCode === 'REACT_AGENT') || null
  }
  if (stageTitle.includes('前置编排')) {
    return stageTraces.value.find((item) => item.stageCode === 'INTENT') || null
  }
  if (stageTitle.includes('请求入口')) {
    return stageTraces.value.find((item) => item.stageCode === 'ROUTE') || null
  }
  if (stageTitle.includes('生成回答')) {
    return stageTraces.value.find((item) => item.stageCode === 'ANSWER_GENERATE') || null
  }
  if (stageTitle.includes('模型使用')) {
    return stageTraces.value.find((item) => item.stageCode === 'ANSWER_GENERATE') || null
  }
  if (stageTitle.includes('结果与诊断')) {
    return stageTraces.value.find((item) => item.stageCode === 'FINALIZE') || null
  }
  return null
}

function canOpenStage(stage) {
  if (!stage) {
    return false
  }
  return stage.key === 'usage' || Boolean(findStageTrace(stage.title))
}

function openSummaryStage(stage) {
  if (!stage) {
    return
  }
  if (stage.key === 'usage') {
    overlayInspector.value = buildUsageStageInspector(activeExchange.value)
    traceDetailOpen.value = true
    return
  }
  const trace = findStageTrace(stage.title)
  if (!trace) {
    return
  }
  selectedTraceStageId.value = String(trace.stageId)
  overlayInspector.value = buildTraceStageInspector(trace, activeExchange.value)
  traceDetailOpen.value = true
}

watch([conversationId, exchangeId], () => {
  activeSession.value = null
  activeExchangeDetail.value = null
  traceDetailOpen.value = false
  overlayInspector.value = null
  selectedTraceStageId.value = ''
  loadPage()
}, { immediate: true })

watchEffect(() => {
  if (typeof window === 'undefined') {
    return
  }
  window.__obsDetailState = {
    loadingPage: loadingPage.value,
    hasSession: Boolean(activeSession.value),
    hasExchangeDetail: Boolean(activeExchangeDetail.value),
    conversationId: conversationId.value,
    exchangeId: exchangeId.value,
    selectedTraceStageId: selectedTraceStageId.value,
    traceDetailOpen: traceDetailOpen.value,
    overlayTitle: overlayInspector.value?.title || ''
  }
})
</script>

<style scoped>
.round-detail-page {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.detail-toolbar,
.toolbar-actions,
.hero-chip-row,
.hero-context,
.summary-metrics,
.record-badges,
.timeline-head,
.timeline-row,
.timeline-meta,
.timeline-item,
.summary-row-head,
.summary-chip-row {
  display: flex;
}

.detail-toolbar,
.timeline-head,
.summary-row-head {
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.toolbar-actions,
.hero-chip-row,
.hero-context,
.summary-metrics,
.summary-chip-row {
  flex-wrap: wrap;
  gap: 10px;
}

.tool-icon {
  width: 18px;
  height: 18px;
}

.back-link,
.ghost-button {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  border-radius: 14px;
  padding: 10px 14px;
  font-weight: 600;
}

.back-link,
.ghost-button,
.sheet-close {
  border: 1px solid var(--color-border);
  background: #fff;
  color: var(--color-text);
}

.back-link:hover,
.ghost-button:hover:not(:disabled),
.sheet-close:hover {
  border-color: rgba(37, 87, 214, 0.22);
  background: rgba(255, 255, 255, 0.92);
}

.ghost-button:disabled {
  opacity: 0.65;
}

.round-hero,
.timeline-section,
.round-summary-section,
.empty-card,
.summary-row,
.timeline-item {
  position: relative;
  overflow: hidden;
  border-radius: 24px;
  border: 1px solid rgba(23, 48, 79, 0.08);
  background: #fff;
  box-shadow: 0 20px 50px rgba(15, 23, 42, 0.06);
}

.round-hero,
.timeline-section,
.round-summary-section {
  padding: 24px;
}

.round-hero {
  background:
    radial-gradient(circle at top right, rgba(37, 87, 214, 0.14), transparent 28%),
    radial-gradient(circle at left bottom, rgba(13, 124, 124, 0.1), transparent 34%),
    linear-gradient(180deg, rgba(255, 255, 255, 0.99), rgba(246, 249, 253, 0.98));
}

.hero-kicker,
.section-kicker,
.summary-kicker {
  display: block;
  color: var(--color-muted);
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  font-family: 'Fira Code', var(--font-sans);
}

.hero-copy h2,
.section-head h3,
.trace-sheet-head h3 {
  margin: 12px 0 10px;
  color: var(--color-text-strong);
  line-height: 1.16;
}

.hero-copy h2 {
  font-size: clamp(28px, 3vw, 38px);
}

.section-head h3,
.trace-sheet-head h3 {
  font-size: 24px;
}

.hero-copy p,
.section-head p,
.timeline-summary,
.inline-notice,
.empty-card,
.summary-row p,
.summary-list-preview p {
  margin: 0;
  color: var(--color-muted-strong);
  line-height: 1.75;
}

.hero-chip,
.record-badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  border-radius: 999px;
  padding: 7px 12px;
  font-size: 12px;
  font-weight: 600;
}

.hero-chip-primary,
.record-badge.tone-primary {
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

.hero-context {
  margin-top: 22px;
}

.context-item {
  min-width: 150px;
  padding: 14px 16px;
  border-radius: 18px;
  border: 1px solid rgba(23, 48, 79, 0.08);
  background: rgba(255, 255, 255, 0.84);
}

.context-item span {
  display: block;
  font-size: 12px;
  color: var(--color-muted);
}

.context-item strong {
  display: block;
  margin-top: 8px;
  color: var(--color-text-strong);
  font-size: 22px;
}

.timeline-list,
.summary-stack,
.detail-list-stack {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.timeline-item {
  width: 100%;
  text-align: left;
  padding: 16px;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  transition: transform 0.2s ease, border-color 0.2s ease, box-shadow 0.2s ease;
}

.timeline-item:hover {
  transform: translateY(-2px);
  border-color: rgba(37, 87, 214, 0.24);
  box-shadow: 0 18px 32px rgba(15, 23, 42, 0.09);
}

.timeline-item.active {
  border-color: rgba(37, 87, 214, 0.3);
  background: linear-gradient(135deg, rgba(37, 87, 214, 0.08), rgba(13, 124, 124, 0.05));
}

.timeline-main {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.timeline-row {
  justify-content: space-between;
  gap: 12px;
}

.timeline-row strong,
.summary-row-head h4,
.detail-block strong {
  color: var(--color-text-strong);
}

.timeline-bar-track {
  height: 10px;
  border-radius: 999px;
  background: rgba(23, 48, 79, 0.08);
  overflow: hidden;
}

.timeline-bar-fill {
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(135deg, #0d7c7c, #2557d6);
}

.timeline-meta {
  gap: 12px;
  color: var(--color-muted);
  font-size: 13px;
}

.timeline-side {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 10px;
}

.timeline-link,
.inline-link {
  color: var(--color-primary-strong);
  font-weight: 600;
}

.summary-row {
  padding: 18px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.summary-row-head h4 {
  margin: 6px 0 6px;
  font-size: 20px;
}

.summary-metrics {
  color: var(--color-muted-strong);
  font-size: 13px;
}

.summary-pairs,
.detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.summary-pair,
.detail-block,
.detail-list-block {
  padding: 14px 16px;
  border-radius: 16px;
  border: 1px solid rgba(23, 48, 79, 0.08);
  background: rgba(255, 255, 255, 0.94);
}

.summary-pair span,
.detail-block span,
.detail-list-block span {
  display: block;
  color: var(--color-muted);
  font-size: 12px;
  margin-bottom: 8px;
}

.summary-list-preview {
  color: var(--color-muted-strong);
}

.summary-row-foot {
  display: flex;
  justify-content: flex-end;
}

.inline-link {
  background: transparent;
  border: none;
  padding: 0;
}

.trace-overlay {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.28);
  display: flex;
  justify-content: flex-end;
  padding: 24px;
  z-index: 60;
}

.trace-sheet {
  width: min(760px, 100%);
  height: calc(100vh - 48px);
  overflow: auto;
  border-radius: 24px;
  border: 1px solid rgba(23, 48, 79, 0.08);
  background: #ffffff;
  box-shadow: 0 28px 60px rgba(15, 23, 42, 0.18);
  padding: 20px;
}

.trace-sheet-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 18px;
}

.sheet-close {
  border-radius: 12px;
  padding: 10px 14px;
  font-weight: 600;
}

.detail-metrics {
  margin-bottom: 16px;
}

.detail-list-stack {
  margin-top: 16px;
}

.table-section-stack {
  display: flex;
  flex-direction: column;
  gap: 16px;
  margin-top: 16px;
}

.table-section {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.table-label {
  color: var(--color-muted);
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: 0.08em;
}

.table-wrapper {
  overflow: auto;
  border-radius: 16px;
  border: 1px solid rgba(23, 48, 79, 0.08);
  background: rgba(255, 255, 255, 0.94);
}

.detail-table {
  width: 100%;
  border-collapse: collapse;
  min-width: 720px;
}

.detail-table th,
.detail-table td {
  padding: 12px 14px;
  text-align: left;
  border-bottom: 1px solid rgba(23, 48, 79, 0.08);
  line-height: 1.6;
}

.detail-table th {
  background: rgba(245, 248, 252, 0.98);
  color: var(--color-muted-strong);
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: 0.06em;
}

.detail-table td {
  color: var(--color-text);
}

.detail-table tbody tr:last-child td {
  border-bottom: none;
}

.plain-list {
  margin: 0;
  padding-left: 18px;
  color: var(--color-text);
  line-height: 1.8;
}

.ordered-list {
  list-style: decimal;
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

.advanced-panel {
  margin-top: 18px;
  border-radius: 18px;
  border: 1px dashed rgba(23, 48, 79, 0.16);
  background: rgba(245, 248, 252, 0.98);
}

.advanced-panel summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 16px;
  cursor: pointer;
  font-weight: 600;
  color: var(--color-text-strong);
  list-style: none;
}

.advanced-panel summary::-webkit-details-marker {
  display: none;
}

.advanced-grid {
  padding: 0 16px 16px;
}

.advanced-block {
  background: rgba(245, 248, 252, 0.96);
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
  .summary-pairs,
  .detail-grid {
    grid-template-columns: 1fr;
  }

  .timeline-item {
    flex-direction: column;
    align-items: flex-start;
  }

  .timeline-side {
    width: 100%;
    align-items: flex-start;
  }
}

@media (max-width: 760px) {
  .detail-toolbar {
    flex-direction: column;
    align-items: stretch;
  }

  .round-hero,
  .timeline-section,
  .round-summary-section,
  .summary-row,
  .timeline-item,
  .empty-card,
  .trace-sheet {
    border-radius: 20px;
  }

  .round-hero,
  .timeline-section,
  .round-summary-section,
  .trace-sheet {
    padding: 18px;
  }

  .trace-overlay {
    padding: 12px;
  }

  .trace-sheet {
    height: calc(100vh - 24px);
  }

  .timeline-row,
  .timeline-head,
  .summary-row-head,
  .trace-sheet-head {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
