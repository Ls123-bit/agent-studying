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

      <section v-if="showRecommendationBar" class="recommend-bar">
        <p class="recommend-label">推荐追问</p>
        <div class="recommend-list">
          <button
            v-for="(item, index) in message.recommendations"
            :key="`${message.id}-recommend-${index}`"
            class="recommend-chip"
            type="button"
            @click="$emit('recommend', item)"
          >
            {{ item }}
          </button>
        </div>
      </section>
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
  SparklesIcon,
  UserIcon
} from '@heroicons/vue/24/outline'

const props = defineProps({
  message: {
    type: Object,
    required: true
  },
  isStreaming: {
    type: Boolean,
    default: false
  },
  showRecommendations: {
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
const showRecommendationBar = computed(() => {
  return !isUser.value && props.showRecommendations && Array.isArray(props.message.recommendations) && props.message.recommendations.length > 0
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
  margin-bottom: 18px;
}

.message-user {
  flex-direction: row-reverse;
}

.avatar {
  width: 42px;
  height: 42px;
  flex: none;
  display: grid;
  place-items: center;
  border-radius: 14px;
  background: linear-gradient(135deg, rgba(37, 87, 214, 0.16), rgba(239, 123, 57, 0.12));
  border: 1px solid rgba(17, 24, 39, 0.08);
  color: var(--color-primary-strong);
}

.message-user .avatar {
  background: rgba(37, 87, 214, 0.1);
}

.bubble {
  min-width: 0;
  flex: 1;
  padding: 18px;
  border-radius: 18px;
  border: 1px solid rgba(17, 24, 39, 0.08);
  background: rgba(255, 255, 255, 0.94);
  box-shadow: var(--shadow-card);
}

.message-user .bubble {
  max-width: min(760px, 100%);
  background: linear-gradient(135deg, rgba(37, 87, 214, 0.08), rgba(37, 87, 214, 0.03));
}

.bubble-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.role-name {
  margin: 0;
  color: var(--color-text-strong);
  font-weight: 700;
}

.message-time {
  margin: 4px 0 0;
  color: var(--color-muted);
  font-size: 12px;
}

.copy-button {
  width: 36px;
  height: 36px;
  flex: none;
  display: grid;
  place-items: center;
  border: 1px solid rgba(17, 24, 39, 0.08);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.88);
  color: var(--color-text);
}

.plain-text {
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.8;
}

.markdown-body {
  color: var(--color-text);
  line-height: 1.8;
  word-break: break-word;
}

.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3) {
  margin-top: 1.2em;
  margin-bottom: 0.6em;
  color: var(--color-text-strong);
  letter-spacing: -0.02em;
}

.markdown-body :deep(p:first-child) {
  margin-top: 0;
}

.markdown-body :deep(p:last-child) {
  margin-bottom: 0;
}

.markdown-body :deep(a) {
  color: var(--color-primary-strong);
  text-decoration: underline;
  text-decoration-color: rgba(37, 87, 214, 0.22);
  text-underline-offset: 3px;
}

.markdown-body :deep(pre) {
  overflow-x: auto;
  margin: 16px 0;
  padding: 14px;
  border-radius: 14px;
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

.recommend-bar {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid rgba(17, 24, 39, 0.08);
}

.recommend-label {
  margin: 0 0 10px;
  color: var(--color-muted);
  font-size: 12px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.recommend-list {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.recommend-chip {
  border: 1px solid rgba(37, 87, 214, 0.12);
  background: rgba(37, 87, 214, 0.06);
  color: var(--color-primary-strong);
  border-radius: 999px;
  padding: 10px 14px;
  font-size: 13px;
  font-weight: 600;
  transition: transform 0.2s ease, background 0.2s ease, border-color 0.2s ease;
}

.recommend-chip:hover {
  transform: translateY(-1px);
  background: rgba(37, 87, 214, 0.1);
  border-color: rgba(37, 87, 214, 0.18);
}

.icon {
  width: 18px;
  height: 18px;
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
  }
}
</style>
