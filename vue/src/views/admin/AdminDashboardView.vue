<template>
  <section class="dashboard-page">
    <div class="hero-card">
      <div>
        <p class="section-eyebrow">Operations Snapshot</p>
        <h3>把文档接入、切块策略和索引构建串成一条可观察的业务流水线</h3>
        <p class="section-description">
          后台管理台聚焦在文档进入系统后的关键节点：上传、推荐策略、策略确认、索引构建和检索验证。
        </p>
      </div>
      <button class="primary-link" type="button" @click="goDocuments">前往文档接入</button>
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
  gap: 22px;
}

.hero-card,
.metric-card,
.panel-card {
  border: 1px solid rgba(21, 49, 75, 0.08);
  background: var(--color-admin-panel);
  border-radius: 28px;
  box-shadow: 0 18px 42px rgba(21, 49, 75, 0.06);
}

.hero-card {
  padding: 28px 30px;
  display: flex;
  justify-content: space-between;
  gap: 18px;
  align-items: end;
  background:
    radial-gradient(circle at top right, rgba(217, 119, 6, 0.12), transparent 22%),
    linear-gradient(135deg, rgba(13, 124, 124, 0.08), rgba(255, 255, 255, 0.9));
}

.section-eyebrow {
  margin: 0 0 10px;
  font-size: 12px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: #64809d;
}

.hero-card h3,
.panel-header h4 {
  margin: 0;
  color: #13283f;
}

.hero-card h3 {
  max-width: 760px;
  font-size: clamp(28px, 3vw, 40px);
  line-height: 1.15;
}

.section-description {
  max-width: 800px;
  margin: 14px 0 0;
  color: #5c7188;
}

.primary-link,
.ghost-link {
  border: none;
  border-radius: 18px;
  padding: 13px 18px;
  font-weight: 700;
}

.primary-link {
  color: #ffffff;
  background: linear-gradient(135deg, #17304f, #0d7c7c);
}

.ghost-link {
  color: #17304f;
  background: rgba(23, 48, 79, 0.06);
}

.metrics-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 18px;
}

.metric-card {
  padding: 24px;
}

.metric-card span {
  font-size: 13px;
  color: #63809b;
  text-transform: uppercase;
  letter-spacing: 0.08em;
}

.metric-card strong {
  display: block;
  margin-top: 12px;
  font-size: clamp(28px, 3vw, 40px);
  color: #13283f;
}

.metric-card p {
  margin: 12px 0 0;
  color: #63788e;
}

.dashboard-grid {
  display: grid;
  grid-template-columns: 1.05fr 0.95fr;
  gap: 18px;
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

.flow-list {
  margin: 0;
  padding-left: 20px;
  color: #344e68;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.flow-list strong {
  display: block;
  margin-bottom: 6px;
  color: #13283f;
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
  border-radius: 20px;
  background: rgba(245, 248, 252, 0.88);
}

.recent-item-main strong {
  display: block;
  color: #13283f;
}

.recent-item-main p {
  margin: 8px 0 0;
  color: #64798f;
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
  color: #6d8299;
  border-radius: 22px;
  border: 1px dashed rgba(21, 49, 75, 0.14);
}

@media (max-width: 1080px) {
  .metrics-grid,
  .dashboard-grid {
    grid-template-columns: 1fr 1fr;
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
