<template>
  <router-view v-if="isFullscreenLayout" />

  <div v-else class="app-shell">
    <header class="app-header">
      <div class="brand-block">
        <p class="eyebrow">Super Agent Workspace</p>
        <h1>对话工作台与知识运营控制台共用一套更成熟的产品界面。</h1>
        <p class="subtitle">
          面向 `super-agent-business-chat` 的专用前端，保留现有聊天能力，并新增独立的后台管理工作台，用来演示文档接入、策略确认、索引构建与 RAG 检索验证的完整流程。
        </p>

        <div class="brand-tags">
          <span>Streaming Chat</span>
          <span>Document Intake</span>
          <span>RAG Validation</span>
        </div>
      </div>

      <div class="header-card">
        <span class="header-label">Visual Direction</span>
        <strong>编辑感标题、深色控制带、浅色工作面和更高的信息密度</strong>
        <span class="header-note">避免通用 AI 样式里常见的发光渐变、大圆角和无差别卡片堆叠。</span>
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
  padding: 24px;
}

.app-header {
  max-width: 1440px;
  margin: 0 auto 20px;
  display: grid;
  grid-template-columns: minmax(0, 1.3fr) minmax(300px, 0.7fr);
  gap: 20px;
  align-items: stretch;
}

.brand-block,
.header-card {
  border: 1px solid var(--color-border);
  background: var(--color-surface);
  box-shadow: var(--shadow-soft);
  backdrop-filter: blur(18px);
}

.brand-block {
  padding: 30px 34px;
  border-radius: var(--radius-2xl);
  position: relative;
  overflow: hidden;
}

.brand-block::after {
  content: '';
  position: absolute;
  inset: auto -60px -60px auto;
  width: 220px;
  height: 220px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(37, 87, 214, 0.18), transparent 68%);
  pointer-events: none;
}

.eyebrow {
  margin: 0 0 8px;
  font-size: 12px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--color-muted);
}

.brand-block h1 {
  margin: 0;
  max-width: 920px;
  font-family: var(--font-sans);
  font-size: clamp(34px, 4.2vw, 60px);
  line-height: 1.02;
  letter-spacing: -0.03em;
  font-weight: 700;
  color: var(--color-text-strong);
}

.subtitle {
  max-width: 760px;
  margin: 16px 0 0;
  font-size: 15px;
  line-height: 1.8;
  color: var(--color-muted);
}

.brand-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 22px;
}

.brand-tags span {
  padding: 8px 12px;
  border-radius: 999px;
  border: 1px solid rgba(37, 87, 214, 0.14);
  background: rgba(255, 255, 255, 0.76);
  color: var(--color-muted-strong);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;
}

.header-card {
  border-radius: var(--radius-xl);
  padding: 24px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  gap: 12px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.9), rgba(246, 248, 251, 0.96)),
    var(--color-surface);
}

.header-label,
.header-note {
  font-size: 13px;
  color: var(--color-muted);
}

.header-card strong {
  font-size: 22px;
  line-height: 1.45;
  color: var(--color-text-strong);
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
    grid-template-columns: 1fr;
  }
}
</style>
