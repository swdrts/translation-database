import { invoke } from '@tauri-apps/api/core'
import type { DockerStatus, EnvReport, PersistedState, ProgressEvent, WizardConfig } from '../wizard/types'

export const getAppState = () => invoke<PersistedState>('get_app_state')
export const checkEnv = () => invoke<EnvReport>('check_env')
export const dockerProbe = () => invoke<DockerStatus>('docker_probe')
export const ensureDocker = () => invoke<DockerStatus>('ensure_docker')
export const installWsl2 = () => invoke<void>('install_wsl2')
export const saveConfig = (config: WizardConfig) => invoke<void>('save_config', { config })
export const startDeploy = () => invoke<{ url: string }>('start_deploy')
export const openWeb = (url: string) => invoke<void>('open_web', { url })
export const resetState = () => invoke<void>('reset_state')
export { listen as listenProgress } from '@tauri-apps/api/event'
export type { ProgressEvent }
