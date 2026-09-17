import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import LogsTab from './LogsTab.vue'

vi.mock('../api/deployer', () => ({ containerLogs: vi.fn(async () => 'line1\nline2') }))

describe('LogsTab', () => {
  it('选择服务并查看后渲染日志行', async () => {
    const wrapper = mount(LogsTab, { global: { plugins: [ElementPlus] } })
    await wrapper.find('button.view').trigger('click')
    expect(wrapper.text()).toContain('line1')
  })
})
