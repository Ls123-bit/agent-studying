import { createRouter, createWebHistory } from 'vue-router'
import { isAdminAuthenticated } from '../utils/adminAuth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      redirect: '/chat'
    },
    {
      path: '/chat',
      name: 'BusinessChat',
      component: () => import('../views/BusinessChatView.vue'),
      meta: {
        title: '业务对话'
      }
    },
    {
      path: '/admin/login',
      name: 'AdminLogin',
      component: () => import('../views/AdminLoginView.vue'),
      meta: {
        layout: 'fullscreen',
        title: '管理后台登录'
      }
    },
    {
      path: '/admin',
      component: () => import('../views/admin/AdminLayoutView.vue'),
      meta: {
        layout: 'fullscreen',
        requiresAdminAuth: true
      },
      children: [
        {
          path: '',
          redirect: '/admin/dashboard'
        },
        {
          path: 'dashboard',
          name: 'AdminDashboard',
          component: () => import('../views/admin/AdminDashboardView.vue'),
          meta: {
            title: '运营总览'
          }
        },
        {
          path: 'documents',
          name: 'AdminDocuments',
          component: () => import('../views/admin/AdminDocumentListView.vue'),
          meta: {
            title: '文档接入'
          }
        },
        {
          path: 'documents/:documentId',
          name: 'AdminDocumentDetail',
          component: () => import('../views/admin/AdminDocumentDetailView.vue'),
          meta: {
            title: '文档详情'
          }
        },
        {
          path: 'observability',
          name: 'AdminObservabilityList',
          component: () => import('../views/admin/AdminObservabilityListView.vue'),
          meta: {
            title: '对话观测'
          }
        },
        {
          path: 'observability/:conversationId',
          name: 'AdminObservabilityDetail',
          component: () => import('../views/admin/AdminObservabilityDetailView.vue'),
          meta: {
            title: '观测详情'
          }
        }
      ]
    }
  ]
})

router.beforeEach((to) => {
  const requiresAdminAuth = to.matched.some((record) => record.meta?.requiresAdminAuth)
  const isLoginRoute = to.name === 'AdminLogin'

  if (requiresAdminAuth && !isAdminAuthenticated()) {
    return {
      name: 'AdminLogin',
      query: {
        redirect: to.fullPath
      }
    }
  }

  if (isLoginRoute && isAdminAuthenticated()) {
    return typeof to.query.redirect === 'string' && to.query.redirect.startsWith('/admin')
      ? to.query.redirect
      : '/admin/dashboard'
  }

  return true
})

export default router
