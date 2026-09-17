import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import App from './App.vue'

// App.vue setup 经 getCurrentWindow() 读 window.__TAURI_INTERNALS__.metadata.currentWindow.label
// （skip:true 纯本地读取，无 IPC）；jsdom 下无此注入，先伪造再按用例切换 label
beforeEach(() => {
  const w = window as unknown as { __TAURI_INTERNALS__?: { metadata: { currentWindow: { label: string } } } }
  w.__TAURI_INTERNALS__ = { metadata: { currentWindow: { label: 'main' } } }
})

describe('App', () => {
  it('渲染部署器标题', () => {
    // WizardShell 的 onMounted 调用 Tauri API（jsdom 不可用），此处 stub 掉
    const wrapper = mount(App, { global: { stubs: { WizardShell: true, DashboardShell: true } } })
    expect(wrapper.text()).toContain('翻译数据库部署器')
    expect(wrapper.findComponent({ name: 'WizardShell' }).exists()).toBe(true)
  })
  it('dashboard 窗口按 label 路由到管理壳', () => {
    const w = window as unknown as { __TAURI_INTERNALS__: { metadata: { currentWindow: { label: string } } } }
    w.__TAURI_INTERNALS__.metadata.currentWindow.label = 'dashboard'
    const wrapper = mount(App, { global: { stubs: { WizardShell: true, DashboardShell: true } } })
    expect(wrapper.findComponent({ name: 'DashboardShell' }).exists()).toBe(true)
    expect(wrapper.findComponent({ name: 'WizardShell' }).exists()).toBe(false)
  })
})
