<template>
  <div class="admin-reindex-view">
    <div class="admin-card page-card rise">
      <h2 class="page-title">搜索修复</h2>
      <p class="page-lead">
        「重建索引」相当于把书重新整理一遍书架，让搜索又快又准。<b>一般不需要手动操作</b>，
        只有当搜索结果明显不对劲时才使用。
      </p>

      <div class="status-block">
        <div class="status-row">
          <span class="status-label">当前状态</span>
          <el-tag :type="stateType" effect="light" round>{{ stateLabel }}</el-tag>
        </div>
        <div class="status-row">
          <span class="status-label">整理进度</span>
          <span class="status-value">
            {{ status.indexed }} / {{ status.total }}
            <span v-if="status.total > 0">（{{ percent }}%）</span>
          </span>
        </div>
        <div class="status-row" v-if="status.startedAt">
          <span class="status-label">开始时间</span>
          <span class="status-value">{{ formatTime(status.startedAt) }}</span>
        </div>
        <div class="status-row" v-if="status.finishedAt">
          <span class="status-label">结束时间</span>
          <span class="status-value">{{ formatTime(status.finishedAt) }}</span>
        </div>
        <div class="status-row" v-if="status.error">
          <span class="status-label">错误信息</span>
          <span class="status-value error-text">{{ status.error }}</span>
        </div>
      </div>

      <el-progress
        v-if="status.state === 'RUNNING' && status.total > 0"
        :percentage="percent"
        :stroke-width="12"
        striped
        striped-flow
        class="progress"
      />
      <p class="running-hint" v-if="status.state === 'RUNNING'">
        正在整理中，这个页面会自动刷新进度，您可以先去忙别的～
      </p>

      <div class="actions">
        <el-button type="primary" :loading="triggering" data-test="trigger-reindex-btn" @click="doTrigger">
          开始重建
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

const percent = computed(() =>
  status.value.total > 0
    ? Math.min(100, Math.round((status.value.indexed / status.value.total) * 100))
    : 0
)

const stateLabel = computed(
  () =>
    ({ IDLE: '空闲', RUNNING: '整理中', DONE: '已完成', FAILED: '失败' })[
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
    await ElMessageBox.confirm(
      '重建期间搜索结果可能不完整（大约几分钟到几十分钟），确定现在开始吗？',
      '开始重建索引',
      { confirmButtonText: '开始', cancelButtonText: '再想想', type: 'warning' }
    )
  } catch {
    return
  }
  triggering.value = true
  try {
    status.value = await api.triggerReindex()
    ElMessage.success('已开始重建，请留意下方进度')
    if (status.value.state === 'RUNNING') startPolling()
  } catch {
    /* 拦截器已提示 */
  } finally {
    triggering.value = false
  }
}

function formatTime(iso?: string) {
  if (!iso) return '-'
  return new Date(iso).toLocaleString('zh-CN', { hour12: false })
}
</script>

<style scoped>
.admin-reindex-view { max-width: 760px; margin: 0 auto; padding-top: 8px; }
.admin-card { padding: 28px 32px; }

.status-block {
  margin: 22px 0 6px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  background: var(--el-fill-color-lighter);
  border: 1px solid var(--card-edge);
  border-radius: 12px;
  padding: 18px 22px;
}
.status-row { display: flex; align-items: center; gap: 16px; }
.status-label {
  font-family: var(--font-ui);
  font-size: 13px;
  letter-spacing: 2px;
  color: var(--ink-3);
  width: 76px;
  flex: none;
}
.status-value { font-size: 15px; color: var(--ink-2); }
.error-text { color: var(--cinnabar); }

.progress { margin: 16px 0 0; }
.running-hint { margin: 12px 0 0; font-size: 13.5px; color: var(--ink-3); }
.actions { margin-top: 22px; display: flex; gap: 12px; }
</style>
