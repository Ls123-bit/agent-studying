<template>
  <div class="app-shell">
    <header class="app-header">
      <div class="brand-block">
        <p class="eyebrow">Spring AI Alibaba + Business Shell</p>
        <h1>Super Business Chat Agent</h1>
        <p class="subtitle">
          面向 `super-agent-business-chat` 的专用前端，只保留会话、流式回答、引用来源、推荐追问和停止生成这些当前项目真正需要的能力。
        </p>
      </div>

      <div class="header-card">
        <span class="header-label">后端接口</span>
        <strong>`POST /api/chat/stream`</strong>
        <span class="header-note">SSE JSON Event Stream</span>
      </div>
    </header>

    <main class="app-main">
      <router-view />
    </main>
  </div>
</template>

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
