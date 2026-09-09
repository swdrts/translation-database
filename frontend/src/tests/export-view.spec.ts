import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import ExportView from '../views/ExportView.vue'
import { api } from '../api'

vi.mock('../api', () => ({
  api: {
    exportWorks: vi.fn().mockResolvedValue([
      { workTitle: '论语', chapters: 2, totalSegments: 10, translatedSegments: 6 },
      { workTitle: '孟子', chapters: 1, totalSegments: 4, translatedSegments: 4 }
    ]),
    exportPreview: vi.fn().mockResolvedValue({
      segments: 10,
      units: 8,
      pairedUnits: 6,
      chapters: [
        {
          title: '学而第一',
          full: 4,
          src: 4,
          dst: 2,
          paired: 2,
          warnings: ['原文 4 段、译文 2 段，配对 2 段，未配上 4 段']
        }
      ]
    }),
    exportBook: vi.fn().mockResolvedValue(new Blob(['mock-file']))
  }
}))

const mockApi = vi.mocked(api)

function mountView() {
  // attachTo 挂到真实 body：el-dialog 无论是否传送门渲染，document 断言都成立
  return mount(ExportView, { attachTo: document.body, global: { plugins: [ElementPlus] } })
}

describe('ExportView 成书导出页', () => {
  let wrapper: ReturnType<typeof mountView> | null = null

  beforeEach(() => {
    setActivePinia(createPinia())
    // jsdom 无 createObjectURL，打桩
    Object.assign(URL, {
      createObjectURL: vi.fn(() => 'blob:mock'),
      revokeObjectURL: vi.fn()
    })
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.clearAllMocks()
    document.body.innerHTML = ''
  })

  it('挂载后展示书单', async () => {
    wrapper = mountView()
    await flushPromises()
    expect(mockApi.exportWorks).toHaveBeenCalled()
    expect(wrapper.text()).toContain('论语')
    expect(wrapper.text()).toContain('孟子')
  })

  it('点选书籍后加载预览并展示配对统计与警告', async () => {
    wrapper = mountView()
    await flushPromises()
    await wrapper.find('[data-test="work-card"]').trigger('click')
    await flushPromises()
    expect(mockApi.exportPreview).toHaveBeenCalledWith('论语')
    expect(document.body.textContent).toContain('学而第一')
    expect(document.body.textContent).toContain('未配上')
  })

  it('点击导出触发下载', async () => {
    wrapper = mountView()
    await flushPromises()
    await wrapper.find('[data-test="work-card"]').trigger('click')
    await flushPromises()
    const btn = document.querySelector<HTMLButtonElement>('[data-test="download-btn"]')
    expect(btn).not.toBeNull()
    btn!.click()
    await flushPromises()
    expect(mockApi.exportBook).toHaveBeenCalledWith('论语', 'BILINGUAL', 'DOCX')
    expect(URL.createObjectURL).toHaveBeenCalled()
  })
})
