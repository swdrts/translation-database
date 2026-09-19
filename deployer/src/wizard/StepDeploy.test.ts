import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import StepDeploy from './StepDeploy.vue'

vi.mock('../api/deployer', () => ({ startDeploy: vi.fn(async () => ({ url: 'http://localhost' })) }))

describe('StepDeploy', () => {
  it('渲染三段白话子任务并在成功后 emit done 与访问地址', async () => {
    const wrapper = mount(StepDeploy, { global: { plugins: [ElementPlus] }, props: { progress: null } })
    expect(wrapper.text()).toContain('下载组件')
    expect(wrapper.text()).toContain('启动服务')
    expect(wrapper.text()).toContain('健康检查')
    await flushPromises()
    expect(wrapper.emitted('done')![0]).toEqual(['http://localhost'])
  })

  it('pull 阶段高亮第一项子任务', () => {
    const wrapper = mount(StepDeploy, {
      global: { plugins: [ElementPlus] },
      props: { progress: { stage: 'pull', message: 'backend Downloading', pull: null, download: null } },
    })
    expect(wrapper.text()).toContain('正在安装，请稍候…')
  })
})
