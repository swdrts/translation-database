<script setup lang="ts">
import { onMounted, onUnmounted, ref, computed } from 'vue'
import { listen } from '@tauri-apps/api/event'
import StepEnvCheck from './StepEnvCheck.vue'
import StepDocker from './StepDocker.vue'
import StepConfig from './StepConfig.vue'
import StepDeploy from './StepDeploy.vue'
import StepDone from './StepDone.vue'
import { getAppState, saveConfig, openDashboardWindow } from '../api/deployer'
import type { ProgressEvent, WizardConfig } from './types'

const STAGES = ['check_env', 'install_docker', 'wait_engine', 'configure', 'pull', 'up', 'done'] as const
const current = ref<number>(0)
const deployedUrl = ref('')
const deployed = ref(false)
// 完成页密码来源：state.json 落盘时已剥 admin_password（不存明文），
// 重启后无值 → StepDone 显示「本次会话未设置」；当次会话内取提交配置时保存的明文
const lastConfig = ref<WizardConfig | null>(null)
// 环境检测发现默认端口被占时的推荐端口：采纳后预填配置页
const suggestedPort = ref<number | null>(null)
// save_config 返回 true 表示复用了旧 .env 三密钥（保留数据重部署）：本轮输入的管理员
// 密码未生效，完成页改为提示「沿用首次部署所设」，不展示/不可复制本轮输入
const secretsReused = ref(false)
let unlisten: (() => void) | null = null
let unlistenMenu: (() => void) | null = null

const view = computed(() => {
  if (deployed.value) return 'done'
  return ['env', 'docker', 'config', 'deploy', 'done'][current.value] ?? 'env'
})

const progress = ref<ProgressEvent | null>(null)
const uiError = ref('')

onMounted(async () => {
  unlisten = await listen<ProgressEvent>('deploy://progress', (e) => { progress.value = e.payload })
  // 托盘「打开管理窗口」：与 DashboardShell 各订阅一份，openDashboardWindow 幂等
  unlistenMenu = await listen<string>('tray://menu', (e) => {
    if (e.payload === 'open_dashboard') openDashboardWindow()
  })
  let st
  try {
    st = await getAppState()
  } catch (e) {
    // 状态文件不可读（如权限被拒）时不能重置到向导首页，停在首页报错由用户处置
    uiError.value = '读取部署状态失败：' + String(e)
    return
  }
  if (st.deployed) {
    // 重启后直接落在完成页：URL 需按持久化端口还原（非 80 端口要带显式端口）
    deployedUrl.value = st.config && st.config.port !== 80 ? `http://localhost:${st.config.port}` : 'http://localhost'
    deployed.value = true
    return
  }
  // 后端 DeployStage 为 serde internally-tagged 枚举，实际序列化为 { stage: "xxx" } 对象；此处做形状兼容
  const raw = st.stage as unknown as string | { stage?: string }
  const name = typeof raw === 'string' ? raw : (raw?.stage ?? '')
  const idx = STAGES.indexOf(name as (typeof STAGES)[number])
  // CheckEnv→env 页；InstallDocker/WaitEngine→docker 页；Configure→config 页；Pull/Up→deploy 页
  current.value = idx <= 0 ? 0 : idx <= 2 ? 1 : idx === 3 ? 2 : 3
})

onUnmounted(() => { unlisten?.(); unlistenMenu?.() })

function onEnvNext(port?: number) {
  suggestedPort.value = port ?? null
  current.value = 1
}

async function onConfigSubmit(cfg: WizardConfig) {
  lastConfig.value = cfg
  try {
    secretsReused.value = await saveConfig(cfg)
  } catch (e) {
    uiError.value = '保存配置失败：' + String(e)
    return
  }
  uiError.value = ''
  // 部署执行唯一归 StepDeploy 所有（onMounted 初次 + 重试按钮），此处只导航
  current.value = 3
}
</script>

<template>
  <el-alert v-if="uiError" :title="uiError" type="error" :closable="false" style="margin-bottom: 12px" />
  <el-steps :active="deployed ? 4 : current" simple style="margin-bottom: 16px">
    <el-step title="检查电脑" /><el-step title="准备运行环境" /><el-step title="设置密码" /><el-step title="安装" /><el-step title="完成" />
  </el-steps>
  <StepEnvCheck v-if="view === 'env'" @next="onEnvNext" />
  <StepDocker v-else-if="view === 'docker'" :progress="progress" @next="current = 2" />
  <StepConfig v-else-if="view === 'config'" :initial-port="suggestedPort ?? undefined" @submit="onConfigSubmit" />
  <StepDeploy v-else-if="view === 'deploy'" :progress="progress" @done="(u: string) => { deployedUrl = u; deployed = true }" />
  <StepDone v-else :url="deployedUrl" :password="lastConfig?.admin_password ?? ''" :reused="secretsReused" />
</template>
