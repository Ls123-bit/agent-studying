<template>
  <section class="login-shell">
    <div class="login-backdrop"></div>

    <div class="login-panel">
      <div class="login-copy">
        <p class="login-eyebrow">Super Agent Console</p>
        <h1>进入管理后台工作台</h1>
        <p class="login-description">
          这里用于演示文档上传、策略确认、索引构建与 RAG 检索验证。登录采用假登录模式，方便直接体验完整业务流转。
        </p>

        <div class="info-card">
          <span>演示说明</span>
          <strong>账号和密码会自动填充</strong>
          <p>点击登录后仅写入本地登录态，不会触发真实后端鉴权接口。</p>
        </div>
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

        <div class="login-tips">
          <span class="tip-chip">演示账号</span>
          <span class="tip-chip">本地登录态</span>
          <span class="tip-chip">不走真实鉴权</span>
        </div>

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
  position: relative;
  min-height: 100vh;
  overflow: hidden;
  padding: 32px;
  display: grid;
  place-items: center;
}

.login-backdrop {
  position: absolute;
  inset: 0;
  background:
    radial-gradient(circle at 12% 18%, rgba(13, 124, 124, 0.2), transparent 28%),
    radial-gradient(circle at 86% 14%, rgba(217, 119, 6, 0.16), transparent 24%),
    linear-gradient(180deg, #f5f8fc 0%, #eef4fb 48%, #edf1f7 100%);
}

.login-panel {
  position: relative;
  z-index: 1;
  width: min(1120px, 100%);
  display: grid;
  grid-template-columns: 1.15fr 0.9fr;
  gap: 24px;
  align-items: stretch;
}

.login-copy,
.login-form {
  border: 1px solid rgba(21, 49, 75, 0.08);
  border-radius: 32px;
  background: rgba(255, 255, 255, 0.88);
  box-shadow: 0 28px 60px rgba(21, 49, 75, 0.08);
  backdrop-filter: blur(18px);
}

.login-copy {
  padding: 44px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  min-height: 520px;
}

.login-eyebrow {
  margin: 0 0 12px;
  font-size: 12px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: #52708f;
}

.login-copy h1 {
  margin: 0;
  font-size: clamp(34px, 5vw, 56px);
  line-height: 1.05;
  color: #14273d;
}

.login-description {
  max-width: 580px;
  margin: 18px 0 0;
  font-size: 16px;
  line-height: 1.8;
  color: #53687d;
}

.info-card {
  margin-top: 36px;
  padding: 24px 26px;
  border-radius: 24px;
  background: linear-gradient(135deg, rgba(23, 48, 79, 0.96), rgba(13, 124, 124, 0.92));
  color: #ffffff;
}

.info-card span {
  display: block;
  font-size: 12px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: rgba(255, 255, 255, 0.72);
}

.info-card strong {
  display: block;
  margin-top: 10px;
  font-size: 22px;
}

.info-card p {
  margin: 12px 0 0;
  color: rgba(255, 255, 255, 0.76);
}

.login-form {
  padding: 34px 32px;
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.form-header p {
  margin: 0;
  color: #0d7c7c;
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.form-header h2 {
  margin: 10px 0 0;
  font-size: 30px;
  color: #14273d;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 22px;
}

.field span {
  font-size: 14px;
  font-weight: 700;
  color: #41596f;
}

.field input {
  width: 100%;
  border: 1px solid rgba(21, 49, 75, 0.12);
  border-radius: 18px;
  padding: 15px 16px;
  background: #ffffff;
  color: #14273d;
  outline: none;
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}

.field input:focus {
  border-color: rgba(13, 124, 124, 0.36);
  box-shadow: 0 0 0 4px rgba(13, 124, 124, 0.08);
}

.login-tips {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 22px;
}

.tip-chip {
  padding: 7px 12px;
  border-radius: 999px;
  background: rgba(13, 124, 124, 0.08);
  color: #0d7c7c;
  font-size: 12px;
  font-weight: 700;
}

.error-message {
  margin: 18px 0 0;
  color: #c2410c;
  font-size: 14px;
}

.form-actions {
  display: flex;
  gap: 12px;
  margin-top: 28px;
}

.primary-button,
.secondary-button {
  flex: 1;
  border: none;
  border-radius: 18px;
  padding: 14px 18px;
  font-size: 15px;
  font-weight: 700;
  transition: transform 0.2s ease, box-shadow 0.2s ease;
}

.primary-button {
  color: #ffffff;
  background: linear-gradient(135deg, #17304f, #0d7c7c);
  box-shadow: 0 18px 36px rgba(13, 124, 124, 0.18);
}

.secondary-button {
  color: #17304f;
  background: rgba(23, 48, 79, 0.08);
}

.primary-button:hover,
.secondary-button:hover {
  transform: translateY(-1px);
}

@media (max-width: 960px) {
  .login-shell {
    padding: 18px;
  }

  .login-panel {
    grid-template-columns: 1fr;
  }

  .login-copy,
  .login-form {
    padding: 28px;
  }
}
</style>
