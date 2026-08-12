<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { NButton, NInput, NModal, useMessage } from 'naive-ui'
import Cropper from 'cropperjs'
import 'cropperjs/dist/cropper.css'
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

// avatar cropping state
const showCropper = ref(false)
const cropUrl = ref('')
const cropImg = ref<HTMLImageElement | null>(null)
let cropper: Cropper | null = null

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

// ---------- avatar upload with cropping ----------

// native file input: reliable across browsers, no upload-hook surprises
const fileInput = ref<HTMLInputElement | null>(null)

function pickFile() {
  fileInput.value?.click()
}

function onFilePicked(event: Event) {
  const input = event.target as HTMLInputElement
  const raw = input.files?.[0] ?? null
  input.value = '' // allow re-picking the same file
  if (!raw) return
  if (raw.size > 10 * 1024 * 1024) {
    message.warning('图片不能超过 10MB')
    return
  }
  if (cropUrl.value) {
    URL.revokeObjectURL(cropUrl.value)
  }
  cropUrl.value = URL.createObjectURL(raw)
  showCropper.value = true
}

function initCropper() {
  if (!cropImg.value) return
  // wait for the modal transition so the image has a real size
  setTimeout(() => {
    try {
      cropper?.destroy()
      cropper = new Cropper(cropImg.value!, {
        aspectRatio: 1,
        viewMode: 1,
        dragMode: 'move',
        autoCropArea: 0.85,
        background: false,
        guides: true
      })
    } catch {
      message.error('图片加载失败，请换一张试试')
    }
  }, 80)
}

function onCropperClosed() {
  cropper?.destroy()
  cropper = null
  if (cropUrl.value) {
    URL.revokeObjectURL(cropUrl.value)
    cropUrl.value = ''
  }
}

function confirmCrop() {
  if (!cropper) return
  const canvas = cropper.getCroppedCanvas({ width: 256, height: 256, imageSmoothingQuality: 'high' })
  canvas.toBlob(async (blob) => {
    if (!blob) {
      message.error('裁剪失败，请重试')
      return
    }
    uploading.value = true
    try {
      const file = new File([blob], 'avatar.png', { type: 'image/png' })
      const res = await uploadAvatar(file)
      // bust the browser cache: the avatar URL itself never changes
      const url = res.avatarUrl ? res.avatarUrl + '?v=' + Date.now() : null
      auth.setUserInfo(auth.userId, auth.role, auth.nickname, url)
      message.success('头像已更新')
      showCropper.value = false
    } catch (e) {
      message.error(errorMessage(e))
    } finally {
      uploading.value = false
    }
  }, 'image/png')
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
            <input
              ref="fileInput"
              type="file"
              accept="image/png,image/jpeg,image/webp,image/gif"
              class="hidden-file"
              @change="onFilePicked"
            />
            <n-button @click="pickFile">选择图片</n-button>
            <span class="dim hint">支持 png/jpg/webp/gif，不超过 10MB，上传时可裁剪</span>
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

    <n-modal
      v-model:show="showCropper"
      preset="card"
      title="裁剪头像"
      style="width: min(480px, 94vw)"
      :mask-closable="false"
      @after-leave="onCropperClosed"
    >
      <div class="crop-wrap">
        <img v-if="cropUrl" ref="cropImg" :src="cropUrl" alt="avatar crop" @load="initCropper" />
      </div>
      <div class="crop-actions">
        <n-button :loading="uploading" type="primary" @click="confirmCrop">确认上传</n-button>
      </div>
    </n-modal>
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

.hidden-file {
  display: none;
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

.crop-wrap {
  max-height: 360px;
  overflow: hidden;
  background: #000;
  border-radius: 6px;
}

.crop-wrap img {
  display: block;
  max-width: 100%;
}

.crop-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}
</style>

<style>
/* round crop area so the preview matches the round avatar */
.cropper-view-box,
.cropper-face {
  border-radius: 50%;
}
</style>
