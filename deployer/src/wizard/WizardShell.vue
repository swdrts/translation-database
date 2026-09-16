<script setup lang="ts">
import { onMounted, onUnmounted, ref, computed } from 'vue'
import { listen } from '@tauri-apps/api/event'
import StepEnvCheck from './StepEnvCheck.vue'
import StepDocker from './StepDocker.vue'
import StepConfig from './StepConfig.vue'
import StepDeploy from './StepDeploy.vue'
import StepDone from './StepDone.vue'
import { getAppState, saveConfig } from '../api/deployer'
import type { ProgressEvent, WizardConfig } from './types'

const STAGES = ['check_env', 'install_docker', 'wait_engine', 'configure', 'pull', 'up', 'done'] as const
const current = ref<number>(0)
const deployedUrl = ref('')
const deployed = ref(false)
let unlisten: (() => void) | null = null

const view = computed(() => {
  if (deployed.value) return 'done'
  return ['env', 'docker', 'config', 'deploy', 'done'][current.value] ?? 'env'
})

const progress = ref<ProgressEvent | null>(null)

onMounted(async () => {
  unlisten = await listen<ProgressEvent>('deploy://progress', (e) => { progress.value = e.payload })
  const st = await getAppState()
  if (st.deployed) { deployed.value = true; return }
  // 后端 DeployStage 为 serde internally-tagged 枚举，实际序列化为 { stage: "xxx" } 对象；此处做形状兼容
  const raw = st.stage as unknown as string | { stage?: string }
  const name = typeof raw === 'string' ? raw : (raw?.stage ?? '')
  const idx = STAGES.indexOf(name as (typeof STAGES)[number])
  // CheckEnv→env 页；InstallDocker/WaitEngine→docker 页；Configure→config 页；Pull/Up→deploy 页
  current.value = idx <= 0 ? 0 : idx <= 2 ? 1 : idx === 3 ? 2 : 3
})

onUnmounted(() => unlisten?.())

async function onConfigSubmit(cfg: WizardConfig) {
  await saveConfig(cfg)
  // 部署执行唯一归 StepDeploy 所有（onMounted 初次 + 重试按钮），此处只导航
  current.value = 3
}
</script>

<template>
  <el-steps :active="deployed ? 4 : current" simple style="margin-bottom: 16px">
    <el-step title="环境检测" /><el-step title="Docker" /><el-step title="配置" /><el-step title="部署" /><el-step title="完成" />
  </el-steps>
  <StepEnvCheck v-if="view === 'env'" @next="current = 1" />
  <StepDocker v-else-if="view === 'docker'" @next="current = 2" />
  <StepConfig v-else-if="view === 'config'" @submit="onConfigSubmit" />
  <StepDeploy v-else-if="view === 'deploy'" :progress="progress" @done="(u: string) => { deployedUrl = u; deployed = true }" />
  <StepDone v-else :url="deployedUrl" />
</template>
