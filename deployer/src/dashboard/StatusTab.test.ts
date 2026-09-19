import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import StatusTab from './StatusTab.vue'

vi.mock('../api/deployer', () => ({ stackOp: vi.fn(async () => {}), openWeb: vi.fn(async () => {}) }))

const containers = [
  { service: 'frontend', state: 'running', health: null },
  { service: 'backend', state: 'running', health: 'healthy' },
  { service: 'postgres', state: 'running', health: 'healthy' },
  { service: 'elasticsearch', state: 'running', health: 'starting' },
]

describe('StatusTab', () => {
  it('渲染中文角色名与中文状态徽标', () => {
    const wrapper = mount(StatusTab, {
      global: { plugins: [ElementPlus] },
      props: { engineReady: true, containers },
    })
    expect(wrapper.text()).toContain('网页服务')
    expect(wrapper.text()).toContain('搜索引擎')
    expect(wrapper.text()).toContain('运行中·健康')
    expect(wrapper.text()).toContain('启动中')
  })
  it('全部健康时绿色横幅并提供打开翻译数据库按钮', () => {
    const all = containers.map(c => ({ ...c, health: 'healthy' as const }))
    const wrapper = mount(StatusTab, {
      global: { plugins: [ElementPlus] },
      props: { engineReady: true, containers: all },
    })
    expect(wrapper.text()).toContain('服务运行中，网页可正常访问')
    expect(wrapper.find('button.open-web').exists()).toBe(true)
  })
  it('引擎离线时显示红色横幅', () => {
    const wrapper = mount(StatusTab, {
      global: { plugins: [ElementPlus] },
      props: { engineReady: false, containers: [] },
    })
    expect(wrapper.text()).toContain('Docker 未运行')
    expect(wrapper.text()).toContain('暂无运行中的容器')
  })
})
