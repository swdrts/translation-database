<template>
  <div class="edit-view">
    <div class="edit-card">
      <h2 class="edit-title">{{ isEdit ? '编辑条目' : '新建条目' }}</h2>
      <el-form :model="form" label-width="72px" v-loading="loading">
        <el-form-item label="原文" required>
          <el-input v-model="form.sourceText" type="textarea" :rows="4" data-test="source-input" />
        </el-form-item>
        <el-form-item label="译文" required>
          <el-input v-model="form.translatedText" type="textarea" :rows="4" data-test="translated-input" />
        </el-form-item>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="书名"><el-input v-model="form.workTitle" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="章节"><el-input v-model="form.chapter" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="作者"><el-input v-model="form.author" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="朝代"><el-input v-model="form.dynasty" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="译者"><el-input v-model="form.translator" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="状态">
              <el-radio-group v-model="form.status">
                <el-radio value="DRAFT">草稿</el-radio>
                <el-radio value="PUBLISHED">已发布</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="备注"><el-input v-model="form.notes" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="标签">
          <el-select
            v-model="selectedTagIds"
            multiple
            filterable
            remote
            :remote-method="searchTags"
            :loading="tagLoading"
            placeholder="搜索或创建标签，回车创建"
            style="width: 100%"
            data-test="tag-select"
            @keyup.enter="onTagEnter"
          >
            <el-option v-for="t in tagOptions" :key="t.id" :label="t.name" :value="t.id" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" data-test="save-btn" @click="save">保存</el-button>
          <el-button @click="$router.back()">取消</el-button>
        </el-form-item>
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
  if (!form.sourceText.trim() || !form.translatedText.trim()) {
    ElMessage.warning('原文和译文不能为空')
    return
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
      ElMessage.error('已被他人修改，请刷新后重试')
      await loadSegment() // 重新载入最新版本
    }
    /* 其他错误由拦截器统一提示 */
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.edit-view { max-width: 860px; margin: 0 auto; padding-top: 24px; }
.edit-card { background: #fff; border-radius: 10px; padding: 24px 28px; }
.edit-title { color: #3d3d3d; margin: 0 0 20px; font-size: 20px; }
</style>
