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
          <button class="ghost-button" type="button" :disabled="loadingSessions || loadingConversation" @click="reloadCurrentConversation">
            <ArrowPathIcon class="icon" />
            刷新
          </button>
          <button
            class="danger-button"
            type="button"
            :disabled="!isStreaming || isStopping"
            @click="stopStreaming"
          >
            <StopIcon class="icon" />
            {{ isStopping ? '停止中...' : '停止生成' }}
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
          <h3>开始一轮新的业务对话</h3>
          <p>
            当前页面已经适配 `super-agent-business-chat` 的流式协议，支持会话管理、引用来源、推荐追问和中途停止生成。
          </p>
          <div class="prompt-grid">
            <button type="button" class="prompt-chip" @click="sendMessage('帮我总结一下 Spring AI Alibaba ReactAgent 和手搓 ReAct 的差异')">
              ReactAgent 能力对比
            </button>
            <button type="button" class="prompt-chip" @click="sendMessage('请帮我设计一个智能对话系统的后端分层方案')">
              智能对话后端设计
            </button>
            <button type="button" class="prompt-chip" @click="sendMessage('结合联网搜索，分析一下当前项目适合如何做引用来源展示')">
              引用来源展示方案
            </button>
          </div>
        </div>

        <Chat
          v-for="message in displayMessages"
          :key="message.id"
          :message="message"
          :is-streaming="isStreaming && message.id === currentAssistantMessageId"
          @recommend="sendMessage"
        />
      </div>

      <footer class="composer-panel">
        <div class="composer-header">
          <div class="composer-tip">
            <ChatBubbleLeftRightIcon class="icon" />
            <span>按 Enter 发送，Shift + Enter 换行。</span>
          </div>
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
          <span class="conversation-id">conversationId: {{ currentConversationId || '将在发送时生成' }}</span>
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
import {
  ArrowPathIcon,
  Bars3Icon,
  ChatBubbleLeftRightIcon,
  PaperAirplaneIcon,
  PlusIcon,
  SparklesIcon,
  StopIcon,
  TrashIcon,
  XMarkIcon
} from '@heroicons/vue/24/outline'
import Chat from '../components/Chat.vue'
import { APIError, chatApi, createConversationId } from '../api/api'

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

// 后端把每一轮对话结构化成 turn，这里把 turn 展开成用户消息 + 助手消息，
// 这样前端展示层就不需要感知数据库 record 的结构细节。
function mapTurnsToMessages(turns = []) {
  return turns.flatMap((turn) => {
    const userMessage = {
      id: `turn-${turn.turnId}-user`,
      role: 'user',
      content: turn.question || '',
      createdAt: turn.createdAt
    }

    const assistantMessage = {
      id: `turn-${turn.turnId}-assistant`,
      role: 'assistant',
      content: turn.answer || '',
      thinkingSteps: turn.thinkingSteps || [],
      references: turn.references || [],
      recommendations: turn.recommendations || [],
      usedTools: turn.usedTools || [],
      status: turn.status || '',
      statusText: '',
      errorMessage: turn.errorMessage || '',
      firstResponseTimeMs: turn.firstResponseTimeMs,
      totalResponseTimeMs: turn.totalResponseTimeMs,
      createdAt: turn.createdAt,
      updatedAt: turn.updatedAt
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
    displayMessages.value = mapTurnsToMessages(session.turns || [])
    upsertSession(session)
    sidebarOpen.value = false
    await scrollToBottom()
  } catch (error) {
    pageError.value = normalizeError(error, '加载会话详情失败')
  } finally {
    loadingConversation.value = false
  }
}

async function reloadCurrentConversation() {
  if (currentConversationId.value) {
    await loadConversation(currentConversationId.value)
    return
  }

  await refreshSessions()
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
  gap: 20px;
  min-height: calc(100vh - 250px);
}

.sidebar,
.chat-panel {
  position: relative;
  border: 1px solid var(--color-border);
  background: var(--color-surface);
  box-shadow: var(--shadow-soft);
  backdrop-filter: blur(18px);
  border-radius: var(--radius-xl);
}

.sidebar {
  padding: 22px;
  display: flex;
  flex-direction: column;
  gap: 18px;
  min-height: 780px;
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
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.sidebar h2,
.chat-toolbar h2 {
  margin: 0;
  font-size: 22px;
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
  border: 1px solid transparent;
  background: var(--color-surface-strong);
  box-shadow: var(--shadow-card);
  border-radius: var(--radius-md);
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
  border-color: rgba(15, 139, 141, 0.24);
  box-shadow: 0 18px 32px rgba(6, 95, 115, 0.12);
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
}

.running-dot {
  font-size: 12px;
  color: var(--color-success);
  background: rgba(15, 118, 110, 0.12);
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
  border: 1px dashed rgba(15, 139, 141, 0.2);
  border-radius: var(--radius-md);
  color: var(--color-muted);
}

.empty-sidebar p {
  margin: 0 0 6px;
  font-weight: 600;
  color: var(--color-text);
}

.chat-panel {
  min-width: 0;
  padding: 22px;
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.toolbar-left,
.toolbar-actions,
.composer-tip {
  gap: 12px;
}

.messages-panel {
  min-height: 0;
  flex: 1;
  overflow-y: auto;
  padding: 8px 4px 12px;
}

.notice,
.empty-state,
.composer-panel {
  border-radius: var(--radius-lg);
}

.notice {
  margin-bottom: 16px;
  padding: 14px 16px;
  background: rgba(15, 139, 141, 0.08);
  color: var(--color-primary-strong);
}

.notice-error {
  background: rgba(194, 65, 12, 0.1);
  color: var(--color-danger);
}

.empty-state {
  min-height: 100%;
  display: grid;
  place-items: center;
  text-align: center;
  padding: 64px 24px;
  background:
    linear-gradient(135deg, rgba(15, 139, 141, 0.08), rgba(255, 255, 255, 0.52)),
    var(--color-surface-strong);
  border: 1px dashed rgba(15, 139, 141, 0.2);
}

.empty-state h3 {
  margin: 18px 0 8px;
  font-size: 28px;
}

.empty-state p {
  max-width: 680px;
  margin: 0 auto;
  color: var(--color-muted);
}

.empty-icon {
  width: 72px;
  height: 72px;
  margin: 0 auto 10px;
  display: grid;
  place-items: center;
  border-radius: 24px;
  background: linear-gradient(135deg, rgba(15, 139, 141, 0.2), rgba(249, 115, 22, 0.18));
}

.empty-icon .icon {
  width: 32px;
  height: 32px;
}

.prompt-grid {
  margin-top: 28px;
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  justify-content: center;
}

.prompt-chip {
  border: 1px solid rgba(15, 139, 141, 0.14);
  background: #ffffff;
  border-radius: 999px;
  padding: 12px 16px;
  max-width: 320px;
  color: var(--color-text);
}

.composer-panel {
  padding: 18px;
  border: 1px solid rgba(15, 139, 141, 0.12);
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.9), rgba(245, 250, 252, 0.9));
}

.composer-header {
  justify-content: space-between;
  margin-bottom: 12px;
}

.composer-tip,
.streaming-badge,
.conversation-id {
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
  border: 1px solid rgba(15, 139, 141, 0.12);
  border-radius: 18px;
  padding: 16px 18px;
  background: rgba(255, 255, 255, 0.95);
  color: var(--color-text);
  outline: none;
}

.composer-input:focus {
  border-color: rgba(15, 139, 141, 0.4);
  box-shadow: 0 0 0 4px rgba(15, 139, 141, 0.08);
}

.composer-input:disabled {
  background: rgba(239, 244, 248, 0.9);
}

.composer-actions {
  gap: 14px;
  margin-top: 14px;
  flex-wrap: wrap;
}

.primary-button,
.ghost-button,
.danger-button,
.icon-button {
  border: none;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  transition: transform 0.2s ease, opacity 0.2s ease, background 0.2s ease;
}

.primary-button,
.ghost-button,
.danger-button {
  border-radius: 999px;
  padding: 12px 18px;
  font-weight: 600;
}

.primary-button {
  background: linear-gradient(135deg, var(--color-primary), var(--color-primary-strong));
  color: #ffffff;
}

.ghost-button {
  background: rgba(15, 139, 141, 0.08);
  color: var(--color-primary-strong);
}

.danger-button {
  background: rgba(194, 65, 12, 0.1);
  color: var(--color-danger);
}

.new-chat-button {
  justify-content: center;
}

.icon-button {
  width: 40px;
  height: 40px;
  justify-content: center;
  border-radius: 50%;
  background: rgba(15, 139, 141, 0.08);
  color: var(--color-primary-strong);
}

.delete-button {
  width: 36px;
  height: 36px;
  background: rgba(194, 65, 12, 0.08);
  color: var(--color-danger);
  flex: none;
}

.primary-button:hover:not(:disabled),
.ghost-button:hover:not(:disabled),
.danger-button:hover:not(:disabled),
.icon-button:hover:not(:disabled),
.prompt-chip:hover {
  transform: translateY(-1px);
}

.primary-button:disabled,
.ghost-button:disabled,
.danger-button:disabled,
.icon-button:disabled,
.prompt-chip:disabled {
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

  .conversation-id {
    width: 100%;
    word-break: break-all;
  }
}
</style>
