import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      name: 'BusinessChat',
      component: () => import('../views/BusinessChatView.vue')
    }
  ]
})

export default router
