import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import App from './App.vue'

describe('App', () => {
  it('渲染部署器标题', () => {
    // WizardShell 的 onMounted 调用 Tauri API（jsdom 不可用），此处 stub 掉
    const wrapper = mount(App, { global: { stubs: { WizardShell: true } } })
    expect(wrapper.text()).toContain('翻译数据库部署器')
  })
})
