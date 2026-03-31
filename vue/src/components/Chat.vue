<template>
  <article class="message-card" :class="{ 'message-user': isUser, 'message-assistant': !isUser }">
    <div class="avatar">
      <UserIcon v-if="isUser" class="icon" />
      <SparklesIcon v-else class="icon" />
    </div>

    <div class="bubble">
      <div class="bubble-header">
        <div>
          <p class="role-name">{{ isUser ? '你' : '智能助手' }}</p>
          <p class="message-time">{{ formatTime(message.updatedAt || message.createdAt) }}</p>
        </div>
        <button class="copy-button" type="button" :title="copyButtonTitle" @click="copyContent">
          <CheckIcon v-if="copied" class="icon" />
          <DocumentDuplicateIcon v-else class="icon" />
        </button>
      </div>

      <div v-if="isUser" class="plain-text">{{ message.content }}</div>
      <div v-else ref="contentRef" class="markdown-body" v-html="renderedContent"></div>
      <div v-if="isStreaming" class="stream-cursor"></div>

      <details v-if="message.thinkingSteps?.length" class="info-panel">
        <summary>思考过程（{{ message.thinkingSteps.length }}）</summary>
        <ol class="thinking-list">
          <li v-for="(step, index) in message.thinkingSteps" :key="`${message.id}-thinking-${index}`">
            {{ step }}
          </li>
        </ol>
      </details>

      <section v-if="message.references?.length" class="info-panel">
        <div class="panel-title">
          <LinkIcon class="icon" />
          <span>参考来源</span>
        </div>
        <a
          v-for="(reference, index) in message.references"
          :key="`${message.id}-reference-${index}`"
          class="reference-item"
          :href="reference.url"
          target="_blank"
          rel="noreferrer"
        >
          <strong>{{ reference.title || `来源 ${index + 1}` }}</strong>
          <span>{{ reference.snippet || reference.url }}</span>
        </a>
      </section>

      <section v-if="message.recommendations?.length" class="info-panel">
        <div class="panel-title">
          <LightBulbIcon class="icon" />
          <span>继续追问</span>
        </div>
        <div class="recommendation-list">
          <button
            v-for="(item, index) in message.recommendations"
            :key="`${message.id}-recommend-${index}`"
            class="recommendation-chip"
            type="button"
            @click="$emit('recommend', item)"
          >
            {{ item }}
          </button>
        </div>
      </section>

      <section v-if="message.usedTools?.length" class="info-panel">
        <div class="panel-title">
          <WrenchScrewdriverIcon class="icon" />
          <span>使用的工具</span>
        </div>
        <div class="tool-tag-list">
          <span v-for="tool in message.usedTools" :key="`${message.id}-${tool}`" class="tool-tag">
            {{ tool }}
          </span>
        </div>
      </section>

      <div v-if="message.errorMessage" class="status-panel status-error">
        {{ message.errorMessage }}
      </div>
      <div v-else-if="message.statusText" class="status-panel">
        {{ message.statusText }}
      </div>

      <div v-if="hasLatency" class="latency-row">
        <span v-if="message.firstResponseTimeMs != null">首字响应 {{ formatLatency(message.firstResponseTimeMs) }}</span>
        <span v-if="message.totalResponseTimeMs != null">总耗时 {{ formatLatency(message.totalResponseTimeMs) }}</span>
      </div>
    </div>
  </article>
</template>

<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import DOMPurify from 'dompurify'
import hljs from 'highlight.js/lib/core'
import bash from 'highlight.js/lib/languages/bash'
import java from 'highlight.js/lib/languages/java'
import javascript from 'highlight.js/lib/languages/javascript'
import json from 'highlight.js/lib/languages/json'
import sql from 'highlight.js/lib/languages/sql'
import xml from 'highlight.js/lib/languages/xml'
import yaml from 'highlight.js/lib/languages/yaml'
import { marked } from 'marked'
import {
  CheckIcon,
  DocumentDuplicateIcon,
  LightBulbIcon,
  LinkIcon,
  SparklesIcon,
  UserIcon,
  WrenchScrewdriverIcon
} from '@heroicons/vue/24/outline'

const props = defineProps({
  message: {
    type: Object,
    required: true
  },
  isStreaming: {
    type: Boolean,
    default: false
  }
})

defineEmits(['recommend'])

const contentRef = ref(null)
const copied = ref(false)

hljs.registerLanguage('bash', bash)
hljs.registerLanguage('java', java)
hljs.registerLanguage('javascript', javascript)
hljs.registerLanguage('json', json)
hljs.registerLanguage('sql', sql)
hljs.registerLanguage('xml', xml)
hljs.registerLanguage('yaml', yaml)

marked.setOptions({
  breaks: true,
  gfm: true
})

const isUser = computed(() => props.message.role === 'user')
const copyButtonTitle = computed(() => (copied.value ? '已复制' : '复制内容'))
const hasLatency = computed(() => {
  return props.message.firstResponseTimeMs != null || props.message.totalResponseTimeMs != null
})

const renderedContent = computed(() => {
  if (!props.message.content) {
    return ''
  }

  const rendered = marked.parse(props.message.content)
  return DOMPurify.sanitize(rendered, {
    ADD_ATTR: ['target', 'rel', 'class']
  })
})

async function highlightCodeBlocks() {
  await nextTick()

  if (!contentRef.value || isUser.value) {
    return
  }

  contentRef.value.querySelectorAll('pre code').forEach((block) => {
    hljs.highlightElement(block)
  })
}

async function copyContent() {
  try {
    await navigator.clipboard.writeText(props.message.content || '')
    copied.value = true
    setTimeout(() => {
      copied.value = false
    }, 1800)
  } catch (error) {
    console.error('复制消息失败', error)
  }
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
    hour: '2-digit',
    minute: '2-digit'
  }).format(date)
}

function formatLatency(value) {
  if (value == null) {
    return ''
  }

  if (value < 1000) {
    return `${value} ms`
  }

  return `${(value / 1000).toFixed(2)} s`
}

watch(
  () => props.message.content,
  () => {
    if (!isUser.value) {
      highlightCodeBlocks()
    }
  }
)

onMounted(() => {
  if (!isUser.value) {
    highlightCodeBlocks()
  }
})
</script>

<style scoped>
.message-card {
  display: flex;
  gap: 14px;
  margin-bottom: 20px;
}

.message-user {
  flex-direction: row-reverse;
}

.avatar {
  width: 44px;
  height: 44px;
  flex: none;
  display: grid;
  place-items: center;
  border-radius: 16px;
  background: linear-gradient(135deg, rgba(37, 87, 214, 0.16), rgba(239, 123, 57, 0.14));
  color: var(--color-primary-strong);
  border: 1px solid rgba(17, 24, 39, 0.08);
}

.message-user .avatar {
  background: rgba(37, 87, 214, 0.1);
}

.bubble {
  min-width: 0;
  flex: 1;
  padding: 18px;
  border-radius: 20px;
  border: 1px solid rgba(17, 24, 39, 0.08);
  background: rgba(255, 255, 255, 0.92);
  box-shadow: var(--shadow-card);
}

.message-user .bubble {
  background: linear-gradient(135deg, rgba(37, 87, 214, 0.08), rgba(37, 87, 214, 0.03));
}

.bubble-header,
.panel-title,
.latency-row,
.tool-tag-list,
.recommendation-list {
  display: flex;
  align-items: center;
}

.bubble-header {
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.role-name {
  margin: 0;
  font-weight: 700;
  color: var(--color-text-strong);
}

.message-time {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--color-muted);
}

.copy-button {
  width: 36px;
  height: 36px;
  border: 1px solid rgba(17, 24, 39, 0.08);
  flex: none;
  border-radius: 12px;
  display: grid;
  place-items: center;
  background: rgba(255, 255, 255, 0.88);
  color: var(--color-text);
}

.plain-text {
  white-space: pre-wrap;
  line-height: 1.75;
  word-break: break-word;
}

.markdown-body {
  line-height: 1.75;
  color: var(--color-text);
  word-break: break-word;
}

.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3) {
  color: var(--color-text-strong);
  letter-spacing: -0.02em;
}

.markdown-body :deep(a) {
  color: var(--color-primary-strong);
  text-decoration: underline;
  text-decoration-color: rgba(37, 87, 214, 0.22);
  text-underline-offset: 3px;
}

.markdown-body :deep(p:first-child) {
  margin-top: 0;
}

.markdown-body :deep(p:last-child) {
  margin-bottom: 0;
}

.markdown-body :deep(pre) {
  overflow-x: auto;
  border-radius: 16px;
  padding: 14px;
  background: #0f1724;
}

.markdown-body :deep(code:not(pre code)) {
  padding: 2px 6px;
  border-radius: 8px;
  background: rgba(17, 24, 39, 0.08);
}

.stream-cursor {
  width: 10px;
  height: 20px;
  margin-top: 12px;
  border-radius: 999px;
  background: var(--color-primary);
  animation: pulse 1s infinite;
}

.info-panel,
.status-panel {
  margin-top: 14px;
  padding: 14px;
  border-radius: 16px;
  background: var(--color-surface-soft);
  border: 1px solid rgba(17, 24, 39, 0.06);
}

.panel-title {
  gap: 8px;
  margin-bottom: 10px;
  font-weight: 700;
}

.thinking-list {
  margin: 0;
  padding-left: 18px;
  color: var(--color-muted);
}

.thinking-list li + li {
  margin-top: 8px;
}

.reference-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 14px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.9);
  border: 1px solid rgba(17, 24, 39, 0.06);
  text-decoration: none;
  color: inherit;
}

.reference-item + .reference-item {
  margin-top: 10px;
}

.reference-item strong {
  color: var(--color-text-strong);
}

.reference-item span {
  color: var(--color-muted);
  font-size: 14px;
}

.recommendation-list,
.tool-tag-list,
.latency-row {
  gap: 10px;
  flex-wrap: wrap;
}

.recommendation-chip,
.tool-tag {
  border-radius: 999px;
  padding: 9px 12px;
  font-size: 13px;
}

.recommendation-chip {
  border: 1px solid rgba(37, 87, 214, 0.12);
  background: #ffffff;
  color: var(--color-text);
}

.tool-tag {
  background: rgba(37, 87, 214, 0.08);
  color: var(--color-primary-strong);
}

.status-panel {
  color: var(--color-primary-strong);
}

.status-error {
  background: rgba(179, 76, 47, 0.08);
  color: var(--color-danger);
}

.latency-row {
  margin-top: 12px;
  font-size: 12px;
  color: var(--color-muted);
}

.icon {
  width: 18px;
  height: 18px;
}

summary {
  cursor: pointer;
  font-weight: 700;
}

@keyframes pulse {
  0%,
  100% {
    opacity: 0.3;
  }

  50% {
    opacity: 1;
  }
}

@media (max-width: 768px) {
  .message-card {
    gap: 10px;
  }

  .avatar {
    width: 38px;
    height: 38px;
  }

  .bubble {
    padding: 16px;
    border-radius: 20px;
  }
}
</style>
