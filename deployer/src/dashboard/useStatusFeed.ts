import { onMounted, onUnmounted, ref } from 'vue'
import { listen } from '@tauri-apps/api/event'
import { refreshStatus } from '../api/deployer'
import type { ContainerStatus } from '../api/deployer'

/// 状态数据流：托盘 30s `tray://status` 事件为主，10s `refresh_status` 轮询兜底
/// （覆盖事件丢失与窗口刚打开最长 30s 无数据的空窗期）。
export function useStatusFeed() {
  const engineReady = ref(false)
  const containers = ref<ContainerStatus[]>([])
  let unlisten: (() => void) | null = null
  let timer: ReturnType<typeof setInterval> | null = null

  async function refresh() {
    try {
      const p = await refreshStatus()
      engineReady.value = p.engine_ready
      containers.value = p.containers
    } catch {
      // 兜底轮询失败静默：托盘事件仍会补上，弹提示反而制造噪音
    }
  }

  onMounted(async () => {
    unlisten = await listen<{ engine_ready: boolean; containers: ContainerStatus[] }>('tray://status', (e) => {
      engineReady.value = e.payload.engine_ready
      containers.value = e.payload.containers
    })
    await refresh()
    timer = setInterval(refresh, 10_000)
  })
  onUnmounted(() => {
    unlisten?.()
    if (timer) clearInterval(timer)
  })

  return { engineReady, containers, refresh }
}
