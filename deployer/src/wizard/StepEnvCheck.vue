<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { checkEnv } from '../api/deployer'
import type { EnvReport } from './types'

const emit = defineEmits<{ (e: 'next', port?: number): void }>()
const report = ref<EnvReport | null>(null)
const error = ref('')
// 采纳推荐端口后：端口项转绿，下一步时带回配置页预填
const adoptedPort = ref<number | null>(null)

interface Item { key: string; label: string; ok: boolean; warn: boolean; text: string }

const items = computed<Item[]>(() => {
  const r = report.value
  if (!r) return []
  return [
    { key: 'os', label: '操作系统', ok: r.os_ok, warn: false, text: r.os_ok ? '支持（Windows 10 及以上 / macOS）' : '仅支持 Windows 10 及以上、macOS' },
    { key: 'mem', label: '内存', ok: r.mem_ok, warn: !r.mem_ok, text: `${r.mem_gb.toFixed(1)}GB${r.mem_ok ? '' : '（低于建议的 8GB，运行可能较慢）'}` },
    { key: 'disk', label: '磁盘空余', ok: r.disk_ok, warn: false, text: `${r.disk_free_gb.toFixed(1)}GB${r.disk_ok ? '' : '（低于所需的 15GB）'}` },
    { key: 'net', label: '网络连接', ok: r.net_ok, warn: false, text: r.net_ok ? '可以连接到下载源' : '无法连接到下载源，请检查网络后重试' },
    { key: 'port', label: '网页端口', ok: r.port_free || adoptedPort.value !== null, warn: false, text: adoptedPort.value !== null ? `将使用端口 ${adoptedPort.value}` : (r.port_free ? `端口 ${r.port} 可用` : `端口 ${r.port} 已被其他程序占用`) },
  ]
})

// 只显示需要注意的项与用户已做选择的端口项；其余通过项折叠为汇总行
const problems = computed(() => items.value.filter(i => !i.ok || i.warn || (i.key === 'port' && (adoptedPort.value !== null || !(report.value?.port_free ?? true)))))
const passedCount = computed(() => items.value.filter(i => (i.ok && !i.warn && !(i.key === 'port' && adoptedPort.value !== null))).length)
// 硬失败（无法继续）：操作系统/磁盘/网络；内存不足与端口占用可绕过
const HARD_KEYS = ['os', 'disk', 'net']
const blocked = computed(() => items.value.some(i => !i.ok && HARD_KEYS.includes(i.key)))

async function run() {
  error.value = ''
  adoptedPort.value = null
  try { report.value = await checkEnv() } catch (e) { error.value = String(e) }
}

function adoptPort() {
  const r = report.value
  if (r && !r.port_free) adoptedPort.value = r.suggested_port
}

function next() { emit('next', adoptedPort.value ?? undefined) }

onMounted(run)
</script>

<template>
  <div>
    <h3 style="margin: 0 0 8px">正在检查你的电脑是否满足运行条件</h3>
    <p v-if="!report && !error" style="color: #909399">检查中，请稍候…</p>
    <ul v-if="report" style="list-style: none; padding: 0; margin: 0">
      <li v-for="i in problems" :key="i.key" style="margin: 10px 0">
        <span :style="{ color: i.ok ? '#67c23a' : i.warn ? '#e6a23c' : '#f56c6c', fontWeight: 'bold' }">{{ i.ok ? '✓' : i.warn ? '⚠' : '✕' }}</span>
        <b style="margin: 0 6px">{{ i.label }}</b>{{ i.text }}
        <el-button v-if="i.key === 'port' && !i.ok" class="adopt-port" size="small" type="primary" plain style="margin-left: 8px" @click="adoptPort">
          改用端口 {{ report?.suggested_port }}
        </el-button>
      </li>
    </ul>
    <p v-if="report && problems.length === 0" style="color: #67c23a">✓ 检查完成：{{ items.length }} 项全部通过</p>
    <p v-else-if="report && passedCount > 0" style="color: #909399; font-size: 13px">其余 {{ passedCount }} 项检查已通过</p>
    <el-alert v-if="report && blocked" type="error" title="上方红色项必须先解决才能继续" :closable="false" style="margin-top: 8px" />
    <el-alert v-if="error" type="error" :title="error" :closable="false" />
    <el-button v-if="error" style="margin-top: 12px" @click="run">重试</el-button>
    <div style="margin-top: 16px">
      <el-button type="primary" size="large" :disabled="!report || blocked" @click="next">下一步</el-button>
    </div>
  </div>
</template>
