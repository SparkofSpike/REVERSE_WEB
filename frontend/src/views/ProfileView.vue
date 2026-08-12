<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { NButton, NInput, NUpload, useMessage, type UploadCustomRequestOptions } from 'naive-ui'
import AppNav from '@/components/AppNav.vue'
import { changePassword, me, updateProfile, uploadAvatar } from '@/api/auth'
import { errorMessage } from '@/api/http'
import { useAuthStore } from '@/stores/auth'

const message = useMessage()
const auth = useAuthStore()

const nickname = ref('')
const savingNickname = ref(false)

const oldPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const savingPassword = ref(false)
const uploading = ref(false)

const initial = computed(() => (auth.displayName || '?').charAt(0).toUpperCase())

const roleText = computed(() => {
  if (auth.isOp) return '超级管理员（OP）'
  if (auth.isAdmin) return '管理员'
  return '普通用户'
})

onMounted(load)

async function load() {
  try {
    const profile = await me()
    nickname.value = profile.nickname ?? ''
    auth.setUserInfo(profile.id, profile.role, profile.nickname, profile.avatarUrl)
  } catch (e) {
    message.error(errorMessage(e))
  }
}

async function onUpload(options: UploadCustomRequestOptions) {
  const raw = options.file.file as File
  uploading.value = true
  try {
    const res = await uploadAvatar(raw)
    auth.setUserInfo(auth.userId, auth.role, auth.nickname, res.avatarUrl)
    message.success('头像已更新')
    options.onFinish()
  } catch (e) {
    message.error(errorMessage(e))
    options.onError()
  } finally {
    uploading.value = false
  }
}

async function saveNickname() {
  savingNickname.value = true
  try {
    const res = await updateProfile(nickname.value.trim())
    auth.setUserInfo(auth.userId, auth.role, res.nickname, auth.avatarUrl)
    message.success('昵称已更新')
  } catch (e) {
    message.error(errorMessage(e))
  } finally {
    savingNickname.value = false
  }
}

async function savePassword() {
  if (!oldPassword.value) {
    message.warning('请输入原密码')
    return
  }
  if (newPassword.value.length < 6) {
    message.warning('新密码长度需在 6-64 之间')
    return
  }
  if (newPassword.value !== confirmPassword.value) {
    message.warning('两次输入的新密码不一致')
    return
  }
  savingPassword.value = true
  try {
    await changePassword(oldPassword.value, newPassword.value)
    message.success('密码已修改')
    oldPassword.value = ''
    newPassword.value = ''
    confirmPassword.value = ''
  } catch (e) {
    message.error(errorMessage(e))
  } finally {
    savingPassword.value = false
  }
}
</script>

<template>
  <div class="page">
    <AppNav />
    <main class="container">
      <div class="head">
        <h2>编辑资料</h2>
      </div>

      <div class="panel">
        <h3 class="panel-title">头像</h3>
        <div class="avatar-row">
          <n-avatar round :size="72" :src="auth.avatarUrl || undefined" class="big-avatar">
            {{ initial }}
          </n-avatar>
          <div class="avatar-actions">
            <n-upload
              accept="image/png,image/jpeg,image/webp,image/gif"
              :show-file-list="false"
              :custom-request="onUpload"
              :disabled="uploading"
            >
              <n-button :loading="uploading">上传头像</n-button>
            </n-upload>
            <span class="dim hint">支持 png/jpg/webp/gif，不超过 2MB</span>
          </div>
        </div>
      </div>

      <div class="panel">
        <h3 class="panel-title">账号信息</h3>
        <div class="info-row">
          <span class="dim">用户名</span>
          <span>{{ auth.username }}</span>
        </div>
        <div class="info-row">
          <span class="dim">角色</span>
          <span>
            {{ roleText }}
            <span v-if="auth.isOp" class="role-tag">OP</span>
            <span v-else-if="auth.isAdmin" class="role-tag">ADMIN</span>
          </span>
        </div>
        <div class="info-row">
          <span class="dim">显示名</span>
          <span>{{ auth.displayName }}</span>
        </div>
      </div>

      <div class="panel">
        <h3 class="panel-title">修改昵称</h3>
        <p class="dim hint">设置显示名，留空则回退为用户名</p>
        <div class="form-row">
          <n-input v-model:value="nickname" placeholder="昵称（可选，最长 32 字）" :maxlength="32" />
          <n-button type="primary" :loading="savingNickname" @click="saveNickname">保存</n-button>
        </div>
      </div>

      <div class="panel">
        <h3 class="panel-title">修改密码</h3>
        <div class="form">
          <label>原密码</label>
          <n-input v-model:value="oldPassword" type="password" show-password-on="click" placeholder="原密码" />
          <label>新密码</label>
          <n-input v-model:value="newPassword" type="password" show-password-on="click" placeholder="新密码（6-64 位）" />
          <label>确认新密码</label>
          <n-input v-model:value="confirmPassword" type="password" show-password-on="click" placeholder="再次输入新密码" />
          <n-button type="primary" :loading="savingPassword" @click="savePassword">修改密码</n-button>
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
  max-width: 720px;
  margin: 0 auto;
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.head h2 {
  font-size: 20px;
}

.panel-title {
  font-size: 15px;
  margin-bottom: 12px;
}

.hint {
  font-size: 12px;
  margin-bottom: 10px;
}

.avatar-row {
  display: flex;
  align-items: center;
  gap: 16px;
}

.big-avatar {
  background: linear-gradient(135deg, #1e88e5, #0d47a1);
  color: #fff;
  font-weight: 700;
  font-size: 28px;
  flex-shrink: 0;
}

.avatar-actions {
  display: flex;
  flex-direction: column;
  gap: 6px;
  align-items: flex-start;
}

.info-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 0;
  border-bottom: 1px dashed var(--border);
  font-size: 14px;
}

.info-row:last-child {
  border-bottom: none;
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
  margin-left: 6px;
}

.form-row {
  display: flex;
  gap: 10px;
}

.form {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.form label {
  font-size: 13px;
  color: var(--text-dim);
  margin-top: 4px;
}
</style>
