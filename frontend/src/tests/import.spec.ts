import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ImportView from '../views/ImportView.vue'
import DocumentImportPanel from '../components/DocumentImportPanel.vue'
import TableImportPanel from '../components/TableImportPanel.vue'
import { api } from '../api'

vi.mock('../api', () => ({
  api: {
    uploadImport: vi.fn(),
    uploadDocumentImport: vi.fn(),
    confirmImport: vi.fn().mockResolvedValue({
      imported: 2, overwritten: 0, skipped: 1,
      failed: [{ line: 3, reason: 'source_text 不能为空' }]
    })
  }
}))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }), useRoute: () => ({}) }))

describe('ImportView 录入方式选择', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.setItem('transdb_token', 't')
    localStorage.setItem('transdb_user', JSON.stringify({ id: 1, username: 'e', displayName: 'e', role: 'EDITOR' }))
    vi.mocked(api.uploadImport).mockReset()
    vi.mocked(api.uploadDocumentImport).mockReset()
  })

  it('shows three entry cards and switches to document panel', async () => {
    const wrapper = mount(ImportView, { global: { plugins: [createPinia()] } })
    expect(wrapper.text()).toContain('导入整本书 / 文档')
    expect(wrapper.text()).toContain('导入对照表格')
    expect(wrapper.text()).toContain('手动录入一条')
    expect(wrapper.text()).toContain('EPUB、PDF、Word')

    await wrapper.find('[data-test="mode-document"]').trigger('click')
    expect(wrapper.findComponent(DocumentImportPanel).exists()).toBe(true)
  })

  it('switches to table panel', async () => {
    const wrapper = mount(ImportView, { global: { plugins: [createPinia()] } })
    await wrapper.find('[data-test="mode-table"]').trigger('click')
    expect(wrapper.findComponent(TableImportPanel).exists()).toBe(true)
  })
})

describe('TableImportPanel 对照表格导入', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(api.uploadImport).mockReset()
  })

  it('preview shows error rows before confirm', async () => {
    vi.mocked(api.uploadImport).mockResolvedValue({
      previewId: 'p1', strategy: 'SKIP', totalRows: 3, willImportRows: 2,
      overwriteRows: 0, skippedRows: 0,
      errors: [{ line: 3, reason: 'source_text 不能为空' }], duplicates: []
    } as any)
    const wrapper = mount(TableImportPanel, { global: { plugins: [createPinia()] } })
    await (wrapper.vm as any).runPreview(new File(['[]'], 'a.json'))
    await flushPromises()
    expect(wrapper.text()).toContain('source_text 不能为空')
    expect(wrapper.text()).toContain('2')
  })

  it('result step lists failed rows after confirm', async () => {
    const wrapper = mount(TableImportPanel, { global: { plugins: [createPinia()] } })
    ;(wrapper.vm as any).preview = { previewId: 'p1', strategy: 'SKIP', totalRows: 3, willImportRows: 2, overwriteRows: 0, skippedRows: 0, errors: [], duplicates: [] }
    ;(wrapper.vm as any).step = 2
    await (wrapper.vm as any).confirm()
    await flushPromises()
    expect(wrapper.text()).toContain('导入成功')
    expect(wrapper.text()).toContain('source_text 不能为空')
  })
})

describe('DocumentImportPanel 整本书导入', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(api.uploadDocumentImport).mockReset()
    vi.mocked(api.confirmImport).mockClear()
  })

  it('preview shows samples and prefills detected title, confirm sends overrides', async () => {
    vi.mocked(api.uploadDocumentImport).mockResolvedValue({
      previewId: 'd1', strategy: 'SKIP', sourceType: 'DOCUMENT',
      totalRows: 2, willImportRows: 2, overwriteRows: 0, skippedRows: 0,
      errors: [], duplicates: [],
      documentTitle: '论语', documentAuthor: '孔门弟子', chapterCount: 2,
      sampleRows: [
        { line: 1, chapter: '学而第一', text: '子曰：学而时习之，不亦说乎？' },
        { line: 2, chapter: '为政第二', text: '为政以德，譬如北辰。' }
      ]
    } as any)
    const wrapper = mount(DocumentImportPanel, { global: { plugins: [createPinia()] } })
    await (wrapper.vm as any).runPreview(new File(['x'], 'lunyu.txt'))
    await flushPromises()

    expect(wrapper.text()).toContain('识别出段落')
    expect(wrapper.text()).toContain('学而第一')
    expect(wrapper.text()).toContain('子曰：学而时习之')
    // 识别到的书名/作者已填入表单
    expect((wrapper.vm as any).meta.workTitle).toBe('论语')
    expect((wrapper.vm as any).meta.author).toBe('孔门弟子')

    // 修改书名并确认 → 覆盖参数随 confirm 提交，默认草稿
    ;(wrapper.vm as any).meta.workTitle = '论语（中华书局）'
    ;(wrapper.vm as any).meta.dynasty = '先秦'
    await (wrapper.vm as any).confirm()
    await flushPromises()

    expect(api.confirmImport).toHaveBeenCalledWith('d1', {
      workTitle: '论语（中华书局）',
      author: '孔门弟子',
      dynasty: '先秦',
      translator: '',
      tags: undefined,
      status: 'DRAFT'
    })
    expect(wrapper.text()).toContain('导入成功')
    expect(wrapper.text()).toContain('译文都空着')
  })

  it('normalizes comma-separated tags to bar-separated', async () => {
    vi.mocked(api.uploadDocumentImport).mockResolvedValue({
      previewId: 'd2', strategy: 'SKIP', sourceType: 'DOCUMENT',
      totalRows: 1, willImportRows: 1, overwriteRows: 0, skippedRows: 0,
      errors: [], duplicates: [],
      documentTitle: '孟子', sampleRows: [{ line: 1, chapter: '', text: '孟子见梁惠王。' }]
    } as any)
    const wrapper = mount(DocumentImportPanel, { global: { plugins: [createPinia()] } })
    await (wrapper.vm as any).runPreview(new File(['x'], 'mengzi.epub'))
    await flushPromises()

    const panel = wrapper.vm as any
    panel.metaTags = '儒家，语录、经典'
    await panel.confirm()
    await flushPromises()

    expect(api.confirmImport).toHaveBeenCalledWith('d2', expect.objectContaining({ tags: '儒家|语录|经典' }))
  })
})
