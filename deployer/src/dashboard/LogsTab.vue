<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { containerLogs } from '../api/deployer'

// 与 compose 编排的四服务一一对应（frontend/backend/postgres/elasticsearch）
const SERVICES = ['frontend', 'backend', 'postgres', 'elasticsearch']
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
</script>

<template>
  <div style="display: flex; gap: 8px; margin-bottom: 12px">
    <el-select v-model="service" style="width: 180px">
      <el-option v-for="s in SERVICES" :key="s" :label="s" :value="s" />
    </el-select>
    <el-button class="view" type="primary" plain :loading="loading" @click="view">查看 / 刷新</el-button>
  </div>
  <pre style="max-height: 420px; overflow: auto; background: #f5f7fa; padding: 12px; margin: 0">{{ logs || '（空）' }}</pre>
</template>
