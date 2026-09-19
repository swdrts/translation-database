import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'
import StepDone from './StepDone.vue'
import { openWeb } from '../api/deployer'

vi.mock('../api/deployer', () => ({
  openWeb: vi.fn(async () => {}),
  openDashboardWindow: vi.fn(async () => {}),
}))
// 只拦截 ElMessage.success（复制成功提示）；默认插件等其余导出保留真实现
vi.mock('element-plus', async (importOriginal) => {
  const orig = await importOriginal<typeof import('element-plus')>()
  return { ...orig, ElMessage: { ...orig.ElMessage, success: vi.fn() } }
})

const writeText = vi.fn(async () => {})

describe('StepDone', () => {
  beforeEach(() => {
    writeText.mockClear()
    vi.mocked(ElMessage.success).mockClear()
    // jsdom 无 Clipboard API：注入 navigator.clipboard 模拟
    //（真机页面运行于 http://localhost 安全上下文，navigator.clipboard 原生可用）
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
  })

  it('展示访问地址与本次会话所设密码', () => {
    const wrapper = mount(StepDone, {
      global: { plugins: [ElementPlus] },
      props: { url: 'http://localhost:8080', password: 'password8' },
    })
    expect(wrapper.text()).toContain('http://localhost:8080')
    expect(wrapper.text()).toContain('admin')
    expect(wrapper.text()).toContain('password8')
    expect(wrapper.find('.copy-password').exists()).toBe(true)
  })

  it('完成页展示醒目的密码保管警示', () => {
    const wrapper = mount(StepDone, {
      global: { plugins: [ElementPlus] },
      props: { url: 'http://localhost', password: 'password8' },
    })
    expect(wrapper.text()).toContain('重要：请务必记下管理员密码')
    expect(wrapper.text()).toContain('忘记密码无法找回')
  })

  it('会话内无密码（重启后 state.json 已剥密码）时提示部署时所设且不显示复制按钮', () => {
    const wrapper = mount(StepDone, {
      global: { plugins: [ElementPlus] },
      props: { url: 'http://localhost', password: '' },
    })
    expect(wrapper.text()).toContain('（本次会话未设置，密码为部署时所设）')
    expect(wrapper.find('.copy-password').exists()).toBe(false)
  })

  it('密钥复用（保留数据重部署，reused=true）时不展示本轮密码与复制按钮，提示沿用首次部署', () => {
    const wrapper = mount(StepDone, {
      global: { plugins: [ElementPlus] },
      props: { url: 'http://localhost', password: 'new-input-8', reused: true },
    })
    expect(wrapper.text()).toContain('管理员密码沿用首次部署所设，本次输入未生效')
    expect(wrapper.text()).not.toContain('new-input-8')
    expect(wrapper.find('.copy-password').exists()).toBe(false)
  })

  it('点击复制密码写入剪贴板并提示已复制', async () => {
    const wrapper = mount(StepDone, {
      global: { plugins: [ElementPlus] },
      props: { url: 'http://localhost', password: 'password8' },
    })
    await wrapper.find('.copy-password').trigger('click')
    await flushPromises()
    expect(writeText).toHaveBeenCalledWith('password8')
    expect(ElMessage.success).toHaveBeenCalledWith('已复制')
    expect(openWeb).not.toHaveBeenCalled()
  })
})
