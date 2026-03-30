<template>
  <section class="qa-page">
    <div class="qa-grid">
      <article class="panel-card selector-card">
        <div class="section-header">
          <div>
            <p class="section-eyebrow">Retrieval Scope</p>
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
            <p class="section-eyebrow">QA Playground</p>
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
  gap: 22px;
}

.qa-grid {
  display: grid;
  grid-template-columns: 0.9fr 1.1fr;
  gap: 18px;
}

.panel-card {
  border: 1px solid rgba(21, 49, 75, 0.08);
  background: var(--color-admin-panel);
  border-radius: 28px;
  box-shadow: 0 18px 42px rgba(21, 49, 75, 0.06);
  padding: 24px 26px;
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

.section-eyebrow {
  margin: 0 0 8px;
  font-size: 12px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: #6b839d;
}

.section-header h3,
.compact-header h4 {
  margin: 0;
  color: #13283f;
}

.ghost-button,
.primary-button {
  border: none;
  border-radius: 16px;
  padding: 12px 18px;
  font-weight: 700;
}

.ghost-button {
  color: #17304f;
  background: rgba(23, 48, 79, 0.08);
}

.primary-button {
  color: #ffffff;
  background: linear-gradient(135deg, #17304f, #0d7c7c);
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
  border-radius: 22px;
  background: rgba(245, 248, 252, 0.9);
}

.select-item input {
  margin-top: 6px;
}

.select-main {
  min-width: 0;
  flex: 1;
}

.select-main strong {
  display: block;
  color: #13283f;
}

.select-main p {
  margin: 8px 0 0;
  color: #687f98;
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
  font-weight: 700;
  color: #47627b;
}

.field textarea,
.field input {
  width: 100%;
  border: 1px solid rgba(21, 49, 75, 0.12);
  border-radius: 18px;
  padding: 14px 16px;
  background: #ffffff;
  outline: none;
}

.field textarea:focus,
.field input:focus {
  border-color: rgba(13, 124, 124, 0.34);
  box-shadow: 0 0 0 4px rgba(13, 124, 124, 0.08);
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
  border-radius: 16px;
  background: rgba(23, 48, 79, 0.08);
  color: #17304f;
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
  border-radius: 22px;
  background: rgba(245, 248, 252, 0.92);
}

.result-answer p,
.reference-item p {
  margin: 14px 0 0;
  color: #405972;
  line-height: 1.8;
}

.reference-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.reference-head strong {
  color: #13283f;
}

.reference-head span,
.reference-meta {
  color: #69819b;
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
  color: #6e849c;
  border-radius: 22px;
  border: 1px dashed rgba(21, 49, 75, 0.14);
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
