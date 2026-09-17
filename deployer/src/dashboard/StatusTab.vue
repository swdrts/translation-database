<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { stackOp } from '../api/deployer'

defineProps<{ engineReady: boolean; containers: { service: string; state: string; health: string | null }[] }>()

const busy = ref('')
async function op(name: 'start' | 'stop' | 'restart') {
  busy.value = name
  try {
    await stackOp(name)
  } catch (e) {
    // 启停失败（引擎未就绪/compose 报错）需用户可见反馈，不能吞成 unhandled rejection
    ElMessage.error(String(e))
  } finally {
    busy.value = ''
  }
}
</script>

<template>
  <el-alert v-if="!engineReady" type="error" :closable="false" title="Docker 未运行——请在托盘菜单选择「尝试启动 Docker」" style="margin-bottom: 12px" />
  <el-space wrap>
    <el-card v-for="c in containers" :key="c.service" style="width: 200px" shadow="hover">
      <b>{{ c.service }}</b>
      <p>{{ c.state }} · {{ c.health ?? '—' }}</p>
    </el-card>
  </el-space>
  <div v-if="!containers.length" style="color: #909399; padding: 24px">暂无运行中的容器</div>
  <el-divider />
  <el-button :loading="busy === 'start'" type="primary" plain @click="op('start')">启动</el-button>
  <el-button :loading="busy === 'stop'" @click="op('stop')">停止</el-button>
  <el-button :loading="busy === 'restart'" @click="op('restart')">重启</el-button>
</template>
