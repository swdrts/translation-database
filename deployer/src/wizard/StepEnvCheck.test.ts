import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import StepEnvCheck from './StepEnvCheck.vue'
import type { EnvReport } from './types'

const baseReport: EnvReport = {
  os_ok: true, mem_gb: 16, mem_ok: true, disk_free_gb: 40, disk_ok: true,
  net_ok: true, port: 80, port_free: true, suggested_port: 8080,
}
vi.mock('../api/deployer', () => ({ checkEnv: vi.fn() }))
import { checkEnv } from '../api/deployer'

describe('StepEnvCheck', () => {
  beforeEach(() => { vi.mocked(checkEnv).mockResolvedValue(baseReport) })

  it('全部通过时汇总绿字且下一步可点，不带端口', async () => {
    const wrapper = mount(StepEnvCheck, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(wrapper.text()).toContain('5 项全部通过')
    const next = wrapper.find('button')
    expect(next.attributes('disabled')).toBeUndefined()
    await next.trigger('click')
    expect(wrapper.emitted('next')![0]).toEqual([undefined])
  })

  it('磁盘不足时下一步禁用并提示先解决红色项', async () => {
    vi.mocked(checkEnv).mockResolvedValueOnce({ ...baseReport, disk_ok: false, disk_free_gb: 3 })
    const wrapper = mount(StepEnvCheck, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(wrapper.text()).toContain('低于所需的 15GB')
    expect(wrapper.text()).toContain('必须先解决才能继续')
    expect(wrapper.find('button').attributes('disabled')).toBeDefined()
  })

  it('端口被占时提供采纳按钮，采纳后下一步带回推荐端口', async () => {
    vi.mocked(checkEnv).mockResolvedValueOnce({ ...baseReport, port_free: false })
    const wrapper = mount(StepEnvCheck, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(wrapper.text()).toContain('已被其他程序占用')
    await wrapper.find('button.adopt-port').trigger('click')
    expect(wrapper.text()).toContain('将使用端口 8080')
    await wrapper.find('button:not(.adopt-port)').trigger('click')
    expect(wrapper.emitted('next')![0][0]).toBe(8080)
  })
})
