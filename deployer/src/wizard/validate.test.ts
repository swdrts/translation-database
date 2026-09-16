import { describe, it, expect } from 'vitest'
import { validateConfig, defaultWizardConfig } from './validate'

describe('validateConfig', () => {
  it('默认配置 + 8 位密码通过', () => {
    expect(validateConfig({ ...defaultWizardConfig(), admin_password: '12345678' })).toEqual([])
  })
  it('短密码报错', () => {
    const errs = validateConfig({ ...defaultWizardConfig(), admin_password: '123' })
    expect(errs.some((e) => e.includes('管理员密码'))).toBe(true)
  })
  it('堆内存越界与非法镜像版本报错', () => {
    const errs = validateConfig({ ...defaultWizardConfig(), admin_password: '12345678', es_heap_gb: 0 })
    expect(errs.length).toBeGreaterThan(0)
    expect(validateConfig({ ...defaultWizardConfig(), admin_password: '12345678', app_version: 'bad tag!' }).length).toBeGreaterThan(0)
  })
})
