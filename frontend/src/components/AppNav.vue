<script setup lang="ts">
import { computed, h } from 'vue'
import { useRouter } from 'vue-router'
import { NDropdown, useMessage, type DropdownOption } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const message = useMessage()
const auth = useAuthStore()
const appVersion = __APP_VERSION__

const navItems = [
  { name: 'home', label: '作战室' },
  { name: 'pvp', label: '房间大厅' },
  { name: 'builds', label: '构筑管理' },
  { name: 'records', label: '战报' }
]

const initial = computed(() => (auth.displayName || '?').charAt(0).toUpperCase())

const roleLabel = computed(() => (auth.isOp ? 'OP' : auth.isAdmin ? 'ADMIN' : ''))

const menuOptions = computed<DropdownOption[]>(() => {
  // account header: single row, equal height with the menu items below
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
    { type: 'divider', key: 'd1' },
    { key: 'profile', label: '编辑资料' }
  ]
  // design management: ADMIN and OP only
  if (auth.isAdmin) {
    options.push({ key: 'design', label: '设计管理' })
  }
  // account & permission management: OP only
  if (auth.isOp) {
    options.push({ key: 'admin-users', label: '权限管理' })
  }
  options.push(
    { type: 'divider', key: 'd2' },
    { key: 'logout', label: '退出登录', class: 'menu-logout' }
  )
  return options
})

function onSelect(key: string) {
  if (key === 'header') return
  if (key === 'profile') {
    router.push({ name: 'profile' })
  } else if (key === 'design') {
    router.push({ name: 'design' })
  } else if (key === 'admin-users') {
    router.push({ name: 'admin-users' })
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
  <header class="nav">
    <div class="nav-brand">
      <span class="brand-mark">TEST</span>
      <span class="version-tag" :title="`GitHub commit: ${appVersion}`">v{{ appVersion }}</span>
      <span class="brand-sub dim">战斗辅助终端</span>
    </div>
    <nav class="nav-links">
      <router-link v-for="item in navItems" :key="item.name" :to="{ name: item.name }">
        {{ item.label }}
      </router-link>
    </nav>
    <div class="nav-user">
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
  .nav {
    gap: 8px;
    padding: 0 10px;
    height: 48px;
  }
  /* only the brand mark stays; tag + subtitle would crush the links */
  .brand-sub {
    display: none;
  }
  .version-tag {
    display: none;
  }
  .brand-mark {
    font-size: 16px;
    letter-spacing: 1px;
  }
  .nav-links {
    gap: 2px;
    justify-content: flex-end;
    min-width: 0;
  }
  .nav-links a {
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
/* avatar dropdown: even item spacing and width (not scoped, reaches the popup) */
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

/* logout item in danger color */
.menu-logout .n-dropdown-option-body {
  color: var(--danger) !important;
}
</style>
