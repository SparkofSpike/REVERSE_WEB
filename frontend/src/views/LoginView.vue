<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { NForm, NFormItem, NInput, NButton, useMessage } from 'naive-ui'
import { login } from '@/api/auth'
import { errorMessage } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const message = useMessage()
const auth = useAuthStore()

const username = ref('')
const password = ref('')
const loading = ref(false)

async function onSubmit() {
  if (!username.value.trim() || !password.value) {
    message.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    const res = await login(username.value.trim(), password.value)
    auth.setAuth(res.token, res.username)
    message.success(`欢迎回来，${res.username}`)
    router.push({ name: 'home' })
  } catch (e) {
    message.error(errorMessage(e))
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="auth-page">
    <div class="auth-card">
      <div class="auth-title">
        <h1>TEST 战斗辅助</h1>
        <p class="dim">赛博桌游战斗裁决终端</p>
      </div>
      <n-form @submit.prevent="onSubmit">
        <n-form-item label="用户名">
          <n-input v-model:value="username" placeholder="用户名" size="large" />
        </n-form-item>
        <n-form-item label="密码">
          <n-input
            v-model:value="password"
            type="password"
            show-password-on="click"
            placeholder="密码"
            size="large"
            @keydown.enter="onSubmit"
          />
        </n-form-item>
        <n-button type="primary" block size="large" :loading="loading" @click="onSubmit">
          登录
        </n-button>
      </n-form>
      <div class="auth-footer">
        <span class="dim">还没有账号？</span>
        <router-link to="/register">注册</router-link>
      </div>
    </div>
  </div>
</template>

<style scoped>
.auth-page {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background:
    radial-gradient(ellipse at 20% 20%, rgba(76, 194, 255, 0.08), transparent 50%),
    radial-gradient(ellipse at 80% 80%, rgba(255, 93, 108, 0.06), transparent 50%),
    var(--bg);
}

.auth-card {
  width: 360px;
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 32px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.4);
}

.auth-title {
  text-align: center;
  margin-bottom: 24px;
}

.auth-title h1 {
  font-size: 22px;
  letter-spacing: 2px;
  color: var(--accent);
}

.auth-title p {
  margin-top: 6px;
  font-size: 12px;
}

.auth-footer {
  margin-top: 16px;
  text-align: center;
  font-size: 13px;
}

.auth-footer a {
  margin-left: 6px;
}
</style>
