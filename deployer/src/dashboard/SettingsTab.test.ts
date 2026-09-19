// vitest 2.x 无 flushPromises 导出（brief 注笔误），从 @vue/test-utils 导入同名 API
import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import SettingsTab from './SettingsTab.vue'
import { getToolAutostart, setToolAutostart, changePort, openDataDir, getRegistryMirrors, setRegistryMirrors, probeRegistryMirror } from '../api/deployer'

const DEFAULTS = ['https://docker.1ms.run', 'https://docker.xuanyuan.me', 'https://docker.m.daocloud.io', 'https://hub.rat.dev']

vi.mock('../api/deployer', () => ({
  getToolAutostart: vi.fn(async () => true),
  setToolAutostart: vi.fn(async () => {}),
  changePort: vi.fn(async () => {}),
  openDataDir: vi.fn(async () => {}),
  getRegistryMirrors: vi.fn(async () => ({ path: 'C:/Users/x/.docker/daemon.json', mirrors: [], defaults: DEFAULTS })),
  setRegistryMirrors: vi.fn(async (m: string[]) => m),
  probeRegistryMirror: vi.fn(async (url: string) => ({ reachable: url.includes('1ms.run'), status: url.includes('1ms.run') ? 401 : 0 })),
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

  it('挂载时读取镜像配置并回显已保存镜像', async () => {
    vi.mocked(getRegistryMirrors).mockResolvedValueOnce({ path: '/home/x/.docker/daemon.json', mirrors: ['https://docker.m.daocloud.io'], defaults: DEFAULTS })
    const wrapper = mount(SettingsTab, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(getRegistryMirrors).toHaveBeenCalled()
    expect((wrapper.find('.mirrors-input textarea').element as HTMLTextAreaElement).value).toBe('https://docker.m.daocloud.io')
  })

  it('点击「填入推荐的国内镜像」预填实测可用的默认地址', async () => {
    const wrapper = mount(SettingsTab, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('button.fill-default-mirrors').trigger('click')
    const text = (wrapper.find('.mirrors-input textarea').element as HTMLTextAreaElement).value
    for (const d of DEFAULTS) {
      expect(text).toContain(d)
    }
  })

  it('保存镜像配置调用 setRegistryMirrors 并回显生效值', async () => {
    const wrapper = mount(SettingsTab, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('button.fill-default-mirrors').trigger('click')
    await wrapper.find('button.save-mirrors').trigger('click')
    await flushPromises()
    expect(setRegistryMirrors).toHaveBeenCalledWith(DEFAULTS)
  })

  it('检测可用性逐行标记可达/不可达', async () => {
    const wrapper = mount(SettingsTab, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('.mirrors-input textarea').setValue('https://docker.1ms.run\nhttps://broken.example')
    await wrapper.find('button.probe-mirrors').trigger('click')
    await flushPromises()
    expect(probeRegistryMirror).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.mirror-probe-list').text()).toContain('✓ https://docker.1ms.run')
    expect(wrapper.find('.mirror-probe-list').text()).toContain('✗ https://broken.example')
  })
})