<template>
  <div class="segment-editor" data-test="editor-rows">
    <div class="editor-toolbar">
      <el-select v-model="chapterFilter" class="chapter-select" data-test="editor-chapter-select" @change="reload">
        <el-option label="全部章节" value="" />
        <el-option v-for="c in chapters" :key="c.title || '__NONE__'"
                   :label="`${c.title || '未分章'}（${c.rowCount}段）`" :value="c.title" />
      </el-select>
      <el-checkbox v-model="suspicious" data-test="editor-suspicious-toggle" @change="reload">只看可疑段</el-checkbox>
      <template v-if="suspicious">
        <span class="thresh">长段&gt;=</span>
        <el-input-number v-model="longAbove" :min="1" size="small" controls-position="right" class="thresh-num"
                         data-test="editor-long-above" @change="reload" />
        <span class="thresh">短段&lt;=</span>
        <el-input-number v-model="shortBelow" :min="0" size="small" controls-position="right" class="thresh-num"
                         data-test="editor-short-below" @change="reload" />
      </template>
      <span v-if="stats" class="editor-stats">
        当前 {{ stats.totalRows }} 段 · 新导入 {{ stats.willImportRows }} · 跳过 {{ stats.skippedRows }} · 覆盖 {{ stats.overwriteRows }}
      </span>
    </div>

    <div v-loading="busy" class="seg-list">
      <div v-for="row in rows" :key="row.rowId" class="seg-item">
        <label class="seg-check">
          <input type="checkbox" :checked="checked.includes(row.rowId)" :data-test="`row-check-${row.rowId}`"
                 @change="toggleCheck(row.rowId)" />
        </label>
        <span class="seg-seq">{{ row.seq }}</span>
        <div class="seg-main">
          <div class="seg-text" :class="{ 'seg-edited-text': row.edited }">{{ row.text }}</div>
          <div class="seg-meta">
            <span v-if="row.chapter" class="seg-chapter">{{ row.chapter }}</span>
            <el-tag size="small" :type="planTagType(row.planType)">{{ planTagText(row.planType) }}</el-tag>
            <el-tag v-if="row.edited" size="small" type="warning" effect="plain">已修改</el-tag>
          </div>
        </div>
        <div class="seg-actions">
          <el-button link type="primary" size="small" data-test="row-edit-btn" @click="openEdit(row)">编辑</el-button>
          <el-button link type="primary" size="small" data-test="row-split-btn" @click="openSplit(row)">拆分</el-button>
          <el-button link type="warning" size="small" :disabled="row.prevRowId === -1"
                     data-test="row-merge-up-btn" @click="mergeWithPrev(row)">并入上段</el-button>
          <el-button link size="small" data-test="row-chapter-btn" @click="openChapter(row)">改章节</el-button>
          <el-button link type="danger" size="small" data-test="row-delete-btn" @click="askDelete(row)">删除</el-button>
        </div>
      </div>
      <el-empty v-if="!busy && rows.length === 0" description="没有符合条件的分段" :image-size="60" />
    </div>

    <div class="editor-footer">
      <el-button size="small" :disabled="!canMergeChecked" data-test="editor-merge-btn" @click="mergeChecked">
        合并所选{{ checked.length ? `（${checked.length}段）` : '' }}
      </el-button>
      <el-button size="small" data-test="editor-rename-btn" @click="renameDialog.visible = true">章节改名</el-button>
      <div class="editor-pager" data-test="editor-pager">
        <el-button size="small" :disabled="page <= 0" data-test="editor-prev-page" @click="gotoPage(page - 1)">← 上一页</el-button>
        <span class="pager-text">第 {{ page + 1 }} / {{ totalPages }} 页 · 每页 {{ pageSize }} 段</span>
        <el-button size="small" :disabled="page >= totalPages - 1" data-test="editor-next-page" @click="gotoPage(page + 1)">下一页 →</el-button>
      </div>
    </div>

    <!-- 编辑正文 -->
    <el-dialog v-model="editDialog.visible" title="编辑段落文字" width="620px">
      <el-input v-model="editDialog.text" type="textarea" :rows="7" data-test="seg-edit-input" />
      <template #footer>
        <el-button @click="editDialog.visible = false">取消</el-button>
        <el-button type="primary" data-test="seg-edit-save" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 拆分 -->
    <el-dialog v-model="splitDialog.visible" title="在光标处拆分" width="620px">
      <p class="split-tip">点击下面文字，把光标放在要切开的位置：</p>
      <textarea ref="splitBox" v-model="splitDialog.text" readonly rows="6" class="split-box"
                data-test="seg-split-input" @click="syncCaret" @keyup="syncCaret" />
      <div v-if="splitOk" class="split-preview" data-test="seg-split-preview">
        <div>上：{{ splitDialog.text.slice(0, splitDialog.atChar) }}</div>
        <div>下：{{ splitDialog.text.slice(splitDialog.atChar) }}</div>
      </div>
      <template #footer>
        <el-button @click="splitDialog.visible = false">取消</el-button>
        <el-button type="primary" :disabled="!splitOk" data-test="seg-split-save" @click="doSplit">拆分</el-button>
      </template>
    </el-dialog>

    <!-- 改章节 -->
    <el-dialog v-model="chapterDialog.visible" title="调整该段的章节" width="480px">
      <el-select v-model="chapterDialog.chapter" filterable allow-create default-first-option
                 style="width: 100%" data-test="seg-chapter-input" placeholder="选择已有章节，或输入新章节名">
        <el-option label="（未分章）" value="" />
        <el-option v-for="c in chapters" :key="c.title || '__NONE__'"
                   :label="c.title || '（未分章）'" :value="c.title" />
      </el-select>
      <template #footer>
        <el-button @click="chapterDialog.visible = false">取消</el-button>
        <el-button type="primary" data-test="seg-chapter-save" @click="saveChapter">保存</el-button>
      </template>
    </el-dialog>

    <!-- 章节改名 -->
    <el-dialog v-model="renameDialog.visible" title="章节改名（作用于该章全部段落）" width="480px">
      <el-select v-model="renameDialog.from" style="width: 100%; margin-bottom: 12px" placeholder="选择要改名的章节">
        <el-option v-for="c in chapters" :key="c.title || '__NONE__'"
                   :label="`${c.title || '未分章'}（${c.rowCount}段）`" :value="c.title" />
      </el-select>
      <el-input v-model="renameDialog.to" placeholder="新章节名（与已有章节同名即两章合并；留空归入未分章）"
                data-test="seg-rename-to" />
      <template #footer>
        <el-button @click="renameDialog.visible = false">取消</el-button>
        <el-button type="primary" data-test="seg-rename-save" @click="doRename">改名</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  api, type ImportChapterStat, type ImportEditStats, type ImportRowsPage, type ImportSegmentRow
} from '../api'

const props = defineProps<{ previewId: string; sideNoun?: string }>()
const emit = defineEmits<{ expired: []; 'stats-change': [ImportEditStats | null] }>()

const pageSize = 100
const rows = ref<ImportSegmentRow[]>([])
const chapters = ref<ImportChapterStat[]>([])
const stats = ref<ImportEditStats | null>(null)
const page = ref(0)
const totalPages = ref(1)
const busy = ref(false)
const checked = ref<number[]>([])
const chapterFilter = ref('')
const suspicious = ref(false)
const longAbove = ref(300)
const shortBelow = ref(10)

const editDialog = reactive({ visible: false, rowId: 0, text: '' })
const splitDialog = reactive({ visible: false, rowId: 0, text: '', atChar: 0 })
const chapterDialog = reactive({ visible: false, rowId: 0, chapter: '' })
const renameDialog = reactive({ visible: false, from: '', to: '' })
const splitBox = ref()

/** 所选段须同页且 seq 连续才可合并 */
const canMergeChecked = computed(() => {
  if (checked.value.length < 2) return false
  const selected = rows.value.filter((r) => checked.value.includes(r.rowId)).map((r) => r.seq).sort((a, b) => a - b)
  return selected.every((s, i) => i === 0 || s === selected[i - 1] + 1)
})
const splitOk = computed(() => splitDialog.atChar > 0 && splitDialog.atChar < splitDialog.text.length)

function planTagType(t: string) {
  return t === 'IMPORT' ? 'success' : t === 'OVERWRITE' ? 'warning' : 'info'
}
function planTagText(t: string) {
  return t === 'IMPORT' ? '新导入' : t === 'OVERWRITE' ? '将替换' : '已存在跳过'
}
function isPreviewGone(e: unknown) {
  const err = e as { response?: { data?: { code?: number } }; message?: string }
  const code = err?.response?.data?.code
  return code === 3003 || code === 3004 || !!err?.message?.includes('预览不存在') || !!err?.message?.includes('已过期')
}
async function guard(e: unknown) {
  if (isPreviewGone(e)) emit('expired')
}

function applyStats(s: ImportEditStats | null) {
  stats.value = s
  emit('stats-change', s)
}

async function loadRows() {
  busy.value = true
  try {
    const params: Record<string, unknown> = { page: page.value, size: pageSize }
    if (chapterFilter.value) params.chapter = chapterFilter.value
    if (suspicious.value) {
      params.suspicious = true
      params.longAbove = longAbove.value
      params.shortBelow = shortBelow.value
    }
    const data: ImportRowsPage = await api.listImportRows(props.previewId, params)
    if (data.rows.length === 0 && data.page > 0) {
      page.value = data.page - 1
      return loadRows()
    }
    rows.value = data.rows
    totalPages.value = data.totalPages
    applyStats(data.stats)
  } catch (e) {
    await guard(e)
  } finally {
    busy.value = false
  }
}

async function loadChapters() {
  try {
    chapters.value = await api.listImportChapters(props.previewId)
  } catch (e) {
    await guard(e)
  }
}

async function reload() {
  page.value = 0
  await Promise.all([loadRows(), loadChapters()])
}

async function gotoPage(p: number) {
  page.value = p
  await loadRows()
}

function toggleCheck(rowId: number) {
  const i = checked.value.indexOf(rowId)
  if (i >= 0) checked.value.splice(i, 1)
  else checked.value.push(rowId)
}

function setChapter(chapter: string) {
  chapterFilter.value = chapter
  page.value = 0
  return loadRows()
}
function toggleSuspicious() {
  suspicious.value = !suspicious.value
  page.value = 0
  return loadRows()
}

async function afterOp() {
  checked.value = []
  await Promise.all([loadRows(), loadChapters()])
}

async function mergeChecked() {
  if (!canMergeChecked.value) {
    ElMessage.error('只能合并相邻的段落，请在同一页按顺序勾选')
    return
  }
  try {
    const res = await api.mergeImportRows(props.previewId, [...checked.value])
    applyStats(res.stats)
    await afterOp()
  } catch (e) {
    await guard(e)
  }
}

async function mergeWithPrev(row: ImportSegmentRow) {
  try {
    const res = await api.mergeImportRows(props.previewId, [row.prevRowId, row.rowId])
    applyStats(res.stats)
    await afterOp()
  } catch (e) {
    await guard(e)
  }
}

function openEdit(row: ImportSegmentRow) {
  Object.assign(editDialog, { visible: true, rowId: row.rowId, text: row.text })
}

async function saveEdit() {
  if (!editDialog.text.trim()) {
    ElMessage.error('段落内容不能为空')
    return
  }
  try {
    const res = await api.editImportRow(props.previewId, editDialog.rowId, { text: editDialog.text })
    applyStats(res.stats)
    editDialog.visible = false
    await afterOp()
  } catch (e) {
    await guard(e)
  }
}

function openSplit(row: ImportSegmentRow) {
  Object.assign(splitDialog, { visible: true, rowId: row.rowId, text: row.text, atChar: 0 })
}

function syncCaret(e: Event) {
  splitDialog.atChar = (e.target as HTMLTextAreaElement).selectionStart ?? 0
}

async function doSplit() {
  try {
    const res = await api.splitImportRow(props.previewId, splitDialog.rowId, splitDialog.atChar)
    applyStats(res.stats)
    splitDialog.visible = false
    await afterOp()
  } catch (e) {
    await guard(e)
  }
}

async function askDelete(row: ImportSegmentRow) {
  try {
    await ElMessageBox.confirm(`确定删除第 ${row.seq} 段？删除后不可恢复（重新上传可重来）。`, '删除段落', { type: 'warning' })
  } catch {
    return
  }
  await deleteConfirmed(row.rowId)
}

async function deleteConfirmed(rowId: number) {
  try {
    applyStats(await api.deleteImportRow(props.previewId, rowId))
    await afterOp()
  } catch (e) {
    await guard(e)
  }
}

function openChapter(row: ImportSegmentRow) {
  Object.assign(chapterDialog, { visible: true, rowId: row.rowId, chapter: row.chapter })
}

async function saveChapter() {
  try {
    const res = await api.editImportRow(props.previewId, chapterDialog.rowId, { chapter: chapterDialog.chapter })
    applyStats(res.stats)
    chapterDialog.visible = false
    await afterOp()
  } catch (e) {
    await guard(e)
  }
}

async function doRename() {
  if (!renameDialog.from && renameDialog.from !== '') {
    ElMessage.error('请选择要改名的章节')
    return
  }
  try {
    applyStats(await api.renameImportChapter(props.previewId, renameDialog.from, renameDialog.to))
    renameDialog.visible = false
    await afterOp()
  } catch (e) {
    await guard(e)
  }
}

onMounted(reload)

defineExpose({
  rows, stats, chapters, page, totalPages, checked, suspicious,
  setChapter, toggleSuspicious, mergeChecked, loadRows, loadChapters, deleteConfirmed
})
</script>

<style scoped>
.segment-editor { margin-top: 4px; }
.editor-toolbar {
  display: flex; align-items: center; gap: 12px; flex-wrap: wrap;
  padding: 10px 12px; border: 1px solid var(--card-edge); border-radius: 12px;
  background: var(--el-fill-color-lighter); margin-bottom: 12px;
}
.chapter-select { width: 220px; }
.thresh { font-size: 12.5px; color: var(--ink-3); }
.thresh-num { width: 92px; }
.editor-stats { margin-left: auto; font-size: 12.5px; color: var(--ink-3); }
.seg-list { min-height: 200px; }
.seg-item {
  display: flex; gap: 10px; align-items: flex-start;
  padding: 10px 12px; border-bottom: 1px dashed var(--card-edge);
}
.seg-item:hover { background: var(--el-fill-color-lighter); }
.seg-check { padding-top: 2px; }
.seg-seq { min-width: 28px; text-align: right; color: var(--ink-3); font-size: 12.5px; padding-top: 2px; }
.seg-main { flex: 1; min-width: 0; }
.seg-text {
  font-family: var(--font-serif); font-size: 14.5px; line-height: 1.9;
  color: var(--ink); white-space: pre-wrap; word-break: break-word;
}
.seg-edited-text { padding-left: 8px; border-left: 3px solid var(--el-color-warning); }
.seg-meta { display: flex; gap: 8px; align-items: center; margin-top: 4px; }
.seg-chapter {
  font-size: 12px; color: var(--ink-3);
  border: 1px solid var(--card-edge); border-radius: 6px; padding: 0 6px;
}
.seg-actions { display: flex; flex-direction: column; gap: 0; align-items: stretch; min-width: 76px; }
.seg-actions .el-button { margin: 0; padding: 2px 0; justify-content: flex-start; }
.editor-footer {
  display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-top: 12px;
}
.editor-pager { margin-left: auto; display: flex; align-items: center; gap: 8px; }
.pager-text { font-size: 12.5px; color: var(--ink-3); }
.split-tip { margin: 0 0 8px; font-size: 13px; color: var(--ink-3); }
.split-box {
  width: 100%; border: 1px solid var(--card-edge); border-radius: 8px;
  padding: 10px; font-family: var(--font-serif); font-size: 14.5px; line-height: 1.9;
  cursor: text; resize: vertical;
}
.split-preview {
  margin-top: 10px; padding: 10px; border-radius: 8px;
  background: var(--el-fill-color-lighter); font-size: 13.5px; line-height: 1.8;
}
.split-preview div:first-child { border-bottom: 1px dashed var(--card-edge); padding-bottom: 6px; margin-bottom: 6px; }
</style>
