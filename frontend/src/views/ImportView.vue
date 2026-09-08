<template>
  <div class="import-view page-narrow">
    <div class="import-card page-card rise">
      <h2 class="page-title">上传资料</h2>
      <p class="page-lead">把整理好的文件一次性导入数据库。整个过程分三步，跟着提示走就可以。</p>

      <el-steps :active="step" align-center class="steps" finish-status="success">
        <el-step title="① 选择文件" description="挑一个文件上传" />
        <el-step title="② 检查预览" description="先看看会导入什么" />
        <el-step title="③ 完成" description="大功告成" />
      </el-steps>

      <!-- 步骤 ①：上传 -->
      <div v-if="step === 0" class="step-body">
        <el-upload
          drag
          :auto-upload="false"
          :limit="1"
          :on-change="onFileChange"
          :on-remove="() => (file = null)"
          :file-list="file ? [file] : []"
          accept=".json,.csv,.xlsx,.xls"
          class="upload-zone"
        >
          <el-icon class="el-icon--upload"><upload-filled /></el-icon>
          <div class="el-upload__text">把文件拖到这里，或 <em>点击选择文件</em></div>
          <template #tip>
            <div class="el-upload__tip">支持 json / csv / xlsx / xls 四种格式，单个文件不超过 50MB</div>
          </template>
        </el-upload>

        <div class="strategy-block">
          <div class="strategy-title">如果文件里有和数据库重复的内容，怎么处理？</div>
          <el-radio-group v-model="strategy" class="strategy-group">
            <el-radio value="SKIP" class="strategy-item">
              <span class="s-name">跳过重复的（推荐）</span>
              <span class="s-desc">已有的不动，只添加新内容</span>
            </el-radio>
            <el-radio value="OVERWRITE" class="strategy-item">
              <span class="s-name">用文件里的替换</span>
              <span class="s-desc">文件内容优先，覆盖旧记录</span>
            </el-radio>
            <el-radio value="KEEP" class="strategy-item">
              <span class="s-name">两份都保留</span>
              <span class="s-desc">新旧内容共存，互不影响</span>
            </el-radio>
          </el-radio-group>
        </div>

        <div class="actions">
          <el-button
            type="primary"
            size="large"
            :loading="uploading"
            :disabled="!file"
            data-test="preview-btn"
            @click="runPreview(file!)"
          >
            下一步：先帮我检查一下
          </el-button>
        </div>
      </div>

      <!-- 步骤 ②：预览 -->
      <div v-else-if="step === 1" class="step-body" v-loading="uploading">
        <p class="step-lead">检查结果如下——<b>此时还没有真正导入</b>，请核对后点击最下面的确认按钮。</p>

        <div class="stat-cards">
          <div class="stat-card"><div class="stat-num">{{ preview?.totalRows }}</div><div class="stat-label">文件总行数</div></div>
          <div class="stat-card ok"><div class="stat-num">{{ preview?.willImportRows }}</div><div class="stat-label">将新加入</div></div>
          <div class="stat-card warn"><div class="stat-num">{{ preview?.overwriteRows }}</div><div class="stat-label">将被替换</div></div>
          <div class="stat-card"><div class="stat-num">{{ preview?.skippedRows }}</div><div class="stat-label">将跳过</div></div>
        </div>

        <template v-if="preview?.errors?.length">
          <h3 class="section-title">⚠ 这些行有格式问题，不会被导入</h3>
          <el-table :data="preview.errors" size="small" border>
            <el-table-column prop="line" label="第几行" width="100" />
            <el-table-column prop="reason" label="问题原因" />
          </el-table>
        </template>

        <template v-if="preview?.duplicates?.length">
          <h3 class="section-title">⎋ 这些行与已有内容重复</h3>
          <el-table :data="preview.duplicates" size="small" border>
            <el-table-column prop="line" label="第几行" width="100" />
            <el-table-column prop="reason" label="说明" />
          </el-table>
        </template>

        <div class="actions">
          <el-button type="primary" size="large" :loading="confirming" data-test="confirm-btn" @click="confirm">
            确认无误，开始导入
          </el-button>
          <el-button size="large" @click="cancelPreview">不对，我要重新选</el-button>
        </div>
      </div>

      <!-- 步骤 ③：结果 -->
      <div v-else class="step-body">
        <el-result icon="success" title="导入成功">
          <template #sub-title>
            <div class="stat-cards">
              <div class="stat-card ok"><div class="stat-num">{{ result?.imported }}</div><div class="stat-label">新加入</div></div>
              <div class="stat-card warn"><div class="stat-num">{{ result?.overwritten }}</div><div class="stat-label">被替换</div></div>
              <div class="stat-card"><div class="stat-num">{{ result?.skipped }}</div><div class="stat-label">跳过</div></div>
            </div>
          </template>
        </el-result>

        <template v-if="result?.failed?.length">
          <h3 class="section-title">⚠ 这些行导入失败了，可以修正文件后重新上传</h3>
          <el-table :data="result.failed" size="small" border>
            <el-table-column prop="line" label="第几行" width="100" />
            <el-table-column prop="reason" label="失败原因" />
          </el-table>
        </template>

        <div class="actions">
          <el-button type="primary" size="large" @click="reset">再导一批</el-button>
          <el-button size="large" @click="router.push('/')">完成，去看看内容</el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import { api, type ImportPreview, type ImportResult } from '../api'

const router = useRouter()

const MAX_SIZE = 50 * 1024 * 1024
const ALLOWED_EXT = ['json', 'csv', 'xlsx', 'xls']

const step = ref(0)
const file = ref<File | null>(null)
const strategy = ref('SKIP')
const uploading = ref(false)
const confirming = ref(false)
const preview = ref<ImportPreview | null>(null)
const result = ref<ImportResult | null>(null)

function onFileChange(uploadFile: { raw?: File }) {
  const f = uploadFile.raw
  if (!f) return
  const ext = f.name.split('.').pop()?.toLowerCase() || ''
  if (!ALLOWED_EXT.includes(ext)) {
    ElMessage.error('这个文件格式不支持，请使用 json / csv / xlsx / xls 文件')
    return
  }
  if (f.size > MAX_SIZE) {
    ElMessage.error('文件太大了，请上传 50MB 以内的文件')
    return
  }
  file.value = f
}

/** 前端预检 + 上传解析，成功后进入预览步骤 */
async function runPreview(f: File) {
  const ext = f.name.split('.').pop()?.toLowerCase() || ''
  if (!ALLOWED_EXT.includes(ext)) {
    ElMessage.error('这个文件格式不支持，请使用 json / csv / xlsx / xls 文件')
    return
  }
  if (f.size > MAX_SIZE) {
    ElMessage.error('文件太大了，请上传 50MB 以内的文件')
    return
  }
  uploading.value = true
  try {
    preview.value = await api.uploadImport(f, strategy.value)
    step.value = 1
  } catch {
    /* 拦截器已提示 */
  } finally {
    uploading.value = false
  }
}

function cancelPreview() {
  step.value = 0
  preview.value = null
  file.value = null
}

/** 确认导入，进入结果步骤 */
async function confirm() {
  if (!preview.value) return
  confirming.value = true
  try {
    result.value = await api.confirmImport(preview.value.previewId)
    step.value = 2
  } catch {
    /* 拦截器已提示 */
  } finally {
    confirming.value = false
  }
}

function reset() {
  step.value = 0
  preview.value = null
  result.value = null
  file.value = null
}

defineExpose({ preview, step, runPreview, confirm })
</script>

<style scoped>
.import-view { padding-top: 8px; }
.import-card { padding: 30px 36px; }
.steps { margin-bottom: 30px; }
.steps :deep(.el-step__title) { font-family: var(--font-ui); font-size: 14.5px; }
.steps :deep(.el-step__description) { font-size: 12px; }
.step-body { min-height: 240px; }
.step-lead {
  margin: 0 0 18px;
  font-size: 14.5px;
  color: var(--ink-2);
  background: var(--el-color-warning-light-9);
  border: 1px solid rgba(176, 125, 43, 0.3);
  border-radius: 10px;
  padding: 10px 16px;
}

.upload-zone :deep(.el-upload-dragger) {
  border-radius: 14px;
  border: 2px dashed var(--card-edge);
  background: var(--el-fill-color-lighter);
  transition: border-color 0.2s;
}
.upload-zone :deep(.el-upload-dragger:hover) { border-color: var(--cinnabar); }

/* 重复策略：三个选项卡片化，附大白话解释 */
.strategy-block { margin-top: 24px; }
.strategy-title {
  font-family: var(--font-ui);
  font-size: 14.5px;
  font-weight: 600;
  color: var(--ink-2);
  margin-bottom: 12px;
}
.strategy-group { display: flex; flex-direction: column; gap: 10px; align-items: stretch; }
.strategy-item {
  display: flex;
  align-items: flex-start;
  margin-right: 0 !important;
  height: auto !important;
  padding: 12px 16px;
  border: 1px solid var(--card-edge);
  border-radius: 12px;
  background: var(--el-fill-color-lighter);
}
.strategy-item.is-checked { border-color: var(--cinnabar); }
.strategy-item :deep(.el-radio__label) { display: flex; flex-direction: column; gap: 3px; padding-left: 10px; }
.s-name { font-family: var(--font-ui); font-size: 14.5px; font-weight: 600; color: var(--ink); }
.s-desc { font-family: var(--font-ui); font-size: 12.5px; color: var(--ink-3); }

.actions { margin-top: 28px; display: flex; gap: 12px; }

/* 统计卡片 */
.stat-cards { display: flex; gap: 14px; justify-content: center; flex-wrap: wrap; margin: 8px 0 4px; }
.stat-card {
  width: 128px;
  text-align: center;
  background: var(--el-fill-color-lighter);
  border: 1px solid var(--card-edge);
  border-radius: 12px;
  padding: 16px 8px 12px;
}
.stat-num { font-size: 28px; font-weight: 700; color: var(--ink); font-family: var(--font-serif); }
.stat-card.ok .stat-num { color: var(--verdigris); }
.stat-card.warn .stat-num { color: var(--cinnabar); }
.stat-label { font-family: var(--font-ui); font-size: 12.5px; color: var(--ink-3); margin-top: 4px; }

.section-title {
  font-family: var(--font-ui);
  color: var(--ink-2);
  font-size: 14.5px;
  font-weight: 600;
  margin: 24px 0 10px;
}
</style>
