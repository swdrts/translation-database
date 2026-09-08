import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import SegmentEditView from '../views/SegmentEditView.vue'
import { api } from '../api'

vi.mock('../api', () => ({
  api: {
    listTags: vi.fn().mockResolvedValue([]),
    getSegment: vi.fn(),
    createSegment: vi.fn().mockResolvedValue({
      id: 42, sourceText: '学而时习之', translatedText: '', status: 'DRAFT',
      version: 0, tags: [], createdAt: '', updatedAt: ''
    }),
    updateSegment: vi.fn()
  }
}))
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn(), back: vi.fn() }),
  useRoute: () => ({ params: {} })
}))

describe('SegmentEditView 手动录入', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(api.createSegment).mockClear()
  })

  it('allows saving with source only (translation left blank as draft)', async () => {
    const wrapper = mount(SegmentEditView, { global: { plugins: [createPinia()] } })
    await flushPromises()

    await wrapper.find('textarea[data-test="source-input"]').setValue('学而时习之，不亦说乎？')
    await wrapper.find('[data-test="save-btn"]').trigger('click')
    await flushPromises()

    expect(api.createSegment).toHaveBeenCalledWith(expect.objectContaining({
      sourceText: '学而时习之，不亦说乎？',
      translatedText: '',
      status: 'DRAFT'
    }))
  })

  it('blocks save when source is blank', async () => {
    const wrapper = mount(SegmentEditView, { global: { plugins: [createPinia()] } })
    await flushPromises()

    await wrapper.find('[data-test="save-btn"]').trigger('click')
    await flushPromises()

    expect(api.createSegment).not.toHaveBeenCalled()
  })
})
