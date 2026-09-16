<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { dockerProbe, ensureDocker, installWsl2 } from '../api/deployer'
const emit = defineEmits<{ (e: 'next'): void }>()
const status = ref<'checking' | 'ready' | 'working' | 'error'>('checking')
const message = ref('')
const wslError = ref(false)

async function run() {
  status.value = 'checking'
  try {
    const probe = await dockerProbe()
    if (probe.engine_ready) { status.value = 'ready'; emit('next'); return }
    status.value = 'working'
    await ensureDocker()
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
  <el-result v-if="status === 'ready'" icon="success" title="Docker 已就绪" />
  <el-result v-else-if="status === 'checking'" icon="info" title="正在检测 Docker…" />
  <div v-else-if="status === 'working'">
    <el-alert type="info" :title="message || '正在准备 Docker（下载/安装/启动，视网速可能较久）…'" :closable="false" />
    <el-progress indeterminate style="margin-top: 12px" />
  </div>
  <div v-else>
    <el-alert type="error" :title="message" :closable="false" />
    <el-button v-if="wslError" type="primary" style="margin-top: 12px" @click="fixWsl">一键安装 WSL2</el-button>
    <el-button style="margin-top: 12px" @click="run">重试</el-button>
  </div>
</template>
