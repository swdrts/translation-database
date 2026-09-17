// vitest 2.x 无 flushPromises 导出（brief 注笔误），从 @vue/test-utils 导入同名 API
import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import SettingsTab from './SettingsTab.vue'
import { getToolAutostart, setToolAutostart, changePort, openDataDir } from '../api/deployer'

vi.mock('../api/deployer', () => ({
  getToolAutostart: vi.fn(async () => true),
  setToolAutostart: vi.fn(async () => {}),
  changePort: vi.fn(async () => {}),
  openDataDir: vi.fn(async () => {}),
}))

describe('SettingsTab', () => {
  it('回显工具自启开启状态', async () => {
    const wrapper = mount(SettingsTab, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(wrapper.find('.tool-autostart input').exists()).toBe(true)
  })

  it('开关从后端回显 true 并在切换时调用 setToolAutostart', async () => {
    const wrapper = mount(SettingsTab, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(getToolAutostart).toHaveBeenCalled()
    expect(wrapper.find('.tool-autostart').classes()).toContain('is-checked')
    await wrapper.find('.tool-autostart').trigger('click')
    await flushPromises()
    expect(setToolAutostart).toHaveBeenCalledWith(false)
  })

  it('输入端口并点击应用调用 changePort', async () => {
    const wrapper = mount(SettingsTab, { global: { plugins: [ElementPlus] }, props: { port: 80 } })
    await flushPromises()
    await wrapper.find('.port-input input').setValue('8080')
    await wrapper.find('button.apply-port').trigger('click')
    await flushPromises()
    expect(changePort).toHaveBeenCalledWith(8080)
  })

  it('点击打开数据目录调用 openDataDir', async () => {
    const wrapper = mount(SettingsTab, { global: { plugins: [ElementPlus] }, props: { port: 80 } })
    await flushPromises()
    await wrapper.find('button.open-data').trigger('click')
    await flushPromises()
    expect(openDataDir).toHaveBeenCalled()
  })
})
