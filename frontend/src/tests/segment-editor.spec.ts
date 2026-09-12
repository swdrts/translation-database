import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import SegmentEditorPanel from '../components/SegmentEditorPanel.vue'
import { api } from '../api'
import type { ImportRowsPage } from '../api'

vi.mock('../api', () => ({
  api: {
    listImportRows: vi.fn(),
    listImportChapters: vi.fn(),
    editImportRow: vi.fn(),
    mergeImportRows: vi.fn(),
    splitImportRow: vi.fn(),
    deleteImportRow: vi.fn(),
    renameImportChapter: vi.fn()
  }
}))
vi.mock('element-plus', async (importOriginal) => ({
  ...(await importOriginal<typeof import('element-plus')>()),
  ElMessageBox: { confirm: vi.fn().mockResolvedValue('confirm'), alert: vi.fn().mockResolvedValue(undefined) }
}))

const stats = { totalRows: 3, willImportRows: 2, overwriteRows: 0, skippedRows: 1 }
const page1: ImportRowsPage = {
  stats, page: 0, totalPages: 2,
  rows: [
    { rowId: 1, seq: 1, prevRowId: -1, chapter: '学而第一', text: '学而时习之，不亦说乎？', planType: 'IMPORT', edited: false },
    { rowId: 2, seq: 2, prevRowId: 1, chapter: '学而第一', text: '其为人也孝弟。', planType: 'IMPORT', edited: false },
    { rowId: 3, seq: 3, prevRowId: 2, chapter: '', text: '短句。', planType: 'SKIP', edited: true }
  ]
}

function mountEditor() {
  return mount(SegmentEditorPanel, {
    props: { previewId: 'p1', sideNoun: '段落' }
  })
}

describe('SegmentEditorPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.listImportChapters).mockResolvedValue([
      { title: '学而第一', rowCount: 2 }, { title: '', rowCount: 1 }
    ])
    vi.mocked(api.listImportRows).mockResolvedValue(page1)
  })

  it('renders rows with full text, planType labels, edited mark and chapter', async () => {
    const w = mountEditor()
    await flushPromises()
    expect(w.text()).toContain('学而时习之，不亦说乎？')
    expect(w.text()).toContain('已存在跳过')
    expect(w.text()).toContain('已修改')
    expect(w.text()).toContain('学而第一')
    expect(w.text()).toContain('当前 3 段')
  })

  it('passes chapter filter and suspicious flags to listImportRows', async () => {
    const w = mountEditor()
    await flushPromises()
    const vm = w.vm as any
    await vm.setChapter('学而第一')
    await flushPromises()
    expect(api.listImportRows).toHaveBeenCalledWith('p1',
      expect.objectContaining({ chapter: '学而第一', page: 0, size: 10 }))
    await vm.toggleSuspicious()
    await flushPromises()
    expect(api.listImportRows).toHaveBeenCalledWith('p1',
      expect.objectContaining({ suspicious: true, longAbove: 300, shortBelow: 10 }))
  })

  it('mergeChecked on adjacent rows calls api, updates stats and reloads', async () => {
    const w = mountEditor()
    await flushPromises()
    const vm = w.vm as any
    vm.checked.push(1, 2)
    const newStats = { totalRows: 2, willImportRows: 2, overwriteRows: 0, skippedRows: 0 }
    // 合并后的重载会带回服务端最新统计，mock 同步切到新页
    vi.mocked(api.listImportRows).mockResolvedValue({
      stats: newStats, page: 0, totalPages: 1,
      rows: [{ rowId: 4, seq: 1, prevRowId: -1, chapter: '学而第一', text: '合并段', planType: 'IMPORT', edited: true }]
    })
    vi.mocked(api.mergeImportRows).mockResolvedValue({
      row: { rowId: 4, seq: 1, prevRowId: -1, chapter: '学而第一', text: '合并段', planType: 'IMPORT', edited: true },
      stats: newStats
    })
    await vm.mergeChecked()
    await flushPromises()
    expect(api.mergeImportRows).toHaveBeenCalledWith('p1', [1, 2])
    expect(vm.stats.totalRows).toBe(2)
    expect(w.emitted('stats-change')?.at(-1)?.[0]).toEqual({ totalRows: 2, willImportRows: 2, overwriteRows: 0, skippedRows: 0 })
    expect(api.listImportRows).toHaveBeenCalled()
  })

  it('rejects merge when checked rows are not adjacent', async () => {
    const w = mountEditor()
    await flushPromises()
    const vm = w.vm as any
    vm.checked = [1, 3]
    await vm.mergeChecked()
    expect(api.mergeImportRows).not.toHaveBeenCalled()
  })

  it('merge-up button of second row uses prevRowId pair', async () => {
    const w = mountEditor()
    await flushPromises()
    vi.mocked(api.mergeImportRows).mockResolvedValue({} as any)
    const buttons = w.findAll('[data-test="row-merge-up-btn"]')
    expect(buttons.length).toBe(3)
    expect(buttons[0].attributes('disabled')).toBeDefined()   // 第 1 行没有上段
    await buttons[1].trigger('click')
    await flushPromises()
    expect(api.mergeImportRows).toHaveBeenCalledWith('p1', [1, 2])
  })

  it('split dialog uses caret offset', async () => {
    const w = mountEditor()
    await flushPromises()
    const vm = w.vm as any
    Object.assign(vm.splitDialog, { visible: true, rowId: 1, text: '学而时习之不亦说乎', atChar: 5 })
    vi.mocked(api.splitImportRow).mockResolvedValue({ rows: [], stats })
    await vm.doSplit()
    await flushPromises()
    expect(api.splitImportRow).toHaveBeenCalledWith('p1', 1, 5)
  })

  it('deleteConfirmed calls api and refreshes', async () => {
    const w = mountEditor()
    await flushPromises()
    vi.mocked(api.deleteImportRow).mockResolvedValue({ totalRows: 2, willImportRows: 2, overwriteRows: 0, skippedRows: 0 })
    await (w.vm as any).deleteConfirmed(3)
    await flushPromises()
    expect(api.deleteImportRow).toHaveBeenCalledWith('p1', 3)
    expect(api.listImportRows).toHaveBeenCalled()
  })

  it('pagination jumps to first, specific and last page', async () => {
    // 25 段 → 3 页（每页 10）
    vi.mocked(api.listImportRows).mockImplementation((_id: string, params: any) => {
      const p: number = params?.page ?? 0
      return Promise.resolve({
        stats: { totalRows: 25, willImportRows: 25, overwriteRows: 0, skippedRows: 0 },
        page: p, totalPages: 3,
        rows: Array.from({ length: p === 2 ? 5 : 10 }, (_, i) => ({
          rowId: p * 10 + i + 1, seq: p * 10 + i + 1, prevRowId: p * 10 + i,
          chapter: '', text: `段${p * 10 + i + 1}`, planType: 'IMPORT' as const, edited: false
        }))
      })
    })
    const w = mountEditor()
    await flushPromises()
    const vm = w.vm as any

    await vm.gotoPage(3)   // 末页
    await flushPromises()
    expect(api.listImportRows).toHaveBeenLastCalledWith('p1', expect.objectContaining({ page: 2, size: 10 }))
    expect(vm.page).toBe(2)

    await vm.gotoPage(1)   // 首页
    await flushPromises()
    expect(vm.page).toBe(0)

    await vm.gotoPage(2)   // 指定页
    await flushPromises()
    expect(vm.page).toBe(1)
    expect(api.listImportRows).toHaveBeenLastCalledWith('p1', expect.objectContaining({ page: 1 }))
  })

  it('expired preview emits expired', async () => {
    vi.mocked(api.listImportRows).mockRejectedValue(Object.assign(
      new Error('导入预览不存在或已过期'),
      { response: { status: 404, data: { code: 3003 } } }
    ))
    const w = mountEditor()
    await flushPromises()
    expect(w.emitted('expired')).toBeTruthy()
  })
})
