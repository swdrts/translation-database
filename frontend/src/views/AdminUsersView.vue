<template>
  <div class="admin-users-view">
    <div class="admin-card page-card rise">
      <div class="card-head">
        <div>
          <h2 class="page-title">用户账号</h2>
          <p class="page-lead">给同事开账号、分配权限。角色含义：查看者＝只能浏览；编辑者＝可录入和维护内容；管理员＝拥有全部权限。</p>
        </div>
        <el-button type="primary" data-test="create-user-btn" @click="openCreate">+ 新建账号</el-button>
      </div>

      <el-table :data="users" v-loading="loading" stripe>
        <el-table-column prop="username" label="用户名" min-width="120" />
        <el-table-column prop="displayName" label="显示名" min-width="120" />
        <el-table-column prop="role" label="角色" width="120">
          <template #default="{ row }">
            <el-tag :type="row.role === 'ADMIN' ? 'danger' : row.role === 'EDITOR' ? 'warning' : 'info'" effect="light" round>
              {{ roleLabel(row.role) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'DISABLED' ? 'danger' : 'success'" effect="light" round>
              {{ row.status === 'DISABLED' ? '已禁用' : '正常' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240">
          <template #default="{ row }">
            <el-button size="small" @click="openRole(row)">修改角色</el-button>
            <el-button size="small" :type="row.status === 'DISABLED' ? 'success' : 'danger'" plain @click="toggleStatus(row)">
              {{ row.status === 'DISABLED' ? '恢复使用' : '暂时禁用' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 新建用户 -->
    <el-dialog v-model="createVisible" title="新建账号" width="460px">
      <el-form :model="createForm" label-position="top">
        <el-form-item label="用户名（用于登录）" required><el-input v-model="createForm.username" placeholder="如：zhangsan" /></el-form-item>
        <el-form-item label="初始密码" required><el-input v-model="createForm.password" type="password" show-password placeholder="建议包含字母和数字" /></el-form-item>
        <el-form-item label="显示名（大家看到的名字）"><el-input v-model="createForm.displayName" placeholder="如：张三" /></el-form-item>
        <el-form-item label="角色（决定能做什么）">
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
      <p class="dialog-lead">把 <b>{{ target?.username }}</b> 的角色改为：</p>
      <el-radio-group v-model="newRole" class="role-group">
        <el-radio value="VIEWER">查看者（只能浏览）</el-radio>
        <el-radio value="EDITOR">编辑者（可录入维护）</el-radio>
        <el-radio value="ADMIN">管理员（全部权限）</el-radio>
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
    ElMessage.success('账号创建成功')
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
      disabling
        ? `禁用后「${row.username}」将无法登录（数据不会丢失），确定吗？`
        : `恢复后「${row.username}」可以重新登录，确定吗？`,
      disabling ? '暂时禁用该账号' : '恢复该账号',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await api.updateUser(row.id, { status: disabling ? 'DISABLED' : 'ACTIVE' })
    ElMessage.success(disabling ? '已禁用' : '已恢复')
    await load()
  } catch {
    /* 拦截器已提示 */
  }
}
</script>

<style scoped>
.admin-users-view { max-width: 980px; margin: 0 auto; padding-top: 8px; }
.admin-card { padding: 28px 32px; }
.card-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 20px;
}
.dialog-lead { margin: 0 0 14px; color: var(--ink-2); }
.role-group { display: flex; flex-direction: column; gap: 8px; align-items: flex-start; }
</style>
