<script setup lang="ts">
import { computed, h } from 'vue'
import { useRouter } from 'vue-router'
import { NDropdown, useMessage, type DropdownOption } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'

// King's Chess has its own top bar: the module used to borrow the TEST battle
// system's AppNav, which carried that system's brand and nav links. Only the
// account menu (shared auth) and the portal link are kept from the shell.
const router = useRouter()
const message = useMessage()
const auth = useAuthStore()
const appVersion = __APP_VERSION__

const initial = computed(() => (auth.displayName || '?').charAt(0).toUpperCase())

const roleLabel = computed(() => (auth.isOp ? 'OP' : auth.isAdmin ? 'ADMIN' : ''))

const menuOptions = computed<DropdownOption[]>(() => {
  const options: DropdownOption[] = [
    {
      key: 'header',
      render: () =>
        h('div', { class: 'menu-header' }, [
          h('span', { class: 'menu-header-name' }, auth.displayName),
          h('span', { class: 'menu-header-sub dim' },
            `@${auth.username}${roleLabel.value ? ' ' + roleLabel.value : ''}`)
        ])
    },
    { key: 'profile', label: '编辑资料' },
    { type: 'divider', key: 'd2' },
    { key: 'logout', label: '退出登录', class: 'menu-logout' }
  ]
  return options
})

function onSelect(key: string) {
  if (key === 'header') return
  if (key === 'profile') {
    router.push({ name: 'profile' })
  } else if (key === 'logout') {
    logout()
  }
}

function logout() {
  auth.logout()
  message.info('已退出登录')
  router.push({ name: 'login' })
}
</script>

<template>
  <header class="king-nav">
    <div class="king-nav-brand">
      <span class="brand-mark">KING'S CHESS</span>
      <span class="version-tag" :title="`GitHub commit: ${appVersion}`">v{{ appVersion }}</span>
      <span class="brand-sub dim">国王棋 · 岚中对</span>
    </div>
    <nav class="king-nav-links">
      <router-link :to="{ name: 'portal' }">返回门户</router-link>
    </nav>
    <div class="king-nav-user">
      <n-dropdown trigger="click" :options="menuOptions" @select="onSelect">
        <div class="avatar-trigger" title="账号菜单">
          <span class="nav-avatar">
            <img v-if="auth.avatarUrl" :src="auth.avatarUrl" class="nav-avatar-img" alt="avatar" />
            <span v-else class="nav-avatar-fallback">{{ initial }}</span>
          </span>
          <span class="nav-username">{{ auth.displayName }}</span>
          <span v-if="roleLabel" class="role-tag">{{ roleLabel }}</span>
        </div>
      </n-dropdown>
    </div>
  </header>
</template>

<style scoped>
.king-nav {
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

.king-nav-brand {
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

.version-tag {
  font-size: 11px;
  font-family: Consolas, 'Courier New', monospace;
  color: var(--text-dim);
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 0 5px;
  line-height: 1.6;
}

.brand-sub {
  font-size: 12px;
}

.king-nav-links {
  display: flex;
  gap: 4px;
  flex: 1;
}

.king-nav-links a {
  color: var(--text-dim);
  padding: 6px 12px;
  border-radius: 6px;
  font-size: 14px;
}

.king-nav-links a:hover {
  color: var(--text);
  background: var(--bg-panel-2);
}

.king-nav-user {
  display: flex;
  align-items: center;
  font-size: 13px;
}

.avatar-trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 3px 8px 3px 4px;
  border: 1px solid transparent;
  border-radius: 20px;
  cursor: pointer;
  transition: border-color 0.15s ease, background 0.15s ease;
}

.avatar-trigger:hover {
  border-color: var(--border);
  background: var(--bg-panel-2);
}

.nav-avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  overflow: hidden;
  flex-shrink: 0;
  position: relative;
}

.nav-avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.nav-avatar-fallback {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1e88e5, #0d47a1);
  color: #fff;
  font-weight: 700;
  font-size: 13px;
}

.nav-username {
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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

.menu-header {
  display: flex;
  align-items: baseline;
  gap: 8px;
  line-height: 1.4;
}

.menu-header-name {
  font-weight: 600;
}

.menu-header-sub {
  font-size: 12px;
}

/* ---------- mobile: compact top bar ---------- */
@media (max-width: 768px) {
  .king-nav {
    gap: 8px;
    padding: 0 10px;
    height: 48px;
  }
  .brand-sub {
    display: none;
  }
  .version-tag {
    display: none;
  }
  .brand-mark {
    font-size: 15px;
    letter-spacing: 1px;
  }
  .king-nav-links {
    justify-content: flex-end;
    min-width: 0;
  }
  .king-nav-links a {
    padding: 6px 6px;
    font-size: 13px;
    white-space: nowrap;
    flex-shrink: 0;
  }
  .nav-username {
    display: none;
  }
  .avatar-trigger {
    padding: 3px 4px;
  }
  .role-tag {
    display: none;
  }
}
</style>

<style>
/* Global styles for the account dropdown popup (same shell as AppNav). */
.n-dropdown-menu {
  min-width: 200px;
}

.n-dropdown-menu .n-dropdown-option-body {
  height: 36px;
  line-height: 36px;
  padding: 0 16px;
  font-size: 14px;
  align-items: center;
}

.menu-logout .n-dropdown-option-body {
  color: var(--danger) !important;
}
</style>
