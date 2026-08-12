<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { NSwitch, useMessage } from 'naive-ui'
import AppNav from '@/components/AppNav.vue'
import { listUsers, setUserEnabled, setUserRole } from '@/api/admin'
import { errorMessage } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import type { AdminUser } from '@/types'

const message = useMessage()
const auth = useAuthStore()

const users = ref<AdminUser[]>([])
const loading = ref(false)

const currentUserId = computed(() => auth.userId)

onMounted(load)

async function load() {
  loading.value = true
  try {
    users.value = await listUsers()
  } catch (e) {
    message.error(errorMessage(e))
  } finally {
    loading.value = false
  }
}

async function toggleRole(user: AdminUser) {
  const next: 'ADMIN' | 'USER' = user.role === 'ADMIN' ? 'USER' : 'ADMIN'
  try {
    await setUserRole(user.id, next)
    message.success(next === 'ADMIN' ? `已将 ${user.username} 设为管理员` : `已取消 ${user.username} 的管理员`)
    await load()
  } catch (e) {
    message.error(errorMessage(e))
  }
}

async function toggleEnabled(user: AdminUser) {
  try {
    await setUserEnabled(user.id, !user.enabled)
    message.success(user.enabled ? `已禁用 ${user.username}` : `已启用 ${user.username}`)
    await load()
  } catch (e) {
    message.error(errorMessage(e))
  }
}

function isSelf(user: AdminUser): boolean {
  return currentUserId.value !== null && user.id === currentUserId.value
}

function formatTime(iso: string): string {
  return new Date(iso).toLocaleString()
}
</script>

<template>
  <div class="page">
    <AppNav />
    <main class="container">
      <div class="head">
        <h2>权限管理</h2>
        <span class="dim">超级管理员（OP）专属</span>
      </div>

      <div v-if="users.length === 0" class="panel empty">
        <p class="dim">暂无用户</p>
      </div>
      <div v-else class="user-list">
        <div v-for="u in users" :key="u.id" class="panel user-row">
          <div class="user-info">
            <div class="user-name">
              <span class="name-text">{{ u.nickname || u.username }}</span>
              <span v-if="u.role === 'OP'" class="role-tag op">OP</span>
              <span v-else-if="u.role === 'ADMIN'" class="role-tag">ADMIN</span>
              <span v-if="isSelf(u)" class="dim self-tag">（我）</span>
            </div>
            <div class="dim user-meta">
              <span>@{{ u.username }}</span>
              <span>ID {{ u.id }}</span>
              <span>注册于 {{ formatTime(u.createdAt) }}</span>
            </div>
          </div>
          <div class="user-actions">
            <label class="switch-label">
              <span class="dim">管理员</span>
              <n-switch
                :value="u.role === 'ADMIN' || u.role === 'OP'"
                :disabled="u.role === 'OP' || isSelf(u)"
                @update:value="toggleRole(u)"
              />
            </label>
            <label class="switch-label">
              <span class="dim">启用</span>
              <n-switch
                :value="u.enabled"
                :disabled="u.role === 'OP' || isSelf(u)"
                @update:value="toggleEnabled(u)"
              />
            </label>
          </div>
        </div>
      </div>
    </main>
  </div>
</template>

<style scoped>
.page {
  min-height: 100%;
  background: var(--bg);
}

.container {
  max-width: 860px;
  margin: 0 auto;
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
}

.head h2 {
  font-size: 20px;
}

.head span {
  font-size: 12px;
}

.empty {
  text-align: center;
  padding: 40px;
}

.user-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.user-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 16px;
  gap: 12px;
}

.user-info {
  min-width: 0;
}

.user-name {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
}

.self-tag {
  font-weight: 400;
  font-size: 12px;
}

.user-meta {
  display: flex;
  gap: 12px;
  margin-top: 4px;
  font-size: 12px;
  flex-wrap: wrap;
}

.user-actions {
  display: flex;
  gap: 20px;
  align-items: center;
  flex-shrink: 0;
}

.switch-label {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  cursor: pointer;
}

.role-tag {
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 1px;
  color: var(--accent);
  border: 1px solid var(--accent-dim);
  border-radius: 4px;
  padding: 0 4px;
  line-height: 1.5;
}

.role-tag.op {
  color: var(--warn);
  border-color: rgba(255, 200, 87, 0.5);
}

/* ---------- mobile ---------- */
@media (max-width: 768px) {
  .user-row {
    flex-direction: column;
    align-items: flex-start;
  }
  .user-actions {
    width: 100%;
    justify-content: flex-end;
  }
}
</style>
