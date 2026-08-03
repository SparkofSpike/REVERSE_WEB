<script setup lang="ts">
import { useRouter } from 'vue-router'
import { NButton, useMessage } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const message = useMessage()
const auth = useAuthStore()

const navItems = [
  { name: 'home', label: '作战室' },
  { name: 'builds', label: '构筑管理' },
  { name: 'records', label: '战报' }
]

function logout() {
  auth.logout()
  message.info('已退出登录')
  router.push({ name: 'login' })
}
</script>

<template>
  <header class="nav">
    <div class="nav-brand">
      <span class="brand-mark">TEST</span>
      <span class="brand-sub dim">战斗辅助终端</span>
    </div>
    <nav class="nav-links">
      <router-link v-for="item in navItems" :key="item.name" :to="{ name: item.name }">
        {{ item.label }}
      </router-link>
    </nav>
    <div class="nav-user">
      <span class="dim">{{ auth.username }}</span>
      <n-button quaternary size="small" @click="logout">退出</n-button>
    </div>
  </header>
</template>

<style scoped>
.nav {
  display: flex;
  align-items: center;
  gap: 24px;
  padding: 0 24px;
  height: 52px;
  border-bottom: 1px solid var(--border);
  background: var(--bg-panel);
  position: sticky;
  top: 0;
  z-index: 10;
}

.nav-brand {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.brand-mark {
  font-size: 18px;
  font-weight: 700;
  letter-spacing: 2px;
  color: var(--accent);
}

.brand-sub {
  font-size: 12px;
}

.nav-links {
  display: flex;
  gap: 4px;
  flex: 1;
}

.nav-links a {
  color: var(--text-dim);
  padding: 6px 12px;
  border-radius: 6px;
  font-size: 14px;
}

.nav-links a:hover {
  color: var(--text);
  background: var(--bg-panel-2);
}

.nav-links a.router-link-active {
  color: var(--accent);
  background: rgba(76, 194, 255, 0.1);
}

.nav-user {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}
</style>
