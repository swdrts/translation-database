<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { startDeploy } from '../api/deployer'
import type { ProgressEvent } from './types'
const props = defineProps<{ progress: ProgressEvent | null }>()
const emit = defineEmits<{ (e: 'done', url: string): void }>()
const error = ref('')
const busy = ref(false)

// 后端 stage（pull/up）映射为白话子任务；up 阶段消息出现 Healthy 视为进入健康检查
const TASKS = [
  { label: '下载组件', desc: '从网上下载程序文件（约 1.7GB），时间取决于网速' },
  { label: '启动服务', desc: '启动数据库与程序' },
  { label: '健康检查', desc: '确认各部分都正常工作' },
]
const phase = computed(() => {
  const s = props.progress?.stage ?? ''
  if (s === 'up') return /healthy/i.test(props.progress?.message ?? '') ? 2 : 1
  // 尚无进度（刚启动）与 pull 停在第一项；其余阶段归入收尾
  return !s || s === 'pull' ? 0 : 2
})

async function run() {
  busy.value = true
  error.value = ''
  try { emit('done', (await startDeploy()).url) } catch (e) { error.value = String(e) } finally { busy.value = false }
}
onMounted(run)
</script>

<template>
  <div>
    <h3 style="margin: 0 0 8px">正在安装，请稍候…</h3>
    <p style="color: #909399; margin-top: 0">整个过程通常需要 5–15 分钟，请不要关闭电脑或本窗口。</p>
    <ul style="list-style: none; padding: 0; margin: 16px 0">
      <li v-for="(t, i) in TASKS" :key="t.label" style="margin: 12px 0">
        <span v-if="i < phase" style="color: #67c23a; font-weight: bold">✓</span>
        <span v-else-if="i === phase && busy" style="color: #409eff; font-weight: bold">●</span>
        <span v-else style="color: #c0c4cc">○</span>
        <b style="margin: 0 6px" :style="{ color: i <= phase ? '#303133' : '#909399' }">{{ t.label }}</b>
        <span style="color: #909399; font-size: 13px">{{ t.desc }}</span>
      </li>
    </ul>
    <el-progress v-if="busy" indeterminate style="margin: 12px 0" />
    <p v-if="busy && progress?.message" style="color: #909399; font-size: 13px">{{ progress.message }}</p>
    <!-- white-space 可继承：让后端失败知识库附带的 \n建议：… 换行展示 -->
    <el-alert v-if="error" type="error" title="安装中途出了问题，别担心，已下载的部分不会重来" :closable="false" />
    <el-alert v-if="error" type="error" :title="error" :closable="false" style="white-space: pre-line; margin-top: 8px" />
    <el-button v-if="error" style="margin-top: 12px" @click="run">重试本步</el-button>
  </div>
</template>
