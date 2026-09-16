<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { checkEnv } from '../api/deployer'
import type { EnvReport } from './types'
const emit = defineEmits<{ (e: 'next'): void }>()
const report = ref<EnvReport | null>(null)
const error = ref('')
onMounted(async () => { try { report.value = await checkEnv() } catch (e) { error.value = String(e) } })
</script>

<template>
  <el-descriptions v-if="report" title="环境检测" :column="1" border>
    <el-descriptions-item label="操作系统">{{ report.os_ok ? '支持' : '仅支持 Windows 10+/macOS' }}</el-descriptions-item>
    <el-descriptions-item label="内存">{{ report.mem_gb.toFixed(1) }}GB {{ report.mem_ok ? '✓' : '（低于建议 8GB，仍可继续）' }}</el-descriptions-item>
    <el-descriptions-item label="磁盘空余">{{ report.disk_free_gb.toFixed(1) }}GB {{ report.disk_ok ? '✓' : '（低于所需 15GB）' }}</el-descriptions-item>
    <el-descriptions-item label="网络">{{ report.net_ok ? '可访问下载源' : '无法访问下载源，请检查网络' }}</el-descriptions-item>
    <el-descriptions-item label="端口">{{ report.port }} {{ report.port_free ? '可用' : '被占用，请稍后在配置步更换' }}</el-descriptions-item>
  </el-descriptions>
  <el-alert v-if="error" type="error" :title="error" :closable="false" />
  <el-button type="primary" style="margin-top: 16px" :disabled="!report" @click="emit('next')">下一步</el-button>
</template>
