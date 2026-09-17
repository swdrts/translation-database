<script setup lang="ts">
import { onMounted, onUnmounted } from 'vue'
import { listen } from '@tauri-apps/api/event'
import { openDashboardWindow } from '../api/deployer'
import StatusTab from './StatusTab.vue'
import { useStatusFeed } from './useStatusFeed'

const { engineReady, containers } = useStatusFeed()
let unlistenMenu: (() => void) | null = null

onMounted(async () => {
  // 与 WizardShell 各订阅一份（openDashboardWindow 幂等）：
  // 本窗口隐藏期间托盘「打开管理窗口」仍能把它唤起
  unlistenMenu = await listen<string>('tray://menu', (e) => {
    if (e.payload === 'open_dashboard') openDashboardWindow()
  })
})
onUnmounted(() => unlistenMenu?.())
</script>

<template>
  <el-tabs>
    <el-tab-pane label="状态">
      <StatusTab :engine-ready="engineReady" :containers="containers" />
    </el-tab-pane>
    <el-tab-pane label="日志"><!-- Task 6 实现 --></el-tab-pane>
    <el-tab-pane label="设置"><!-- Task 7 实现 --></el-tab-pane>
    <el-tab-pane label="维护"><!-- Task 8 实现 --></el-tab-pane>
  </el-tabs>
</template>
