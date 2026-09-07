<template>
  <div class="admin-reindex-view">
    <div class="admin-card">
      <h2 class="page-title">重建索引</h2>

      <el-descriptions :column="2" border class="status-card">
        <el-descriptions-item label="状态">
          <el-tag :type="stateType">{{ stateLabel }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="进度">
          {{ status.indexed }} / {{ status.total }}
        </el-descriptions-item>
        <el-descriptions-item label="开始时间">{{ status.startedAt || '-' }}</el-descriptions-item>
        <el-descriptions-item label="结束时间">{{ status.finishedAt || '-' }}</el-descriptions-item>
        <el-descriptions-item label="错误信息" :span="2">{{ status.error || '-' }}</el-descriptions-item>
      </el-descriptions>

      <el-progress
        v-if="status.state === 'RUNNING' && status.total > 0"
        :percentage="Math.min(100, Math.round((status.indexed / status.total) * 100))"
        class="progress"
      />

      <div class="actions">
        <el-button type="primary" :loading="triggering" data-test="trigger-reindex-btn" @click="doTrigger">
          触发重建
        </el-button>
        <el-button @click="refresh">刷新状态</el-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, type ReindexStatus } from '../api'

const status = ref<ReindexStatus>({ state: 'IDLE', indexed: 0, total: 0 })
const triggering = ref(false)
let pollTimer: ReturnType<typeof setInterval> | null = null

const stateLabel = computed(
  () =>
    ({ IDLE: '空闲', RUNNING: '进行中', DONE: '已完成', FAILED: '失败' })[
      status.value.state
    ] || status.value.state
)
const stateType = computed(
  () =>
    ({ IDLE: 'info', RUNNING: 'warning', DONE: 'success', FAILED: 'danger' }[
      status.value.state
    ] || 'info')
)

onMounted(async () => {
  await refresh()
  if (status.value.state === 'RUNNING') startPolling()
})

onUnmounted(stopPolling)

async function refresh() {
  try {
    status.value = await api.reindexStatus()
    if (status.value.state === 'RUNNING') startPolling()
    else stopPolling()
  } catch {
    /* 拦截器已提示 */
  }
}

function startPolling() {
  if (pollTimer) return
  pollTimer = setInterval(async () => {
    try {
      status.value = await api.reindexStatus()
      if (status.value.state !== 'RUNNING') stopPolling()
    } catch {
      stopPolling()
    }
  }, 5000)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

async function doTrigger() {
  try {
    await ElMessageBox.confirm('确定要触发全量重建索引吗？重建期间搜索可能不完整。', '确认', {
      confirmButtonText: '触发',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  triggering.value = true
  try {
    status.value = await api.triggerReindex()
    ElMessage.success('已触发重建')
    if (status.value.state === 'RUNNING') startPolling()
  } catch {
    /* 拦截器已提示 */
  } finally {
    triggering.value = false
  }
}
</script>

<style scoped>
.admin-reindex-view { max-width: 860px; margin: 0 auto; padding-top: 24px; }
.admin-card { background: #fff; border-radius: 10px; padding: 24px 28px; }
.page-title { color: #3d3d3d; margin: 0 0 20px; font-size: 20px; }
.status-card { margin-bottom: 20px; }
.progress { margin-bottom: 16px; }
.actions { margin-top: 8px; }
</style>
