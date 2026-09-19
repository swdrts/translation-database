<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { stackOp, openWeb } from '../api/deployer'

const props = defineProps<{ engineReady: boolean; containers: { service: string; state: string; health: string | null }[] }>()

const ROLE: Record<string, string> = {
  frontend: '网页服务',
  backend: '后台服务',
  postgres: '数据库',
  elasticsearch: '搜索引擎',
}

function stateText(c: { state: string; health: string | null }): string {
  if (c.state !== 'running') return '已停止'
  if (c.health === 'healthy') return '运行中·健康'
  if (c.health === 'starting') return '启动中'
  return c.health === 'unhealthy' ? '异常' : '运行中'
}

function tagType(c: { state: string; health: string | null }): 'success' | 'warning' | 'danger' | 'info' {
  if (c.state !== 'running') return 'info'
  if (c.health === 'healthy' || c.health === null) return 'success'
  return c.health === 'starting' ? 'warning' : 'danger'
}

const banner = computed<{ type: 'success' | 'warning' | 'error'; text: string }>(() => {
  if (!props.engineReady) return { type: 'error', text: 'Docker 未运行——请在托盘菜单选择「尝试启动 Docker」' }
  if (!props.containers.length) return { type: 'warning', text: '尚未部署或已卸载，打开部署器可重新安装' }
  const allHealthy = props.containers.every(c => c.state === 'running' && (c.health === 'healthy' || c.health === null))
  return allHealthy
    ? { type: 'success', text: '服务运行中，网页可正常访问' }
    : { type: 'warning', text: '服务正在启动或存在异常，请稍候或查看日志' }
})

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

async function onOpenWeb() {
  try {
    await openWeb()
  } catch (e) {
    ElMessage.error(String(e))
  }
}
</script>

<template>
  <el-alert :type="banner.type" :title="banner.text" :closable="false" style="margin-bottom: 12px" />
  <el-button class="open-web" type="primary" size="large" style="margin-bottom: 16px" @click="onOpenWeb">
    打开翻译数据库
  </el-button>
  <el-space wrap>
    <el-card v-for="c in containers" :key="c.service" style="width: 200px" shadow="hover">
      <b>{{ ROLE[c.service] ?? c.service }}</b>
      <p style="color: #909399; font-size: 12px; margin: 2px 0">{{ c.service }}</p>
      <el-tag :type="tagType(c)">{{ stateText(c) }}</el-tag>
    </el-card>
  </el-space>
  <div v-if="!containers.length" style="color: #909399; padding: 24px">暂无运行中的容器</div>
  <el-divider />
  <el-button :loading="busy === 'start'" type="primary" plain @click="op('start')">启动</el-button>
  <el-button :loading="busy === 'stop'" @click="op('stop')">停止</el-button>
  <el-button :loading="busy === 'restart'" @click="op('restart')">重启</el-button>
</template>
