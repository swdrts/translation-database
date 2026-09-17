import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { nextTick } from 'vue'
import ElementPlus, { ElMessageBox } from 'element-plus'
import MaintainTab from './MaintainTab.vue'
import { upgradeStack, uninstall } from '../api/deployer'
import { listen } from '@tauri-apps/api/event'
import type { ProgressEvent } from '../wizard/types'

vi.mock('../api/deployer', () => ({
  upgradeStack: vi.fn(async () => {}),
  uninstall: vi.fn(async () => {}),
}))

// 只拦截 ElMessageBox.confirm（两步确认）；其余导出（默认插件/ElMessage/ElCheckbox）保留真实现
vi.mock('element-plus', async (importOriginal) => {
  const orig = await importOriginal<typeof import('element-plus')>()
  return { ...orig, ElMessageBox: { confirm: vi.fn(async () => 'confirm') } }
})

vi.mock('@tauri-apps/api/event', () => ({
  listen: vi.fn(async () => () => {}),
}))

beforeEach(() => {
  vi.clearAllMocks()
})

describe('MaintainTab', () => {
  it('点击卸载触发两步确认，未勾选删除数据则以 removeData=false 卸载', async () => {
    const wrapper = mount(MaintainTab, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('button.uninstall').trigger('click')
    await flushPromises()
    expect(ElMessageBox.confirm).toHaveBeenCalledTimes(2)
    // 第二步 mock 下勾选框从未挂载，removeData 保持 false
    expect(uninstall).toHaveBeenCalledWith(false)
  })

  it('第二步取消时不执行卸载', async () => {
    // as never：vi.mocked 沿用真实 MessageBoxData 返回类型，测试只需任意值驱动 resolve/reject
    vi.mocked(ElMessageBox.confirm)
      .mockResolvedValueOnce('confirm' as never)
      .mockRejectedValueOnce('cancel' as never)
    const wrapper = mount(MaintainTab, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('button.uninstall').trigger('click')
    await flushPromises()
    expect(ElMessageBox.confirm).toHaveBeenCalledTimes(2)
    expect(uninstall).not.toHaveBeenCalled()
  })

  it('点击升级调用 upgradeStack', async () => {
    const wrapper = mount(MaintainTab, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    await wrapper.find('button.upgrade').trigger('click')
    await flushPromises()
    expect(upgradeStack).toHaveBeenCalled()
  })

  it('升级期间订阅 deploy://progress 显示一行进度文案', async () => {
    let progressHandler: ((e: { payload: ProgressEvent }) => void) | null = null
    vi.mocked(listen).mockImplementation(async (_name, cb) => {
      progressHandler = cb as typeof progressHandler
      return () => {}
    })
    const wrapper = mount(MaintainTab, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(listen).toHaveBeenCalledWith('deploy://progress', expect.any(Function))
    // click 不先 await：await 期间 upgradeStack mock 的微任务已结算，loading 已翻回 false，
    // guard `if (upgrading)` 会吞掉事件。trigger 同步调 handler，随后立刻注入进度事件
    void wrapper.find('button.upgrade').trigger('click')
    progressHandler!({ payload: { stage: 'pull', message: 'backend Downloading', pull: null, download: null } })
    // 只等一帧渲染：flushPromises 会连 upgradeStack 一起结算，进度行已被 finally 收起
    await nextTick()
    expect(wrapper.find('.upgrade-progress').text()).toContain('backend Downloading')
    // 完成后进度行随 loading 一并收起
    await flushPromises()
    expect(wrapper.find('.upgrade-progress').exists()).toBe(false)
  })
})
