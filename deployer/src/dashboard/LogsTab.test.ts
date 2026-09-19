import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import LogsTab from './LogsTab.vue'

vi.mock('../api/deployer', () => ({ containerLogs: vi.fn(async () => 'line1\nline2') }))

describe('LogsTab', () => {
  it('挂载后自动加载默认服务日志', async () => {
    const wrapper = mount(LogsTab, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(wrapper.text()).toContain('line1')
  })
  it('点击刷新重新渲染日志行', async () => {
    const wrapper = mount(LogsTab, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('button.view').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('line2')
  })
  it('服务下拉展示中文角色名', () => {
    const wrapper = mount(LogsTab, { global: { plugins: [ElementPlus] } })
    // el-select 选项渲染在 teleport 中，断言选项数据而非 DOM
    const options = wrapper.findAllComponents({ name: 'ElOption' })
    expect(options.some(o => (o.props('label') as string).includes('后台服务'))).toBe(true)
  })
})
