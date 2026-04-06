<template>
  <section class="login-shell">
    <div class="login-panel">
      <div class="login-copy">
        <h1>进入管理后台工作台</h1>
        <p class="login-description">
          这里用于演示文档上传、策略确认、索引构建与对话观测。登录采用假登录模式，方便直接体验完整业务流转。
        </p>
      </div>

      <form class="login-form" @submit.prevent="submitLogin">
        <div class="form-header">
          <p>后台入口</p>
          <h2>管理台登录</h2>
        </div>

        <label class="field">
          <span>账号</span>
          <input v-model="form.username" type="text" placeholder="账号自动填充中..." autocomplete="username" />
        </label>

        <label class="field">
          <span>密码</span>
          <input v-model="form.password" type="password" placeholder="密码自动填充中..." autocomplete="current-password" />
        </label>

        <p v-if="errorMessage" class="error-message">{{ errorMessage }}</p>

        <div class="form-actions">
          <button class="secondary-button" type="button" @click="goBackChat">返回聊天</button>
          <button class="primary-button" type="submit">{{ fillReady ? '进入管理台' : '正在填充账号...' }}</button>
        </div>
      </form>
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { loginAdminDemo } from '../utils/adminAuth'

const router = useRouter()
const route = useRoute()

const form = reactive({
  username: '',
  password: ''
})
const errorMessage = ref('')
const fillReady = computed(() => form.username.trim() && form.password.trim())

function fillDemoAccount() {
  form.username = 'admin'
  form.password = 'REDACTED-ADMIN-PASSWORD'
}

function submitLogin() {
  errorMessage.value = ''
  if (!fillReady.value) {
    errorMessage.value = '演示账号还在填充，请稍候再试。'
    return
  }

  loginAdminDemo(form.username.trim())
  const redirect = typeof route.query.redirect === 'string' && route.query.redirect.startsWith('/admin')
    ? route.query.redirect
    : '/admin/dashboard'
  router.replace(redirect)
}

function goBackChat() {
  router.push('/chat')
}

onMounted(() => {
  window.setTimeout(fillDemoAccount, 320)
})
</script>

<style scoped>
.login-shell {
  min-height: 100vh;
  padding: 32px;
  display: grid;
  place-items: center;
  background: var(--color-bg);
}

.login-panel {
  width: min(960px, 100%);
  display: grid;
  grid-template-columns: 1.15fr 0.9fr;
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-md);
  overflow: hidden;
}

.login-copy {
  background: #fff;
  border-radius: var(--radius-lg) 0 0 var(--radius-lg);
  padding: 40px;
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.login-copy h1 {
  margin: 0;
  font-size: 24px;
  font-weight: 600;
  color: var(--color-text-strong);
}

.login-description {
  max-width: 580px;
  margin: 14px 0 0;
  font-size: 15px;
  line-height: 1.7;
  color: var(--color-muted);
}

.login-form {
  background: #fff;
  border-radius: 0 var(--radius-lg) var(--radius-lg) 0;
  border-left: 1px solid var(--color-border);
  padding: 40px;
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.form-header p {
  margin: 0;
  color: var(--color-primary);
  font-size: 13px;
  font-weight: 600;
}

.form-header h2 {
  margin: 8px 0 0;
  font-size: 20px;
  color: var(--color-text-strong);
}

.field {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 20px;
}

.field span {
  font-size: 14px;
  font-weight: 600;
  color: var(--color-muted);
}

.field input {
  width: 100%;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  padding: 10px 12px;
  background: #ffffff;
  color: var(--color-text);
  outline: none;
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}

.field input:focus {
  border-color: var(--color-primary);
  box-shadow: 0 0 0 3px var(--color-primary-soft);
}

.error-message {
  margin: 16px 0 0;
  color: var(--color-danger);
  font-size: 14px;
}

.form-actions {
  display: flex;
  gap: 12px;
  margin-top: 24px;
}

.primary-button,
.secondary-button {
  flex: 1;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  padding: 10px 16px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: opacity 0.2s ease;
}

.primary-button {
  color: #ffffff;
  background: var(--color-primary);
}

.primary-button:hover {
  opacity: 0.9;
}

.secondary-button {
  color: var(--color-text);
  background: #fff;
  border-color: var(--color-border);
}

.secondary-button:hover {
  background: var(--color-surface-soft);
}

@media (max-width: 960px) {
  .login-shell {
    padding: 18px;
  }

  .login-panel {
    grid-template-columns: 1fr;
  }

  .login-copy {
    border-radius: var(--radius-lg) var(--radius-lg) 0 0;
  }

  .login-form {
    border-left: none;
    border-top: 1px solid var(--color-border);
    border-radius: 0 0 var(--radius-lg) var(--radius-lg);
  }

  .login-copy,
  .login-form {
    padding: 28px;
  }
}
</style>
