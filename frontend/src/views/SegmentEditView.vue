<template>
  <div class="edit-view page-narrow">
    <div class="edit-card page-card rise">
      <div class="card-head">
        <div>
          <h2 class="page-title">{{ isEdit ? '修改这条内容' : '手动录入一条' }}</h2>
          <p class="page-lead">
            {{ isEdit ? '修改完成后记得点「保存」。' : '把「原文」填上就能保存；译文没写好可以先留空，存成草稿以后再补。' }}
          </p>
        </div>
      </div>

      <el-form :model="form" label-position="top" class="edit-form" v-loading="loading">
        <!-- 第 ① 步：必填 -->
        <section class="form-group">
          <h3 class="group-title">
            <span class="group-no">①</span>原文与译文
            <span class="group-req">原文必填</span>
            <span class="group-opt">译文可以以后再补</span>
          </h3>

          <el-form-item label="古文原文">
            <el-input
              v-model="form.sourceText"
              type="textarea"
              :rows="4"
              placeholder="把古文原句粘贴或打字输入到这里，例如：学而时习之，不亦说乎？"
              data-test="source-input"
            />
          </el-form-item>
          <el-form-item label="英文译文">
            <el-input
              v-model="form.translatedText"
              type="textarea"
              :rows="4"
              placeholder="填上这句话的英文翻译，例如：Is it not a pleasure to learn and practise what one has learnt？暂时没有也可先空着"
              data-test="translated-input"
            />
            <div v-if="!form.translatedText.trim() && form.sourceText.trim()" class="field-hint">
              还没写译文？可以先空着——保存后会存成「待翻译」的草稿，之后回来补上就行。
            </div>
          </el-form-item>
        </section>

        <!-- 第 ② 步：出处（选填） -->
        <section class="form-group">
          <h3 class="group-title"><span class="group-no">②</span>出处信息<span class="group-opt">选填，方便日后查找</span></h3>

          <el-row :gutter="16">
            <el-col :span="12">
              <el-form-item label="书名"><el-input v-model="form.workTitle" placeholder="如：论语" /></el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="章节"><el-input v-model="form.chapter" placeholder="如：学而篇第一" /></el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="作者"><el-input v-model="form.author" placeholder="如：孔子弟子" /></el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="朝代"><el-input v-model="form.dynasty" placeholder="如：先秦" /></el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="译者"><el-input v-model="form.translator" placeholder="如：James Legge（理雅各）" /></el-form-item>
            </el-col>
          </el-row>
        </section>

        <!-- 第 ③ 步：其他（选填） -->
        <section class="form-group">
          <h3 class="group-title"><span class="group-no">③</span>标签与其他<span class="group-opt">选填</span></h3>

          <el-form-item label="是否公开">
            <el-radio-group v-model="form.status">
              <el-radio value="DRAFT">草稿（暂时只有自己能看到）</el-radio>
              <el-radio value="PUBLISHED" :disabled="!form.translatedText.trim()">
                发布（大家搜索时都能看到；需先填好译文）
              </el-radio>
            </el-radio-group>
          </el-form-item>

          <el-form-item label="标签">
            <el-select
              v-model="selectedTagIds"
              multiple
              filterable
              remote
              :remote-method="searchTags"
              :loading="tagLoading"
              placeholder="点这里挑选标签；输入新名字后按回车，会自动创建"
              style="width: 100%"
              data-test="tag-select"
              @keyup.enter="onTagEnter"
            >
              <el-option v-for="t in tagOptions" :key="t.id" :label="t.name" :value="t.id" />
            </el-select>
            <div class="field-hint">标签就是分类小纸条，比如：儒家、论语、修身。</div>
          </el-form-item>

          <el-form-item label="备注">
            <el-input v-model="form.notes" type="textarea" :rows="2" placeholder="想留给同事的话写在这里（可不填）" />
          </el-form-item>
        </section>

        <!-- 操作区 -->
        <div class="form-actions">
          <el-button type="primary" size="large" :loading="saving" data-test="save-btn" @click="save">
            保 存
          </el-button>
          <el-button size="large" @click="router.back()">不保存，返回</el-button>
        </div>
      </el-form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api, type SegmentVO, type TagVO } from '../api'

const route = useRoute()
const router = useRouter()

const editingId = computed(() => (route.params.id ? Number(route.params.id) : null))
const isEdit = computed(() => editingId.value !== null)

const loading = ref(false)
const saving = ref(false)
const version = ref<number>()
const form = reactive({
  sourceText: '',
  translatedText: '',
  workTitle: '',
  chapter: '',
  author: '',
  dynasty: '',
  translator: '',
  notes: '',
  status: 'DRAFT' as 'DRAFT' | 'PUBLISHED'
})

// 标签
const allTags = ref<TagVO[]>([])
const tagOptions = ref<TagVO[]>([])
const selectedTagIds = ref<number[]>([])
const tagLoading = ref(false)
const tagQuery = ref('')

onMounted(async () => {
  await loadTags()
  if (isEdit.value) await loadSegment()
})

async function loadTags() {
  try {
    allTags.value = await api.listTags()
    tagOptions.value = [...allTags.value]
  } catch {
    /* 拦截器已提示 */
  }
}

async function loadSegment() {
  loading.value = true
  try {
    const s: SegmentVO = await api.getSegment(editingId.value!)
    form.sourceText = s.sourceText
    form.translatedText = s.translatedText
    form.workTitle = s.workTitle || ''
    form.chapter = s.chapter || ''
    form.author = s.author || ''
    form.dynasty = s.dynasty || ''
    form.translator = s.translator || ''
    form.notes = s.notes || ''
    form.status = s.status
    version.value = s.version
    // 标签以名字返回，需要映射回 id
    const byName = new Map(allTags.value.map((t) => [t.name, t]))
    selectedTagIds.value = s.tags.map((n) => byName.get(n)?.id).filter((id): id is number => id != null)
  } finally {
    loading.value = false
  }
}

function searchTags(query: string) {
  tagQuery.value = query.trim()
  const kw = tagQuery.value.toLowerCase()
  tagOptions.value = kw
    ? allTags.value.filter((t) => t.name.toLowerCase().includes(kw))
    : [...allTags.value]
}

/** 回车：无完全匹配时即时创建标签并选中 */
async function onTagEnter() {
  const name = tagQuery.value
  if (!name) return
  const exact = allTags.value.find((t) => t.name === name)
  if (exact) {
    if (!selectedTagIds.value.includes(exact.id)) selectedTagIds.value.push(exact.id)
    tagQuery.value = ''
    return
  }
  tagLoading.value = true
  try {
    const created = await api.createTag(name)
    allTags.value.push(created)
    tagOptions.value.push(created)
    selectedTagIds.value.push(created.id)
    tagQuery.value = ''
    ElMessage.success(`已创建标签「${name}」`)
  } catch {
    /* 拦截器已提示（如 5002 重名） */
  } finally {
    tagLoading.value = false
  }
}

function isConflict(err: unknown): boolean {
  const e = err as { response?: { status?: number; data?: { code?: number } } }
  return e?.response?.status === 409 || e?.response?.data?.code === 2002
}

async function save() {
  // 原文必填；译文可留空，缺译文时后端会强制存为待翻译草稿
  if (!form.sourceText.trim()) {
    ElMessage.warning('「原文」是必填的，请先填上')
    return
  }
  // 译文留空 = 待翻译草稿：本地先纠正状态，后端也会强制 DRAFT
  if (!form.translatedText.trim() && form.status === 'PUBLISHED') {
    form.status = 'DRAFT'
    ElMessage.info('还没有译文，已自动改为「草稿」保存；补上译文后可再发布。')
  }
  saving.value = true
  try {
    const dto = {
      ...form,
      tagIds: selectedTagIds.value,
      ...(isEdit.value ? { version: version.value } : {})
    }
    const saved = isEdit.value
      ? await api.updateSegment(editingId.value!, dto)
      : await api.createSegment(dto)
    ElMessage.success('保存成功')
    router.push(`/segments/${saved.id}`)
  } catch (err) {
    if (isConflict(err)) {
      ElMessage.error('别人刚刚也改了这条内容。已为你载入最新版本，请对照后再保存一次。')
      await loadSegment() // 重新载入最新版本
    }
    /* 其他错误由拦截器统一提示 */
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.edit-view { padding-top: 8px; }
.edit-card { padding: 30px 36px; }
.card-head { margin-bottom: 26px; }

/* 分组标题：①②③ 步骤感 */
.group-title {
  display: flex;
  align-items: center;
  gap: 10px;
  font-family: var(--font-display);
  font-size: 17px;
  font-weight: 700;
  letter-spacing: 2px;
  color: var(--ink);
  margin: 0 0 16px;
}
.group-no {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  font-size: 14px;
  color: #fdf4e3;
  background: var(--cinnabar);
  border-radius: 8px;
}
.group-req {
  font-family: var(--font-ui);
  font-size: 12px;
  font-weight: 600;
  color: var(--cinnabar);
  background: rgba(168, 67, 60, 0.08);
  border: 1px solid rgba(168, 67, 60, 0.3);
  border-radius: 999px;
  padding: 1px 10px;
}
.group-opt {
  font-family: var(--font-ui);
  font-size: 12px;
  color: var(--ink-3);
  letter-spacing: 1px;
}

.form-group {
  padding: 20px 22px;
  border: 1px solid var(--card-edge);
  border-radius: 14px;
  background: var(--el-fill-color-lighter);
  margin-bottom: 18px;
}
.edit-form :deep(.el-form-item__label) {
  font-family: var(--font-ui);
  font-size: 14px;
  font-weight: 600;
  color: var(--ink-2);
}
.field-hint {
  font-family: var(--font-ui);
  font-size: 12.5px;
  color: var(--ink-3);
  margin-top: 6px;
  line-height: 1.7;
}

.form-actions {
  display: flex;
  gap: 12px;
  padding-top: 6px;
}

@media (max-width: 640px) {
  .edit-card { padding: 20px 16px; }
  .edit-form :deep(.el-col-12) { max-width: 100%; flex: 0 0 100%; }
}
</style>
