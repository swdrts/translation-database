<template>
  <div class="export-page">
    <header class="page-header">
      <h1>成书导出</h1>
      <p class="subtitle">翻译完成后，把整本书按原始顺序合并成书稿文件</p>
    </header>

    <div v-loading="loading" class="work-grid">
      <div
        v-for="w in works"
        :key="w.workTitle"
        class="work-card"
        data-test="work-card"
        @click="openPreview(w)"
      >
        <div class="work-title">{{ w.workTitle }}</div>
        <div class="work-meta">
          {{ w.chapters }} 章 · {{ w.totalSegments }} 段 · 已译 {{ w.translatedSegments }} 段
        </div>
      </div>
      <p v-if="!loading && works.length === 0" class="empty-tip">
        还没有可导出的书——先在「录入资料」里导入整本书，或给句段填写书名。
      </p>
    </div>

    <el-dialog v-model="dialogVisible" :title="`导出《${selected?.workTitle ?? ''}》`" width="640px">
      <template v-if="preview">
        <div class="progress-row">
          <span class="progress-label">成书完成度（按配对计算）</span>
          <el-progress :percentage="percentage" :status="percentage === 100 ? 'success' : undefined" />
        </div>
        <el-radio-group v-model="mode" class="option-row">
          <el-radio-button value="BILINGUAL">原文译文对照</el-radio-button>
          <el-radio-button value="TRANSLATION_ONLY">仅译文</el-radio-button>
          <el-radio-button value="SOURCE_ONLY">仅原文</el-radio-button>
        </el-radio-group>
        <el-radio-group v-model="format" class="option-row">
          <el-radio-button value="DOCX">Word</el-radio-button>
          <el-radio-button value="MARKDOWN">Markdown</el-radio-button>
          <el-radio-button value="TXT">TXT</el-radio-button>
        </el-radio-group>
        <p class="stat-legend">
          齐全：原文译文都有 · 待译：只有原文 · 缺原文：只有译文 · 已配对：原文侧与译文侧分批导入后按位置对上的对数
        </p>
        <el-table :data="preview.chapters" size="small" max-height="260">
          <el-table-column prop="title" label="章节" width="140">
            <template #default="{ row }">{{ row.title ?? '（无章节）' }}</template>
          </el-table-column>
          <el-table-column prop="full" label="齐全" width="56" />
          <el-table-column prop="src" label="待译" width="56" />
          <el-table-column prop="dst" label="缺原文" width="64" />
          <el-table-column prop="paired" label="已配对" width="64" />
          <el-table-column label="提示">
            <template #default="{ row }">
              <span v-for="w in row.warnings" :key="w" class="warning-text">{{ w }}</span>
              <span v-if="row.warnings.length === 0" class="ok-text">齐整</span>
            </template>
          </el-table-column>
        </el-table>
      </template>
      <div v-else v-loading="previewLoading" class="preview-loading" />
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          data-test="download-btn"
          :loading="downloading"
          :disabled="!preview"
          @click="download"
        >
          导出下载
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  api,
  type ExportFormat,
  type ExportMode,
  type ExportPreview,
  type ExportWorkItem
} from '../api'

const works = ref<ExportWorkItem[]>([])
const loading = ref(false)
const selected = ref<ExportWorkItem | null>(null)
const preview = ref<ExportPreview | null>(null)
const previewLoading = ref(false)
const dialogVisible = ref(false)
const downloading = ref(false)
const mode = ref<ExportMode>('BILINGUAL')
const format = ref<ExportFormat>('DOCX')

const percentage = computed(() => {
  if (!preview.value || preview.value.units === 0) return 0
  return Math.round((preview.value.pairedUnits / preview.value.units) * 100)
})

onMounted(async () => {
  loading.value = true
  try {
    works.value = await api.exportWorks()
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
})

async function openPreview(w: ExportWorkItem) {
  selected.value = w
  preview.value = null
  dialogVisible.value = true
  previewLoading.value = true
  try {
    preview.value = await api.exportPreview(w.workTitle)
  } catch {
    /* 拦截器已提示 */
  } finally {
    previewLoading.value = false
  }
}

async function download() {
  if (!selected.value) return
  downloading.value = true
  try {
    const blob = await api.exportBook(selected.value.workTitle, mode.value, format.value)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${selected.value.workTitle}-${modeLabel(mode.value)}.${ext(format.value)}`
    a.click()
    URL.revokeObjectURL(url)
  } catch {
    /* 拦截器已提示 */
  } finally {
    downloading.value = false
  }
}

function modeLabel(m: ExportMode): string {
  return m === 'BILINGUAL' ? '对照' : m === 'TRANSLATION_ONLY' ? '译文' : '原文'
}

function ext(f: ExportFormat): string {
  return f === 'DOCX' ? 'docx' : f === 'MARKDOWN' ? 'md' : 'txt'
}
</script>

<style scoped>
.export-page {
  max-width: 960px;
  margin: 0 auto;
}

.page-header h1 {
  margin: 0 0 4px;
  font-family: var(--font-display);
  letter-spacing: 3px;
}

.subtitle {
  margin: 0 0 20px;
  color: var(--ink-3);
}

.work-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 16px;
  min-height: 120px;
}

.work-card {
  border: 1px solid var(--card-edge);
  border-radius: 12px;
  padding: 18px;
  cursor: pointer;
  background: var(--card-bg, #fffdf7);
  transition: box-shadow 0.2s, transform 0.2s;
}

.work-card:hover {
  box-shadow: 0 4px 14px rgba(107, 87, 48, 0.14);
  transform: translateY(-2px);
}

.work-title {
  font-family: var(--font-display);
  font-size: 19px;
  font-weight: 700;
  letter-spacing: 2px;
  color: var(--ink);
  margin-bottom: 8px;
}

.work-meta {
  color: var(--ink-3);
  font-size: 13px;
  letter-spacing: 1px;
}

.empty-tip {
  grid-column: 1 / -1;
  color: var(--ink-3);
}

.progress-row {
  margin-bottom: 14px;
}

.progress-label {
  display: block;
  font-size: 13px;
  color: var(--ink-3);
  margin-bottom: 4px;
}

.stat-legend {
  margin: 0 0 8px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ink-3);
}

.option-row {
  display: flex;
  margin-bottom: 14px;
}

.warning-text {
  color: var(--el-color-warning);
  font-size: 12px;
  display: block;
}

.ok-text {
  color: var(--el-color-success);
  font-size: 12px;
}

.preview-loading {
  min-height: 200px;
}
</style>
