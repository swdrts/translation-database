import { invoke } from '@tauri-apps/api/core'
import type { DockerStatus, EnvReport, PersistedState, ProgressEvent, WizardConfig } from '../wizard/types'

export interface ContainerStatus { service: string; state: string; health: string | null }
export interface StatusPayload { engine_ready: boolean; containers: ContainerStatus[] }
export interface RegistryMirrorsInfo { path: string; mirrors: string[]; defaults: string[] }
export interface MirrorProbe { reachable: boolean; status: number }

export const getAppState = () => invoke<PersistedState>('get_app_state')
export const checkEnv = () => invoke<EnvReport>('check_env')
export const dockerProbe = () => invoke<DockerStatus>('docker_probe')
export const ensureDocker = () => invoke<DockerStatus>('ensure_docker')
export const installWsl2 = () => invoke<void>('install_wsl2')
// true=检测到既有 .env 复用旧三密钥（本轮输入的管理员密码未生效），完成页须提示沿用首次部署
export const saveConfig = (config: WizardConfig) => invoke<boolean>('save_config', { config })
export const startDeploy = () => invoke<{ url: string }>('start_deploy')
// open_web 不再收 url：后端 open_web_now 统一按持久化端口计算，与托盘菜单同源
export const openWeb = () => invoke<void>('open_web')
export const resetState = () => invoke<void>('reset_state')
export const refreshStatus = () => invoke<StatusPayload>('refresh_status')
export const stackOp = (op: 'start' | 'stop' | 'restart') => invoke<void>('stack_op', { op })
export const containerLogs = (service: string) => invoke<string>('container_logs', { service })
export const openDashboardWindow = () => invoke<void>('open_dashboard_window')
export const webUrl = () => invoke<string>('web_url')
// 设置页：工具自启开关（autostart 插件读写 Run 键/登录项）、改端口（只重建前端）、打开数据目录
export const getToolAutostart = () => invoke<boolean>('get_tool_autostart')
export const setToolAutostart = (enabled: boolean) => invoke<void>('set_tool_autostart', { enabled })
export const changePort = (port: number) => invoke<void>('change_port', { port })
export const openDataDir = () => invoke<void>('open_data_dir')
// 维护页：升级（pull 最新镜像 + up --wait 重建，进度走 deploy://progress）；
// 卸载 down[-v]，removeData=true 连数据卷删除并重置向导状态
export const upgradeStack = () => invoke<void>('upgrade_stack')
export const uninstall = (removeData: boolean) => invoke<void>('uninstall', { removeData })
// 镜像加速（国内镜像仓库）：读取/保存 ~/.docker/daemon.json；引擎运行中保存会自动重启 Docker 生效；
// set 返回值为引擎实际生效的镜像列表（重启后回读），网络错误时 probe 返回 reachable=false
export const getRegistryMirrors = () => invoke<RegistryMirrorsInfo>('get_registry_mirrors')
export const setRegistryMirrors = (mirrors: string[]) => invoke<string[]>('set_registry_mirrors', { mirrors })
export const probeRegistryMirror = (url: string) => invoke<MirrorProbe>('probe_registry_mirror', { url })
export { listen as listenProgress } from '@tauri-apps/api/event'
export type { ProgressEvent }
