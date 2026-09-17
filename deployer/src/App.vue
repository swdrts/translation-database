<script setup lang="ts">
import { getCurrentWindow } from '@tauri-apps/api/window'
import { computed } from 'vue'
import WizardShell from './wizard/WizardShell.vue'
import DashboardShell from './dashboard/DashboardShell.vue'

// 两窗口共用同一 SPA：按窗口 label 路由（main=向导，dashboard=管理界面）
const isDashboard = computed(() => getCurrentWindow().label === 'dashboard')
</script>

<template>
  <main style="padding: 24px">
    <h1>翻译数据库部署器</h1>
    <DashboardShell v-if="isDashboard" />
    <WizardShell v-else />
  </main>
</template>
