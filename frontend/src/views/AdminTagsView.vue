<template>
  <div class="admin-tags-view">
    <div class="admin-card">
      <div class="card-head">
        <h2 class="page-title">标签管理</h2>
        <el-button type="primary" data-test="create-tag-btn" @click="openCreate">新建标签</el-button>
      </div>

      <el-table :data="tags" v-loading="loading" border stripe>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="name" label="名称" />
        <el-table-column prop="description" label="描述" />
        <el-table-column label="操作" width="180">
          <template #default="{ row }">
            <el-button size="small" @click="openRename(row)">改名</el-button>
            <el-button size="small" type="danger" plain @click="doDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 新建 -->
    <el-dialog v-model="createVisible" title="新建标签" width="420px">
      <el-form label-width="60px">
        <el-form-item label="名称" required><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="form.description" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- 改名 -->
    <el-dialog v-model="renameVisible" title="修改标签" width="420px">
      <el-form label-width="60px">
        <el-form-item label="名称" required><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="form.description" /></el-form-item>
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
    ElMessage.warning('标签名称不能为空')
    return
  }
  saving.value = true
  try {
    await api.createTag(form.name.trim(), form.description || undefined)
    ElMessage.success('创建成功')
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
    ElMessage.warning('标签名称不能为空')
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
    await ElMessageBox.confirm(`确定要删除标签「${row.name}」吗？`, '确认', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
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
.admin-tags-view { max-width: 860px; margin: 0 auto; padding-top: 24px; }
.admin-card { background: #fff; border-radius: 10px; padding: 24px 28px; }
.card-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-title { color: #3d3d3d; margin: 0; font-size: 20px; }
</style>
