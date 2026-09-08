<template>
  <div>
    <div class="panel-head">
      <button class="back-link" type="button" @click="emit('back')">‹ 换一种录入方式</button>
      <h3 class="panel-title">导入整本书 / 文档</h3>
      <p class="panel-desc">
        把 EPUB、PDF、Word、TXT 等电子书或文档直接传上来，系统会自动拆成一段一段的原文收进数据库，
        译文先留空，之后可以逐条补写。<b>上传后先给你看预览，确认了才真正入库。</b>
      </p>
    </div>

    <el-steps :active="step" align-center class="steps" finish-status="success">
      <el-step title="① 选择文件" description="上传一本书或文档" />
      <el-step title="② 检查与补信息" description="看看拆得对不对" />
      <el-step title="③ 完成" description="大功告成" />
    </el-steps>

    <!-- 步骤 ①：上传 -->
    <div v-if="step === 0" class="step-body">
      <div class="side-block">
        <div class="strategy-title">这次导入的是书的哪一部分？</div>
        <el-radio-group v-model="textRole" class="strategy-group">
          <el-radio value="SOURCE" class="strategy-item" data-test="role-source">
            <span class="s-name">原文（古文）（推荐）</span>
            <span class="s-desc">比如论语原典。译文留空，之后逐条补写</span>
          </el-radio>
          <el-radio value="TRANSLATION" class="strategy-item" data-test="role-translation">
            <span class="s-name">译文（英译本）</span>
            <span class="s-desc">书里是英文翻译？选这个导入，之后再配上原文</span>
          </el-radio>
        </el-radio-group>
      </div>

      <el-upload
        drag
        :auto-upload="false"
        :limit="1"
        :on-change="onFileChange"
        :on-remove="() => (file = null)"
        :file-list="file ? [file] : []"
        accept=".epub,.pdf,.docx,.doc,.txt,.md,.markdown,.html,.htm,.xhtml"
        class="upload-zone"
      >
        <el-icon class="el-icon--upload"><reading /></el-icon>
        <div class="el-upload__text">把整本书拖到这里，或 <em>点击选择文件</em></div>
        <template #tip>
          <div class="el-upload__tip">
            支持 EPUB 电子书、PDF、Word（docx / doc）、TXT、Markdown、HTML，单个文件不超过 50MB
          </div>
          <div class="el-upload__tip warn-tip">⚠ 注意：扫描版 PDF（整页都是图片）里没有文字，无法导入</div>
        </template>
      </el-upload>

      <div class="strategy-block">
        <div class="strategy-title">书里有些段落以前已经导入过，怎么处理？</div>
        <el-radio-group v-model="strategy" class="strategy-group">
          <el-radio value="SKIP" class="strategy-item">
            <span class="s-name">跳过重复的（推荐）</span>
            <span class="s-desc">已有的不动，只收新段落</span>
          </el-radio>
          <el-radio value="KEEP" class="strategy-item">
            <span class="s-name">两份都保留</span>
            <span class="s-desc">重复段落也再存一份</span>
          </el-radio>
          <el-radio value="OVERWRITE" class="strategy-item">
            <span class="s-name">用文件里的替换</span>
            <span class="s-desc">覆盖旧记录；已写好译文的段落会受保护，不会被覆盖</span>
          </el-radio>
        </el-radio-group>
      </div>

      <div class="actions">
        <el-button
          type="primary"
          size="large"
          :loading="uploading"
          :disabled="!file"
          data-test="doc-preview-btn"
          @click="runPreview(file!)"
        >
          下一步：先帮我检查一下
        </el-button>
      </div>
    </div>

    <!-- 步骤 ②：预览 + 补充书目信息 -->
    <div v-else-if="step === 1" class="step-body" v-loading="uploading">
      <p class="step-lead">
        系统从书里识别出了 <b>{{ preview?.totalRows }}</b> 个{{ sideNoun }}<template v-if="preview?.chapterCount">，分成 <b>{{ preview?.chapterCount }}</b> 章</template>。
        下面先核对拆分结果、补一补书目信息——<b>此时还没有真正入库</b>，点最下面的按钮才开始。
      </p>

      <div class="stat-cards">
        <div class="stat-card"><div class="stat-num">{{ preview?.totalRows }}</div><div class="stat-label">识别出{{ sideNoun }}</div></div>
        <div class="stat-card ok"><div class="stat-num">{{ preview?.willImportRows }}</div><div class="stat-label">将新加入</div></div>
        <div class="stat-card warn"><div class="stat-num">{{ preview?.skippedRows }}</div><div class="stat-label">将跳过</div></div>
        <div class="stat-card warn"><div class="stat-num">{{ preview?.overwriteRows }}</div><div class="stat-label">将被替换</div></div>
      </div>

      <!-- 拆分抽样 -->
      <h3 class="section-title">📖 拆分出来的{{ sideNoun }}长这样（只显示前几条）</h3>
      <el-table :data="preview?.sampleRows || []" size="small" border>
        <el-table-column prop="line" label="第几段" width="90" />
        <el-table-column prop="chapter" label="所在章节" width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.chapter || '—' }}</template>
        </el-table-column>
        <el-table-column prop="text" label="内容" show-overflow-tooltip />
      </el-table>

      <!-- 书目信息 -->
      <h3 class="section-title">🏷 这本书的信息（识别到的已帮你填好，可以改）</h3>
      <el-row :gutter="16" class="meta-form">
        <el-col :span="12">
          <el-form-item label="书名">
            <el-input v-model="meta.workTitle" placeholder="如：论语" data-test="doc-work-title" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="作者">
            <el-input v-model="meta.author" placeholder="如：孔子弟子" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="朝代">
            <el-input v-model="meta.dynasty" placeholder="如：先秦" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="译者">
            <el-input v-model="meta.translator" placeholder="如暂无可留空" />
          </el-form-item>
        </el-col>
        <el-col :span="24">
          <el-form-item label="标签">
            <el-input v-model="metaTags" placeholder="多个标签用逗号隔开，如：儒家，语录（可不填）" />
          </el-form-item>
        </el-col>
        <el-col :span="24">
          <el-form-item label="导入后马上公开吗？">
            <el-radio-group v-model="meta.status">
              <el-radio value="DRAFT">先不公开（推荐）——译文还没补，等补好再发布</el-radio>
              <el-radio value="PUBLISHED">马上公开——大家搜索时立刻能看到</el-radio>
            </el-radio-group>
          </el-form-item>
        </el-col>
      </el-row>

      <template v-if="preview?.errors?.length">
        <h3 class="section-title">⚠ 这些段落有格式问题，不会被导入</h3>
        <el-table :data="preview.errors" size="small" border>
          <el-table-column prop="line" label="第几段" width="90" />
          <el-table-column prop="reason" label="问题原因" />
        </el-table>
      </template>

      <template v-if="preview?.duplicates?.length">
        <h3 class="section-title">⎋ 这些段落与库里已有的重复</h3>
        <el-table :data="preview.duplicates" size="small" border max-height="260">
          <el-table-column prop="line" label="第几段" width="90" />
          <el-table-column prop="reason" label="说明" />
        </el-table>
      </template>

      <div class="actions">
        <el-button type="primary" size="large" :loading="confirming" data-test="doc-confirm-btn" @click="confirm">
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
          <p class="next-hint">
            <template v-if="textRole === 'SOURCE'">
              这些段落现在只有原文，译文都空着。可以到「找一找」里搜到它们，点进去逐条补写译文。
            </template>
            <template v-else>
              这些条目现在只有译文，原文还空着。可以到「找一找」里搜到它们，点进去逐条配上原文。
            </template>
          </p>
        </template>
      </el-result>

      <template v-if="result?.failed?.length">
        <h3 class="section-title">⚠ 这些段落导入失败了</h3>
        <el-table :data="result.failed" size="small" border>
          <el-table-column prop="line" label="第几段" width="90" />
          <el-table-column prop="reason" label="失败原因" />
        </el-table>
      </template>

      <div class="actions">
        <el-button type="primary" size="large" @click="reset">再导一本</el-button>
        <el-button size="large" @click="emit('back')">完成</el-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Reading } from '@element-plus/icons-vue'
import { api, type ImportPreview, type ImportResult } from '../api'

const emit = defineEmits<{ back: [] }>()

const MAX_SIZE = 50 * 1024 * 1024
const ALLOWED_EXT = ['epub', 'pdf', 'docx', 'doc', 'txt', 'md', 'markdown', 'html', 'htm', 'xhtml']

const step = ref(0)
const file = ref<File | null>(null)
const strategy = ref('SKIP')
/** 本次导入的是原文还是译文（决定段落落到条目的哪一侧） */
const textRole = ref<'SOURCE' | 'TRANSLATION'>('SOURCE')
const uploading = ref(false)
const confirming = ref(false)
const preview = ref<ImportPreview | null>(null)
const result = ref<ImportResult | null>(null)
/** 书目信息：预览返回的识别结果作为初始值，用户确认时可修改 */
const meta = reactive({
  workTitle: '',
  author: '',
  dynasty: '',
  translator: '',
  status: 'DRAFT' as 'DRAFT' | 'PUBLISHED'
})
const metaTags = ref('')

/** 预览文案按导入侧用词：原文侧叫「段落」，译文侧叫「译文段落」 */
const sideNoun = computed(() => (textRole.value === 'TRANSLATION' ? '译文段落' : '段落'))

function checkFile(f: File): boolean {
  const ext = f.name.split('.').pop()?.toLowerCase() || ''
  if (!ALLOWED_EXT.includes(ext)) {
    ElMessage.error('这个文件格式暂不支持，请使用 EPUB / PDF / Word / TXT / Markdown / HTML 文件')
    return false
  }
  if (f.size > MAX_SIZE) {
    ElMessage.error('文件太大了，请上传 50MB 以内的文件')
    return false
  }
  return true
}

function onFileChange(uploadFile: { raw?: File }) {
  const f = uploadFile.raw
  if (!f) return
  if (checkFile(f)) file.value = f
}

/** 上传解析：成功后进入预览步骤，识别到的书名/作者自动填入表单 */
async function runPreview(f: File) {
  if (!checkFile(f)) return
  uploading.value = true
  try {
    preview.value = await api.uploadDocumentImport(f, strategy.value, textRole.value)
    meta.workTitle = preview.value.documentTitle || ''
    meta.author = preview.value.documentAuthor || ''
    step.value = 1
  } catch {
    /* 拦截器已提示（如扫描版 PDF 无文字 → 3005） */
  } finally {
    uploading.value = false
  }
}

function cancelPreview() {
  step.value = 0
  preview.value = null
  file.value = null
}

/** 确认导入：书目信息随确认一起提交，覆盖所有段落 */
async function confirm() {
  if (!preview.value) return
  confirming.value = true
  try {
    const tags = metaTags.value
      .split(/[,，|、]/)
      .map((t) => t.trim())
      .filter(Boolean)
      .join('|')
    result.value = await api.confirmImport(preview.value.previewId, {
      workTitle: meta.workTitle,
      author: meta.author,
      dynasty: meta.dynasty,
      translator: meta.translator,
      tags: tags || undefined,
      status: meta.status
    })
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
  meta.workTitle = ''
  meta.author = ''
  meta.dynasty = ''
  meta.translator = ''
  meta.status = 'DRAFT'
  metaTags.value = ''
  textRole.value = 'SOURCE'
}

defineExpose({ preview, step, meta, textRole, runPreview, confirm })
</script>

<style scoped>
.panel-head { margin-bottom: 22px; }
.back-link {
  background: none;
  border: none;
  cursor: pointer;
  font-family: var(--font-ui);
  font-size: 13.5px;
  color: var(--ink-3);
  padding: 0 0 8px;
}
.back-link:hover { color: var(--cinnabar); }
.panel-title {
  font-family: var(--font-display);
  font-size: 18px;
  font-weight: 700;
  letter-spacing: 2px;
  color: var(--ink);
  margin: 0 0 8px;
}
.panel-desc {
  font-family: var(--font-ui);
  font-size: 13.5px;
  color: var(--ink-3);
  line-height: 1.8;
  margin: 0;
}

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

.side-block { margin-bottom: 20px; }
.upload-zone :deep(.el-upload-dragger) {
  border-radius: 14px;
  border: 2px dashed var(--card-edge);
  background: var(--el-fill-color-lighter);
  transition: border-color 0.2s;
}
.upload-zone :deep(.el-upload-dragger:hover) { border-color: var(--cinnabar); }
.warn-tip { color: var(--el-color-warning); }

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

.meta-form { padding: 16px 18px 4px; border: 1px solid var(--card-edge); border-radius: 14px; background: var(--el-fill-color-lighter); }
.meta-form :deep(.el-form-item__label) {
  font-family: var(--font-ui);
  font-size: 13.5px;
  font-weight: 600;
  color: var(--ink-2);
}

.next-hint {
  font-family: var(--font-ui);
  font-size: 13.5px;
  color: var(--ink-3);
  line-height: 1.8;
  margin: 10px 0 0;
}

@media (max-width: 640px) {
  .meta-form :deep(.el-col-12) { max-width: 100%; flex: 0 0 100%; }
}
</style>
