<template>
  <section class="document-page">
    <div class="top-grid">
      <article class="panel-card upload-card">
        <div class="panel-title">
          <div>
            <p class="section-eyebrow">Document Intake</p>
            <h3>上传资料并进入推荐流程</h3>
          </div>
        </div>

        <div class="upload-grid">
          <label class="field">
            <span>文档名称</span>
            <input v-model="uploadForm.documentName" type="text" placeholder="不填则使用原始文件名" />
          </label>

          <label class="field">
            <span>选择文件</span>
            <input ref="fileInputRef" type="file" class="file-input" @change="handleFileChange" />
          </label>
        </div>

        <div class="upload-footer">
          <div class="upload-hint">
            <span>支持 PDF / DOC / DOCX / TXT / MD / HTML</span>
            <strong>{{ uploadForm.file ? uploadForm.file.name : '尚未选择文件' }}</strong>
          </div>

          <div class="upload-actions">
            <button class="ghost-button" type="button" @click="clearSelectedFile">清空</button>
            <button class="primary-button" type="button" :disabled="uploading || !uploadForm.file" @click="submitUpload">
              {{ uploading ? '上传中...' : '上传并解析' }}
            </button>
          </div>
        </div>
      </article>

      <article class="panel-card tips-card">
        <div class="panel-title">
          <div>
            <p class="section-eyebrow">Flow Hints</p>
            <h3>建议操作顺序</h3>
          </div>
        </div>

        <ul class="tips-list">
          <li>先上传文档，系统会异步解析并生成推荐切块策略。</li>
          <li>点击任意文档，进入单独详情页查看解析结果、Chunk 和任务轨迹。</li>
          <li>在详情页确认策略并构建索引，列表页专注浏览和筛选。</li>
        </ul>
      </article>
    </div>

    <div v-if="pageNotice.message" class="page-notice" :class="`page-notice-${pageNotice.type}`">
      {{ pageNotice.message }}
    </div>

    <article class="panel-card list-card">
      <div class="list-toolbar">
        <div>
          <p class="section-eyebrow">Document Pool</p>
          <h3>文档列表</h3>
          <p class="toolbar-caption">共 {{ total }} 份文档，当前第 {{ currentPage }} 页。</p>
        </div>

        <div class="list-actions">
          <input
            v-model="keyword"
            class="search-input"
            type="text"
            placeholder="搜索文档名称或原始文件名"
            @keydown.enter="submitSearch"
          />
          <button class="ghost-button" type="button" @click="submitSearch">搜索</button>
        </div>
      </div>

      <div class="document-list">
        <button
          v-for="item in documents"
          :key="item.documentId"
          class="document-row"
          type="button"
          @click="openDocumentDetail(item.documentId)"
        >
          <div class="document-row-main">
            <div class="document-row-title">
              <strong>{{ item.documentName }}</strong>
              <span>{{ item.fileTypeName }}</span>
            </div>
            <p>{{ item.originalFileName }}</p>
            <div class="document-row-meta">
              <span>{{ formatFileSize(item.fileSize) }}</span>
              <span>{{ formatDateTime(item.editTime) }}</span>
            </div>
          </div>
          <div class="document-row-side">
            <div class="document-row-status">
              <AdminStatusBadge :label="item.parseStatusName" :code="item.parseStatus" type="parse" />
              <AdminStatusBadge :label="item.strategyStatusName" :code="item.strategyStatus" type="strategy" />
              <AdminStatusBadge :label="item.indexStatusName" :code="item.indexStatus" type="index" />
            </div>
            <span class="detail-link">查看详情</span>
          </div>
        </button>

        <div v-if="!listLoading && !documents.length" class="empty-block">
          还没有文档，先上传一份资料开始体验。
        </div>
        <div v-if="listLoading" class="empty-block">正在加载文档列表...</div>
      </div>

      <div v-if="documents.length" class="pagination-bar">
        <button class="ghost-button" type="button" :disabled="currentPage <= 1 || listLoading" @click="changePage(currentPage - 1)">
          上一页
        </button>
        <div class="pagination-status">
          <strong>第 {{ currentPage }} / {{ totalPages }} 页</strong>
          <span>共 {{ total }} 条文档</span>
        </div>
        <button class="ghost-button" type="button" :disabled="currentPage >= totalPages || listLoading" @click="changePage(currentPage + 1)">
          下一页
        </button>
      </div>
    </article>
  </section>
</template>

<script setup>
import { reactive, ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { APIError, manageApi } from '../../api/api'
import AdminStatusBadge from '../../components/admin/AdminStatusBadge.vue'
import { formatDateTime, formatFileSize } from '../../utils/manageFormat'

const router = useRouter()
const OPERATOR_ID = '10001'
const DEFAULT_PAGE_SIZE = 12

const uploadForm = reactive({
  documentName: '',
  file: null
})
const fileInputRef = ref(null)
const uploading = ref(false)
const listLoading = ref(false)
const keyword = ref('')
const documents = ref([])
const currentPage = ref(1)
const pageSize = ref(DEFAULT_PAGE_SIZE)
const total = ref(0)
const pageNotice = reactive({
  type: 'info',
  message: ''
})

const totalPages = computed(() => {
  return Math.max(1, Math.ceil((total.value || 0) / pageSize.value))
})

function showNotice(message, type = 'info') {
  pageNotice.type = type
  pageNotice.message = message
}

function clearNotice() {
  pageNotice.message = ''
}

function handleFileChange(event) {
  uploadForm.file = event.target.files?.[0] || null
}

function clearSelectedFile() {
  uploadForm.file = null
  uploadForm.documentName = ''
  if (fileInputRef.value) {
    fileInputRef.value.value = ''
  }
}

async function loadDocuments(page = currentPage.value) {
  listLoading.value = true

  try {
    const data = await manageApi.queryDocumentPage({
      pageNo: page,
      pageSize: pageSize.value,
      keyword: keyword.value.trim()
    })
    documents.value = Array.isArray(data?.records) ? data.records : []
    currentPage.value = Number(data?.pageNo || page)
    pageSize.value = Number(data?.pageSize || pageSize.value)
    total.value = Number(data?.total || 0)
  } catch (error) {
    console.error('加载文档列表失败', error)
    showNotice(normalizeError(error, '加载文档列表失败'), 'danger')
    documents.value = []
  } finally {
    listLoading.value = false
  }
}

function submitSearch() {
  currentPage.value = 1
  loadDocuments(1)
}

function changePage(page) {
  if (page < 1 || page > totalPages.value || page === currentPage.value) {
    return
  }
  loadDocuments(page)
}

function openDocumentDetail(documentId) {
  router.push({
    name: 'AdminDocumentDetail',
    params: {
      documentId: String(documentId)
    }
  })
}

async function submitUpload() {
  if (!uploadForm.file) {
    showNotice('请先选择要上传的文档。', 'danger')
    return
  }

  uploading.value = true
  clearNotice()

  try {
    const result = await manageApi.uploadDocument({
      file: uploadForm.file,
      documentName: uploadForm.documentName.trim(),
      operatorId: OPERATOR_ID
    })
    clearSelectedFile()
    showNotice(`文档已上传，任务 ${result.taskId} 已进入解析与策略推荐队列。`, 'success')
    await loadDocuments(1)
    openDocumentDetail(result.documentId)
  } catch (error) {
    console.error('上传文档失败', error)
    showNotice(normalizeError(error, '上传文档失败'), 'danger')
  } finally {
    uploading.value = false
  }
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

onMounted(() => {
  loadDocuments()
})
</script>

<style scoped>
.document-page {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.top-grid {
  display: grid;
  grid-template-columns: 1.05fr 0.95fr;
  gap: 16px;
  align-items: stretch;
}

.panel-card {
  border: 1px solid rgba(17, 24, 39, 0.08);
  background: var(--color-admin-panel);
  border-radius: 22px;
  box-shadow: var(--shadow-card);
  padding: 22px 24px;
}

.panel-title,
.list-toolbar,
.upload-actions,
.list-actions,
.document-row,
.document-row-title,
.document-row-meta,
.document-row-status,
.pagination-bar {
  display: flex;
  align-items: center;
}

.panel-title,
.list-toolbar,
.pagination-bar {
  justify-content: space-between;
  gap: 12px;
}

.panel-title h3,
.list-toolbar h3 {
  margin: 0;
  color: var(--color-text-strong);
  font-size: 28px;
  letter-spacing: -0.02em;
}

.section-eyebrow {
  margin: 0 0 8px;
  font-size: 12px;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  color: var(--color-muted);
}

.toolbar-caption {
  margin: 8px 0 0;
  color: var(--color-muted);
  font-size: 14px;
}

.upload-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
  margin-top: 16px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.field span {
  font-size: 13px;
  font-weight: 700;
  color: var(--color-muted-strong);
}

.field input,
.search-input {
  width: 100%;
  border: 1px solid rgba(17, 24, 39, 0.1);
  border-radius: 14px;
  padding: 12px 14px;
  background: #ffffff;
  outline: none;
  color: var(--color-text);
}

.field input:focus,
.search-input:focus {
  border-color: rgba(37, 87, 214, 0.28);
  box-shadow: 0 0 0 4px rgba(37, 87, 214, 0.08);
}

.upload-hint {
  padding: 14px 16px;
  border-radius: 16px;
  background: var(--color-admin-panel-muted);
  border: 1px solid rgba(17, 24, 39, 0.06);
  flex: 1;
  min-width: 0;
}

.upload-hint span {
  display: block;
  color: var(--color-muted);
  font-size: 13px;
}

.upload-hint strong {
  display: block;
  margin-top: 8px;
  color: var(--color-text-strong);
  word-break: break-all;
}

.upload-actions,
.list-actions {
  gap: 12px;
}

.upload-footer {
  margin-top: 14px;
  display: flex;
  align-items: stretch;
  justify-content: space-between;
  gap: 12px;
}

.upload-actions {
  margin-top: 0;
  justify-content: flex-end;
  flex: none;
  align-self: center;
}

.tips-list {
  margin: 12px 0 0;
  padding-left: 18px;
  color: var(--color-muted-strong);
  display: flex;
  flex-direction: column;
  gap: 12px;
  font-size: 14px;
  line-height: 1.7;
}

.page-notice {
  padding: 14px 18px;
  border-radius: 16px;
  font-weight: 600;
  border: 1px solid transparent;
}

.page-notice-info {
  background: rgba(37, 87, 214, 0.08);
  color: #1f4ebb;
  border-color: rgba(37, 87, 214, 0.1);
}

.page-notice-success {
  background: rgba(21, 115, 91, 0.1);
  color: #12644f;
  border-color: rgba(21, 115, 91, 0.12);
}

.page-notice-danger {
  background: rgba(179, 76, 47, 0.1);
  color: #9f422b;
  border-color: rgba(179, 76, 47, 0.12);
}

.document-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: 18px;
  min-height: 420px;
}

.document-row {
  width: 100%;
  border: 1px solid rgba(17, 24, 39, 0.08);
  border-radius: 18px;
  padding: 18px 18px;
  justify-content: space-between;
  gap: 16px;
  text-align: left;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.94), rgba(246, 248, 250, 0.96)),
    var(--color-admin-panel-muted);
  transition: transform 0.2s ease, border-color 0.2s ease, box-shadow 0.2s ease;
}

.document-row:hover {
  transform: translateY(-1px);
  border-color: rgba(37, 87, 214, 0.2);
  box-shadow: 0 18px 28px rgba(15, 23, 36, 0.08);
}

.document-row-main,
.document-row-side {
  min-width: 0;
}

.document-row-side {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 10px;
}

.document-row-title {
  gap: 10px;
  justify-content: flex-start;
}

.document-row-title strong {
  color: var(--color-text-strong);
  font-size: 18px;
}

.document-row-title span,
.document-row-main p,
.document-row-meta {
  color: var(--color-muted);
}

.document-row-main p {
  margin: 8px 0;
  word-break: break-all;
  line-height: 1.7;
}

.document-row-meta,
.document-row-status {
  gap: 8px;
  flex-wrap: wrap;
}

.detail-link {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  align-self: flex-end;
  gap: 8px;
  min-width: 108px;
  padding: 10px 14px;
  border-radius: 12px;
  background: rgba(37, 87, 214, 0.08);
  border: 1px solid rgba(37, 87, 214, 0.12);
  color: #1f4ebb;
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0.04em;
}

.detail-link::after {
  content: '→';
  font-size: 14px;
  line-height: 1;
}

.pagination-bar {
  margin-top: 18px;
  padding-top: 18px;
  border-top: 1px solid rgba(17, 24, 39, 0.08);
}

.pagination-status {
  text-align: center;
}

.pagination-status strong {
  display: block;
  color: var(--color-text-strong);
}

.pagination-status span {
  display: block;
  margin-top: 6px;
  color: var(--color-muted);
  font-size: 13px;
}

.empty-block {
  min-height: 260px;
  display: grid;
  place-items: center;
  text-align: center;
  color: var(--color-muted);
  border-radius: 18px;
  border: 1px dashed rgba(17, 24, 39, 0.16);
  background: rgba(244, 246, 249, 0.72);
}

.primary-button,
.ghost-button {
  border: 1px solid transparent;
  border-radius: 14px;
  padding: 12px 16px;
  font-weight: 700;
}

.primary-button {
  color: #ffffff;
  background: linear-gradient(135deg, var(--color-primary), var(--color-primary-strong));
  box-shadow: 0 14px 24px rgba(37, 87, 214, 0.18);
}

.ghost-button {
  color: var(--color-text);
  background: rgba(255, 255, 255, 0.84);
  border-color: rgba(17, 24, 39, 0.08);
}

.file-input::file-selector-button {
  border: none;
  border-radius: 10px;
  padding: 8px 12px;
  margin-right: 12px;
  background: rgba(37, 87, 214, 0.08);
  color: #1f4ebb;
}

@media (max-width: 860px) {
  .upload-grid {
    grid-template-columns: 1fr;
  }

  .upload-footer {
    flex-direction: column;
  }

  .list-toolbar,
  .document-row,
  .pagination-bar {
    flex-direction: column;
    align-items: stretch;
  }

  .document-row-side {
    align-items: stretch;
  }
}
</style>
