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
    radial-gradient(circle at top left, rgba(13, 124, 124, 0.12), transparent 26%),
    linear-gradient(180deg, #f6f9fc 0%, #eef3f9 52%, #edf1f6 100%);
}

.admin-sidebar {
  position: relative;
  z-index: 5;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 24px 18px;
  background: var(--color-admin-sidebar);
  color: #ffffff;
  box-shadow: inset -1px 0 0 rgba(255, 255, 255, 0.06);
}

.sidebar-brand {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 8px 8px 22px;
}

.brand-mark {
  width: 48px;
  height: 48px;
  display: grid;
  place-items: center;
  border-radius: 18px;
  background: linear-gradient(135deg, rgba(255, 255, 255, 0.2), rgba(217, 119, 6, 0.35));
  font-weight: 800;
  font-size: 20px;
}

.sidebar-brand p,
.demo-user p,
.header-title p {
  margin: 0;
  font-size: 12px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.sidebar-brand h1,
.header-title h2,
.demo-user strong {
  margin: 4px 0 0;
}

.sidebar-nav {
  display: flex;
  flex-direction: column;
  gap: 10px;
  flex: 1;
}

.nav-item,
.header-button,
.sidebar-logout {
  text-decoration: none;
  display: inline-flex;
  align-items: center;
  gap: 12px;
  border-radius: 18px;
  border: 1px solid transparent;
  transition: transform 0.2s ease, background 0.2s ease, border-color 0.2s ease;
}

.nav-item {
  padding: 14px 16px;
  color: rgba(255, 255, 255, 0.74);
  background: rgba(255, 255, 255, 0.03);
}

.nav-item.active {
  color: #ffffff;
  background: rgba(255, 255, 255, 0.12);
  border-color: rgba(255, 255, 255, 0.12);
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
  background: rgba(255, 255, 255, 0.16);
  font-weight: 800;
}

.sidebar-logout {
  width: 100%;
  justify-content: center;
  border: none;
  padding: 12px 16px;
  color: #ffffff;
  background: rgba(255, 255, 255, 0.08);
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
  padding: 22px 28px 18px;
  backdrop-filter: blur(12px);
  background: rgba(246, 249, 252, 0.76);
  border-bottom: 1px solid rgba(21, 49, 75, 0.08);
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
  color: #68809b;
}

.header-title h2 {
  font-size: clamp(24px, 2vw, 32px);
  color: #13283f;
}

.header-actions {
  gap: 12px;
}

.header-button {
  padding: 12px 18px;
  color: #17304f;
  background: rgba(23, 48, 79, 0.06);
}

.user-chip {
  gap: 10px;
  padding: 8px 10px 8px 8px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.82);
  border: 1px solid rgba(21, 49, 75, 0.08);
  color: #17304f;
  font-weight: 700;
}

.user-chip-avatar {
  width: 34px;
  height: 34px;
  background: linear-gradient(135deg, rgba(23, 48, 79, 0.18), rgba(13, 124, 124, 0.2));
}

.admin-content {
  padding: 24px 28px 28px;
}

.menu-button {
  border: none;
  width: 42px;
  height: 42px;
  justify-content: center;
  border-radius: 14px;
  color: #17304f;
  background: rgba(23, 48, 79, 0.08);
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
    border-radius: 28px;
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
