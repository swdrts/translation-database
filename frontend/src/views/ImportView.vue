<template>
  <div class="import-view">
    <div class="import-card">
      <h2 class="page-title">批量导入</h2>

      <el-steps :active="step" align-center class="steps" finish-status="success">
        <el-step title="上传文件" />
        <el-step title="预览确认" />
        <el-step title="导入结果" />
      </el-steps>

      <!-- 步骤 1：上传 -->
      <div v-if="step === 0" class="step-body">
        <el-upload
          ref="uploadRef"
          drag
          :auto-upload="false"
          :limit="1"
          :on-change="onFileChange"
          :on-remove="() => (file = null)"
          :file-list="file ? [file] : []"
          accept=".json,.csv,.xlsx,.xls"
        >
          <el-icon class="el-icon--upload"><upload-filled /></el-icon>
          <div class="el-upload__text">拖拽文件到此处，或 <em>点击选择文件</em></div>
          <template #tip>
            <div class="el-upload__tip">支持 json / csv / xlsx / xls，不超过 50MB</div>
          </template>
        </el-upload>

        <div class="strategy-row">
          <span class="strategy-label">重复策略：</span>
          <el-radio-group v-model="strategy">
            <el-radio value="SKIP">跳过已存在</el-radio>
            <el-radio value="OVERWRITE">覆盖已存在</el-radio>
            <el-radio value="KEEP">保留两者</el-radio>
          </el-radio-group>
        </div>

        <div class="actions">
          <el-button type="primary" :loading="uploading" :disabled="!file" data-test="preview-btn" @click="runPreview(file!)">
            开始预览
          </el-button>
        </div>
      </div>

      <!-- 步骤 2：预览 -->
      <div v-else-if="step === 1" class="step-body" v-loading="uploading">
        <div class="stat-cards">
          <el-card shadow="never"><div class="stat-num">{{ preview?.totalRows }}</div><div class="stat-label">总行数</div></el-card>
          <el-card shadow="never"><div class="stat-num ok">{{ preview?.willImportRows }}</div><div class="stat-label">将导入</div></el-card>
          <el-card shadow="never"><div class="stat-num warn">{{ preview?.overwriteRows }}</div><div class="stat-label">将覆盖</div></el-card>
          <el-card shadow="never"><div class="stat-num">{{ preview?.skippedRows }}</div><div class="stat-label">将跳过</div></el-card>
        </div>

        <template v-if="preview?.errors?.length">
          <h3 class="section-title">错误行</h3>
          <el-table :data="preview.errors" size="small" border>
            <el-table-column prop="line" label="行号" width="90" />
            <el-table-column prop="reason" label="原因" />
          </el-table>
        </template>

        <template v-if="preview?.duplicates?.length">
          <h3 class="section-title">重复行</h3>
          <el-table :data="preview.duplicates" size="small" border>
            <el-table-column prop="line" label="行号" width="90" />
            <el-table-column prop="reason" label="原因" />
          </el-table>
        </template>

        <div class="actions">
          <el-button type="primary" :loading="confirming" data-test="confirm-btn" @click="confirm">确认导入</el-button>
          <el-button @click="cancelPreview">取消</el-button>
        </div>
      </div>

      <!-- 步骤 3：结果 -->
      <div v-else class="step-body">
        <el-result icon="success" title="导入成功">
          <template #sub-title>
            <div class="stat-cards">
              <el-card shadow="never"><div class="stat-num ok">{{ result?.imported }}</div><div class="stat-label">新导入</div></el-card>
              <el-card shadow="never"><div class="stat-num warn">{{ result?.overwritten }}</div><div class="stat-label">覆盖</div></el-card>
              <el-card shadow="never"><div class="stat-num">{{ result?.skipped }}</div><div class="stat-label">跳过</div></el-card>
            </div>
          </template>
        </el-result>

        <template v-if="result?.failed?.length">
          <h3 class="section-title">失败行</h3>
          <el-table :data="result.failed" size="small" border>
            <el-table-column prop="line" label="行号" width="90" />
            <el-table-column prop="reason" label="原因" />
          </el-table>
        </template>

        <div class="actions">
          <el-button type="primary" @click="reset">再导一批</el-button>
          <el-button @click="$router.push('/')">完成</el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import { api, type ImportPreview, type ImportResult } from '../api'

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
    ElMessage.error('仅支持 json / csv / xlsx / xls 文件')
    return
  }
  if (f.size > MAX_SIZE) {
    ElMessage.error('文件大小不能超过 50MB')
    return
  }
  file.value = f
}

/** 前端预检 + 上传解析，成功后进入预览步骤 */
async function runPreview(f: File) {
  const ext = f.name.split('.').pop()?.toLowerCase() || ''
  if (!ALLOWED_EXT.includes(ext)) {
    ElMessage.error('仅支持 json / csv / xlsx / xls 文件')
    return
  }
  if (f.size > MAX_SIZE) {
    ElMessage.error('文件大小不能超过 50MB')
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
.import-view { max-width: 860px; margin: 0 auto; padding-top: 24px; }
.import-card { background: #fff; border-radius: 10px; padding: 24px 28px; }
.page-title { color: #3d3d3d; margin: 0 0 20px; font-size: 20px; }
.steps { margin-bottom: 28px; }
.step-body { min-height: 220px; }
.strategy-row { margin-top: 20px; }
.strategy-label { color: #3d3d3d; font-size: 14px; margin-right: 8px; }
.actions { margin-top: 24px; }
.stat-cards { display: flex; gap: 14px; justify-content: center; flex-wrap: wrap; }
.stat-cards .el-card { width: 120px; text-align: center; }
.stat-num { font-size: 24px; font-weight: 700; color: #3d3d3d; }
.stat-num.ok { color: #4a7c59; }
.stat-num.warn { color: #b03a2e; }
.stat-label { font-size: 12px; color: #8a8378; margin-top: 4px; }
.section-title { color: #3d3d3d; font-size: 15px; margin: 24px 0 10px; }
</style>
