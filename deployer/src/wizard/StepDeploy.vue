<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { startDeploy } from '../api/deployer'
import type { ProgressEvent } from './types'
defineProps<{ progress: ProgressEvent | null }>()
const emit = defineEmits<{ (e: 'done', url: string): void }>()
const error = ref('')
const busy = ref(false)

async function run() {
  busy.value = true
  error.value = ''
  try { emit('done', (await startDeploy()).url) } catch (e) { error.value = String(e) } finally { busy.value = false }
}
onMounted(run)
</script>

<template>
  <div>
    <el-alert v-if="progress" type="info" :title="`${progress.stage}：${progress.message}`" :closable="false" />
    <el-progress v-if="busy" indeterminate style="margin: 12px 0" />
    <!-- white-space 可继承：让后端失败知识库附带的 \n建议：… 换行展示 -->
    <el-alert v-if="error" type="error" :title="error" :closable="false" style="white-space: pre-line" />
    <el-button v-if="error" style="margin-top: 12px" @click="run">重试本步</el-button>
  </div>
</template>
