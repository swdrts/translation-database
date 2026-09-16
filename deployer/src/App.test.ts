import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import App from './App.vue'

describe('App', () => {
  it('渲染部署器标题', () => {
    const wrapper = mount(App, { global: { plugins: [] } })
    expect(wrapper.text()).toContain('翻译数据库部署器')
  })
})
