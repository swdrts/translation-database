<template>
  <div class="admin-tags-view">
    <div class="admin-card page-card rise">
      <div class="card-head">
        <div>
          <h2 class="page-title">标签分类</h2>
          <p class="page-lead">标签是给内容贴的分类小纸条（如：儒家、唐诗、哲学），方便大家按主题浏览。</p>
        </div>
        <el-button type="primary" data-test="create-tag-btn" @click="openCreate">+ 新建标签</el-button>
      </div>

      <el-table :data="tags" v-loading="loading" stripe>
        <el-table-column prop="name" label="标签名" min-width="140">
          <template #default="{ row }">
            <span class="tag-name">{{ row.name }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="说明" min-width="200" />
        <el-table-column label="操作" width="190">
          <template #default="{ row }">
            <el-button size="small" @click="openRename(row)">编辑</el-button>
            <el-button size="small" type="danger" plain @click="doDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 新建 -->
    <el-dialog v-model="createVisible" title="新建标签" width="440px">
      <el-form label-position="top">
        <el-form-item label="标签名" required><el-input v-model="form.name" placeholder="如：儒家" /></el-form-item>
        <el-form-item label="说明（做什么用的，可不填）"><el-input v-model="form.description" placeholder="如：先秦儒家经典相关内容" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- 编辑 -->
    <el-dialog v-model="renameVisible" title="编辑标签" width="440px">
      <el-form label-position="top">
        <el-form-item label="标签名" required><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="说明（可不填）"><el-input v-model="form.description" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="renameVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doRename">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, type TagVO } from '../api'

const tags = ref<TagVO[]>([])
const loading = ref(false)
const saving = ref(false)
const createVisible = ref(false)
const renameVisible = ref(false)
const target = ref<TagVO | null>(null)
const form = reactive({ name: '', description: '' })

onMounted(load)

async function load() {
  loading.value = true
  try {
    tags.value = await api.listTags()
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

function openCreate() {
  form.name = ''
  form.description = ''
  createVisible.value = true
}

function openRename(row: TagVO) {
  target.value = row
  form.name = row.name
  form.description = row.description || ''
  renameVisible.value = true
}

async function doCreate() {
  if (!form.name.trim()) {
    ElMessage.warning('请先填写标签名')
    return
  }
  saving.value = true
  try {
    await api.createTag(form.name.trim(), form.description || undefined)
    ElMessage.success('标签创建成功')
    createVisible.value = false
    await load()
  } catch {
    /* 拦截器已提示（如重名） */
  } finally {
    saving.value = false
  }
}

async function doRename() {
  if (!target.value || !form.name.trim()) {
    ElMessage.warning('请先填写标签名')
    return
  }
  saving.value = true
  try {
    await api.updateTag(target.value.id, form.name.trim(), form.description || undefined)
    ElMessage.success('已保存')
    renameVisible.value = false
    await load()
  } catch {
    /* 拦截器已提示 */
  } finally {
    saving.value = false
  }
}

async function doDelete(row: TagVO) {
  try {
    await ElMessageBox.confirm(
      `删除标签「${row.name}」不会删除内容本身，只是去掉这个分类纸条，确定吗？`,
      '删除标签',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await api.deleteTag(row.id)
    ElMessage.success('已删除')
    await load()
  } catch {
    /* 拦截器已提示 */
  }
}
</script>

<style scoped>
.admin-tags-view { max-width: 880px; margin: 0 auto; padding-top: 8px; }
.admin-card { padding: 28px 32px; }
.card-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 20px;
}
.tag-name {
  font-weight: 600;
  color: #8a6d1f;
  background: rgba(185, 138, 47, 0.1);
  border: 1px solid rgba(185, 138, 47, 0.3);
  border-radius: 6px;
  padding: 1px 9px;
}
</style>
