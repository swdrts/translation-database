<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { containerLogs } from '../api/deployer'

// 与 compose 编排的四服务一一对应（frontend/backend/postgres/elasticsearch），
// 选项显示中文角色名 + 英文原名，零基础用户也能对上
const SERVICES = [
  { value: 'backend', label: '后台服务 (backend)' },
  { value: 'frontend', label: '网页服务 (frontend)' },
  { value: 'postgres', label: '数据库 (postgres)' },
  { value: 'elasticsearch', label: '搜索引擎 (elasticsearch)' },
]
const service = ref('backend')
const logs = ref('')
const loading = ref(false)

async function view() {
  loading.value = true
  try {
    logs.value = await containerLogs(service.value)
  } catch (e) {
    // 引擎未起/容器不存在时 compose 报错需用户可见，不能吞成 unhandled rejection
    ElMessage.error(String(e))
  } finally {
    loading.value = false
  }
}

onMounted(view)
</script>

<template>
  <div style="display: flex; gap: 8px; margin-bottom: 12px">
    <el-select v-model="service" style="width: 220px">
      <el-option v-for="s in SERVICES" :key="s.value" :label="s.label" :value="s.value" />
    </el-select>
    <el-button class="view" type="primary" plain :loading="loading" @click="view">刷新</el-button>
  </div>
  <p style="color: #909399; font-size: 13px; margin: 0 0 8px">显示所选服务最近 500 行日志，出问题时可发给维护人员排查</p>
  <pre style="max-height: 420px; overflow: auto; background: #f5f7fa; padding: 12px; margin: 0">{{ logs || '（空）' }}</pre>
</template>
