<template>
  <section class="admin-shell">
    <aside class="admin-sidebar" :class="{ 'admin-sidebar-open': sidebarOpen }">
      <div class="sidebar-brand">
        <div class="brand-mark">S</div>
        <div>
          <p>Super Agent</p>
          <h1>管理后台</h1>
        </div>
      </div>

      <nav class="sidebar-nav">
        <RouterLink
          v-for="item in navItems"
          :key="item.to"
          :to="item.to"
          class="nav-item"
          :class="{ active: route.path === item.to }"
          @click="sidebarOpen = false"
        >
          <component :is="item.icon" class="nav-icon" />
          <span>{{ item.label }}</span>
        </RouterLink>
      </nav>

      <div class="sidebar-footer">
        <div class="demo-user">
          <div class="demo-avatar">{{ username.slice(0, 1).toUpperCase() }}</div>
          <div>
            <p>演示账号</p>
            <strong>{{ username }}</strong>
          </div>
        </div>
        <button class="sidebar-logout" type="button" @click="logout">
          <ArrowLeftOnRectangleIcon class="nav-icon" />
          退出演示登录
        </button>
      </div>
    </aside>

    <div v-if="sidebarOpen" class="sidebar-mask" @click="sidebarOpen = false"></div>

    <div class="admin-main">
      <header class="admin-header">
        <div class="header-title">
          <button class="menu-button mobile-only" type="button" @click="sidebarOpen = true">
            <Bars3Icon class="nav-icon" />
          </button>
          <div>
            <p>Knowledge Operations Console</p>
            <h2>{{ pageTitle }}</h2>
          </div>
        </div>

        <div class="header-actions">
          <RouterLink to="/chat" class="header-button">
            <ChatBubbleLeftRightIcon class="nav-icon" />
            返回聊天
          </RouterLink>
          <div class="user-chip">
            <div class="user-chip-avatar">{{ username.slice(0, 1).toUpperCase() }}</div>
            <span>{{ username }}</span>
          </div>
        </div>
      </header>

      <main class="admin-content">
        <RouterView />
      </main>
    </div>
  </section>
</template>

<script setup>
import { computed, ref } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import {
  ArrowLeftOnRectangleIcon,
  Bars3Icon,
  ChatBubbleLeftRightIcon,
  ClipboardDocumentListIcon,
  HomeModernIcon,
  MagnifyingGlassCircleIcon
} from '@heroicons/vue/24/outline'
import { getAdminUsername, logoutAdminDemo } from '../../utils/adminAuth'

const route = useRoute()
const router = useRouter()
const sidebarOpen = ref(false)

const navItems = [
  {
    to: '/admin/dashboard',
    label: '运营总览',
    icon: HomeModernIcon
  },
  {
    to: '/admin/documents',
    label: '文档接入',
    icon: ClipboardDocumentListIcon
  },
  {
    to: '/admin/qa',
    label: '检索验证',
    icon: MagnifyingGlassCircleIcon
  }
]

const pageTitle = computed(() => route.meta?.title || '管理后台')
const username = computed(() => getAdminUsername())

function logout() {
  logoutAdminDemo()
  router.replace('/admin/login')
}
</script>

<style scoped>
.admin-shell {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr);
  background:
    linear-gradient(180deg, rgba(10, 15, 24, 0.02), rgba(10, 15, 24, 0)),
    linear-gradient(180deg, #f7f8fa 0%, #edf1f5 58%, #e8edf2 100%);
}

.admin-sidebar {
  position: relative;
  z-index: 5;
  display: flex;
  flex-direction: column;
  justify-content: flex-start;
  padding: 24px 18px 20px;
  background: var(--color-admin-sidebar);
  color: #ffffff;
  box-shadow:
    inset -1px 0 0 rgba(255, 255, 255, 0.06),
    24px 0 60px rgba(15, 23, 36, 0.14);
}

.sidebar-brand {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 10px 8px 24px;
}

.brand-mark {
  width: 48px;
  height: 48px;
  display: grid;
  place-items: center;
  border-radius: 15px;
  background:
    linear-gradient(145deg, rgba(255, 255, 255, 0.16), rgba(37, 87, 214, 0.44)),
    rgba(255, 255, 255, 0.08);
  font-weight: 800;
  font-size: 20px;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.2);
}

.sidebar-brand p,
.demo-user p,
.header-title p {
  margin: 0;
  font-size: 12px;
  letter-spacing: 0.16em;
  text-transform: uppercase;
}

.sidebar-brand h1,
.header-title h2,
.demo-user strong {
  margin: 4px 0 0;
}

.sidebar-brand h1 {
  font-size: 21px;
}

.sidebar-nav {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.nav-item,
.header-button,
.sidebar-logout {
  text-decoration: none;
  display: inline-flex;
  align-items: center;
  gap: 12px;
  border-radius: 16px;
  border: 1px solid transparent;
  transition: transform 0.22s ease, background 0.22s ease, border-color 0.22s ease, color 0.22s ease;
}

.nav-item {
  padding: 14px 16px;
  color: rgba(255, 255, 255, 0.72);
  background: rgba(255, 255, 255, 0.03);
  border-color: rgba(255, 255, 255, 0.03);
}

.nav-item.active {
  color: #ffffff;
  background: linear-gradient(135deg, rgba(37, 87, 214, 0.28), rgba(255, 255, 255, 0.1));
  border-color: rgba(255, 255, 255, 0.12);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.08);
}

.nav-item:hover {
  color: #ffffff;
  background: rgba(255, 255, 255, 0.08);
  border-color: rgba(255, 255, 255, 0.08);
}

.nav-item:hover,
.header-button:hover,
.sidebar-logout:hover {
  transform: translateY(-1px);
}

.nav-icon {
  width: 20px;
  height: 20px;
  flex: none;
}

.sidebar-footer {
  margin-top: auto;
  padding: 18px 8px 4px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
}

.demo-user {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 14px;
}

.demo-avatar,
.user-chip-avatar {
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.12);
  font-weight: 800;
  border: 1px solid rgba(255, 255, 255, 0.08);
}

.sidebar-logout {
  width: 100%;
  justify-content: center;
  border: 1px solid rgba(255, 255, 255, 0.08);
  padding: 12px 16px;
  color: #ffffff;
  background: rgba(255, 255, 255, 0.05);
}

.admin-main {
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.admin-header {
  position: sticky;
  top: 0;
  z-index: 4;
  display: flex;
  justify-content: space-between;
  gap: 20px;
  margin: 18px 22px 0;
  padding: 18px 22px;
  border-radius: 22px;
  backdrop-filter: blur(18px);
  background: rgba(250, 251, 253, 0.8);
  border: 1px solid rgba(17, 24, 39, 0.08);
  box-shadow: 0 14px 28px rgba(15, 23, 36, 0.05);
}

.header-title,
.header-actions,
.user-chip,
.menu-button {
  display: flex;
  align-items: center;
}

.header-title {
  gap: 14px;
}

.header-title p {
  color: var(--color-muted);
}

.header-title h2 {
  font-family: var(--font-sans);
  font-size: clamp(30px, 2.6vw, 42px);
  font-weight: 700;
  letter-spacing: -0.03em;
  color: var(--color-text-strong);
}

.header-actions {
  gap: 12px;
}

.header-button {
  padding: 12px 16px;
  color: var(--color-text);
  background: rgba(255, 255, 255, 0.82);
  border-color: rgba(17, 24, 39, 0.08);
}

.user-chip {
  gap: 10px;
  padding: 8px 12px 8px 8px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.88);
  border: 1px solid rgba(17, 24, 39, 0.08);
  color: var(--color-text);
  font-weight: 700;
}

.user-chip-avatar {
  width: 34px;
  height: 34px;
  background: linear-gradient(135deg, rgba(37, 87, 214, 0.16), rgba(239, 123, 57, 0.16));
  color: var(--color-text-strong);
}

.admin-content {
  padding: 22px;
}

.menu-button {
  border: 1px solid rgba(17, 24, 39, 0.08);
  width: 42px;
  height: 42px;
  justify-content: center;
  border-radius: 14px;
  color: var(--color-text);
  background: rgba(255, 255, 255, 0.82);
}

.mobile-only,
.sidebar-mask {
  display: none;
}

@media (max-width: 1040px) {
  .admin-shell {
    grid-template-columns: 1fr;
  }

  .admin-sidebar {
    position: fixed;
    left: 16px;
    top: 16px;
    bottom: 16px;
    width: min(300px, calc(100vw - 32px));
    transform: translateX(-120%);
    transition: transform 0.24s ease;
    border-radius: 24px;
  }

  .admin-sidebar.admin-sidebar-open {
    transform: translateX(0);
  }

  .sidebar-mask {
    display: block;
    position: fixed;
    inset: 0;
    background: rgba(9, 21, 34, 0.42);
    z-index: 4;
  }

  .mobile-only {
    display: inline-flex;
  }

  .admin-header {
    margin: 18px 18px 0;
    padding: 18px;
    flex-direction: column;
    align-items: stretch;
  }

  .header-actions {
    justify-content: space-between;
  }

  .admin-content {
    padding: 18px;
  }
}

@media (max-width: 640px) {
  .header-actions {
    flex-direction: column;
    align-items: stretch;
  }

  .header-button,
  .user-chip {
    justify-content: center;
  }
}
</style>
