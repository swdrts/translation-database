<template>
  <el-container class="layout">
    <el-header class="header">
      <div class="brand" @click="$router.push('/')">翻译学术数据库</div>
      <el-menu mode="horizontal" :default-active="route.path" router class="menu">
        <el-menu-item index="/">首页</el-menu-item>
        <el-menu-item v-if="auth.isEditor" index="/import">导入</el-menu-item>
        <el-sub-menu v-if="auth.isAdmin" index="admin">
          <template #title>管理</template>
          <el-menu-item index="/admin/users">用户管理</el-menu-item>
          <el-menu-item index="/admin/tags">标签管理</el-menu-item>
          <el-menu-item index="/admin/reindex">索引重建</el-menu-item>
        </el-sub-menu>
      </el-menu>
      <div class="user-area">
        <span v-if="auth.user">{{ auth.user.displayName || auth.user.username }}</span>
        <el-button link @click="logout">退出</el-button>
      </div>
    </el-header>
    <el-main><slot /></el-main>
  </el-container>
</template>

<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

function logout() {
  auth.clear()
  router.push('/login')
}
</script>

<style scoped>
.layout { min-height: 100vh; background: #f7f4ef; }
.header { display: flex; align-items: center; background: #fffdf9; border-bottom: 1px solid #e5ded2; }
.brand { font-weight: 700; color: #3d3d3d; margin-right: 32px; cursor: pointer; }
.menu { flex: 1; border-bottom: none; }
.user-area { display: flex; align-items: center; gap: 12px; color: #3d3d3d; }
</style>
