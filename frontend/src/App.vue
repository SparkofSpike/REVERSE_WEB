<script setup lang="ts">
import { NConfigProvider, NGlobalStyle, NMessageProvider, darkTheme, zhCN, dateZhCN, type GlobalThemeOverrides } from 'naive-ui'
import { RouterView } from 'vue-router'
import { computed } from 'vue'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const theme = computed(() => (auth.theme === 'dark' ? darkTheme : null))

// Match Naive UI's primary color to the site accent (blue) so primary
// buttons blend with the rest of the UI instead of the default green.
const themeOverrides: GlobalThemeOverrides = {
  common: {
    primaryColor: '#4cc2ff',
    primaryColorHover: '#6ed0ff',
    primaryColorPressed: '#2a7fb8',
    primaryColorSuppl: '#4cc2ff'
  }
}
</script>

<template>
  <n-config-provider :theme="theme" :theme-overrides="themeOverrides" :locale="zhCN" :date-locale="dateZhCN">
    <n-global-style />
    <n-message-provider>
      <router-view />
    </n-message-provider>
  </n-config-provider>
</template>
