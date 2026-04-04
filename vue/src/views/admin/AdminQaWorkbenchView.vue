<template>
  <section class="qa-page">
    <div class="qa-grid">
      <article class="panel-card selector-card">
        <div class="section-header">
          <div>
            <h3>选择参与检索的文档</h3>
          </div>
          <button class="ghost-button" type="button" @click="loadIndexedDocuments">刷新</button>
        </div>

        <div v-if="loadingDocuments" class="empty-block">正在加载可检索文档...</div>
        <div v-else-if="!indexedDocuments.length" class="empty-block">
          还没有索引完成的文档，请先去“文档接入”页面完成索引构建。
        </div>

        <div v-else class="select-list">
          <label v-for="item in indexedDocuments" :key="item.documentId" class="select-item">
            <input v-model="selectedDocumentIds" type="checkbox" :value="String(item.documentId)" />
            <div class="select-main">
              <strong>{{ item.documentName }}</strong>
              <p>{{ item.originalFileName }}</p>
            </div>
            <AdminStatusBadge :label="item.indexStatusName" :code="item.indexStatus" type="index" />
          </label>
        </div>
      </article>

      <article class="panel-card ask-card">
        <div class="section-header">
          <div>
            <h3>发起检索增强问答</h3>
          </div>
        </div>

        <label class="field">
          <span>问题</span>
          <textarea
            v-model="question"
            rows="5"
            placeholder="例如：员工年假是怎么计算的？"
          ></textarea>
        </label>

        <div class="ask-toolbar">
          <label class="field field-inline">
            <span>TopK</span>
            <input v-model="topK" type="number" min="1" max="20" />
          </label>
          <button class="primary-button" type="button" :disabled="asking || !canSubmit" @click="submitQuestion">
            {{ asking ? '检索中...' : '开始问答' }}
          </button>
        </div>

        <div v-if="noticeMessage" class="inline-notice">{{ noticeMessage }}</div>

        <div v-if="answerResult" class="result-block">
          <div class="result-answer">
            <div class="section-header compact-header">
              <h4>模型回答</h4>
              <span>命中 {{ answerResult.hitCount }} 个片段</span>
            </div>
            <p>{{ answerResult.answer }}</p>
          </div>

          <div class="reference-list">
            <div class="section-header compact-header">
              <h4>引用片段</h4>
              <span>{{ answerResult.referenceList?.length || 0 }} 条</span>
            </div>

            <article
              v-for="item in answerResult.referenceList || []"
              :key="`${item.chunkId}-${item.chunkNo}`"
              class="reference-item"
            >
              <div class="reference-head">
                <strong>{{ item.documentName }}</strong>
                <span>相似度 {{ item.similarityScore }}</span>
              </div>
              <div class="reference-meta">
                <span>Chunk #{{ item.chunkNo }}</span>
                <span>{{ item.sectionPath || '未识别章节' }}</span>
                <span>{{ item.pageNo || '无页码信息' }}</span>
              </div>
              <p>{{ item.chunkText }}</p>
            </article>
          </div>
        </div>
      </article>
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { APIError, manageApi } from '../../api/api'
import AdminStatusBadge from '../../components/admin/AdminStatusBadge.vue'
import { hasCode } from '../../utils/manageFormat'

const loadingDocuments = ref(false)
const asking = ref(false)
const indexedDocuments = ref([])
const selectedDocumentIds = ref([])
const question = ref('')
const topK = ref(5)
const noticeMessage = ref('')
const answerResult = ref(null)

const canSubmit = computed(() => question.value.trim() && selectedDocumentIds.value.length > 0)

async function loadIndexedDocuments() {
  loadingDocuments.value = true

  try {
    const data = await manageApi.queryDocumentPage({
      pageNo: 1,
      pageSize: 50,
      keyword: ''
    })
    const list = Array.isArray(data?.records) ? data.records : []
    indexedDocuments.value = list.filter((item) => hasCode(item.indexStatus, 3))
  } catch (error) {
    console.error('加载索引文档失败', error)
    noticeMessage.value = normalizeError(error, '加载索引文档失败')
    indexedDocuments.value = []
  } finally {
    loadingDocuments.value = false
  }
}

async function submitQuestion() {
  if (!canSubmit.value) {
    noticeMessage.value = '请先选择文档并输入问题。'
    return
  }

  asking.value = true
  noticeMessage.value = ''

  try {
    answerResult.value = await manageApi.askQuestion({
      question: question.value.trim(),
      topK: String(topK.value || 5),
      documentIdList: selectedDocumentIds.value.map((item) => String(item))
    })
  } catch (error) {
    console.error('发起问答失败', error)
    noticeMessage.value = normalizeError(error, '发起问答失败')
    answerResult.value = null
  } finally {
    asking.value = false
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

onMounted(loadIndexedDocuments)
</script>

<style scoped>
.qa-page {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.qa-grid {
  display: grid;
  grid-template-columns: 0.9fr 1.1fr;
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
.ask-toolbar,
.reference-head,
.reference-meta {
  display: flex;
  align-items: center;
}

.section-header,
.ask-toolbar,
.reference-head {
  justify-content: space-between;
  gap: 12px;
}

.section-header h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--color-text-strong);
}

.compact-header h4 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--color-text-strong);
}

.ghost-button,
.primary-button {
  border-radius: var(--radius-sm);
  padding: 8px 16px;
  font-weight: 600;
}

.ghost-button {
  color: var(--color-primary);
  background: var(--color-primary-soft);
  border: 1px solid transparent;
}

.ghost-button:hover:not(:disabled) {
  background: rgba(37, 87, 214, 0.14);
}

.primary-button {
  color: #ffffff;
  background: var(--color-primary);
  border: none;
}

.select-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: 18px;
}

.select-item {
  display: flex;
  gap: 14px;
  align-items: start;
  padding: 16px 18px;
  border-radius: var(--radius-md);
  background: var(--color-surface-soft);
  border: 1px solid var(--color-border);
}

.select-item input {
  margin-top: 6px;
  accent-color: var(--color-primary);
}

.select-main {
  min-width: 0;
  flex: 1;
}

.select-main strong {
  display: block;
  color: var(--color-text-strong);
}

.select-main p {
  margin: 8px 0 0;
  color: var(--color-muted);
  word-break: break-all;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 18px;
}

.field span {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-muted);
}

.field textarea,
.field input {
  width: 100%;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  padding: 10px 12px;
  background: #ffffff;
  outline: none;
  color: var(--color-text);
}

.field textarea:focus,
.field input:focus {
  border-color: var(--color-primary);
  box-shadow: 0 0 0 3px rgba(37, 87, 214, 0.08);
}

.field-inline {
  margin-top: 0;
  width: 132px;
}

.ask-toolbar {
  margin-top: 18px;
}

.inline-notice {
  margin-top: 16px;
  padding: 12px 14px;
  border-radius: var(--radius-sm);
  background: rgba(37, 87, 214, 0.08);
  border: 1px solid rgba(37, 87, 214, 0.1);
  color: #1f4ebb;
}

.result-block {
  margin-top: 22px;
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.result-answer,
.reference-item {
  padding: 18px 20px;
  border-radius: var(--radius-md);
  background: var(--color-surface-soft);
  border: 1px solid var(--color-border);
}

.result-answer p,
.reference-item p {
  margin: 14px 0 0;
  color: var(--color-text);
  line-height: 1.8;
}

.reference-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.reference-head strong {
  color: var(--color-text-strong);
}

.reference-head span,
.reference-meta {
  color: var(--color-muted);
  font-size: 13px;
}

.reference-meta {
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 10px;
}

.empty-block {
  min-height: 280px;
  display: grid;
  place-items: center;
  text-align: center;
  color: var(--color-muted);
  border-radius: var(--radius-md);
  border: 1px dashed var(--color-border);
  background: var(--color-surface-soft);
  margin-top: 18px;
}

@media (max-width: 1080px) {
  .qa-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .ask-toolbar,
  .section-header,
  .reference-head {
    flex-direction: column;
    align-items: stretch;
  }

  .field-inline {
    width: 100%;
  }
}
</style>
