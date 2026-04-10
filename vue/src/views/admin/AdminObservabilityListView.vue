<template>
  <section class="observability-hub">
    <header class="hero-card">
      <div class="hero-copy">
        <span class="hero-kicker">Conversation Observatory</span>
        <h2>先选会话，再进入整页观测详情</h2>
        <p>
          列表页只负责定位问题会话，详情页再按单轮执行阶段展开。这样不会把大量轨迹信息压缩在同一块区域里，
          也更适合教学演示和排障复盘。
        </p>
      </div>

      <div class="hero-actions">
        <button class="primary-button" type="button" :disabled="loadingSessions" @click="loadSessions">
          {{ loadingSessions ? '正在刷新...' : '刷新会话列表' }}
        </button>
      </div>

      <div class="hero-metrics">
        <article v-for="item in summaryStats" :key="item.label" class="metric-card">
          <span class="metric-label">{{ item.label }}</span>
          <strong>{{ item.value }}</strong>
          <p>{{ item.description }}</p>
        </article>
      </div>
    </header>

    <section class="toolbar-card">
      <label class="toolbar-field search-field">
        <span>搜索会话</span>
        <input
          v-model.trim="keyword"
          type="text"
          placeholder="按会话ID、文档名、问题或回答筛选"
          @keydown.enter.prevent="applyFilters"
        />
      </label>

      <label class="toolbar-field">
        <span>提问模式</span>
        <select v-model="modeFilter">
          <option value="ALL">全部模式</option>
          <option value="DOCUMENT">当前文档问答</option>
          <option value="OPEN_CHAT">开放式提问</option>
        </select>
      </label>

      <label class="toolbar-field">
        <span>最近状态</span>
        <select v-model="statusFilter">
          <option value="ALL">全部状态</option>
          <option value="RUNNING">进行中</option>
          <option value="COMPLETED">已完成</option>
          <option value="FAILED">失败</option>
          <option value="STOPPED">已停止</option>
        </select>
      </label>

      <div class="toolbar-buttons">
        <button class="ghost-button" type="button" :disabled="loadingSessions" @click="resetFilters">
          重置筛选
        </button>
        <button class="primary-button inline-primary" type="button" :disabled="loadingSessions" @click="applyFilters">
          应用筛选
        </button>
      </div>
    </section>

    <div v-if="pageError" class="inline-notice error-notice">{{ pageError }}</div>
    <div v-if="loadingSessions" class="empty-card">正在加载会话列表...</div>
    <div v-else-if="!sessions.length" class="empty-card">
      当前筛选条件下没有匹配的会话。可以先清空筛选，或者去聊天页发起一轮对话再回来观察。
    </div>

    <div v-else class="session-grid">
      <article
        v-for="session in sessions"
        :key="session.conversationId"
        class="session-card"
        :class="`tone-${sessionTone(session)}`"
      >
        <RouterLink :to="detailTarget(session)" class="session-main-link">
          <div class="session-card-head">
            <div class="session-chip-row">
              <span class="mode-chip">{{ formatChatMode(session.chatMode) }}</span>
              <span v-if="session.running" class="state-chip state-running">实时执行中</span>
              <span v-else-if="session.latestTurnStatus" class="state-chip" :class="`state-${statusTone(session.latestTurnStatus)}`">
                {{ formatStatusLabel(session.latestTurnStatus) }}
              </span>
            </div>
            <span class="session-updated">{{ formatTime(session.updatedAt) }}</span>
          </div>

          <h3>{{ sessionTitle(session) }}</h3>
          <p class="session-preview">{{ sessionPreview(session) }}</p>

          <div class="session-meta">
            <span>{{ sessionMessageCount(session) }} 条消息</span>
            <span v-if="session.selectedDocumentName">{{ session.selectedDocumentName }}</span>
            <span v-else>未绑定文档</span>
          </div>

          <p v-if="session.latestTurnErrorMessage" class="session-error">
            最近一轮异常：{{ truncate(session.latestTurnErrorMessage, 88) }}
          </p>
        </RouterLink>

        <div class="session-foot">
          <code>{{ session.conversationId }}</code>
          <div class="session-foot-actions">
            <RouterLink :to="detailTarget(session)" class="detail-link">
              查看整页详情
            </RouterLink>
            <RouterLink
              v-if="session.latestExchangeId"
              :to="exchangeTarget(session)"
              class="detail-link subtle-link"
            >
              {{ exchangeLinkLabel(session) }}
            </RouterLink>
          </div>
        </div>
      </article>
    </div>

    <section v-if="!loadingSessions && totalPagesCount > 0" class="pagination-card">
      <div class="pagination-summary">
        <strong>第 {{ pageNo }} / {{ totalPages }} 页</strong>
        <span>共 {{ totalSize }} 条会话记录，当前每页 {{ pageSize }} 条</span>
      </div>

      <div class="pagination-actions">
        <label class="page-size-field">
          <span>每页条数</span>
          <select v-model="pageSize" @change="handlePageSizeChange">
            <option value="12">12</option>
            <option value="24">24</option>
            <option value="36">36</option>
            <option value="48">48</option>
          </select>
        </label>

        <div class="page-button-row">
          <button class="page-button" type="button" :disabled="!canPrev" @click="goPrevPage">上一页</button>
          <button
            v-for="(item, index) in paginationItems"
            :key="`page-${item}-${index}`"
            class="page-button"
            :class="{ active: item === pageNo, gap: item === '...'}"
            type="button"
            :disabled="item === '...'"
            @click="typeof item === 'string' && item !== '...' ? goPage(item) : null"
          >
            {{ item }}
          </button>
          <button class="page-button" type="button" :disabled="!canNext" @click="goNextPage">下一页</button>
        </div>
      </div>
    </section>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { chatApi } from '../../api/api'
import {
  formatChatMode,
  formatStatusLabel,
  formatTime,
  normalizeError,
  sessionMessageCount,
  sessionPreview,
  sessionTitle,
  statusTone,
  truncate
} from './observabilityHelpers'

const sessions = ref([])
const loadingSessions = ref(false)
const pageError = ref('')
const keyword = ref('')
const modeFilter = ref('ALL')
const statusFilter = ref('ALL')
const pageNo = ref('1')
const pageSize = ref('12')
const totalSize = ref('0')
const totalPages = ref('0')

const currentPageNumber = computed(() => Number(pageNo.value || '1') || 1)
const totalPagesCount = computed(() => Number(totalPages.value || '0') || 0)
const canPrev = computed(() => currentPageNumber.value > 1)
const canNext = computed(() => totalPagesCount.value > 0 && currentPageNumber.value < totalPagesCount.value)

const summaryStats = computed(() => {
  const total = totalSize.value
  const running = sessions.value.filter((item) => item.running).length
  const documentMode = sessions.value.filter((item) => item.chatMode === 'DOCUMENT').length
  const failed = sessions.value.filter((item) => item.latestTurnStatus === 'FAILED').length

  return [
    {
      label: '会话总数',
      value: total,
      description: '后台当前可回看的全部业务会话数'
    },
    {
      label: '本页运行中',
      value: running,
      description: '正在生成中的会话会在详情页实时轮询'
    },
    {
      label: '本页文档问答',
      value: documentMode,
      description: '当前页里走 RAG 编排链路的会话规模'
    },
    {
      label: '本页最近失败',
      value: failed,
      description: '优先进入这些会话可更快定位问题'
    }
  ]
})

const paginationItems = computed(() => {
  const total = totalPagesCount.value
  const current = currentPageNumber.value
  if (total <= 7) {
    return Array.from({ length: total }, (_, index) => String(index + 1))
  }
  if (current <= 4) {
    return ['1', '2', '3', '4', '5', '...', String(total)]
  }
  if (current >= total - 3) {
    return ['1', '...', String(total - 4), String(total - 3), String(total - 2), String(total - 1), String(total)]
  }
  return ['1', '...', String(current - 1), String(current), String(current + 1), '...', String(total)]
})

async function loadSessions(options = {}) {
  loadingSessions.value = true
  pageError.value = ''

  try {
    const page = await chatApi.listSessionsPage({
      keyword: options.keyword ?? keyword.value,
      chatMode: options.chatMode ?? modeFilter.value,
      turnStatus: options.turnStatus ?? statusFilter.value,
      pageNo: options.pageNo || pageNo.value,
      pageSize: options.pageSize || pageSize.value
    })
    sessions.value = page.sessions || []
    pageNo.value = page.pageNo || '1'
    pageSize.value = page.pageSize || pageSize.value
    totalSize.value = page.totalSize || '0'
    totalPages.value = page.totalPages || '0'
  } catch (error) {
    pageError.value = normalizeError(error, '加载会话列表失败')
  } finally {
    loadingSessions.value = false
  }
}

function sessionTone(session) {
  if (session.running) {
    return 'running'
  }
  return statusTone(session.latestTurnStatus)
}

function goPage(nextPageNo) {
  if (!nextPageNo || nextPageNo === pageNo.value || loadingSessions.value) {
    return
  }
  loadSessions({
    keyword: keyword.value,
    chatMode: modeFilter.value,
    turnStatus: statusFilter.value,
    pageNo: String(nextPageNo),
    pageSize: pageSize.value
  })
}

function goPrevPage() {
  if (!canPrev.value) {
    return
  }
  goPage(String(currentPageNumber.value - 1))
}

function goNextPage() {
  if (!canNext.value) {
    return
  }
  goPage(String(currentPageNumber.value + 1))
}

function handlePageSizeChange() {
  loadSessions({
    keyword: keyword.value,
    chatMode: modeFilter.value,
    turnStatus: statusFilter.value,
    pageNo: '1',
    pageSize: pageSize.value
  })
}

function applyFilters() {
  loadSessions({
    keyword: keyword.value,
    chatMode: modeFilter.value,
    turnStatus: statusFilter.value,
    pageNo: '1',
    pageSize: pageSize.value
  })
}

function resetFilters() {
  keyword.value = ''
  modeFilter.value = 'ALL'
  statusFilter.value = 'ALL'
  loadSessions({
    keyword: '',
    chatMode: 'ALL',
    turnStatus: 'ALL',
    pageNo: '1',
    pageSize: pageSize.value
  })
}

function detailTarget(session) {
  return {
    name: 'AdminObservabilityDetail',
    params: {
      conversationId: session.conversationId
    }
  }
}

function exchangeTarget(session) {
  return {
    name: 'AdminObservabilityDetail',
    params: {
      conversationId: session.conversationId
    },
    query: {
      exchangeId: String(session.latestExchangeId)
    }
  }
}

function exchangeLinkLabel(session) {
  if (session.running) {
    return '直达当前轮次'
  }
  if (session.latestTurnStatus === 'FAILED' || session.latestTurnStatus === 'STOPPED') {
    return '直达异常轮次'
  }
  return '直达最近轮次'
}

onMounted(loadSessions)
</script>

<style scoped>
.observability-hub {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.hero-card,
.toolbar-card,
.session-card,
.empty-card,
.pagination-card {
  position: relative;
  overflow: hidden;
  border-radius: 24px;
  border: 1px solid rgba(23, 48, 79, 0.08);
  background: #ffffff;
  box-shadow: 0 20px 50px rgba(15, 23, 42, 0.06);
}

.hero-card {
  padding: 28px;
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) auto;
  gap: 20px;
  background:
    radial-gradient(circle at top right, rgba(37, 87, 214, 0.16), transparent 28%),
    radial-gradient(circle at left bottom, rgba(13, 124, 124, 0.12), transparent 32%),
    linear-gradient(180deg, rgba(255, 255, 255, 0.98), rgba(245, 248, 252, 0.98));
}

.hero-card::after {
  content: '';
  position: absolute;
  inset: auto -8% -50% auto;
  width: 280px;
  height: 280px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(37, 87, 214, 0.12), transparent 68%);
  pointer-events: none;
}

.hero-copy {
  position: relative;
  z-index: 1;
}

.hero-kicker {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  border-radius: 999px;
  background: rgba(23, 48, 79, 0.07);
  color: #17304f;
  font-size: 12px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  font-family: 'Fira Code', var(--font-sans);
}

.hero-copy h2 {
  margin: 16px 0 10px;
  font-size: clamp(28px, 3vw, 38px);
  line-height: 1.15;
  color: var(--color-text-strong);
}

.hero-copy p {
  margin: 0;
  max-width: 760px;
  color: var(--color-muted-strong);
  line-height: 1.75;
}

.hero-actions {
  position: relative;
  z-index: 1;
  display: flex;
  justify-content: flex-end;
  align-items: flex-start;
}

.primary-button {
  border: none;
  border-radius: 14px;
  padding: 12px 18px;
  font-weight: 600;
  color: #fff;
  background: linear-gradient(135deg, #173da8, #2557d6);
  box-shadow: 0 12px 30px rgba(37, 87, 214, 0.24);
}

.primary-button:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 18px 34px rgba(37, 87, 214, 0.28);
}

.primary-button:disabled {
  opacity: 0.65;
}

.hero-metrics {
  position: relative;
  z-index: 1;
  grid-column: 1 / -1;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.metric-card {
  padding: 16px 18px;
  border-radius: 18px;
  border: 1px solid rgba(23, 48, 79, 0.08);
  background: rgba(255, 255, 255, 0.78);
  backdrop-filter: blur(14px);
}

.metric-label {
  display: block;
  color: var(--color-muted);
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: 0.08em;
}

.metric-card strong {
  display: block;
  margin-top: 10px;
  font-size: 30px;
  line-height: 1;
  color: var(--color-text-strong);
  font-family: 'Fira Code', var(--font-sans);
}

.metric-card p {
  margin: 10px 0 0;
  color: var(--color-muted-strong);
  line-height: 1.6;
  font-size: 13px;
}

.toolbar-card {
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) repeat(2, minmax(180px, 0.56fr)) auto;
  gap: 12px;
  padding: 18px;
}

.toolbar-field {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.toolbar-buttons,
.session-foot-actions {
  display: flex;
  gap: 10px;
}

.toolbar-buttons {
  align-items: flex-end;
}

.ghost-button {
  border: 1px solid var(--color-border);
  border-radius: 14px;
  padding: 12px 16px;
  font-weight: 600;
  color: var(--color-text);
  background: #fff;
}

.ghost-button:hover:not(:disabled) {
  border-color: rgba(37, 87, 214, 0.24);
  background: rgba(37, 87, 214, 0.06);
}

.inline-primary {
  min-height: 46px;
}

.toolbar-field span {
  color: var(--color-muted);
  font-size: 12px;
  letter-spacing: 0.04em;
  text-transform: uppercase;
}

.toolbar-field input,
.toolbar-field select {
  width: 100%;
  border-radius: 14px;
  border: 1px solid var(--color-border);
  background: #fff;
  color: var(--color-text);
  padding: 12px 14px;
}

.toolbar-field input:focus,
.toolbar-field select:focus {
  outline: none;
  border-color: var(--color-primary);
  box-shadow: 0 0 0 4px rgba(37, 87, 214, 0.12);
}

.session-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  gap: 16px;
}

.session-card {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 0;
  transition: transform 0.22s ease, border-color 0.22s ease, box-shadow 0.22s ease, background 0.22s ease;
}

.session-card:hover {
  transform: translateY(-4px);
  border-color: rgba(37, 87, 214, 0.24);
  box-shadow: 0 24px 44px rgba(15, 23, 42, 0.1);
}

.session-card-head,
.session-chip-row,
.session-meta,
.session-foot,
.session-main-link {
  display: flex;
}

.session-card-head,
.session-foot {
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.session-chip-row,
.session-meta {
  flex-wrap: wrap;
  gap: 8px;
}

.session-main-link {
  flex: 1;
  flex-direction: column;
  gap: 16px;
  padding: 20px 20px 0;
}

.mode-chip,
.state-chip {
  display: inline-flex;
  align-items: center;
  border-radius: 999px;
  padding: 6px 10px;
  font-size: 12px;
  font-weight: 600;
}

.mode-chip {
  background: rgba(23, 48, 79, 0.08);
  color: #17304f;
}

.state-chip {
  background: rgba(37, 87, 214, 0.08);
  color: var(--color-primary-strong);
}

.state-running {
  background: rgba(13, 124, 124, 0.12);
  color: #0d7c7c;
}

.state-completed {
  background: rgba(21, 115, 91, 0.12);
  color: var(--color-success);
}

.state-failed {
  background: rgba(179, 76, 47, 0.12);
  color: var(--color-danger);
}

.state-stopped {
  background: rgba(168, 101, 32, 0.12);
  color: var(--color-warning);
}

.session-updated,
.session-meta,
.session-preview {
  color: var(--color-muted-strong);
}

.session-updated {
  font-size: 12px;
  white-space: nowrap;
}

.session-card h3 {
  margin: 0;
  color: var(--color-text-strong);
  font-size: 20px;
  line-height: 1.3;
}

.session-preview {
  margin: 0;
  line-height: 1.75;
  min-height: 52px;
}

.session-meta {
  font-size: 13px;
}

.session-foot {
  margin-top: auto;
  padding: 14px 20px 20px;
  border-top: 1px solid rgba(23, 48, 79, 0.08);
}

.session-foot code {
  color: #17304f;
  font-size: 12px;
  font-family: 'Fira Code', var(--font-sans);
  word-break: break-all;
}

.detail-link {
  display: inline-flex;
  align-items: center;
  color: var(--color-primary-strong);
  font-weight: 600;
}

.subtle-link {
  color: var(--color-muted-strong);
}

.session-error {
  margin: 0;
  padding: 12px 14px;
  border-radius: 14px;
  background: rgba(179, 76, 47, 0.08);
  color: var(--color-danger);
  line-height: 1.65;
}

.tone-running {
  background: linear-gradient(180deg, rgba(255, 255, 255, 1), rgba(13, 124, 124, 0.04));
}

.tone-failed {
  background: linear-gradient(180deg, rgba(255, 255, 255, 1), rgba(179, 76, 47, 0.04));
}

.tone-completed {
  background: linear-gradient(180deg, rgba(255, 255, 255, 1), rgba(37, 87, 214, 0.03));
}

.empty-card {
  padding: 48px 24px;
  text-align: center;
  color: var(--color-muted);
  line-height: 1.8;
}

.pagination-card,
.pagination-actions,
.page-button-row {
  display: flex;
}

.pagination-card {
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 18px;
}

.pagination-summary {
  display: flex;
  flex-direction: column;
  gap: 6px;
  color: var(--color-muted-strong);
}

.pagination-summary strong {
  color: var(--color-text-strong);
}

.pagination-actions {
  align-items: center;
  gap: 14px;
}

.page-size-field {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--color-muted);
  font-size: 13px;
}

.page-size-field select {
  border-radius: 12px;
  border: 1px solid var(--color-border);
  background: #fff;
  color: var(--color-text);
  padding: 8px 10px;
}

.page-button-row {
  flex-wrap: wrap;
  gap: 8px;
}

.page-button {
  min-width: 42px;
  border: 1px solid var(--color-border);
  border-radius: 12px;
  padding: 9px 12px;
  background: #fff;
  color: var(--color-text);
  font-weight: 600;
}

.page-button:hover:not(:disabled) {
  border-color: rgba(37, 87, 214, 0.24);
  background: rgba(37, 87, 214, 0.06);
}

.page-button.active {
  border-color: var(--color-primary);
  background: var(--color-primary-soft);
  color: var(--color-primary-strong);
}

.page-button.gap {
  background: transparent;
  border-style: dashed;
  color: var(--color-muted);
}

.inline-notice {
  padding: 14px 16px;
  border-radius: 14px;
  line-height: 1.65;
}

.error-notice {
  color: var(--color-danger);
  background: rgba(179, 76, 47, 0.08);
  border: 1px solid rgba(179, 76, 47, 0.12);
}

@media (max-width: 1180px) {
  .hero-card {
    grid-template-columns: 1fr;
  }

  .hero-actions {
    justify-content: flex-start;
  }

  .hero-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .toolbar-card {
    grid-template-columns: 1fr;
  }

  .toolbar-buttons {
    justify-content: flex-end;
  }

  .pagination-card,
  .pagination-actions {
    flex-direction: column;
    align-items: stretch;
  }

  .page-size-field {
    justify-content: space-between;
  }
}

@media (max-width: 720px) {
  .hero-card,
  .toolbar-card,
  .session-card {
    border-radius: 20px;
  }

  .hero-card {
    padding: 22px;
  }

  .hero-metrics {
    grid-template-columns: 1fr;
  }

  .session-card-head,
  .session-foot {
    flex-direction: column;
    align-items: flex-start;
  }

  .session-foot-actions {
    width: 100%;
    flex-direction: column;
  }
}
</style>
