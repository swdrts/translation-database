import type { WizardConfig } from './types'

export function defaultWizardConfig(): WizardConfig {
  return { port: 80, admin_password: '', es_heap_gb: 2, backend_heap_gb: 1, app_version: '0.1.0' }
}

export function validateConfig(cfg: WizardConfig): string[] {
  const errs: string[] = []
  if (!Number.isInteger(cfg.port) || cfg.port < 1 || cfg.port > 65535) errs.push('网页端口必须在 1-65535 之间')
  if (cfg.admin_password.length < 8) errs.push('管理员密码至少 8 位')
  if (!(cfg.es_heap_gb >= 1 && cfg.es_heap_gb <= 16)) errs.push('ES 堆内存必须在 1-16GB')
  if (!(cfg.backend_heap_gb >= 1 && cfg.backend_heap_gb <= 16)) errs.push('后端 JVM 内存必须在 1-16GB')
  if (!/^[0-9A-Za-z][0-9A-Za-z._-]{0,63}$/.test(cfg.app_version)) errs.push('镜像版本只能是字母数字与 . _ -（≤64 位）')
  return errs
}
