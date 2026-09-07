<template>
  <div class="login-page">
    <el-card class="login-card">
      <h2 class="login-title">翻译学术数据库</h2>
      <el-form :model="form" @keyup.enter="submit">
        <el-form-item>
          <el-input v-model="form.username" placeholder="用户名" data-test="username" />
        </el-form-item>
        <el-form-item>
          <el-input v-model="form.password" type="password" placeholder="密码" show-password data-test="password" />
        </el-form-item>
        <el-button type="primary" style="width: 100%" :loading="loading" data-test="submit" @click="submit">
          登 录
        </el-button>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import { useAuthStore, type AuthUser } from '../stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const form = reactive({ username: '', password: '' })
const loading = ref(false)

async function submit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    const result = await api.login(form.username, form.password)
    auth.setSession(result.token, result.user as AuthUser)
    router.push((route.query.redirect as string) || '/')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page { display: flex; justify-content: center; align-items: center; min-height: 100vh; background: #f7f4ef; }
.login-card { width: 380px; padding: 12px 8px; }
.login-title { text-align: center; color: #3d3d3d; margin-bottom: 24px; }
</style>
