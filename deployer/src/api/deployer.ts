import { invoke } from '@tauri-apps/api/core'
import type { DockerStatus, EnvReport, PersistedState, ProgressEvent, WizardConfig } from '../wizard/types'

export interface ContainerStatus { service: string; state: string; health: string | null }
export interface StatusPayload { engine_ready: boolean; containers: ContainerStatus[] }

export const getAppState = () => invoke<PersistedState>('get_app_state')
export const checkEnv = () => invoke<EnvReport>('check_env')
export const dockerProbe = () => invoke<DockerStatus>('docker_probe')
export const ensureDocker = () => invoke<DockerStatus>('ensure_docker')
export const installWsl2 = () => invoke<void>('install_wsl2')
export const saveConfig = (config: WizardConfig) => invoke<void>('save_config', { config })
export const startDeploy = () => invoke<{ url: string }>('start_deploy')
// open_web 不再收 url：后端 open_web_now 统一按持久化端口计算，与托盘菜单同源
export const openWeb = () => invoke<void>('open_web')
export const resetState = () => invoke<void>('reset_state')
export const refreshStatus = () => invoke<StatusPayload>('refresh_status')
export const stackOp = (op: 'start' | 'stop' | 'restart') => invoke<void>('stack_op', { op })
export const containerLogs = (service: string) => invoke<string>('container_logs', { service })
export const openDashboardWindow = () => invoke<void>('open_dashboard_window')
export const webUrl = () => invoke<string>('web_url')
export { listen as listenProgress } from '@tauri-apps/api/event'
export type { ProgressEvent }
