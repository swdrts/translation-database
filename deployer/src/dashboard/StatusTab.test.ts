import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import StatusTab from './StatusTab.vue'

describe('StatusTab', () => {
  it('渲染 4 容器卡片与健康徽标', () => {
    const wrapper = mount(StatusTab, {
      global: { plugins: [ElementPlus] },
      props: {
        engineReady: true,
        containers: [
          { service: 'frontend', state: 'running', health: null },
          { service: 'backend', state: 'running', health: 'healthy' },
          { service: 'postgres', state: 'running', health: 'healthy' },
          { service: 'elasticsearch', state: 'running', health: 'starting' },
        ],
      },
    })
    expect(wrapper.text()).toContain('frontend')
    expect(wrapper.text()).toContain('elasticsearch')
    expect(wrapper.text()).toContain('healthy')
    expect(wrapper.text()).toContain('starting')
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
