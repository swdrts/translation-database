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
    }),
    listImportRows: vi.fn(),
    listImportChapters: vi.fn(),
    editImportRow: vi.fn(),
    mergeImportRows: vi.fn(),
    splitImportRow: vi.fn(),
    deleteImportRow: vi.fn(),
    renameImportChapter: vi.fn()
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
  const editorPage = {
    stats: { totalRows: 2, willImportRows: 2, overwriteRows: 0, skippedRows: 0 },
    page: 0, totalPages: 1,
    rows: [
      { rowId: 1, seq: 1, prevRowId: -1, chapter: '学而第一', text: '子曰：学而时习之，不亦说乎？', planType: 'IMPORT', edited: false },
      { rowId: 2, seq: 2, prevRowId: 1, chapter: '为政第二', text: '为政以德，譬如北辰。', planType: 'IMPORT', edited: false }
    ]
  }

  beforeEach(() => {
    setActivePinia(createPinia())
    vi.mocked(api.uploadDocumentImport).mockReset()
    vi.mocked(api.confirmImport).mockClear()
    vi.mocked(api.listImportRows).mockReset().mockResolvedValue(editorPage as any)
    vi.mocked(api.listImportChapters).mockReset().mockResolvedValue([])
    vi.clearAllMocks()
    // clearAllMocks 会清掉上面的默认返回值，重新设一遍
    vi.mocked(api.listImportRows).mockResolvedValue(editorPage as any)
    vi.mocked(api.listImportChapters).mockResolvedValue([])
    vi.mocked(api.confirmImport).mockResolvedValue({
      imported: 2, overwritten: 0, skipped: 1, failed: []
    })
  })

  it('4-step wizard: entry card leads to editor, confirm from editor step', async () => {
    vi.mocked(api.uploadDocumentImport).mockResolvedValue({
      previewId: 'd1', strategy: 'SKIP', sourceType: 'DOCUMENT', textRole: 'SOURCE',
      totalRows: 2, willImportRows: 2, overwriteRows: 0, skippedRows: 0,
      errors: [], duplicates: [],
      documentTitle: '论语', documentAuthor: '孔门弟子', chapterCount: 2
    } as any)
    const wrapper = mount(DocumentImportPanel, { global: { plugins: [createPinia()] } })
    const panel = wrapper.vm as any
    await panel.runPreview(new File(['x'], 'lunyu.txt'))
    await flushPromises()

    // ② 步：无抽样表，有入口卡与统计；识别书名已填入表单
    expect(panel.step).toBe(1)
    expect(wrapper.text()).not.toContain('只显示前几条')
    expect(wrapper.text()).toContain('共 2 段')
    expect(wrapper.text()).toContain('点这里预览并调整分段')
    expect(panel.meta.workTitle).toBe('论语')
    expect(panel.meta.author).toBe('孔门弟子')

    // 点入口卡进入 ③ 步：编辑器加载全量分段
    await wrapper.find('[data-test="doc-to-editor-btn"]').trigger('click')
    await flushPromises()
    expect(panel.step).toBe(2)
    expect(api.listImportRows).toHaveBeenCalledWith('d1', expect.objectContaining({ page: 0, size: 100 }))
    expect(wrapper.text()).toContain('子曰：学而时习之')
    // stats-change 已把统计带回，确认按钮可用
    expect(panel.editorStats.totalRows).toBe(2)

    // 改书名后从 ③ 步确认 → 覆盖参数随 confirm 提交，进入 ④ 步
    panel.meta.workTitle = '论语（中华书局）'
    panel.meta.dynasty = '先秦'
    await panel.confirm()
    await flushPromises()
    expect(api.confirmImport).toHaveBeenCalledWith('d1', {
      workTitle: '论语（中华书局）',
      author: '孔门弟子',
      dynasty: '先秦',
      translator: '',
      tags: undefined,
      status: 'DRAFT'
    })
    expect(panel.step).toBe(3)
    expect(wrapper.text()).toContain('导入成功')
    expect(wrapper.text()).toContain('译文都空着')
  })

  it('translation side upload passes textRole and shows translation wording', async () => {
    vi.mocked(api.uploadDocumentImport).mockResolvedValue({
      previewId: 'd3', strategy: 'SKIP', sourceType: 'DOCUMENT', textRole: 'TRANSLATION',
      totalRows: 1, willImportRows: 1, overwriteRows: 0, skippedRows: 0,
      errors: [], duplicates: [],
      documentTitle: 'The Analects'
    } as any)
    const wrapper = mount(DocumentImportPanel, { global: { plugins: [createPinia()] } })
    const panel = wrapper.vm as any
    panel.textRole = 'TRANSLATION'
    await panel.runPreview(new File(['x'], 'analects-en.txt'))
    await flushPromises()

    expect(api.uploadDocumentImport).toHaveBeenCalledWith(expect.any(File), 'SKIP', 'TRANSLATION')
    expect(wrapper.text()).toContain('识别出译文段落')
    // ③ 步编辑器提示译文侧
    await panel.goEditor()
    await flushPromises()
    expect(wrapper.text()).toContain('显示的是译文文字')
  })

  it('normalizes comma-separated tags to bar-separated', async () => {
    vi.mocked(api.uploadDocumentImport).mockResolvedValue({
      previewId: 'd2', strategy: 'SKIP', sourceType: 'DOCUMENT',
      totalRows: 1, willImportRows: 1, overwriteRows: 0, skippedRows: 0,
      errors: [], duplicates: [],
      documentTitle: '孟子'
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
