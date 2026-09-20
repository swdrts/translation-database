import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import StepDocker from './StepDocker.vue'
import { getRegistryMirrors, setRegistryMirrors } from '../api/deployer'

const DEFAULTS = ['https://docker.1ms.run', 'https://docker.xuanyuan.me', 'https://docker.m.daocloud.io', 'https://hub.rat.dev']

vi.mock('../api/deployer', () => ({
  dockerProbe: vi.fn(async () => ({ installed: true, engine_ready: true })),
  ensureDocker: vi.fn(async () => ({ installed: true, engine_ready: true })),
  installWsl2: vi.fn(async () => {}),
  getRegistryMirrors: vi.fn(async () => ({ path: 'C:/Users/x/.docker/daemon.json', mirrors: [], defaults: DEFAULTS })),
  setRegistryMirrors: vi.fn(async (m: string[]) => m),
}))

describe('StepDocker 镜像配置步', () => {
  it('引擎就绪后进入镜像配置步并预填国内默认地址，不自动跳下一步', async () => {
    const wrapper = mount(StepDocker, { global: { plugins: [ElementPlus] }, props: { progress: null } })
    await flushPromises()
    expect(wrapper.find('.mirror-input textarea').exists()).toBe(true)
    expect((wrapper.find('.mirror-input textarea').element as HTMLTextAreaElement).value).toBe(DEFAULTS.join('\n'))
    expect(wrapper.emitted('next')).toBeUndefined()
  })

  it('已有镜像配置时回显既有值而非默认值', async () => {
    vi.mocked(getRegistryMirrors).mockResolvedValueOnce({ path: 'p', mirrors: ['https://my.mirror.cn'], defaults: DEFAULTS })
    const wrapper = mount(StepDocker, { global: { plugins: [ElementPlus] }, props: { progress: null } })
    await flushPromises()
    expect((wrapper.find('.mirror-input textarea').element as HTMLTextAreaElement).value).toBe('https://my.mirror.cn')
  })

  it('编辑后点击应用调用 setRegistryMirrors 并进入下一步', async () => {
    const wrapper = mount(StepDocker, { global: { plugins: [ElementPlus] }, props: { progress: null } })
    await flushPromises()
    await wrapper.find('.mirror-input textarea').setValue('https://docker.1ms.run')
    await wrapper.find('button.apply-mirrors').trigger('click')
    await flushPromises()
    expect(setRegistryMirrors).toHaveBeenCalledWith(['https://docker.1ms.run'])
    expect(wrapper.emitted('next')).toBeTruthy()
  })

  it('点击跳过不写入配置直接进入下一步', async () => {
    const wrapper = mount(StepDocker, { global: { plugins: [ElementPlus] }, props: { progress: null } })
    await flushPromises()
    const callsBefore = vi.mocked(setRegistryMirrors).mock.calls.length
    await wrapper.find('button.skip-mirrors').trigger('click')
    await flushPromises()
    expect(vi.mocked(setRegistryMirrors).mock.calls.length).toBe(callsBefore)
    expect(wrapper.emitted('next')).toBeTruthy()
  })
})