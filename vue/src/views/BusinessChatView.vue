<template>
  <section class="workspace">
    <aside class="sidebar" :class="{ 'sidebar-open': sidebarOpen }">
      <div class="sidebar-header">
        <div>
          <p class="sidebar-eyebrow">会话管理</p>
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
            <p class="toolbar-eyebrow">当前会话</p>
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
  position: relative;
  border: 1px solid var(--color-border);
  background: var(--color-surface);
  box-shadow: var(--shadow-soft);
  backdrop-filter: blur(18px);
  border-radius: var(--radius-2xl);
}

.sidebar {
  padding: 22px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: 760px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.92), rgba(246, 248, 251, 0.96)),
    var(--color-surface);
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

.sidebar-eyebrow,
.toolbar-eyebrow {
  margin: 0 0 4px;
  font-size: 12px;
  color: var(--color-muted);
  letter-spacing: 0.16em;
  text-transform: uppercase;
}

.sidebar h2,
.chat-toolbar h2 {
  margin: 0;
  color: var(--color-text-strong);
}

.sidebar h2 {
  font-size: 24px;
}

.session-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 0;
  overflow-y: auto;
  padding-right: 4px;
}

.session-card {
  width: 100%;
  border: 1px solid rgba(17, 24, 39, 0.08);
  background: rgba(255, 255, 255, 0.92);
  box-shadow: var(--shadow-card);
  border-radius: 18px;
  padding: 14px;
  display: flex;
  gap: 12px;
  text-align: left;
  color: inherit;
  transition: transform 0.2s ease, border-color 0.2s ease, box-shadow 0.2s ease;
}

.session-card:hover,
.session-card.active {
  transform: translateY(-1px);
  border-color: rgba(37, 87, 214, 0.22);
  box-shadow: 0 18px 28px rgba(15, 23, 36, 0.08);
}

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
  font-size: 15px;
  font-weight: 700;
  color: var(--color-text-strong);
}

.running-dot {
  font-size: 12px;
  color: var(--color-primary);
  background: rgba(37, 87, 214, 0.1);
  border-radius: 999px;
  padding: 4px 8px;
}

.session-preview {
  margin: 8px 0 10px;
  color: var(--color-muted);
  font-size: 14px;
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
  border: 1px dashed rgba(17, 24, 39, 0.16);
  border-radius: 18px;
  color: var(--color-muted);
  background: rgba(244, 246, 249, 0.72);
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
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.84), rgba(248, 249, 252, 0.92)),
    var(--color-surface);
}

.toolbar-left,
.toolbar-actions {
  gap: 12px;
}

.chat-toolbar {
  padding-bottom: 14px;
  border-bottom: 1px solid rgba(17, 24, 39, 0.08);
}

.chat-toolbar h2 {
  font-family: var(--font-sans);
  font-size: clamp(28px, 2.4vw, 36px);
  font-weight: 700;
  letter-spacing: -0.03em;
}

.messages-panel {
  min-height: 0;
  flex: 1;
  overflow-y: auto;
  padding: 18px;
  border-radius: 20px;
  border: 1px solid rgba(17, 24, 39, 0.08);
  background: rgba(255, 255, 255, 0.78);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.5);
}

.notice,
.empty-state,
.composer-panel {
  border-radius: 20px;
}

.notice {
  margin-bottom: 16px;
  padding: 14px 16px;
  background: rgba(37, 87, 214, 0.08);
  border: 1px solid rgba(37, 87, 214, 0.1);
  color: var(--color-primary-strong);
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
  background:
    linear-gradient(135deg, rgba(37, 87, 214, 0.08), rgba(255, 255, 255, 0.7)),
    var(--color-surface-strong);
  border: 1px dashed rgba(17, 24, 39, 0.16);
}

.empty-state h3 {
  max-width: 720px;
  margin: 18px 0 8px;
  font-family: var(--font-sans);
  font-size: clamp(30px, 3.4vw, 40px);
  line-height: 1.08;
  letter-spacing: -0.03em;
  font-weight: 700;
  color: var(--color-text-strong);
}

.empty-state p {
  max-width: 620px;
  margin: 0 auto;
  color: var(--color-muted);
  line-height: 1.8;
}

.empty-icon {
  width: 72px;
  height: 72px;
  margin: 0 auto 10px;
  display: grid;
  place-items: center;
  border-radius: 22px;
  background: linear-gradient(135deg, rgba(37, 87, 214, 0.2), rgba(239, 123, 57, 0.14));
}

.empty-icon .icon {
  width: 32px;
  height: 32px;
}

.prompt-grid {
  margin-top: 24px;
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 12px;
}

.prompt-chip {
  border: 1px solid rgba(37, 87, 214, 0.12);
  background: rgba(255, 255, 255, 0.92);
  color: var(--color-text);
  border-radius: 999px;
  padding: 12px 16px;
  font-size: 13px;
  font-weight: 600;
  box-shadow: 0 10px 18px rgba(15, 23, 36, 0.04);
  transition: transform 0.2s ease, border-color 0.2s ease, background 0.2s ease;
}

.prompt-chip:hover {
  transform: translateY(-1px);
  border-color: rgba(37, 87, 214, 0.2);
  background: rgba(255, 255, 255, 0.98);
}

.composer-panel {
  padding: 18px;
  border: 1px solid rgba(17, 24, 39, 0.08);
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.94), rgba(246, 248, 251, 0.94));
}

.composer-header {
  justify-content: space-between;
  margin-bottom: 12px;
}

.composer-tip,
.streaming-badge {
  font-size: 13px;
  color: var(--color-muted);
}

.streaming-badge {
  color: var(--color-primary-strong);
  font-weight: 600;
}

.composer-input {
  width: 100%;
  min-height: 62px;
  max-height: 220px;
  resize: none;
  border: 1px solid rgba(17, 24, 39, 0.1);
  border-radius: 16px;
  padding: 16px 18px;
  background: rgba(255, 255, 255, 0.95);
  color: var(--color-text);
  outline: none;
}

.composer-input:focus {
  border-color: rgba(37, 87, 214, 0.32);
  box-shadow: 0 0 0 4px rgba(37, 87, 214, 0.08);
}

.composer-input:disabled {
  background: rgba(239, 244, 248, 0.9);
}

.composer-actions {
  gap: 12px;
  margin-top: 14px;
  justify-content: flex-end;
}

.primary-button,
.ghost-button,
.icon-button {
  border: 1px solid transparent;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  transition: transform 0.2s ease, opacity 0.2s ease, background 0.2s ease;
}

.primary-button,
.ghost-button {
  border-radius: 999px;
  padding: 12px 18px;
  font-weight: 600;
}

.primary-button {
  background: linear-gradient(135deg, var(--color-primary), var(--color-primary-strong));
  color: #ffffff;
  box-shadow: 0 14px 24px rgba(37, 87, 214, 0.18);
}

.ghost-button {
  background: rgba(255, 255, 255, 0.86);
  color: var(--color-text);
  border-color: rgba(17, 24, 39, 0.08);
}

.admin-entry-button {
  border: 1px solid transparent;
  border-radius: 999px;
  padding: 12px 18px;
  font-weight: 700;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: #ffffff;
  background: linear-gradient(135deg, #111827, #2557d6);
  box-shadow: 0 16px 28px rgba(17, 24, 39, 0.18);
  transition: transform 0.2s ease, box-shadow 0.2s ease, opacity 0.2s ease;
}

.new-chat-button {
  justify-content: center;
}

.icon-button {
  width: 40px;
  height: 40px;
  justify-content: center;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.86);
  border-color: rgba(17, 24, 39, 0.08);
  color: var(--color-text);
}

.delete-button {
  width: 36px;
  height: 36px;
  background: rgba(179, 76, 47, 0.08);
  border-color: rgba(179, 76, 47, 0.12);
  color: var(--color-danger);
  flex: none;
}

.primary-button:hover:not(:disabled),
.ghost-button:hover:not(:disabled),
.admin-entry-button:hover:not(:disabled),
.icon-button:hover:not(:disabled) {
  transform: translateY(-1px);
}

.primary-button:disabled,
.ghost-button:disabled,
.admin-entry-button:disabled,
.icon-button:disabled {
  opacity: 0.55;
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
    padding: 18px;
    border-radius: 24px;
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
