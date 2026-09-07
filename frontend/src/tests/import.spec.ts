import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ImportView from '../views/ImportView.vue'
import { api } from '../api'

vi.mock('../api', () => ({
  api: {
    uploadImport: vi.fn(),
    confirmImport: vi.fn().mockResolvedValue({
      imported: 2, overwritten: 0, skipped: 1,
      failed: [{ line: 3, reason: 'source_text 不能为空' }]
    })
  }
}))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }), useRoute: () => ({}) }))

describe('ImportView wizard', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.setItem('transdb_token', 't')
    localStorage.setItem('transdb_user', JSON.stringify({ id: 1, username: 'e', displayName: 'e', role: 'EDITOR' }))
    vi.mocked(api.uploadImport).mockReset()
  })

  it('preview shows error rows before confirm', async () => {
    vi.mocked(api.uploadImport).mockResolvedValue({
      previewId: 'p1', strategy: 'SKIP', totalRows: 3, willImportRows: 2,
      overwriteRows: 0, skippedRows: 0,
      errors: [{ line: 3, reason: 'source_text 不能为空' }], duplicates: []
    } as any)
    const wrapper = mount(ImportView, { global: { plugins: [createPinia()] } })
    await (wrapper.vm as any).runPreview(new File(['[]'], 'a.json'))
    await flushPromises()
    expect(wrapper.text()).toContain('source_text 不能为空')
    expect(wrapper.text()).toContain('2')
  })

  it('result step lists failed rows after confirm', async () => {
    const wrapper = mount(ImportView, { global: { plugins: [createPinia()] } })
    ;(wrapper.vm as any).preview = { previewId: 'p1', strategy: 'SKIP', totalRows: 3, willImportRows: 2, overwriteRows: 0, skippedRows: 0, errors: [], duplicates: [] }
    ;(wrapper.vm as any).step = 2
    await (wrapper.vm as any).confirm()
    await flushPromises()
    expect(wrapper.text()).toContain('导入成功')
    expect(wrapper.text()).toContain('source_text 不能为空')
  })
})
