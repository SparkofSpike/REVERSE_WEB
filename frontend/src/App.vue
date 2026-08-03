<script setup lang="ts">
import { NConfigProvider, NGlobalStyle, darkTheme, zhCN, dateZhCN } from 'naive-ui'
import { RouterView } from 'vue-router'
import { computed } from 'vue'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const theme = computed(() => (auth.theme === 'dark' ? darkTheme : null))
</script>

<template>
  <n-config-provider :theme="theme" :locale="zhCN" :date-locale="dateZhCN">
    <n-global-style />
    <router-view />
  </n-config-provider>
</template>
