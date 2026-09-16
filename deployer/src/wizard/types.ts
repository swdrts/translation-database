export interface WizardConfig {
  port: number
  admin_password: string
  es_heap_gb: number
  backend_heap_gb: number
  app_version: string
}
export interface EnvReport {
  os_ok: boolean
  mem_gb: number
  mem_ok: boolean
  disk_free_gb: number
  disk_ok: boolean
  net_ok: boolean
  port: number
  port_free: boolean
}
export interface DockerStatus { installed: boolean; engine_ready: boolean }
export interface PullEvent { service: string; phase: string; detail: string }
export interface ProgressEvent {
  stage: string
  message: string
  pull: PullEvent | null
  download: { downloaded: number; total: number } | null
}
export interface PersistedState {
  stage: string
  deployed: boolean
  config: WizardConfig | null
}
