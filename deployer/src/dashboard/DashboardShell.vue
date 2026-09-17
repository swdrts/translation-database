<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { listen } from '@tauri-apps/api/event'
import { openDashboardWindow, getAppState } from '../api/deployer'
import StatusTab from './StatusTab.vue'
import LogsTab from './LogsTab.vue'
import SettingsTab from './SettingsTab.vue'
import MaintainTab from './MaintainTab.vue'
import { useStatusFeed } from './useStatusFeed'

const { engineReady, containers } = useStatusFeed()
// 设置页端口初始值：从持久化状态取当前部署端口（读取失败保持 80）
const port = ref(80)
let unlistenMenu: (() => void) | null = null

onMounted(async () => {
  try {
    const st = await getAppState()
    port.value = st.config?.port ?? 80
  } catch { /* 读取失败不阻塞页面，端口输入框仍可手动改 */ }
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
    <el-tab-pane label="日志"><LogsTab /></el-tab-pane>
    <el-tab-pane label="设置"><SettingsTab :port="port" /></el-tab-pane>
    <el-tab-pane label="维护"><MaintainTab /></el-tab-pane>
  </el-tabs>
</template>
