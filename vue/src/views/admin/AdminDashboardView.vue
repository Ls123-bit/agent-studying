<template>
  <section class="dashboard-page">
    <div class="hero-card">
      <div class="hero-copy">
        <p class="section-eyebrow">Operations Snapshot</p>
        <h3>把文档接入、切块策略和索引构建串成一条可观察的业务流水线</h3>
        <p class="section-description">
          后台管理台聚焦在文档进入系统后的关键节点：上传、推荐策略、策略确认、索引构建和检索验证。
        </p>
      </div>
      <div class="hero-aside">
        <span class="hero-label">Current Loop</span>
        <div class="hero-flow">
          <span>Parse</span>
          <span>Plan</span>
          <span>Index</span>
          <span>Verify</span>
        </div>
        <p class="hero-note">
          当前已接入 {{ formatCount(summary.total) }} 份文档，其中 {{ formatCount(summary.indexSuccess) }} 份已经能直接参与检索问答。
        </p>
        <button class="primary-link" type="button" @click="goDocuments">前往文档接入</button>
      </div>
    </div>

    <div class="metrics-grid">
      <article class="metric-card">
        <span>文档总数</span>
        <strong>{{ formatCount(summary.total) }}</strong>
        <p>已进入管理台的文档记录</p>
      </article>
      <article class="metric-card">
        <span>解析成功</span>
        <strong>{{ formatCount(summary.parseSuccess) }}</strong>
        <p>可进入策略确认阶段的文档</p>
      </article>
      <article class="metric-card">
        <span>策略已确认</span>
        <strong>{{ formatCount(summary.strategyConfirmed) }}</strong>
        <p>已经形成最终切块链路</p>
      </article>
      <article class="metric-card">
        <span>索引完成</span>
        <strong>{{ formatCount(summary.indexSuccess) }}</strong>
        <p>可直接参与 RAG 检索问答</p>
      </article>
    </div>

    <div class="dashboard-grid">
      <article class="panel-card">
        <div class="panel-header">
          <div>
            <p class="section-eyebrow">Recommended Flow</p>
            <h4>建议演示路径</h4>
          </div>
        </div>

        <ol class="flow-list">
          <li>
            <strong>上传文档</strong>
            <span>通过假登录后的管理台上传 PDF / Word / Markdown 文档。</span>
          </li>
          <li>
            <strong>查看系统推荐策略</strong>
            <span>根据文档结构与内容长度，观察结构切块、递归分块、语义分块和智能切块的组合。</span>
          </li>
          <li>
            <strong>确认并构建索引</strong>
            <span>在推荐结果基础上补充或移除策略，再触发异步构建索引。</span>
          </li>
          <li>
            <strong>做检索验证</strong>
            <span>在检索验证页基于 PGVector 发起问答，查看答案和命中片段引用。</span>
          </li>
        </ol>
      </article>

      <article class="panel-card">
        <div class="panel-header">
          <div>
            <p class="section-eyebrow">Recent Documents</p>
            <h4>最近接入文档</h4>
          </div>
          <button class="ghost-link" type="button" @click="loadDashboard">刷新</button>
        </div>

        <div v-if="loading" class="empty-block">正在加载后台概览...</div>
        <div v-else-if="!documents.length" class="empty-block">当前还没有文档，先去“文档接入”页面上传一份资料。</div>

        <div v-else class="recent-list">
          <article v-for="item in documents.slice(0, 6)" :key="item.documentId" class="recent-item">
            <div class="recent-item-main">
              <strong>{{ item.documentName }}</strong>
              <p>{{ item.originalFileName }}</p>
            </div>
            <div class="recent-item-meta">
              <AdminStatusBadge :label="item.parseStatusName" :code="item.parseStatus" type="parse" />
              <AdminStatusBadge :label="item.indexStatusName" :code="item.indexStatus" type="index" />
            </div>
          </article>
        </div>
      </article>
    </div>
  </section>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { manageApi } from '../../api/api'
import AdminStatusBadge from '../../components/admin/AdminStatusBadge.vue'
import { formatCount, hasCode } from '../../utils/manageFormat'

const router = useRouter()
const loading = ref(false)
const documents = ref([])
const summary = reactive({
  total: 0,
  parseSuccess: 0,
  strategyConfirmed: 0,
  indexSuccess: 0
})

async function loadDashboard() {
  loading.value = true

  try {
    const data = await manageApi.queryDocumentPage({
      pageNo: 1,
      pageSize: 50,
      keyword: ''
    })
    documents.value = Array.isArray(data?.records) ? data.records : []

    summary.total = Number(data?.total || documents.value.length || 0)
    summary.parseSuccess = documents.value.filter((item) => hasCode(item.parseStatus, 3)).length
    summary.strategyConfirmed = documents.value.filter((item) => hasCode(item.strategyStatus, 3)).length
    summary.indexSuccess = documents.value.filter((item) => hasCode(item.indexStatus, 3)).length
  } catch (error) {
    console.error('加载后台概览失败', error)
    documents.value = []
    summary.total = 0
    summary.parseSuccess = 0
    summary.strategyConfirmed = 0
    summary.indexSuccess = 0
  } finally {
    loading.value = false
  }
}

function goDocuments() {
  router.push('/admin/documents')
}

onMounted(loadDashboard)
</script>

<style scoped>
.dashboard-page {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.hero-card,
.metric-card,
.panel-card {
  border: 1px solid rgba(17, 24, 39, 0.08);
  background: var(--color-admin-panel);
  border-radius: 22px;
  box-shadow: var(--shadow-card);
}

.hero-card {
  padding: 28px 30px;
  display: grid;
  grid-template-columns: minmax(0, 1.3fr) minmax(280px, 0.7fr);
  gap: 22px;
  align-items: stretch;
  background:
    linear-gradient(135deg, rgba(37, 87, 214, 0.08), rgba(255, 255, 255, 0.96)),
    var(--color-admin-panel);
}

.hero-copy {
  max-width: 860px;
}

.section-eyebrow {
  margin: 0 0 10px;
  font-size: 12px;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  color: var(--color-muted);
}

.hero-card h3,
.panel-header h4 {
  margin: 0;
  color: var(--color-text-strong);
}

.hero-card h3 {
  font-family: var(--font-sans);
  font-size: clamp(36px, 3.8vw, 54px);
  line-height: 1.02;
  letter-spacing: -0.03em;
  font-weight: 700;
}

.section-description {
  margin: 14px 0 0;
  color: var(--color-muted);
  line-height: 1.8;
}

.hero-aside {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  gap: 18px;
  padding: 18px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.82);
  border: 1px solid rgba(17, 24, 39, 0.08);
}

.hero-label {
  font-size: 11px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--color-muted);
}

.hero-flow {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.hero-flow span,
.metric-card span {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.hero-flow span {
  padding: 8px 12px;
  border-radius: 999px;
  background: rgba(37, 87, 214, 0.08);
  color: #234eb8;
}

.hero-note {
  margin: 0;
  color: var(--color-muted-strong);
  line-height: 1.7;
}

.primary-link,
.ghost-link {
  border: 1px solid transparent;
  border-radius: 14px;
  padding: 12px 16px;
  font-weight: 700;
  transition: transform 0.22s ease, background 0.22s ease, border-color 0.22s ease;
}

.primary-link {
  color: #ffffff;
  background: linear-gradient(135deg, var(--color-primary), var(--color-primary-strong));
  box-shadow: 0 14px 24px rgba(37, 87, 214, 0.18);
}

.ghost-link {
  color: var(--color-text);
  background: rgba(37, 87, 214, 0.08);
  border-color: rgba(37, 87, 214, 0.1);
}

.metrics-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
}

.metric-card {
  padding: 22px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.9), rgba(245, 247, 250, 0.95)),
    var(--color-admin-panel);
}

.metric-card strong {
  display: block;
  margin-top: 12px;
  font-size: clamp(30px, 3vw, 42px);
  color: var(--color-text-strong);
}

.metric-card p {
  margin: 12px 0 0;
  color: var(--color-muted);
  line-height: 1.7;
}

.dashboard-grid {
  display: grid;
  grid-template-columns: 1.05fr 0.95fr;
  gap: 16px;
}

.panel-card {
  padding: 24px 26px;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
  margin-bottom: 18px;
}

.panel-header h4 {
  font-size: 26px;
  letter-spacing: -0.02em;
}

.flow-list {
  margin: 0;
  padding-left: 20px;
  color: var(--color-muted-strong);
  display: flex;
  flex-direction: column;
  gap: 16px;
  line-height: 1.7;
}

.flow-list strong {
  display: block;
  margin-bottom: 6px;
  color: var(--color-text-strong);
}

.recent-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.recent-item {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: start;
  padding: 16px 18px;
  border-radius: 18px;
  background: var(--color-admin-panel-muted);
  border: 1px solid rgba(17, 24, 39, 0.06);
}

.recent-item-main strong {
  display: block;
  color: var(--color-text-strong);
}

.recent-item-main p {
  margin: 8px 0 0;
  color: var(--color-muted);
  word-break: break-all;
}

.recent-item-meta {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: end;
}

.empty-block {
  min-height: 220px;
  display: grid;
  place-items: center;
  text-align: center;
  color: var(--color-muted);
  border-radius: 18px;
  border: 1px dashed rgba(17, 24, 39, 0.16);
  background: rgba(244, 246, 249, 0.72);
}

@media (max-width: 1080px) {
  .metrics-grid,
  .dashboard-grid {
    grid-template-columns: 1fr 1fr;
  }

  .hero-card {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .hero-card,
  .panel-header,
  .recent-item {
    flex-direction: column;
    align-items: stretch;
  }

  .metrics-grid,
  .dashboard-grid {
    grid-template-columns: 1fr;
  }
}
</style>
