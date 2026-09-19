<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { dockerProbe, ensureDocker, installWsl2, getRegistryMirrors, setRegistryMirrors } from '../api/deployer'
import type { ProgressEvent } from './types'
const props = defineProps<{ progress: ProgressEvent | null }>()
const emit = defineEmits<{ (e: 'next'): void }>()
const status = ref<'checking' | 'ready' | 'working' | 'error'>('checking')
const message = ref('')
const wslError = ref(false)
const mirrorNote = ref('')

// 后端 install_docker 阶段的下载进度（downloaded/total 字节）；total=0（无 Content-Length）时只显示已下载量
const downloadPct = computed(() => {
  const d = props.progress?.download
  return d && d.total > 0 ? Math.floor((d.downloaded / d.total) * 100) : null
})
const downloadMb = computed(() => {
  const d = props.progress?.download
  if (!d) return null
  const dl = Math.floor(d.downloaded / 1048576)
  return d.total > 0 ? `${dl} / ${Math.floor(d.total / 1048576)} MB` : `${dl} MB`
})

// 引擎就绪后配置国内镜像加速：已有配置尊重用户（不改），未配置才预填默认。
// 失败只提示不阻塞向导（镜像地址可稍后在设置页配置）
async function applyMirrors() {
  try {
    const info = await getRegistryMirrors()
    if (info.mirrors.length > 0) return
    await setRegistryMirrors(info.defaults)
    mirrorNote.value = '已为你配置国内镜像加速，拉取镜像会更快'
  } catch {
    mirrorNote.value = '镜像加速暂未能自动配置，可稍后在管理窗口「设置」页配置'
  }
}

async function run() {
  status.value = 'checking'
  try {
    const probe = await dockerProbe()
    if (probe.engine_ready) { await applyMirrors(); status.value = 'ready'; emit('next'); return }
    status.value = 'working'
    await ensureDocker()
    await applyMirrors()
    status.value = 'ready'
    emit('next')
  } catch (e) {
    status.value = 'error'
    message.value = String(e)
    wslError.value = message.value.includes('WSL2')
  }
}

onMounted(run)

async function fixWsl() {
  await installWsl2()
  message.value = 'WSL2 安装命令已执行，请按系统提示完成并重启电脑，再回到本窗口重试。'
}
</script>

<template>
  <el-result v-if="status === 'ready'" icon="success" title="运行环境已就绪" :sub-title="mirrorNote || '即将进入下一步'" />
  <div v-else-if="status === 'checking'">
    <el-alert type="info" :closable="false" title="翻译数据库需要一个叫 Docker 的运行引擎，正在检查你的电脑是否已有…" />
    <el-progress indeterminate style="margin-top: 12px" />
  </div>
  <div v-else-if="status === 'working'">
    <el-alert type="info" :closable="false" title="正在自动准备运行引擎（Docker）" description="工具会自动为你下载并安装，大约需要 3–10 分钟（视网速）。期间可能弹出系统确认框，请点击「是」或「允许」。请勿关闭电脑或本窗口。" />
    <p v-if="progress?.message" style="color: #909399; font-size: 13px">{{ progress.message }}</p>
    <template v-if="downloadPct !== null">
      <div style="margin-top: 12px">正在下载 Docker Desktop</div>
      <el-progress :percentage="downloadPct" style="margin-top: 12px" />
      <p v-if="downloadMb" style="color: #909399; font-size: 13px; margin-top: 4px">已下载 {{ downloadMb }}</p>
    </template>
    <el-progress v-else indeterminate style="margin-top: 12px" />
  </div>
  <div v-else>
    <el-alert type="error" title="自动准备失败了，别担心，可以重试" :closable="false" />
    <!-- white-space 可继承：让后端失败知识库附带的 \n建议：… 换行展示 -->
    <el-alert type="error" :title="message" :closable="false" style="white-space: pre-line; margin-top: 8px" />
    <el-button v-if="wslError" type="primary" style="margin-top: 12px" @click="fixWsl">一键安装 WSL2</el-button>
    <el-button style="margin-top: 12px" @click="run">重试</el-button>
  </div>
</template>