<template>
  <router-view v-if="isFullscreenLayout" />

  <div v-else class="app-shell">
    <header class="app-header">
      <div class="brand-block">
        <p class="eyebrow">Spring AI Alibaba + Business Shell</p>
        <h1>Super Business Chat Agent</h1>
        <p class="subtitle">
          面向 `super-agent-business-chat` 的专用前端，保留现有聊天能力，并新增独立的后台管理工作台，用来演示文档接入、策略确认、索引构建与 RAG 检索验证的完整流程。
        </p>
      </div>

      <div class="header-card">
        <span class="header-label">双工作区模式</span>
        <strong>聊天区 + 管理后台</strong>
        <span class="header-note">聊天链路保持不动，管理能力独立扩展</span>
      </div>
    </header>

    <main class="app-main">
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()

const isFullscreenLayout = computed(() => route.meta?.layout === 'fullscreen')
</script>

<style scoped>
.app-shell {
  min-height: 100vh;
  padding: 28px;
}

.app-header {
  max-width: 1440px;
  margin: 0 auto 24px;
  display: flex;
  gap: 20px;
  align-items: stretch;
  justify-content: space-between;
}

.brand-block,
.header-card {
  border: 1px solid var(--color-border);
  background: var(--color-surface);
  box-shadow: var(--shadow-soft);
  backdrop-filter: blur(18px);
  border-radius: 28px;
}

.brand-block {
  flex: 1;
  padding: 28px 32px;
}

.eyebrow {
  margin: 0 0 8px;
  font-size: 12px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--color-muted);
}

.brand-block h1 {
  margin: 0;
  font-size: clamp(28px, 4vw, 44px);
  line-height: 1.05;
  letter-spacing: -0.04em;
}

.subtitle {
  max-width: 780px;
  margin: 12px 0 0;
  color: var(--color-muted);
}

.header-card {
  min-width: 280px;
  padding: 24px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 10px;
}

.header-label,
.header-note {
  font-size: 13px;
  color: var(--color-muted);
}

.header-card strong {
  font-size: 18px;
}

.app-main {
  max-width: 1440px;
  margin: 0 auto;
}

@media (max-width: 960px) {
  .app-shell {
    padding: 18px;
  }

  .app-header {
    flex-direction: column;
  }
}
</style>
