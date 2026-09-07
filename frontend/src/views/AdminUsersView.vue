<template>
  <div class="admin-users-view">
    <div class="admin-card">
      <div class="card-head">
        <h2 class="page-title">用户管理</h2>
        <el-button type="primary" data-test="create-user-btn" @click="openCreate">新建用户</el-button>
      </div>

      <el-table :data="users" v-loading="loading" border stripe>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="username" label="用户名" />
        <el-table-column prop="displayName" label="显示名" />
        <el-table-column prop="role" label="角色" width="110">
          <template #default="{ row }">
            <el-tag :type="row.role === 'ADMIN' ? 'danger' : row.role === 'EDITOR' ? 'warning' : 'info'">
              {{ roleLabel(row.role) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 'DISABLED' ? 'danger' : 'success'">
              {{ row.status === 'DISABLED' ? '已禁用' : '正常' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="260">
          <template #default="{ row }">
            <el-button size="small" @click="openRole(row)">改角色</el-button>
            <el-button size="small" :type="row.status === 'DISABLED' ? 'success' : 'danger'" plain @click="toggleStatus(row)">
              {{ row.status === 'DISABLED' ? '启用' : '禁用' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 新建用户 -->
    <el-dialog v-model="createVisible" title="新建用户" width="440px">
      <el-form :model="createForm" label-width="80px">
        <el-form-item label="用户名" required><el-input v-model="createForm.username" /></el-form-item>
        <el-form-item label="密码" required><el-input v-model="createForm.password" type="password" show-password /></el-form-item>
        <el-form-item label="显示名"><el-input v-model="createForm.displayName" /></el-form-item>
        <el-form-item label="角色">
          <el-radio-group v-model="createForm.role">
            <el-radio value="VIEWER">查看者</el-radio>
            <el-radio value="EDITOR">编辑者</el-radio>
            <el-radio value="ADMIN">管理员</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- 修改角色 -->
    <el-dialog v-model="roleVisible" title="修改角色" width="400px">
      <el-radio-group v-model="newRole">
        <el-radio value="VIEWER">查看者</el-radio>
        <el-radio value="EDITOR">编辑者</el-radio>
        <el-radio value="ADMIN">管理员</el-radio>
      </el-radio-group>
      <template #footer>
        <el-button @click="roleVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doChangeRole">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, type UserVO } from '../api'

const users = ref<UserVO[]>([])
const loading = ref(false)
const saving = ref(false)

const createVisible = ref(false)
const createForm = reactive({ username: '', password: '', displayName: '', role: 'VIEWER' })

const roleVisible = ref(false)
const target = ref<UserVO | null>(null)
const newRole = ref('VIEWER')

onMounted(load)

async function load() {
  loading.value = true
  try {
    const page = await api.listUsers(0, 100)
    users.value = page.content
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

function roleLabel(role: string) {
  return role === 'ADMIN' ? '管理员' : role === 'EDITOR' ? '编辑者' : '查看者'
}

function openCreate() {
  createForm.username = ''
  createForm.password = ''
  createForm.displayName = ''
  createForm.role = 'VIEWER'
  createVisible.value = true
}

async function doCreate() {
  if (!createForm.username.trim() || !createForm.password) {
    ElMessage.warning('用户名和密码不能为空')
    return
  }
  saving.value = true
  try {
    await api.createUser({ ...createForm, displayName: createForm.displayName || undefined })
    ElMessage.success('创建成功')
    createVisible.value = false
    await load()
  } catch {
    /* 拦截器已提示 */
  } finally {
    saving.value = false
  }
}

function openRole(row: UserVO) {
  target.value = row
  newRole.value = row.role
  roleVisible.value = true
}

async function doChangeRole() {
  if (!target.value) return
  saving.value = true
  try {
    await api.updateUser(target.value.id, { role: newRole.value })
    ElMessage.success('角色已更新')
    roleVisible.value = false
    await load()
  } catch {
    /* 拦截器已提示 */
  } finally {
    saving.value = false
  }
}

async function toggleStatus(row: UserVO & { status?: string }) {
  const disabling = row.status !== 'DISABLED'
  try {
    await ElMessageBox.confirm(
      `确定要${disabling ? '禁用' : '启用'}用户「${row.username}」吗？`,
      '确认',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await api.updateUser(row.id, { status: disabling ? 'DISABLED' : 'ACTIVE' })
    ElMessage.success(`已${disabling ? '禁用' : '启用'}`)
    await load()
  } catch {
    /* 拦截器已提示 */
  }
}
</script>

<style scoped>
.admin-users-view { max-width: 960px; margin: 0 auto; padding-top: 24px; }
.admin-card { background: #fff; border-radius: 10px; padding: 24px 28px; }
.card-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-title { color: #3d3d3d; margin: 0; font-size: 20px; }
</style>
