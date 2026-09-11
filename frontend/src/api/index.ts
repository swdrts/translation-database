import http from './http'

export interface LoginResult {
  token: string
  user: { id: number; username: string; displayName: string; role: string }
}

export interface SegmentDto {
  sourceText: string
  translatedText: string
  workTitle?: string
  chapter?: string
  author?: string
  dynasty?: string
  translator?: string
  notes?: string
  status?: 'DRAFT' | 'PUBLISHED'
  tagIds?: number[]
  version?: number
}

export interface SegmentVO {
  id: number
  sourceText: string
  translatedText: string
  workTitle?: string
  chapter?: string
  author?: string
  dynasty?: string
  translator?: string
  notes?: string
  status: 'DRAFT' | 'PUBLISHED'
  version: number
  tags: string[]
  createdAt: string
  updatedAt: string
}

export interface PageResponse<T> {
  content: T[]
  total: number
  page: number
  size: number
}

export interface SearchItem {
  id: number
  sourceText: string
  translatedText: string
  workTitle?: string
  chapter?: string
  author?: string
  dynasty?: string
  translator?: string
  tags: string[]
  highlight: Record<string, string[]>
  score: number
}

export interface FacetItem { name: string; count: number }
export interface Facets { tags: FacetItem[]; dynasties: FacetItem[]; works: FacetItem[] }
export interface SearchResult {
  content: SearchItem[]
  total: number
  page: number
  size: number
  degraded: boolean
  facets: Facets
}
export interface SuggestResult { works: string[]; authors: string[]; tags: string[] }
export interface TagVO { id: number; name: string; description?: string }
export interface UserVO { id: number; username: string; displayName?: string; role: string }
export interface LineError { line: number; reason: string }
export interface ImportPreview {
  previewId: string
  strategy: string
  totalRows: number
  willImportRows: number
  overwriteRows: number
  skippedRows: number
  errors: LineError[]
  duplicates: LineError[]
  sourceType?: 'TABLE' | 'DOCUMENT'
  documentTitle?: string
  documentAuthor?: string
  chapterCount?: number
  textRole?: 'SOURCE' | 'TRANSLATION'
}
/** 导入预览会话的当前统计。 */
export interface ImportEditStats {
  totalRows: number
  willImportRows: number
  overwriteRows: number
  skippedRows: number
}
/** 分段编辑器的一行。prevRowId 无上一段时为 -1；chapter 空串=未分章。 */
export interface ImportSegmentRow {
  rowId: number
  seq: number
  prevRowId: number
  chapter: string
  text: string
  planType: 'IMPORT' | 'OVERWRITE' | 'SKIP'
  edited: boolean
}
export interface ImportRowsPage {
  stats: ImportEditStats
  page: number
  totalPages: number
  rows: ImportSegmentRow[]
}
export interface ImportChapterStat { title: string; rowCount: number }
export interface ImportRowOpResult { row: ImportSegmentRow; stats: ImportEditStats }
export interface ImportSplitResult { rows: ImportSegmentRow[]; stats: ImportEditStats }
/** 确认导入时的元数据覆盖（整本书导入专用）：非空字段应用到所有段落。 */
export interface ImportConfirmOverrides {
  workTitle?: string
  author?: string
  dynasty?: string
  translator?: string
  tags?: string
  status?: 'DRAFT' | 'PUBLISHED'
}
export interface ImportResult {
  imported: number
  overwritten: number
  skipped: number
  failed: LineError[]
}
export interface ReindexStatus {
  state: 'IDLE' | 'RUNNING' | 'DONE' | 'FAILED'
  indexed: number
  total: number
  startedAt?: string
  finishedAt?: string
  error?: string
}

export interface ExportWorkItem {
  workTitle: string
  chapters: number
  totalSegments: number
  translatedSegments: number
}

export interface ExportChapterStat {
  title: string | null
  full: number
  src: number
  dst: number
  paired: number
  warnings: string[]
}

export interface ExportPreview {
  segments: number
  units: number
  pairedUnits: number
  chapters: ExportChapterStat[]
}

export type ExportMode = 'TRANSLATION_ONLY' | 'BILINGUAL' | 'SOURCE_ONLY'
export type ExportFormat = 'TXT' | 'MARKDOWN' | 'DOCX'

export const api = {
  login: (username: string, password: string) =>
    http.post<never, LoginResult>('/auth/login', { username, password }),
  me: () => http.get<never, LoginResult['user']>('/auth/me'),
  listSegments: (params: Record<string, unknown>) =>
    http.get<never, PageResponse<SegmentVO>>('/segments', { params }),
  getSegment: (id: number) => http.get<never, SegmentVO>(`/segments/${id}`),
  createSegment: (dto: SegmentDto) => http.post<never, SegmentVO>('/segments', dto),
  updateSegment: (id: number, dto: SegmentDto) => http.put<never, SegmentVO>(`/segments/${id}`, dto),
  deleteSegment: (id: number) => http.delete(`/segments/${id}`),
  search: (params: Record<string, unknown>) => http.get<never, SearchResult>('/search', { params }),
  suggest: (q: string) => http.get<never, SuggestResult>('/suggest', { params: { q } }),
  facets: () => http.get<never, Facets>('/facets'),
  listTags: () => http.get<never, TagVO[]>('/tags'),
  createTag: (name: string, description?: string) => http.post<never, TagVO>('/tags', { name, description }),
  updateTag: (id: number, name: string, description?: string) => http.put<never, TagVO>(`/tags/${id}`, { name, description }),
  deleteTag: (id: number) => http.delete(`/tags/${id}`),
  listUsers: (page = 0, size = 20) => http.get<never, PageResponse<UserVO>>('/users', { params: { page, size } }),
  createUser: (dto: { username: string; password: string; displayName?: string; role: string }) =>
    http.post<never, UserVO>('/users', dto),
  updateUser: (id: number, dto: { displayName?: string; role?: string; status?: string }) =>
    http.put<never, UserVO>(`/users/${id}`, dto),
  uploadImport: (file: File, duplicateStrategy: string) => {
    const form = new FormData()
    form.append('file', file)
    form.append('duplicateStrategy', duplicateStrategy)
    return http.post<never, ImportPreview>('/segments/import', form)
  },
  uploadDocumentImport: (file: File, duplicateStrategy: string, textRole: 'SOURCE' | 'TRANSLATION' = 'SOURCE') => {
    const form = new FormData()
    form.append('file', file)
    form.append('duplicateStrategy', duplicateStrategy)
    form.append('textRole', textRole)
    return http.post<never, ImportPreview>('/segments/import/document', form)
  },
  confirmImport: (previewId: string, overrides?: ImportConfirmOverrides) =>
    http.post<never, ImportResult>(`/segments/import/${previewId}/confirm`, overrides),
  listImportRows: (previewId: string, params: {
    chapter?: string; suspicious?: boolean; longAbove?: number; shortBelow?: number; page?: number; size?: number
  }) => http.get<never, ImportRowsPage>(`/segments/import/${previewId}/rows`, { params }),
  listImportChapters: (previewId: string) =>
    http.get<never, ImportChapterStat[]>(`/segments/import/${previewId}/chapters`),
  editImportRow: (previewId: string, rowId: number, body: { text?: string; chapter?: string }) =>
    http.patch<never, ImportRowOpResult>(`/segments/import/${previewId}/rows/${rowId}`, body),
  mergeImportRows: (previewId: string, rowIds: number[]) =>
    http.post<never, ImportRowOpResult>(`/segments/import/${previewId}/rows/merge`, { rowIds }),
  splitImportRow: (previewId: string, rowId: number, atChar: number) =>
    http.post<never, ImportSplitResult>(`/segments/import/${previewId}/rows/${rowId}/split`, { atChar }),
  deleteImportRow: (previewId: string, rowId: number) =>
    http.delete<never, ImportEditStats>(`/segments/import/${previewId}/rows/${rowId}`),
  renameImportChapter: (previewId: string, from: string, to: string) =>
    http.post<never, ImportEditStats>(`/segments/import/${previewId}/chapters/rename`, { from, to }),
  triggerReindex: () => http.post<never, ReindexStatus>('/admin/reindex'),
  reindexStatus: () => http.get<never, ReindexStatus>('/admin/reindex/status'),
  exportWorks: () => http.get<never, ExportWorkItem[]>('/export/works'),
  exportPreview: (workTitle: string) => http.post<never, ExportPreview>('/export/preview', { workTitle }),
  exportBook: (workTitle: string, mode: ExportMode, format: ExportFormat) =>
    http.post<never, Blob>('/export', { workTitle, mode, format }, { responseType: 'blob' })
}
