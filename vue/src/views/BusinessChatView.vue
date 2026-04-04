<template>
  <section class="workspace">
    <aside class="sidebar" :class="{ 'sidebar-open': sidebarOpen }">
      <div class="sidebar-header">
        <div>
          <h2>聊天记录</h2>
        </div>
        <button class="icon-button mobile-only" type="button" @click="sidebarOpen = false">
          <XMarkIcon class="icon" />
        </button>
      </div>

      <button class="primary-button new-chat-button" type="button" :disabled="isStreaming" @click="startNewConversation">
        <PlusIcon class="icon" />
        新对话
      </button>

      <div class="session-list">
        <article
          v-for="session in sortedSessions"
          :key="session.conversationId"
          class="session-card"
          :class="{ active: session.conversationId === currentConversationId }"
        >
          <button
            class="session-select"
            type="button"
            :disabled="isStreaming"
            @click="loadConversation(session.conversationId)"
          >
            <div class="session-main">
              <div class="session-title-row">
                <span class="session-title">{{ sessionTitle(session) }}</span>
                <span v-if="session.running" class="running-dot">运行中</span>
              </div>
              <p class="session-preview">{{ sessionPreview(session) }}</p>
              <div class="session-meta">
                <span>{{ formatTime(session.updatedAt) }}</span>
                <span>{{ session.messageCount || 0 }} 条消息</span>
              </div>
            </div>
          </button>
          <button
            class="icon-button delete-button"
            type="button"
            title="删除会话"
            :disabled="isStreaming"
            @click.stop="deleteConversation(session.conversationId)"
          >
            <TrashIcon class="icon" />
          </button>
        </article>

        <div v-if="!loadingSessions && !sortedSessions.length" class="empty-sidebar">
          <p>还没有历史会话。</p>
          <span>发送第一条消息后，这里会自动出现会话记录。</span>
        </div>
      </div>
    </aside>

    <div v-if="sidebarOpen" class="sidebar-mask" @click="sidebarOpen = false"></div>

    <main class="chat-panel">
      <header class="chat-toolbar">
        <div class="toolbar-left">
          <button class="icon-button mobile-only" type="button" @click="sidebarOpen = true">
            <Bars3Icon class="icon" />
          </button>
          <div>
            <h2>{{ activeSessionTitle }}</h2>
          </div>
        </div>
        <div class="toolbar-actions">
          <button class="admin-entry-button" type="button" @click="goAdminConsole">
            <BuildingOffice2Icon class="icon" />
            管理后台
          </button>
        </div>
      </header>

      <div class="messages-panel" ref="messagesPanelRef">
        <div v-if="pageError" class="notice notice-error">
          {{ pageError }}
        </div>

        <div v-if="loadingConversation" class="notice">
          正在加载会话内容...
        </div>

        <div v-if="!displayMessages.length && !loadingConversation" class="empty-state">
          <div class="empty-icon">
            <SparklesIcon class="icon" />
          </div>
          <h3>让零散问题更快落成可执行方案</h3>
          <p>结合业务问答、文档理解与知识检索，把想法整理成清晰结论和下一步动作</p>
          <div class="prompt-grid">
            <button type="button" class="prompt-chip" @click="sendMessage('请先介绍一下你能帮我做哪些事情，并给出几个典型使用场景')">
              助手能做什么
            </button>
            <button type="button" class="prompt-chip" @click="sendMessage('请帮我把一个复杂问题拆成清晰的分析步骤，并给出执行建议')">
              拆解复杂问题
            </button>
            <button type="button" class="prompt-chip" @click="sendMessage('结合当前项目，帮我梳理对话能力、知识库能力和后台能力之间的关系')">
              梳理项目能力
            </button>
          </div>
        </div>

        <Chat
          v-for="message in displayMessages"
          :key="message.id"
          :message="message"
          :is-streaming="isStreaming && message.id === currentAssistantMessageId"
          :show-recommendations="message.id === latestAssistantDisplayId"
          @recommend="sendMessage"
        />
      </div>

      <footer class="composer-panel">
        <div class="composer-header">
          <span class="composer-tip">按 Enter 发送，Shift + Enter 换行。</span>
          <span v-if="isStreaming" class="streaming-badge">正在生成回答...</span>
        </div>

        <textarea
          ref="composerRef"
          v-model="userInput"
          class="composer-input"
          rows="1"
          placeholder="请输入你的问题，例如：帮我分析一下这个智能对话方案应该怎么拆分模块。"
          :disabled="isStreaming"
          @input="resizeComposer"
          @keydown="handleComposerKeydown"
        ></textarea>

        <div class="composer-actions">
          <button
            v-if="isStreaming"
            class="ghost-button"
            type="button"
            :disabled="isStopping"
            @click="stopStreaming"
          >
            <StopIcon class="icon" />
            {{ isStopping ? '停止中...' : '停止生成' }}
          </button>
          <button class="primary-button" type="button" :disabled="isStreaming || !userInput.trim()" @click="sendMessage()">
            <PaperAirplaneIcon class="icon" />
            发送
          </button>
        </div>
      </footer>
    </main>
  </section>
</template>

<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  Bars3Icon,
  BuildingOffice2Icon,
  PaperAirplaneIcon,
  PlusIcon,
  SparklesIcon,
  StopIcon,
  TrashIcon,
  XMarkIcon
} from '@heroicons/vue/24/outline'
import Chat from '../components/Chat.vue'
import { APIError, chatApi, createConversationId } from '../api/api'

const router = useRouter()
const composerRef = ref(null)
const messagesPanelRef = ref(null)
const sidebarOpen = ref(false)
const sessions = ref([])
const currentConversationId = ref('')
const displayMessages = ref([])
const userInput = ref('')
const loadingSessions = ref(false)
const loadingConversation = ref(false)
const isStreaming = ref(false)
const isStopping = ref(false)
const pageError = ref('')
const currentStreamHandle = ref(null)
const currentAssistantMessageId = ref('')

const sortedSessions = computed(() => {
  return [...sessions.value].sort((left, right) => {
    const leftTime = left.updatedAt ? new Date(left.updatedAt).getTime() : 0
    const rightTime = right.updatedAt ? new Date(right.updatedAt).getTime() : 0
    return rightTime - leftTime
  })
})

const activeSessionTitle = computed(() => {
  const session = sessions.value.find((item) => item.conversationId === currentConversationId.value)
  return session ? sessionTitle(session) : '新的对话'
})
const latestAssistantDisplayId = computed(() => {
  const message = [...displayMessages.value].reverse().find((item) => item.role === 'assistant')
  return message?.id || ''
})

function sessionTitle(session) {
  return truncate(session.latestUserMessage || session.latestAssistantMessage || '新的对话', 22)
}

function sessionPreview(session) {
  return truncate(session.latestAssistantMessage || session.latestUserMessage || '还没有消息内容', 48)
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

function createUserMessage(question) {
  return {
    id: `user-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
    role: 'user',
    content: question,
    createdAt: new Date().toISOString()
  }
}

function createAssistantMessage() {
  return {
    id: `assistant-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
    role: 'assistant',
    content: '',
    thinkingSteps: [],
    references: [],
    recommendations: [],
    usedTools: [],
    status: 'RUNNING',
    statusText: '',
    errorMessage: '',
    firstResponseTimeMs: null,
    totalResponseTimeMs: null,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString()
  }
}

// 后端把每一轮对话结构化成 exchange，这里把 exchange 展开成用户消息 + 助手消息，
// 这样前端展示层就不需要感知数据库 record 的结构细节。
function mapExchangesToMessages(exchanges = []) {
  return exchanges.flatMap((exchange) => {
    const userMessage = {
      id: `exchange-${exchange.exchangeId}-user`,
      role: 'user',
      content: exchange.question || '',
      createdAt: exchange.createdAt
    }

    const assistantMessage = {
      id: `exchange-${exchange.exchangeId}-assistant`,
      role: 'assistant',
      content: exchange.answer || '',
      thinkingSteps: exchange.thinkingSteps || [],
      references: exchange.references || [],
      recommendations: exchange.recommendations || [],
      usedTools: exchange.usedTools || [],
      status: exchange.status || '',
      statusText: '',
      errorMessage: exchange.errorMessage || '',
      firstResponseTimeMs: exchange.firstResponseTimeMs,
      totalResponseTimeMs: exchange.totalResponseTimeMs,
      createdAt: exchange.createdAt,
      updatedAt: exchange.updatedAt
    }

    return [userMessage, assistantMessage]
  })
}

function upsertSession(session) {
  const index = sessions.value.findIndex((item) => item.conversationId === session.conversationId)
  if (index === -1) {
    sessions.value = [session, ...sessions.value]
    return
  }

  const nextSessions = [...sessions.value]
  nextSessions.splice(index, 1, session)
  sessions.value = nextSessions
}

// SSE 流里拿到的是增量事件，页面需要把它们持续合并进“当前这条助手消息”。
function updateCurrentAssistant(mutator) {
  const index = displayMessages.value.findIndex((message) => message.id === currentAssistantMessageId.value)
  if (index === -1) {
    return
  }

  const nextMessage = {
    ...displayMessages.value[index]
  }
  mutator(nextMessage)

  const nextMessages = [...displayMessages.value]
  nextMessages.splice(index, 1, nextMessage)
  displayMessages.value = nextMessages
}

async function scrollToBottom() {
  await nextTick()
  if (messagesPanelRef.value) {
    messagesPanelRef.value.scrollTop = messagesPanelRef.value.scrollHeight
  }
}

function resizeComposer() {
  nextTick(() => {
    if (!composerRef.value) {
      return
    }
    composerRef.value.style.height = 'auto'
    composerRef.value.style.height = `${Math.min(composerRef.value.scrollHeight, 220)}px`
  })
}

function focusComposer() {
  nextTick(() => {
    composerRef.value?.focus()
    resizeComposer()
  })
}

async function refreshSessions() {
  loadingSessions.value = true

  try {
    const data = await chatApi.listSessions()
    sessions.value = Array.isArray(data) ? data : []
  } catch (error) {
    pageError.value = normalizeError(error, '加载会话列表失败')
  } finally {
    loadingSessions.value = false
  }
}

async function loadConversation(conversationId) {
  if (!conversationId || isStreaming.value) {
    return
  }

  loadingConversation.value = true
  pageError.value = ''

  try {
    const session = await chatApi.getSession(conversationId)
    currentConversationId.value = conversationId
    displayMessages.value = mapExchangesToMessages(session.exchanges || [])
    upsertSession(session)
    sidebarOpen.value = false
    await scrollToBottom()
  } catch (error) {
    pageError.value = normalizeError(error, '加载会话详情失败')
  } finally {
    loadingConversation.value = false
  }
}

async function deleteConversation(conversationId) {
  if (!conversationId || isStreaming.value) {
    return
  }

  try {
    await chatApi.deleteSession(conversationId)
    sessions.value = sessions.value.filter((item) => item.conversationId !== conversationId)

    if (currentConversationId.value === conversationId) {
      const nextSession = sortedSessions.value[0]
      if (nextSession) {
        await loadConversation(nextSession.conversationId)
      } else {
        startNewConversation()
      }
    }
  } catch (error) {
    pageError.value = normalizeError(error, '删除会话失败')
  }
}

function startNewConversation() {
  if (isStreaming.value) {
    return
  }

  currentConversationId.value = createConversationId()
  displayMessages.value = []
  userInput.value = ''
  pageError.value = ''
  sidebarOpen.value = false
  focusComposer()
}

function goAdminConsole() {
  router.push({
    name: 'AdminLogin',
    query: {
      redirect: '/admin/dashboard'
    }
  })
}

function handleComposerKeydown(event) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    sendMessage()
  }
}

// 历史会话是完整快照，流式回答是增量事件，这里统一负责把增量事件映射到展示态。
function applyStreamEvent(event) {
  updateCurrentAssistant((message) => {
    if (event.type === 'text') {
      message.content += event.content || ''
    }

    if (event.type === 'thinking' && event.content && !message.thinkingSteps.includes(event.content)) {
      message.thinkingSteps = [...message.thinkingSteps, event.content]
    }

    if (event.type === 'reference') {
      message.references = Array.isArray(event.content) ? event.content : []
    }

    if (event.type === 'recommend') {
      message.recommendations = Array.isArray(event.content) ? event.content : []
    }

    if (event.type === 'status') {
      message.statusText = event.content || ''
    }

    if (event.type === 'error') {
      message.errorMessage = event.content || '对话执行失败'
      message.status = 'FAILED'
    }

    message.updatedAt = event.timestamp || new Date().toISOString()
  })

  scrollToBottom()
}

async function sendMessage(presetQuestion) {
  const question = (presetQuestion || userInput.value).trim()
  if (!question || isStreaming.value) {
    return
  }

  const conversationId = currentConversationId.value || createConversationId()
  const assistantMessage = createAssistantMessage()
  currentConversationId.value = conversationId
  pageError.value = ''

  displayMessages.value = [
    ...displayMessages.value,
    createUserMessage(question),
    assistantMessage
  ]
  currentAssistantMessageId.value = assistantMessage.id
  isStreaming.value = true
  isStopping.value = false

  if (!presetQuestion) {
    userInput.value = ''
    resizeComposer()
  }

  await scrollToBottom()

  const streamHandle = chatApi.openStream(
    {
      question,
      conversationId
    },
    {
      onEvent: applyStreamEvent
    }
  )

  currentStreamHandle.value = streamHandle

  try {
    await streamHandle.done
  } catch (error) {
    if (error.name !== 'AbortError') {
      updateCurrentAssistant((message) => {
        message.errorMessage = normalizeError(error, '流式对话失败')
        message.status = 'FAILED'
      })
      pageError.value = normalizeError(error, '流式对话失败')
    }
  } finally {
    currentStreamHandle.value = null
    currentAssistantMessageId.value = ''
    isStreaming.value = false
    isStopping.value = false

    try {
      await refreshSessions()
      await loadConversation(conversationId)
    } catch {
      // 这里的错误已经在各自方法里落到页面提示了，不需要再次抛出。
    }
  }
}

async function stopStreaming() {
  if (!isStreaming.value || !currentConversationId.value || !currentStreamHandle.value) {
    return
  }

  isStopping.value = true

  try {
    const result = await chatApi.stopSession(currentConversationId.value)
    updateCurrentAssistant((message) => {
      message.statusText = result?.message || '用户已停止生成'
    })
  } catch (error) {
    pageError.value = normalizeError(error, '停止会话失败')
    isStopping.value = false
    return
  }

  currentStreamHandle.value.controller.abort()
}

function normalizeError(error, fallback) {
  if (error instanceof APIError && error.message) {
    return error.message
  }

  if (error instanceof Error && error.message) {
    return error.message
  }

  return fallback
}

onMounted(async () => {
  await refreshSessions()

  if (sortedSessions.value.length > 0) {
    await loadConversation(sortedSessions.value[0].conversationId)
  } else {
    startNewConversation()
  }
})
</script>

<style scoped>
.workspace {
  display: grid;
  grid-template-columns: 320px minmax(0, 1fr);
  gap: 18px;
  min-height: calc(100vh - 220px);
}

.sidebar,
.chat-panel {
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
}

.sidebar {
  padding: 22px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: 760px;
}

.sidebar-header,
.chat-toolbar,
.composer-header,
.composer-actions,
.toolbar-left,
.toolbar-actions,
.session-title-row,
.session-meta {
  display: flex;
  align-items: center;
}

.sidebar-header,
.chat-toolbar,
.composer-actions {
  justify-content: space-between;
}

.sidebar h2 {
  margin: 0;
  font-size: 16px;
  color: var(--color-text-strong);
}

.chat-toolbar h2 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: var(--color-text-strong);
}

.session-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 0;
  overflow-y: auto;
  padding-right: 4px;
}

.session-card {
  width: 100%;
  border: 1px solid var(--color-border);
  background: #fff;
  border-radius: var(--radius-md);
  padding: 12px;
  display: flex;
  gap: 10px;
  text-align: left;
  color: inherit;
  transition: border-color 0.2s ease;
}

.session-card:hover {
  border-color: rgba(37, 87, 214, 0.2);
}

.session-card.active {
  border-color: var(--color-primary);
}
/* PLACEHOLDER_CHAT_STYLES_PART2 */

.session-select {
  flex: 1;
  min-width: 0;
  padding: 0;
  border: none;
  background: transparent;
  color: inherit;
  text-align: left;
}

.session-select:disabled {
  cursor: not-allowed;
}

.session-main {
  min-width: 0;
  flex: 1;
}

.session-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--color-text-strong);
}

.running-dot {
  font-size: 12px;
  color: var(--color-primary);
  background: var(--color-primary-soft);
  border-radius: 999px;
  padding: 2px 8px;
}

.session-preview {
  margin: 6px 0 8px;
  color: var(--color-muted);
  font-size: 13px;
  line-height: 1.5;
}

.session-meta {
  gap: 10px;
  flex-wrap: wrap;
  font-size: 12px;
  color: var(--color-muted);
}

.empty-sidebar {
  padding: 18px;
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-md);
  color: var(--color-muted);
  background: var(--color-surface-soft);
}

.empty-sidebar p {
  margin: 0 0 6px;
  font-weight: 600;
  color: var(--color-text-strong);
}

.chat-panel {
  min-width: 0;
  min-height: 760px;
  padding: 22px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.toolbar-left,
.toolbar-actions {
  gap: 12px;
}

.chat-toolbar {
  padding-bottom: 14px;
  border-bottom: 1px solid var(--color-border);
}
/* PLACEHOLDER_CHAT_STYLES_PART3 */

.messages-panel {
  min-height: 0;
  flex: 1;
  overflow-y: auto;
  padding: 18px;
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  background: var(--color-surface-soft);
}

.notice {
  margin-bottom: 16px;
  padding: 12px 14px;
  border-radius: var(--radius-sm);
  background: var(--color-primary-soft);
  border: 1px solid rgba(37, 87, 214, 0.1);
  color: var(--color-primary);
}

.notice-error {
  background: rgba(179, 76, 47, 0.08);
  border-color: rgba(179, 76, 47, 0.14);
  color: var(--color-danger);
}

.empty-state {
  min-height: 100%;
  display: grid;
  place-items: center;
  text-align: center;
  padding: 56px 24px;
  border-radius: var(--radius-md);
  background: var(--color-surface-soft);
  border: 1px dashed var(--color-border);
}

.empty-state h3 {
  max-width: 720px;
  margin: 16px 0 8px;
  font-size: 20px;
  line-height: 1.3;
  font-weight: 600;
  color: var(--color-text-strong);
}

.empty-state p {
  max-width: 620px;
  margin: 0 auto;
  color: var(--color-muted);
  line-height: 1.7;
}

.empty-icon {
  width: 56px;
  height: 56px;
  margin: 0 auto 8px;
  display: grid;
  place-items: center;
  border-radius: var(--radius-md);
  background: var(--color-primary-soft);
}

.empty-icon .icon {
  width: 28px;
  height: 28px;
  color: var(--color-primary);
}

.prompt-grid {
  margin-top: 20px;
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 10px;
}

.prompt-chip {
  border: 1px solid var(--color-border);
  background: #fff;
  color: var(--color-text);
  border-radius: 999px;
  padding: 8px 14px;
  font-size: 13px;
  font-weight: 500;
  transition: border-color 0.2s ease, background 0.2s ease;
}

.prompt-chip:hover {
  border-color: rgba(37, 87, 214, 0.2);
  background: var(--color-surface-soft);
}
/* PLACEHOLDER_CHAT_STYLES_PART4 */

.composer-panel {
  padding: 16px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: #fff;
}

.composer-header {
  justify-content: space-between;
  margin-bottom: 10px;
}

.composer-tip,
.streaming-badge {
  font-size: 13px;
  color: var(--color-muted);
}

.streaming-badge {
  color: var(--color-primary);
  font-weight: 600;
}

.composer-input {
  width: 100%;
  min-height: 56px;
  max-height: 220px;
  resize: none;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  padding: 10px 12px;
  background: #fff;
  color: var(--color-text);
  outline: none;
}

.composer-input:focus {
  border-color: var(--color-primary);
  box-shadow: 0 0 0 3px var(--color-primary-soft);
}

.composer-input:disabled {
  background: var(--color-surface-soft);
}

.composer-actions {
  gap: 10px;
  margin-top: 12px;
  justify-content: flex-end;
}

.primary-button,
.ghost-button,
.icon-button {
  border: 1px solid transparent;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  transition: opacity 0.2s ease, background 0.2s ease;
}

.primary-button,
.ghost-button {
  border-radius: 999px;
  padding: 10px 16px;
  font-weight: 600;
  font-size: 14px;
}

.primary-button {
  background: var(--color-primary);
  color: #ffffff;
}

.primary-button:hover:not(:disabled) {
  opacity: 0.9;
}

.ghost-button {
  background: #fff;
  color: var(--color-text);
  border-color: var(--color-border);
}

.ghost-button:hover:not(:disabled) {
  background: var(--color-surface-soft);
}

.admin-entry-button {
  border: 1px solid transparent;
  border-radius: 999px;
  padding: 10px 16px;
  font-weight: 600;
  font-size: 14px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #ffffff;
  background: var(--color-text-strong);
  cursor: pointer;
  transition: opacity 0.2s ease;
}

.admin-entry-button:hover:not(:disabled) {
  opacity: 0.9;
}

.new-chat-button {
  justify-content: center;
}

.icon-button {
  width: 36px;
  height: 36px;
  justify-content: center;
  border-radius: var(--radius-sm);
  background: #fff;
  border-color: var(--color-border);
  color: var(--color-text);
}

.icon-button:hover:not(:disabled) {
  background: var(--color-surface-soft);
}

.delete-button {
  width: 32px;
  height: 32px;
  background: rgba(179, 76, 47, 0.08);
  border-color: rgba(179, 76, 47, 0.12);
  color: var(--color-danger);
  flex: none;
}

.primary-button:disabled,
.ghost-button:disabled,
.admin-entry-button:disabled,
.icon-button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.icon {
  width: 18px;
  height: 18px;
}

.mobile-only,
.sidebar-mask {
  display: none;
}

@media (max-width: 1120px) {
  .workspace {
    grid-template-columns: 1fr;
  }

  .sidebar {
    position: fixed;
    left: 18px;
    top: 18px;
    bottom: 18px;
    width: min(360px, calc(100vw - 36px));
    z-index: 30;
    transform: translateX(-110%);
    transition: transform 0.24s ease;
  }

  .sidebar.sidebar-open {
    transform: translateX(0);
  }

  .sidebar-mask {
    display: block;
    position: fixed;
    inset: 0;
    background: rgba(9, 21, 34, 0.36);
    z-index: 20;
  }

  .mobile-only {
    display: inline-flex;
  }
}

@media (max-width: 768px) {
  .chat-panel,
  .sidebar {
    padding: 16px;
    border-radius: var(--radius-lg);
  }

  .chat-toolbar,
  .composer-header,
  .composer-actions {
    align-items: flex-start;
    flex-direction: column;
  }

  .toolbar-actions {
    width: 100%;
  }

  .toolbar-actions > button {
    flex: 1;
    justify-content: center;
  }
}
</style>
